package com.itdragclick.client.ai;

import com.itdragclick.AmAI;
import com.itdragclick.client.net.OllamaNetworkClient;
import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Per-tick "common sense" layer. Registered on Fabric's
 * {@code ClientTickEvents.END_CLIENT_TICK}, so every method here already runs
 * on the main game thread — safe to touch the player, world and Baritone.
 *
 * Responsibilities:
 *  - Friendly-fire whitelist: whitelisted players are never targeted, even on
 *    an explicit LLM order.
 *  - Combat interceptor with an active PvP movement engine: pause Baritone,
 *    chase (Baritone goal beyond 8 blocks, sprint-forward inputs inside it),
 *    strike within 3.5 blocks, give up beyond a 20-block tracking radius,
 *    then restore the saved objective (Baritone goal + harvest plan step).
 *  - Dynamic auto-eat: full-inventory scan below 14 hunger, hotbar swap via
 *    container SWAP clicks, use-key hold until fed; below 6 hunger mid-combat
 *    the bot retreats 5 blocks, eats, and only re-engages above 12.
 *  - Death matrix: caches death coordinates + dimension, respawns, then picks
 *    Branch A (recover items, < 1000 blocks same dimension) or Branch B
 *    (re-gear from scratch) and tells the LLM what happened.
 */
public final class SurvivalMonitor {

	// -------------------------------------------------- combat constants
	private static final float HEALTH_PANIC_THRESHOLD = 12.0f;
	private static final double THREAT_TRIGGER_RADIUS = 5.0;
	private static final double THREAT_CLEAR_RADIUS = 6.0;
	private static final double COMBAT_TRACKING_RADIUS = 20.0;
	private static final double MELEE_RANGE = 3.0;
	private static final double STRIKE_RADIUS = 3.5;
	private static final double CHASE_PATHFIND_RANGE = 8.0;
	private static final double CHASE_MAX_RANGE = 15.0;
	private static final double TARGET_SEARCH_RADIUS = 32.0;
	private static final int TARGET_SEARCH_PATIENCE_TICKS = 100;
	private static final int CHASE_REPATH_INTERVAL_TICKS = 20;

	// ----------------------------------------------------- eat constants
	private static final int HUNGER_AUTO_EAT = 14;
	private static final int HUNGER_CRITICAL = 6;
	private static final int HUNGER_COMBAT_SAFE = 12;
	private static final double COMBAT_RETREAT_BLOCKS = 5.0;

	/** Extended attacker scan for ranged hits (skeleton arrows from afar). */
	private static final double RANGED_THREAT_RADIUS = 16.0;
	/** Golden apple emergency threshold (5 hearts). */
	private static final float GAPPLE_HEALTH_THRESHOLD = 10.0f;

	// ------------------------------------------------------------- state
	private static float lastHealth = -1.0f;
	private static boolean deathReported = false;
	private static BlockPos deathPos = null;
	private static String deathDimension = null;
	/**
	 * Chat text produced by the LLM while the death screen was open. The
	 * server rejects chat packets from dead players, so the message waits
	 * here until the bot has fully revived, then flushes to chat.
	 */
	private static String pendingRespawnChat = null;

	private static boolean combatMode = false;
	private static LivingEntity currentThreat = null;
	private static boolean combatEatHold = false;
	private static int chaseRepathCooldown = 0;
	private static boolean movementKeysActive = false;

	private static boolean eating = false;
	private static int slotBeforeEating = -1;

	private static String attackTargetName = null;
	private static LivingEntity attackTarget = null;
	private static int attackSearchTicks = 0;
	/** True when the attack order targets a player: lock on until death. */
	private static boolean attackTargetIsPlayer = false;

	/** follow_protect stance: whitelisted player being escorted, or null. */
	private static String protectTarget = null;

	/** Emergency golden-apple consumption (health < 10 in combat). */
	private static boolean gappleActive = false;
	private static int gappleStartCount = 0;

	private SurvivalMonitor() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(SurvivalMonitor::onTick);
	}

	public static boolean isInCombat() {
		return combatMode || attackTargetName != null;
	}

	// ---------------------------------------------------- LLM order intake

	/** Called by {@link AIActionBridge} on the main thread: "attack <name>". */
	public static void requestAttack(String targetName) {
		String cleaned = targetName.trim();
		if (isFriendly(cleaned)) {
			AIDashboardFrame.appendSystemLog("[COMBAT] REFUSED: '" + cleaned
					+ "' is on the friendly whitelist. I don't attack friends.");
			return;
		}
		attackTargetName = cleaned;
		attackTarget = null;
		attackSearchTicks = 0;
		AIDashboardFrame.appendSystemLog("[COMBAT] Attack order: '" + attackTargetName + "'");
	}

	/** Called by {@link AIActionBridge} on the main thread: "eat". */
	public static void requestEat() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		if (!player.getFoodData().needsFood()) {
			AIDashboardFrame.appendSystemLog("[EAT] Hunger already full — nothing to do.");
			return;
		}
		if (!beginEating(mc, player)) {
			AIDashboardFrame.appendSystemLog("[EAT] No food anywhere in the inventory.");
		}
	}

	/**
	 * Called by {@link AIActionBridge} when the LLM wants to chat while the
	 * player is dead — deferred until revival (see class comment on
	 * {@link #pendingRespawnChat}).
	 */
	public static void queueRespawnChat(String chat) {
		if (chat != null && !chat.isBlank()) {
			pendingRespawnChat = chat;
			AIDashboardFrame.appendSystemLog("[SYSTEM] Chat deferred until respawn: \"" + chat + "\"");
		}
	}

	/** Absolute cancellation: wipes combat + attack orders (stop override). */
	public static void clearAllOrders() {
		Minecraft mc = Minecraft.getInstance();
		exitCombat(mc, false);
		clearAttackOrder();
		protectTarget = null;
		gappleActive = false;
	}

	/** follow_protect stance: trail a whitelisted friend and defend them. */
	public static void requestFollowProtect(String playerName) {
		protectTarget = playerName.strip();
		BaritoneBridge.follow(protectTarget);
		AIDashboardFrame.appendSystemLog("[PROTECT] Escorting '" + protectTarget + "' — hostiles will be engaged.");
	}

	/** Whitelist check used by every targeting path (dynamic runtime list). */
	public static boolean isFriendly(String name) {
		return AIWhitelistManager.isWhitelisted(name);
	}

	private static boolean isFriendly(Entity entity) {
		return entity instanceof Player && isFriendly(entity.getName().getString());
	}

	// -------------------------------------------------------------- ticker

	private static boolean emergencySwim = false;

	private static void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			resetTransientState(mc);
			return;
		}
		
		InventoryHelper.tickDrops(mc, player);

		if (handleDeath(mc, player)) {
			return;
		}

		// Emergency drowning prevention
		if (player.getAirSupply() < 60) {
			if (!emergencySwim) {
				emergencySwim = true;
				BaritoneBridge.pauseForCombat();
				AIDashboardFrame.appendSystemLog("[SURVIVAL] Low air! Emergency surfacing.");
			}
			mc.options.keyJump.setDown(true);
			
			// If there's a block directly above us, try to break it
			BlockPos above = player.blockPosition().above(2);
			if (!mc.level.getBlockState(above).isAir()) {
				player.setXRot(-90.0f); // Look straight up
				mc.options.keyAttack.setDown(true);
			} else {
				mc.options.keyAttack.setDown(false);
			}
			
			return; // Skip combat/eating/etc to focus on surviving
		} else if (emergencySwim && player.getAirSupply() == player.getMaxAirSupply()) {
			emergencySwim = false;
			mc.options.keyJump.setDown(false);
			mc.options.keyAttack.setDown(false);
			BaritoneBridge.resumeRememberedGoal();
			AIDashboardFrame.appendSystemLog("[SURVIVAL] Air restored. Resuming task.");
		}

		handleRespawnRecovery(player);
		flushPendingRespawnChat(mc, player);

		tickAutoEat(mc, player);
		tickEating(mc, player);
		tickGoldenApple(mc, player);
		tickProtect(mc, player);
		tickManualAttack(mc, player);
		tickCombatDefense(mc, player);

		lastHealth = player.getHealth();
	}

	// --------------------------------------------------------- death watch

	/** Returns true while dead (all other behaviour suspended). */
	private static boolean handleDeath(Minecraft mc, LocalPlayer player) {
		if (!player.isDeadOrDying()) {
			return false;
		}
		if (!deathReported) {
			deathReported = true;
			// Cache the exact spot + dimension for the recovery decision.
			deathPos = player.blockPosition();
			deathDimension = player.level().dimension().identifier().toString();
			stopEating(mc, player);
			exitCombat(mc, false);
			clearAttackOrder();
			gappleActive = false;
			AIStateManager.onDeath();
			HarvestManager.cancel();
			FarmManager.cancel();
			CraftPlanner.cancel();
			AIDashboardFrame.appendSystemLog("[SYSTEM] Player has died at " + deathPos.toShortString()
					+ " in " + deathDimension + ".");
			OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.SYSTEM, "System",
					"You died in the game. Inform the user and reply with the click_respawn action.");
		}
		return true;
	}

	/** Executes the click_respawn action (invoked by AIActionBridge). */
	public static void clickRespawn() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		if (player.isDeadOrDying()) {
			player.respawn();
			AIDashboardFrame.appendSystemLog("[SYSTEM] Respawn requested — re-entering the world.");
		} else {
			AIDashboardFrame.appendSystemLog("[SYSTEM] click_respawn ignored — player is alive.");
		}
	}

	/**
	 * Post-respawn strategy — runs once on the first tick back alive.
	 * Policy: never walk back to the old death point; always start fresh and
	 * re-gear (basic tools first: wood).
	 */
	private static void handleRespawnRecovery(LocalPlayer player) {
		if (!deathReported) {
			return;
		}
		deathReported = false;
		if (deathPos == null) {
			return;
		}
		AIDashboardFrame.appendSystemLog("[SYSTEM] Items lost permanently. Re-gearing strategy disabled, waiting for orders.");
		BaritoneBridge.stop();
		// Interrupted journeys queue up on the LIFO stack.
		AIStateManager.requeueJourneyAfterRespawn();
		deathPos = null;
		deathDimension = null;
	}

	/** Sends the deferred death-screen chat once the bot is fully revived. */
	private static void flushPendingRespawnChat(Minecraft mc, LocalPlayer player) {
		if (pendingRespawnChat == null) {
			return;
		}
		if (player.isDeadOrDying() || player.getHealth() <= 0 || mc.getConnection() == null) {
			return;
		}
		String chat = pendingRespawnChat;
		pendingRespawnChat = null;
		mc.getConnection().sendChat(chat.length() > 100 ? chat.substring(0, 100) : chat);
		AIDashboardFrame.appendSystemLog("[SYSTEM] Deferred respawn chat sent: \"" + chat + "\"");
	}

	// ---------------------------------------------------------------- eat

	/** Hunger watchdog: kicks in below 14, or when health < 12 and hunger < 20. */
	private static void tickAutoEat(Minecraft mc, LocalPlayer player) {
		if (eating) {
			return;
		}
		int hunger = player.getFoodData().getFoodLevel();
		float health = player.getHealth();
		boolean lowHealth = health < HEALTH_PANIC_THRESHOLD && hunger < 20;
		if (hunger >= HUNGER_AUTO_EAT && !lowHealth) {
			return;
		}
		// Mid-combat we only interrupt for a critical stomach; the combat
		// handler owns the retreat choreography.
		if (isInCombat() && hunger >= HUNGER_CRITICAL && !lowHealth) {
			return;
		}
		if (beginEating(mc, player)) {
			AIDashboardFrame.appendSystemLog("[EAT] Auto-eat engaged (hunger " + hunger + "/20, health " + health + ").");
		}
	}

	/**
	 * Deep-inventory food pass: stages food in hotbar slot 1 (full 0-35 scan
	 * via {@link InventoryHelper}) and starts the use-key hold.
	 */
	private static boolean beginEating(Minecraft mc, LocalPlayer player) {
		if (eating) {
			return true;
		}
		int previousSlot = player.getInventory().getSelectedSlot();
		if (!InventoryHelper.stageFoodInHotbar(mc, player)) {
			return false;
		}
		slotBeforeEating = previousSlot;
		eating = true;
		if (!combatEatHold && !emergencySwim) {
			BaritoneBridge.pauseForCombat();
		}
		AIDashboardFrame.appendSystemLog("[EAT] Eating '"
				+ player.getInventory().getSelectedItem().getHoverName().getString()
				+ "' until hunger is restored.");
		return true;
	}

	private static void tickEating(Minecraft mc, LocalPlayer player) {
		if (!eating) {
			return;
		}
		ItemStack held = player.getInventory().getSelectedItem();
		boolean holdingFood = held.has(DataComponents.FOOD);
		if (!player.getFoodData().needsFood() || !holdingFood) {
			boolean starvedOut = !holdingFood && player.getFoodData().needsFood();
			stopEating(mc, player);
			if (starvedOut) {
				// Maybe more food deeper in the inventory — chain another pass.
				if (!beginEating(mc, player)) {
					AIDashboardFrame.appendSystemLog("[EAT] Ran out of food before hunger was full.");
				}
			} else {
				AIDashboardFrame.appendSystemLog("[EAT] Hunger restored.");
			}
			return;
		}
		// Hold the use key down; the client consumes the item over time.
		mc.options.keyUse.setDown(true);
	}

	private static void stopEating(Minecraft mc, LocalPlayer player) {
		if (!eating) {
			return;
		}
		eating = false;
		mc.options.keyUse.setDown(false);
		if (slotBeforeEating >= 0) {
			player.getInventory().setSelectedSlot(slotBeforeEating);
			slotBeforeEating = -1;
		}
		if (!combatEatHold && !emergencySwim && !combatMode) {
			BaritoneBridge.resumeRememberedGoal();
		}
	}

	// ---------------------------------------------------- golden apple

	/**
	 * Emergency override: health under 5 hearts during any engagement —
	 * pause swinging, eat a golden apple from anywhere in the inventory,
	 * then snap back to the weapon slot and resume fighting.
	 */
	private static void tickGoldenApple(Minecraft mc, LocalPlayer player) {
		if (gappleActive) {
			int remaining = InventoryHelper.countItem(player, "golden_apple")
					+ InventoryHelper.countItem(player, "enchanted_golden_apple");
			if (remaining < gappleStartCount || remaining == 0) {
				// Consumed (or ran out): release, re-arm, resume combat.
				gappleActive = false;
				mc.options.keyUse.setDown(false);
				InventoryHelper.equipBestWeapon(mc, player);
				AIDashboardFrame.appendSystemLog("[GAPPLE] Golden apple consumed — back to the fight.");
			} else {
				mc.options.keyUse.setDown(true);
			}
			return;
		}
		if (!isInCombat() || player.getHealth() >= GAPPLE_HEALTH_THRESHOLD) {
			return;
		}
		int slot = -1;
		for (int i = 0; i < 36; i++) {
			String id = InventoryHelper.itemIdOf(player.getInventory().getItem(i));
			if (id.equals("golden_apple") || id.equals("enchanted_golden_apple")) {
				slot = i;
				break;
			}
		}
		if (slot < 0) {
			return; // no gapples — the normal food path has to do
		}
		if (slot != InventoryHelper.FOOD_HOTBAR_SLOT) {
			InventoryHelper.swapIntoHotbar(mc, player, slot, InventoryHelper.FOOD_HOTBAR_SLOT);
		}
		player.getInventory().setSelectedSlot(InventoryHelper.FOOD_HOTBAR_SLOT);
		gappleStartCount = InventoryHelper.countItem(player, "golden_apple")
				+ InventoryHelper.countItem(player, "enchanted_golden_apple");
		gappleActive = true;
		AIDashboardFrame.appendSystemLog("[GAPPLE] Critical health (" + player.getHealth()
				+ ") — emergency golden apple override.");
	}

	// ------------------------------------------------------ follow_protect

	/** Escort stance: watch the protected player, engage whatever hurts them. */
	private static void tickProtect(Minecraft mc, LocalPlayer player) {
		if (protectTarget == null || attackTargetName != null) {
			return;
		}
		Player escorted = null;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof Player p && p.getName().getString().equalsIgnoreCase(protectTarget) && p.isAlive()) {
				escorted = p;
				break;
			}
		}
		if (escorted == null) {
			return; // out of render range; Baritone keeps following
		}
		// Any hostile monster within 8 blocks of the escorted player?
		LivingEntity threat = null;
		double nearest = 8.0;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof Monster monster && monster.isAlive()) {
				double d = escorted.distanceTo(monster);
				if (d <= nearest) {
					nearest = d;
					threat = monster;
				}
			}
		}
		// The escorted player is visibly taking hits (hurtTime is synced to
		// clients) with no monster around: the attacker is a player —
		// engage the nearest non-whitelisted one next to them.
		if (threat == null && escorted.hurtTime > 0) {
			double nearestPlayer = 6.0;
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (entity instanceof Player other && other != player && other != escorted
						&& other.isAlive() && !isFriendly(other)) {
					double d = escorted.distanceTo(other);
					if (d <= nearestPlayer) {
						nearestPlayer = d;
						threat = other;
					}
				}
			}
		}
		if (threat != null) {
			AIDashboardFrame.appendSystemLog("[PROTECT] Hostile near " + protectTarget + " — engaging!");
			BaritoneBridge.stopQuietly();
			attackTargetName = threat.getName().getString();
			attackTarget = threat;
			attackTargetIsPlayer = false;
			attackSearchTicks = 0;
		}
	}

	// ------------------------------------------------------- manual attack

	private static void tickManualAttack(Minecraft mc, LocalPlayer player) {
		if (attackTargetName == null) {
			return;
		}
		// Locked-on target eliminated?
		if (attackTarget != null && (!attackTarget.isAlive() || attackTarget.isRemoved())) {
			attackTargetIsPlayer = false;
			attackTarget = null;
			// Mob hunting: keep clearing the area — order only completes when
			// no matching entity remains within the 32-block scan radius.
			LivingEntity next = findNearestByName(mc, player, attackTargetName, TARGET_SEARCH_RADIUS);
			if (next != null) {
				attackTarget = next;
				attackTargetIsPlayer = next instanceof Player;
			} else {
				AIDashboardFrame.appendSystemLog("[COMBAT] No more '" + attackTargetName
						+ "' within " + (int) TARGET_SEARCH_RADIUS + " blocks — order complete.");
				completeAttackOrder(mc);
				return;
			}
		}
		// (Re)acquire by name when not locked on or the target wandered off.
		double leash = attackTargetIsPlayer ? 64.0 : TARGET_SEARCH_RADIUS;
		if (attackTarget == null || player.distanceTo(attackTarget) > leash) {
			attackTarget = findNearestByName(mc, player, attackTargetName, leash);
			if (attackTarget != null) {
				attackTargetIsPlayer = attackTarget instanceof Player;
			}
		}
		if (attackTarget == null) {
			// Don't give up instantly — chunks/entities may still be loading.
			// Player targets get a much longer leash (lock-on until death).
			int patience = attackTargetIsPlayer ? 600 : TARGET_SEARCH_PATIENCE_TICKS;
			if (++attackSearchTicks > patience) {
				AIDashboardFrame.appendSystemLog("[COMBAT] No entity named '" + attackTargetName
						+ "' found — order dropped.");
				completeAttackOrder(mc);
			}
			return;
		}
		attackSearchTicks = 0;
		engage(mc, player, attackTarget);
	}

	/** Order finished: release keys and, in escort stance, resume trailing. */
	private static void completeAttackOrder(Minecraft mc) {
		clearAttackOrder();
		releaseMovementKeys(mc);
		if (protectTarget != null) {
			BaritoneBridge.follow(protectTarget);
			AIDashboardFrame.appendSystemLog("[PROTECT] Threat handled — resuming escort of " + protectTarget + ".");
		}
	}

	private static void clearAttackOrder() {
		attackTargetName = null;
		attackTarget = null;
		attackSearchTicks = 0;
		attackTargetIsPlayer = false;
	}

	// ------------------------------------------------------ combat defense

	private static void tickCombatDefense(Minecraft mc, LocalPlayer player) {
		boolean tookDamage = lastHealth >= 0 && player.getHealth() < lastHealth;

		if (!combatMode) {
			boolean lowHealth = player.getHealth() < HEALTH_PANIC_THRESHOLD;
			if (!tookDamage && !lowHealth) {
				return;
			}
			LivingEntity attacker = findAttacker(mc, player, THREAT_TRIGGER_RADIUS);
			// Ranged hits (skeleton arrows): nothing in melee range but we
			// still bled — widen the monster scan before shrugging it off.
			if (attacker == null && tookDamage) {
				attacker = findNearestMonster(mc, player, RANGED_THREAT_RADIUS);
			}
			if (attacker != null) {
				combatMode = true;
				currentThreat = attacker;
				combatEatHold = false;
				// Cache the active objective (Baritone goal) before fighting.
				BaritoneBridge.pauseForCombat();
				AIDashboardFrame.appendSystemLog("[SYSTEM] Under attack! Pausing Baritone and defending myself.");
			}
			return;
		}

		// --- Critical hunger mid-fight: back off, eat, re-engage above 12.
		int hunger = player.getFoodData().getFoodLevel();
		if (!combatEatHold && hunger < HUNGER_CRITICAL && currentThreat != null) {
			combatEatHold = true;
			releaseMovementKeys(mc);
			retreatFrom(player, currentThreat);
			beginEating(mc, player);
			AIDashboardFrame.appendSystemLog("[SYSTEM] Critical hunger mid-combat — retreating 5 blocks to eat.");
		}
		if (combatEatHold) {
			if (hunger > HUNGER_COMBAT_SAFE || (!eating && hunger >= HUNGER_CRITICAL)) {
				combatEatHold = false;
				AIDashboardFrame.appendSystemLog("[SYSTEM] Recovered — returning to the fight.");
			} else {
				return; // hold combat while retreating/eating
			}
		}

		// Locked threat dead, despawned, or outside the tracking radius —
		// look for the next monster, or a new attacker if we're still bleeding.
		if (currentThreat == null || !currentThreat.isAlive() || currentThreat.isRemoved()
				|| player.distanceTo(currentThreat) > COMBAT_TRACKING_RADIUS) {
			currentThreat = findNearestMonster(mc, player, THREAT_CLEAR_RADIUS);
			if (currentThreat == null && tookDamage) {
				currentThreat = findAttacker(mc, player, THREAT_CLEAR_RADIUS);
			}
		}

		if (currentThreat == null) {
			exitCombat(mc, true);
			return;
		}
		engage(mc, player, currentThreat);
	}

	/**
	 * Post-combat resumption: clear overrides, restore the saved Baritone
	 * goal, and re-issue the harvest plan's current milestone.
	 */
	private static void exitCombat(Minecraft mc, boolean resume) {
		combatMode = false;
		currentThreat = null;
		combatEatHold = false;
		releaseMovementKeys(mc);
		if (resume) {
			BaritoneBridge.resumeRememberedGoal();
			HarvestManager.reissueCurrentStep();
		}
	}

	/**
	 * Best guess at who just hurt us. Client-side {@code getLastHurtByMob()}
	 * is not reliably synced for player attackers, so the heuristic is:
	 * the synced attacker if nearby, else the nearest hostile monster, else
	 * the nearest other player. Whitelisted friends and passive mobs never
	 * qualify — fall damage next to a pig must not start a pig massacre.
	 */
	private static LivingEntity findAttacker(Minecraft mc, LocalPlayer player, double radius) {
		LivingEntity synced = player.getLastHurtByMob();
		if (synced != null && synced.isAlive() && !isFriendly(synced) && player.distanceTo(synced) <= radius) {
			return synced;
		}
		// Projectile trace: an arrow near us right after taking damage means
		// a ranged attacker — its owner is the real threat (skeleton snipers
		// far outside the melee scan radius).
		LivingEntity shooter = findProjectileShooter(mc, player);
		if (shooter != null) {
			return shooter;
		}
		LivingEntity monster = findNearestMonster(mc, player, radius);
		if (monster != null) {
			return monster;
		}
		LivingEntity nearestPlayer = null;
		double nearestDistance = radius;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof Player other) || other == player || !other.isAlive() || isFriendly(other)) {
				continue;
			}
			double distance = player.distanceTo(other);
			if (distance <= nearestDistance) {
				nearestPlayer = other;
				nearestDistance = distance;
			}
		}
		return nearestPlayer;
	}

	/**
	 * Finds the living owner of the nearest projectile within 8 blocks of
	 * the bot — arrows/tridents that just hit (or whizzed past) us point
	 * straight at their shooter.
	 */
	private static LivingEntity findProjectileShooter(Minecraft mc, LocalPlayer player) {
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof net.minecraft.world.entity.projectile.Projectile projectile)) {
				continue;
			}
			if (player.distanceTo(projectile) > 8.0) {
				continue;
			}
			if (projectile.getOwner() instanceof LivingEntity owner
					&& owner.isAlive() && owner != player && !isFriendly(owner)
					&& player.distanceTo(owner) <= 64.0) {
				return owner;
			}
		}
		return null;
	}

	private static LivingEntity findNearestMonster(Minecraft mc, LocalPlayer player, double radius) {
		LivingEntity nearest = null;
		double nearestDistance = radius;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof Monster monster) || !monster.isAlive()) {
				continue;
			}
			double distance = player.distanceTo(monster);
			if (distance <= nearestDistance) {
				nearest = monster;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	private static LivingEntity findNearestByName(Minecraft mc, LocalPlayer player, String name, double radius) {
		// Normalize both sides: "iron_golem" from the LLM must match the
		// "Iron Golem" display name, and "Golden_Allay" must match itself.
		String wanted = name.toLowerCase(Locale.ROOT).replace('_', ' ');
		LivingEntity nearest = null;
		double nearestDistance = radius;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || !living.isAlive() || living == player) {
				continue;
			}
			if (isFriendly(living)) {
				continue; // whitelist: skip friends even on name match
			}
			String display = living.getName().getString().toLowerCase(Locale.ROOT).replace('_', ' ');
			if (!display.equals(wanted)) {
				continue;
			}
			double distance = player.distanceTo(living);
			if (distance <= nearestDistance) {
				nearest = living;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	// ------------------------------------------------------------ fighting

	/**
	 * Active engagement loop: track distance every tick, close the gap
	 * (Baritone pathing when far, sprint-forward inputs when close), strike
	 * inside 3.5 blocks while continuing to face the moving target.
	 */
	private static void engage(Minecraft mc, LocalPlayer player, LivingEntity target) {
		// Hard safety: never swing at a whitelisted friend.
		if (isFriendly(target)) {
			return;
		}
		// Golden apple override in progress: hold position, no swinging.
		if (gappleActive) {
			releaseMovementKeys(mc);
			return;
		}
		player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
		// Pre-action inventory prep: best weapon staged in hotbar slot 0.
		if (!eating) {
			InventoryHelper.equipBestWeapon(mc, player);
		}

		double distance = player.distanceTo(target);
		if (chaseRepathCooldown > 0) {
			chaseRepathCooldown--;
		}

		if (distance > MELEE_RANGE && distance <= CHASE_MAX_RANGE) {
			if (distance > CHASE_PATHFIND_RANGE) {
				// Far: let Baritone chase the moving coordinates (throttled).
				releaseMovementKeys(mc);
				if (chaseRepathCooldown == 0) {
					BlockPos pos = target.blockPosition();
					BaritoneBridge.goTo(pos.getX(), pos.getY(), pos.getZ());
					chaseRepathCooldown = CHASE_REPATH_INTERVAL_TICKS;
				}
			} else {
				// Close: manual sprint-chase, more reactive than repathing.
				BaritoneBridge.stopQuietly();
				mc.options.keyUp.setDown(true);
				player.setSprinting(true);
				movementKeysActive = true;
			}
			// Jump assist: hurdle blocks in the chase path instead of
			// running face-first into them and stalling.
			if (player.horizontalCollision && player.onGround()) {
				player.jumpFromGround();
			}
		} else {
			releaseMovementKeys(mc);
		}

		if (distance <= STRIKE_RADIUS && player.getAttackStrengthScale(0.0f) >= 1.0f) {
			try {
				mc.gameMode.attack(player, target);
				player.swing(InteractionHand.MAIN_HAND);
			} catch (Exception e) {
				AmAI.LOGGER.error("[am-ai] Attack dispatch failed", e);
			}
		}
	}

	/** Baritone-path 5 blocks directly away from the threat. */
	private static void retreatFrom(LocalPlayer player, LivingEntity threat) {
		Vec3 away = player.position().subtract(threat.position());
		Vec3 direction = away.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : away.normalize();
		Vec3 retreat = player.position().add(direction.scale(COMBAT_RETREAT_BLOCKS));
		BaritoneBridge.goTo((int) Math.floor(retreat.x), player.blockPosition().getY(), (int) Math.floor(retreat.z));
	}

	private static void releaseMovementKeys(Minecraft mc) {
		if (movementKeysActive) {
			mc.options.keyUp.setDown(false);
			if (mc.player != null) {
				mc.player.setSprinting(false);
			}
			movementKeysActive = false;
		}
	}

	// -------------------------------------------------------------- resets

	private static void resetTransientState(Minecraft mc) {
		if (eating) {
			eating = false;
			slotBeforeEating = -1;
			mc.options.keyUse.setDown(false);
		}
		if (emergencySwim) {
			emergencySwim = false;
			mc.options.keyJump.setDown(false);
			mc.options.keyAttack.setDown(false);
		}
		releaseMovementKeys(mc);
		combatMode = false;
		currentThreat = null;
		combatEatHold = false;
		gappleActive = false;
		protectTarget = null;
		clearAttackOrder();
		lastHealth = -1.0f;
		deathReported = false;
	}
}
