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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
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
	private static final double COMBAT_RETREAT_BLOCKS = 5.0;

	/** Extended attacker scan for ranged hits (skeleton arrows from afar). */
	private static final double RANGED_THREAT_RADIUS = 16.0;
	/** Golden apple emergency threshold (5 hearts). */
	private static final float GAPPLE_HEALTH_THRESHOLD = 10.0f;
	/** Creepers this close are a threat even when nothing else is wrong. */
	private static final double CREEPER_ALERT_RADIUS = 8.0;
	/** Inside this range a swelling creeper means backpedal + shield, no hits. */
	private static final double CREEPER_DANGER_RADIUS = 4.5;
	/** Proactive hostile scan radius while a work task runs. */
	private static final double WORK_DEFEND_RADIUS = 9.0;

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
	/** Throttle for the proactive hostile scan (entity iteration is not free). */
	private static int proactiveScanCooldown = 0;
	/** While > 0 the bot's own shield stays down (around swings / shield-breaking). */
	private static int shieldSuppressTicks = 0;
	/** True while the out-of-combat incoming-arrow block owns the use key. */
	private static boolean passiveShieldActive = false;

	// ------------------------------------------------- mounted lance state
	/** True while the mounted-spear brace holds the use key down. */
	private static boolean lanceBraceActive = false;
	/** Distance to the target on the previous engage tick (pass detection). */
	private static double lancePrevDistance = -1.0;
	/** True once the run-up has enough room and the charge is committed. */
	private static boolean lanceCharging = false;
	/** Cooldown between brace re-presses (use key release/re-hold cycle). */
	private static int lanceRepressCooldown = 0;

	// --------------------------------------------------- mace smash state
	private static final int MACE_IDLE = 0;
	private static final int MACE_LAUNCHING = 2;
	private static final int MACE_AIRBORNE = 3;
	/** Smash landed: ride out the last falling ticks (hit negated the fall
	 *  damage server-side, so the MLG bucket must stay holstered). */
	private static final int MACE_LANDING = 4;
	private static int maceState = MACE_IDLE;
	/** Ticks since the smash routine started (timeout guard). */
	private static int maceTimer = 0;
	/** Cooldown between smash attempts (ticks). */
	private static int maceCooldown = 0;
	/** Ticks until the next mid-air spam swing. */
	private static int maceSwingCooldown = 0;
	/** Swings dispatched during the current fall (0 = clean miss so far). */
	private static int maceHitsLanded = 0;
	/** Ground mace-spam window right after the axe breaks the target's shield. */
	private static int maceShieldPunishTicks = 0;
	/** Punish swings remaining — the window ends after ~5 hits, not on time. */
	private static int macePunishSwingsLeft = 0;
	/** targetBlocking on the previous melee tick (shield-break edge detect). */
	private static boolean targetWasBlocking = false;
	/** Pacing for cooldown-bypass spam (shield break / punish): 1 swing per 4 ticks. */
	private static int spamSwingCooldown = 0;
	/** Anti-bunnyhop: ticks before the next crit jump may be launched. */
	private static int critJumpCooldown = 0;
	/** Ticks left holding a swing for a crit window before giving up. */
	private static int critWaitTicks = 0;

	/** Re-issue throttle for the retreat-while-eating path. */
	private static int eatRetreatRepathCooldown = 0;
	/** Backoff after a failed meal-chain attempt (inventory scan is not free). */
	private static int eatChainRetryCooldown = 0;
	/** Last retreat goal issued (dedupe — Baritone logs every re-issue). */
	private static int lastRetreatX = Integer.MIN_VALUE;
	private static int lastRetreatZ = Integer.MIN_VALUE;

	private static boolean eating = false;
	private static int slotBeforeEating = -1;
	/** Ticks holding the food with the use key UP before chewing starts. */
	private static int eatWarmupTicks = 0;

	public static boolean isEating() {
		return eating;
	}

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

	/** True while an explicit "attack <name>" order is running (a real task). */
	public static boolean hasAttackOrder() {
		return attackTargetName != null;
	}

	/** True while a follow / follow_protect escort is running (a real task). */
	public static boolean hasFollowOrder() {
		return protectTarget != null;
	}

	/** Dashboard label for the running combat/escort order, or null. */
	public static String getOrderDescription() {
		if (attackTargetName != null) {
			return "ATTACK -> " + attackTargetName;
		}
		if (protectTarget != null) {
			return "ESCORT -> " + protectTarget;
		}
		return null;
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
		tickMlgWater(mc, player);

		lastHealth = player.getHealth();
	}

	// --------------------------------------------------------- death watch

	private static int deathTicks = 0;

	/** Returns true while dead (all other behaviour suspended). */
	private static boolean handleDeath(Minecraft mc, LocalPlayer player) {
		if (!player.isDeadOrDying()) {
			deathTicks = 0;
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
		
		deathTicks++;
		if (deathTicks > 60 && Math.random() < 0.01) {
			AIDashboardFrame.appendSystemLog("[SYSTEM] Auto-respawn triggered (failsafe).");
			clickRespawn();
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
		// Mid-combat we only interrupt for a critical stomach — and NEVER by
		// standing still with food out (free crits). The retreat choreography
		// runs the whole meal.
		if (isInCombat()) {
			if (hunger < HUNGER_CRITICAL || lowHealth) {
				beginCombatEatRetreat(mc, player);
			}
			return;
		}
		if (beginEating(mc, player)) {
			AIDashboardFrame.appendSystemLog("[EAT] Auto-eat engaged (hunger " + hunger + "/20, health " + health + ").");
		}
	}

	/**
	 * Starts the retreat-and-eat hold for ANY in-combat eat (low health or
	 * critical hunger): back away from the threat and only then chew. Shared
	 * by tickAutoEat and the low-health branch in tickCombatDefense.
	 */
	private static void beginCombatEatRetreat(Minecraft mc, LocalPlayer player) {
		if (combatEatHold) {
			return;
		}
		// Mid-air in the mace dive: pausing engage() now would freeze the
		// state machine in the air. The meal waits the ~2s until it lands.
		if (maceState != MACE_IDLE) {
			return;
		}
		LivingEntity threat = currentThreat != null ? currentThreat : attackTarget;
		combatEatHold = true;
		eatRetreatRepathCooldown = 0;
		eatChainRetryCooldown = 0;
		lastRetreatX = Integer.MIN_VALUE;
		lastRetreatZ = Integer.MIN_VALUE;
		releaseMovementKeys(mc);
		if (threat != null) {
			tickEatRetreatMovement(mc, player, threat);
		}
		// Full hunger (low-health trigger): vanilla refuses normal food at 20,
		// so don't start a doomed meal — kite while saturation regen heals
		// (the gapple override handles the real emergencies). Otherwise chew.
		if (player.getFoodData().needsFood()) {
			if (!beginEating(mc, player)) {
				// No food anywhere: nothing to chew, so don't hold the fight.
				combatEatHold = false;
				return;
			}
			AIDashboardFrame.appendSystemLog("[SYSTEM] Eating mid-combat — retreating from the threat first.");
		} else {
			AIDashboardFrame.appendSystemLog("[SYSTEM] Low health mid-combat — kiting away while regen works.");
		}
	}

	/**
	 * Keeps the bot moving AWAY from the threat for the whole meal. On foot:
	 * re-issue the Baritone retreat goal every second while the threat is
	 * close (a single 5-block goal gets caught up to). Mounted: steer the
	 * horse by look + forward key — it outruns everything.
	 */
	private static void tickEatRetreatMovement(Minecraft mc, LocalPlayer player, LivingEntity threat) {
		if (threat == null || !threat.isAlive()) {
			return;
		}
		boolean mounted = player.isPassenger() && player.getVehicle() instanceof LivingEntity;
		if (mounted) {
			Vec3 away = player.position().subtract(threat.position());
			Vec3 direction = away.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : away.normalize();
			Vec3 fleePoint = player.position().add(direction.scale(12.0));
			player.lookAt(EntityAnchorArgument.Anchor.EYES,
					new Vec3(fleePoint.x, player.getEyePosition().y, fleePoint.z));
			mc.options.keyUp.setDown(true);
			movementKeysActive = true;
			return;
		}
		if (eatRetreatRepathCooldown > 0) {
			eatRetreatRepathCooldown--;
			return;
		}
		if (player.distanceTo(threat) < 8.0) {
			retreatFrom(player, threat);
			eatRetreatRepathCooldown = 20;
		}
	}

	/**
	 * Shared combat-eat hold: true while the retreat-and-eat is still running
	 * (caller must skip engaging). Releases the hold once healed/fed.
	 */
	private static boolean holdCombatEat(Minecraft mc, LocalPlayer player, LivingEntity threat) {
		if (!combatEatHold) {
			return false;
		}
		float fleeThreshold = com.itdragclick.client.config.SettingsPersistenceManager.get().lowHealthThreshold;
		float safeHealth = Math.min(20.0f, fleeThreshold + 4.0f);
		if (player.getHealth() >= safeHealth || (!eating && player.getHealth() > fleeThreshold)) {
			combatEatHold = false;
			releaseMovementKeys(mc);
			AIDashboardFrame.appendSystemLog("[SYSTEM] Recovered — returning to the fight.");
			return false;
		}
		// Meal ended but the stomach still has room and health is still low:
		// chain the next food item — regen needs saturation to keep working.
		if (!eating && player.getFoodData().needsFood()) {
			if (eatChainRetryCooldown > 0) {
				eatChainRetryCooldown--;
			} else if (!beginEating(mc, player)) {
				eatChainRetryCooldown = 40; // out of food — stop rescanning every tick
			}
		}
		// Keep moving away for the whole meal — a parked bot eating in front
		// of a zombie is a free crit dispenser.
		tickEatRetreatMovement(mc, player, threat);
		return true;
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
		// Shield blocking on the held use key? A continuous press would keep
		// the shield up and the meal would never start: release it and hold
		// the food briefly first. No shield up = eat immediately, no warmup.
		boolean shieldWasUp = mc.options.keyUse.isDown() || player.isBlocking();
		mc.options.keyUse.setDown(false);
		eatWarmupTicks = shieldWasUp
				? com.itdragclick.client.config.SettingsPersistenceManager.get().eatWarmupTicks
				: 0;
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
		// Warmup: stand with the food out and the use key UP so the fresh
		// press lands on the food, not the still-blocking shield.
		if (eatWarmupTicks > 0) {
			eatWarmupTicks--;
			mc.options.keyUse.setDown(false);
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
		eatWarmupTicks = 0;
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
		// Keep the follow block-edit policy pinned: Baritone settings are
		// global and other tasks (mine, goto) flip them mid-escort. Combat
		// chases own the settings while combatMode is active.
		if (!combatMode) {
			BaritoneBridge.enforceFollowBlockPolicy();
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
		// Attack orders honour the retreat-and-eat hold too (this path never
		// reaches tickCombatDefense's hold check when combatMode is off).
		if (!combatMode && holdCombatEat(mc, player, attackTarget)) {
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
		if (rangedActive) {
			rangedActive = false;
			mc.options.keyUse.setDown(false);
		}
		releaseLance(mc);
		maceState = MACE_IDLE;
		maceTimer = 0;
		maceShieldPunishTicks = 0;
		macePunishSwingsLeft = 0;
		spamSwingCooldown = 0;
		critJumpCooldown = 0;
		critWaitTicks = 0;
		targetWasBlocking = false;
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
			// Even before combat locks a target: an arrow already in the air
			// heading our way gets blocked, not tanked.
			tickPassiveShield(mc, player);

			boolean lowHealth = player.getHealth() < HEALTH_PANIC_THRESHOLD;
			if (!tookDamage && !lowHealth) {
				// A manual attack order already owns engage() this tick —
				// opening a second combatMode fight would have two engage
				// calls wrestling over the aim and movement keys.
				if (attackTargetName != null) {
					return;
				}
				// Proactive defense: nearby creepers always count; other
				// hostiles only while a work task runs (setting-gated).
				LivingEntity proactive = findProactiveThreat(mc, player);
				if (proactive != null) {
					combatMode = true;
					AIStats.fightStarted();
					currentThreat = proactive;
					combatEatHold = false;
					BaritoneBridge.pauseForCombat();
					AIDashboardFrame.appendSystemLog("[SYSTEM] Hostile nearby ("
							+ proactive.getName().getString() + ") — pausing work to deal with it.");
				}
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
				AIStats.fightStarted();
				currentThreat = attacker;
				combatEatHold = false;
				// Feelings: a player attacking the bot costs relationship score
				// (once per combat engagement, not per hit-tick).
				if (tookDamage && attacker instanceof Player hostile) {
					String name = hostile.getName().getString();
					com.itdragclick.client.memory.PlayerRelationshipDB.modifyScore(name, -10);
					AIDashboardFrame.appendSystemLog("[RELATIONSHIP] " + name + " attacked me! Score -10 (now "
							+ com.itdragclick.client.memory.PlayerRelationshipDB.getScore(name) + ").");
				}
				// Cache the active objective (Baritone goal) before fighting.
				BaritoneBridge.pauseForCombat();
				AIDashboardFrame.appendSystemLog("[SYSTEM] Under attack! Pausing Baritone and defending myself.");
			}
			return;
		}

		// --- Critical health mid-fight: back off, eat, re-engage once healed
		// above the configured threshold (+4 buffer). Both the toggle and the
		// threshold come from settings, read fresh every tick.
		com.itdragclick.client.config.AIModSettings cfg = com.itdragclick.client.config.SettingsPersistenceManager.get();
		float fleeThreshold = cfg.lowHealthThreshold;
		boolean needsHeal = cfg.fleeOnLowHealth && player.getHealth() <= fleeThreshold;
		if (!combatEatHold && needsHeal && currentThreat != null) {
			beginCombatEatRetreat(mc, player);
		}
		if (holdCombatEat(mc, player, currentThreat)) {
			return; // hold combat while retreating/eating
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
		if (rangedActive) {
			rangedActive = false;
			mc.options.keyUse.setDown(false);
		}
		releaseLance(mc);
		maceState = MACE_IDLE;
		maceTimer = 0;
		maceShieldPunishTicks = 0;
		macePunishSwingsLeft = 0;
		spamSwingCooldown = 0;
		critJumpCooldown = 0;
		critWaitTicks = 0;
		targetWasBlocking = false;
		releaseMovementKeys(mc);
		// Mounted: there is no Baritone goal to restore and re-issuing one
		// while riding would fight the vehicle.
		if (resume && (mc.player == null || !mc.player.isPassenger())) {
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
		// The synced attacker is authoritative — accept it at long range and
		// regardless of type, so ANY entity that hits the bot gets fought back
		// (wolves, iron golems, bees... not just Monster subclasses).
		LivingEntity synced = player.getLastHurtByMob();
		if (synced != null && synced.isAlive() && !isFriendly(synced) && player.distanceTo(synced) <= 64.0) {
			return synced;
		}
		// Projectile trace: an arrow near us right after taking damage means
		// a ranged attacker — its owner is the real threat (skeleton snipers
		// far outside the melee scan radius).
		LivingEntity shooter = findProjectileShooter(mc, player);
		if (shooter != null) {
			return shooter;
		}
		// Nearest entity that CAN be hostile: monsters, neutral mobs (wolves,
		// golems, bees — they only qualify here right after we bled, so a
		// passive pig next to fall damage still never starts a massacre),
		// and other players.
		LivingEntity nearest = null;
		double nearestDistance = radius;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || living == player || !living.isAlive() || isFriendly(living)) {
				continue;
			}
			boolean canBeHostile = living instanceof Monster
					|| living instanceof net.minecraft.world.entity.NeutralMob
					|| living instanceof Player;
			if (!canBeHostile) {
				continue;
			}
			double distance = player.distanceTo(living);
			if (distance <= nearestDistance) {
				// Monsters win ties/priority over neutral mobs and players.
				if (nearest == null || distance < nearestDistance || living instanceof Monster) {
					nearest = living;
					nearestDistance = distance;
				}
			}
		}
		return nearest;
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

	/**
	 * Proactive threat pick (no damage taken yet). Creepers within alert range
	 * always qualify — waiting for the hiss is waiting too long. Everything
	 * else only qualifies while a work task (harvest/farm/craft) is running and
	 * the autoDefendWhileWorking toggle is on. Never first-strikes a mob that
	 * is Monster by class but neutral in behavior (zombified piglin, ...) —
	 * the synced AGGRESSIVE flag must be up before it counts.
	 */
	private static LivingEntity findProactiveThreat(Minecraft mc, LocalPlayer player) {
		if (proactiveScanCooldown > 0) {
			proactiveScanCooldown--;
			return null;
		}
		proactiveScanCooldown = 10;

		boolean working = AIStateManager.anythingActive() || FarmManager.isBusy() || HarvestManager.isBusy();
		boolean defendWork = com.itdragclick.client.config.SettingsPersistenceManager.get().autoDefendWhileWorking
				&& working;

		LivingEntity nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof Monster monster) || !monster.isAlive()) {
				continue;
			}
			// Neutral-until-provoked (zombified piglin etc.): leave alone
			// unless it is already aggro'd. isAggressive() reads the synced
			// entity-data flag, so it works client-side.
			if (monster instanceof NeutralMob && !(monster instanceof Mob m && m.isAggressive())) {
				continue;
			}
			double distance = player.distanceTo(monster);
			double limit = monster instanceof Creeper ? CREEPER_ALERT_RADIUS
					: (defendWork ? WORK_DEFEND_RADIUS : -1.0);
			if (distance <= limit && distance < nearestDistance) {
				nearest = monster;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/**
	 * Out-of-combat shield reflex: an arrow/trident in flight toward the bot
	 * raises the shield immediately instead of waiting for combatMode to lock
	 * a target after the hit lands.
	 */
	private static void tickPassiveShield(Minecraft mc, LocalPlayer player) {
		boolean want = com.itdragclick.client.config.SettingsPersistenceManager.get().useShieldWhileFighting
				&& !eating && !mlgActive && !rangedActive
				&& !lanceBraceActive && maceState == MACE_IDLE
				&& player.getOffhandItem().getItem() == net.minecraft.world.item.Items.SHIELD
				&& isProjectileIncoming(mc, player);
		if (want) {
			mc.options.keyUse.setDown(true);
			passiveShieldActive = true;
		} else if (passiveShieldActive) {
			mc.options.keyUse.setDown(false);
			passiveShieldActive = false;
		}
	}

	/**
	 * True when a hostile arrow/trident within 24 blocks is flying at us:
	 * its velocity vector points at the bot's eyes (dot > 0.9).
	 */
	private static boolean isProjectileIncoming(Minecraft mc, LocalPlayer player) {
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof AbstractArrow arrow) || arrow.isRemoved()) {
				continue;
			}
			if (player.distanceTo(arrow) > 24.0) {
				continue;
			}
			if (arrow.getOwner() == player
					|| (arrow.getOwner() instanceof LivingEntity owner && isFriendly(owner))) {
				continue;
			}
			Vec3 velocity = arrow.getDeltaMovement();
			if (velocity.lengthSqr() < 0.05) {
				continue; // stuck in the ground
			}
			Vec3 toBot = player.getEyePosition().subtract(arrow.position());
			if (toBot.lengthSqr() < 1.0E-4) {
				return true;
			}
			if (velocity.normalize().dot(toBot.normalize()) > 0.9) {
				return true;
			}
		}
		return false;
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
		// Golden apple override in progress: no swinging — and keep moving
		// away while chewing, a parked eater is a free crit dispenser.
		if (gappleActive) {
			tickEatRetreatMovement(mc, player, target);
			return;
		}
		player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());

		com.itdragclick.client.config.AIModSettings cfg = com.itdragclick.client.config.SettingsPersistenceManager.get();
		double distance = player.distanceTo(target);

		// Mace smash combo (jump + wind charge + falling mace hits) owns the
		// whole tick while its state machine is running.
		if (tickMaceSmash(mc, player, target, distance, cfg)) {
			return;
		}

		// Out of melee reach with a bow/crossbow available: stand and shoot
		// instead of chasing. Falls through to melee when ineligible.
		if (tickRangedAttack(mc, player, target, distance, cfg)) {
			return;
		}

		if (cfg.useShieldWhileFighting) {
			InventoryHelper.equipOffhand(mc, player, "shield");
		}
		if (shieldSuppressTicks > 0) {
			shieldSuppressTicks--;
		}

		boolean botBlocking = mc.options.keyUse.isDown() && player.getOffhandItem().getItem() == net.minecraft.world.item.Items.SHIELD;
		// Any shield-carrier (players, shielded piglin brutes, ...) triggers
		// the axe-break routine, not just players.
		boolean targetBlocking = target.isBlocking();
		// Riding a horse/camel: Baritone can't path a vehicle. Steer with the
		// forward key — a saddled mount follows the rider's look yaw, and
		// lookAt above already aims at the target.
		boolean mounted = player.isPassenger() && player.getVehicle() instanceof LivingEntity;

		// Creeper danger window: ignited, visibly swelling, or simply too
		// close. Block the blast and back off instead of trading hits.
		boolean creeperDanger = false;
		if (target instanceof Creeper creeper) {
			creeperDanger = creeper.isIgnited()
					|| creeper.getSwelling(1.0f) > 0.0f
					|| distance < CREEPER_DANGER_RADIUS;
		}

		// Stun slam: the tick the target's shield drops (axe just broke it),
		// open a short punish window — mace out, a few paced hits on the ground.
		if (maceShieldPunishTicks > 0) {
			maceShieldPunishTicks--;
		}
		if (spamSwingCooldown > 0) {
			spamSwingCooldown--;
		}
		if (critJumpCooldown > 0) {
			critJumpCooldown--;
		}
		double shieldBreakStart = cfg.shieldBreakRangeTenths / 10.0;
		if (targetWasBlocking && !targetBlocking && !mounted
				&& cfg.useMaceAttack && InventoryHelper.countItem(player, "mace") > 0) {
			maceShieldPunishTicks = 40;                        // punish window (time cap)
			macePunishSwingsLeft = cfg.macePunishMaxHits;      // hit cap, then normal weapon
			spamSwingCooldown = 0;                             // first mace hit lands THIS tick
			AIDashboardFrame.appendSystemLog("[MACE] Shield broken — punishing with the mace.");
		}
		targetWasBlocking = targetBlocking;

		if (targetBlocking && distance <= shieldBreakStart && !mounted) {
			// Stun slam step 1: axe breaks the shield. Pre-stage the mace into
			// the hotbar NOW — the backpack swap-click happens while we're
			// still chopping, so the punish switch is selection-only (instant).
			InventoryHelper.equipBestAxe(mc, player);
			if (cfg.useMaceAttack) {
				InventoryHelper.stageInHotbar(mc, player, "mace");
			}
		} else if (!eating) {
			// Punish window: keep the mace in hand (selectInHotbar no-ops when
			// already held). Otherwise — including during mace cooldown — fight
			// with the normal best weapon (sword/axe).
			if (!(maceShieldPunishTicks > 0
					&& InventoryHelper.selectInHotbar(mc, player, "mace"))) {
				InventoryHelper.equipBestWeapon(mc, player);
			}
		}
		// Lance mode: on a living mount with a spear in hand the whole fight
		// is charge passes — the kinetic spear component deals contact damage
		// scaled by ride speed while the use key is braced.
		boolean lanceMode = mounted && InventoryHelper.isSpear(player.getMainHandItem());
		if (!lanceMode) {
			releaseLance(mc);
		}

		if (chaseRepathCooldown > 0) {
			chaseRepathCooldown--;
		}

		if (creeperDanger && distance < CREEPER_ALERT_RADIUS && !mounted) {
			// Backpedal facing the creeper: shield stays toward the blast.
			BaritoneBridge.stopQuietly();
			mc.options.keyUp.setDown(false);
			mc.options.keyDown.setDown(true);
			player.setSprinting(false);
			movementKeysActive = true;
		} else if (mounted) {
			mc.options.keyDown.setDown(false);
			if (lanceMode) {
				tickLanceCharge(mc, player, target, distance);
			} else if (distance > STRIKE_RADIUS + 1.0 && distance <= COMBAT_TRACKING_RADIUS) {
				mc.options.keyUp.setDown(true);
				movementKeysActive = true;
			} else {
				releaseMovementKeys(mc);
			}
		} else if (distance > MELEE_RANGE && distance <= CHASE_MAX_RANGE) {
			mc.options.keyDown.setDown(false);
			if (distance > CHASE_PATHFIND_RANGE) {
				// Far: let Baritone chase the moving coordinates (throttled).
				releaseMovementKeys(mc);
				if (chaseRepathCooldown == 0) {
					BlockPos pos = target.blockPosition();
					BaritoneBridge.goToCombat(pos.getX(), pos.getY(), pos.getZ(), cfg.combatAllowBlocks);
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

		// Braced spear: contact damage IS the attack — no swings, and the use
		// key belongs to the brace, so the shield gate below must not run.
		if (lanceMode) {
			return;
		}

		// A creeper in its danger window gets blocked, not hit — landing one
		// more swing is never worth eating the blast.
		if (distance <= STRIKE_RADIUS && !creeperDanger) {
			// Mace in hand = spam (no shield needed on the target); the punish
			// window is just what PUTS it in hand after an axe shield break.
			boolean maceInHand = InventoryHelper.itemIdOf(player.getMainHandItem()).equals("mace");
			// Cooldown-bypass spam is paced (1 swing / 4 ticks) — full-speed
			// clicking looked bot-like and wasted swings.
			boolean spamAttack = false;
			boolean shouldAttack = false;
			if (targetBlocking && distance <= shieldBreakStart) {
				spamAttack = spamSwingCooldown == 0; // Break their shield: no cooldown wait
				shouldAttack = spamAttack;
			} else if (maceInHand) {
				spamAttack = spamSwingCooldown == 0; // Mace: paced spam, no cooldown wait
				shouldAttack = spamAttack;
			} else if (!targetBlocking && player.getAttackStrengthScale(0.0f) >= 1.0f) {
				// Plain sword/axe on an unshielded target: full attack cooldown,
				// no spam — a rushed swing is a weak swing.
				shouldAttack = true;
			}
			// Crit hits: vanilla wants the swing to land while FALLING, not
			// sprinting, out of water and off ladders (1.5x damage). Jump when
			// a swing is due and hold the hit until the way down. Skipped while
			// chopping a raised shield — crits do nothing against a block, and
			// the jump only delays the break.
			if (shouldAttack && cfg.useCritAttack && !targetBlocking
					&& !mounted && !creeperDanger && maceState == MACE_IDLE) {
				boolean critReady = !player.onGround() && player.fallDistance > 0
						&& player.getDeltaMovement().y < 0
						&& !player.isInWater() && !player.onClimbable();
				if (critReady) {
					player.setSprinting(false); // a sprint swing is knockback, not a crit
				} else if (critWaitTicks > 0) {
					critWaitTicks--;
					shouldAttack = false;       // airborne but still rising: hold the hit
				} else if (player.onGround() && critJumpCooldown == 0
						&& !player.isInWater() && !player.onClimbable()) {
					player.setSprinting(false);
					player.jumpFromGround();
					critJumpCooldown = Math.max(1, cfg.critJumpCooldownTicks);
					critWaitTicks = critJumpCooldown; // bail out if no crit window opens
					shouldAttack = false;
				}
				// critWaitTicks ran out (ceiling, water, ladder): swing anyway.
			}
			if (shouldAttack) {
				if (botBlocking) {
					// Legit play: never swing through our own raised shield.
					// Drop it now, swing next tick once the block is down.
					mc.options.keyUse.setDown(false);
					shieldSuppressTicks = Math.max(shieldSuppressTicks, 3);
				} else {
					critWaitTicks = 0;
					try {
						mc.gameMode.attack(player, target);
						player.swing(InteractionHand.MAIN_HAND);
					} catch (Exception e) {
						AmAI.LOGGER.error("[am-ai] Attack dispatch failed", e);
					}
					// Keep the shield down briefly around every swing.
					shieldSuppressTicks = Math.max(shieldSuppressTicks, 2);
					if (spamAttack) {
						spamSwingCooldown = Math.max(1, cfg.spamSwingDelayTicks);
						if (maceInHand && maceShieldPunishTicks > 0 && --macePunishSwingsLeft <= 0) {
							maceShieldPunishTicks = 0; // punish done — normal weapon next tick
						}
					}
				}
			}
		}
		if (targetBlocking) {
			// Whole shield-break sequence runs with our own shield down.
			shieldSuppressTicks = Math.max(shieldSuppressTicks, 2);
		}

		boolean targetHasRanged = target.getMainHandItem().getItem() == net.minecraft.world.item.Items.BOW ||
								  target.getMainHandItem().getItem() == net.minecraft.world.item.Items.CROSSBOW ||
								  target.getOffhandItem().getItem() == net.minecraft.world.item.Items.CROSSBOW;
		// Archer mid-draw: shield goes up before the arrow is loosed, not
		// after it lands.
		boolean targetDrawing = targetHasRanged && target.isUsingItem();
		boolean arrowIncoming = isProjectileIncoming(mc, player);

		double blockDistance = targetHasRanged ? 20.0 : (STRIKE_RADIUS + 2.0);
		boolean threatLooking = distance <= blockDistance
				&& target.getViewVector(1.0f).dot(player.position().subtract(target.position()).normalize()) > 0.5;

		// Creeper danger overrides the post-swing suppression: blocking the
		// blast beats squeezing in another hit.
		boolean wantShield = creeperDanger || arrowIncoming || targetDrawing || threatLooking;
		boolean canRaise = cfg.useShieldWhileFighting
				&& player.getOffhandItem().getItem() == net.minecraft.world.item.Items.SHIELD
				&& (creeperDanger || (shieldSuppressTicks == 0 && !targetBlocking));

		if (player.getOffhandItem().getItem() == net.minecraft.world.item.Items.SHIELD) {
			mc.options.keyUse.setDown(wantShield && canRaise);
		}
	}

	// ------------------------------------------------------- mounted lance

	/**
	 * One tick of mounted spear combat, retreat-then-charge loop: too close =
	 * ride away with the spear lowered until there's ~5 blocks of run-up, then
	 * brace (hold use) and charge straight at the target's body center — the
	 * kinetic spear deals contact damage scaled by ride speed. After blowing
	 * past, drop back to retreat for the next pass. The brace re-presses the
	 * use key whenever the hold times out.
	 */
	private static void tickLanceCharge(Minecraft mc, LocalPlayer player, LivingEntity target, double distance) {
		mc.options.keyUp.setDown(true);
		movementKeysActive = true;

		boolean passedTarget = lancePrevDistance >= 0
				&& distance > lancePrevDistance
				&& lancePrevDistance < STRIKE_RADIUS + 1.5;
		lancePrevDistance = distance;
		if (passedTarget) {
			lanceCharging = false; // pass done — back away for the next run-up
		}

		// RETREAT: contact damage scales with ride speed, so a charge started
		// on top of the target does nothing — the horse just shadows them.
		// Ride AWAY with the spear lowered (fresh brace for the charge) until
		// there's enough room, then commit.
		if (!lanceCharging) {
			if (distance < com.itdragclick.client.config.SettingsPersistenceManager.get().lanceChargeDistance) {
				if (lanceBraceActive) {
					lanceBraceActive = false;
					mc.options.keyUse.setDown(false);
				}
				Vec3 away = player.position().subtract(target.position());
				Vec3 flat = new Vec3(away.x, 0, away.z);
				if (flat.lengthSqr() < 1.0E-4) {
					Vec3 back = player.getLookAngle().scale(-1); // on top of them: any way out
					flat = new Vec3(back.x, 0, back.z);
				}
				if (flat.lengthSqr() > 1.0E-4) {
					Vec3 out = player.position().add(flat.normalize().scale(8.0));
					player.lookAt(EntityAnchorArgument.Anchor.EYES,
							new Vec3(out.x, player.getEyePosition().y, out.z));
				}
				return;
			}
			lanceCharging = true;
		}

		// CHARGE: brace the spear and ride straight at the target's body
		// center — no flank offset, no velocity lead, correct every tick.
		// Brace watchdog: the kinetic brace times out when held too long and a
		// still-held use key does NOT restart it — release for one tick, then
		// re-press.
		boolean braceDown = true;
		if (lanceRepressCooldown > 0) {
			lanceRepressCooldown--;
		}
		if (lanceBraceActive && !player.isUsingItem() && lanceRepressCooldown == 0) {
			braceDown = false;
			lanceRepressCooldown = 4;
		}
		mc.options.keyUse.setDown(braceDown);
		lanceBraceActive = true;
		player.lookAt(EntityAnchorArgument.Anchor.EYES,
				new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ()));
	}

	/** Drops the spear brace (use key) and resets the pass state. */
	private static void releaseLance(Minecraft mc) {
		if (lanceBraceActive) {
			lanceBraceActive = false;
			mc.options.keyUse.setDown(false);
		}
		lancePrevDistance = -1.0;
		lanceCharging = false;
		lanceRepressCooldown = 0;
	}

	// --------------------------------------------------------- mace smash

	/**
	 * Mace smash combo, setting-gated: jump, throw a wind charge at the feet
	 * at the jump apex (the burst stacks on the jump for max height), swap to
	 * the mace mid-air, then spam swings every 2 ticks for the whole descent
	 * (~4-5 hits) — the first connecting hit is the fall-distance smash.
	 * Returns true while the routine owns the tick. Hard rule: no wind charge
	 * in the inventory means the mace is never attempted at all.
	 */
	private static boolean tickMaceSmash(Minecraft mc, LocalPlayer player, LivingEntity target, double distance,
			com.itdragclick.client.config.AIModSettings cfg) {
		if (maceCooldown > 0) {
			maceCooldown--;
		}
		if (maceState == MACE_IDLE) {
			// A raised shield blocks the smash outright — don't waste the jump
			// and wind charge. Handing the tick back routes into the melee
			// shield-break branch (best axe + cooldown-bypass spam). The punish
			// window right after the break is ground mace spam, not a combo.
			boolean eligible = cfg.useMaceAttack && maceCooldown == 0
					&& !player.isPassenger() && !eating && !gappleActive && !combatEatHold
					&& !(target instanceof Creeper)
					&& !target.isBlocking()
					&& maceShieldPunishTicks == 0
					&& player.onGround()
					&& distance <= STRIKE_RADIUS
					&& InventoryHelper.countItem(player, "mace") > 0
					&& InventoryHelper.countItem(player, "wind_charge") > 0;
			if (!eligible) {
				return false;
			}
			// Own shield first: use is busy while blocking, so the wind charge
			// can't be thrown. Drop the shield now (suppression keeps the later
			// shield gate from re-raising it) and launch next tick.
			if (player.isBlocking()) {
				mc.options.keyUse.setDown(false);
				shieldSuppressTicks = Math.max(shieldSuppressTicks, 3);
				return false;
			}
			if (!InventoryHelper.selectInHotbar(mc, player, "wind_charge")) {
				maceCooldown = 100;
				return false;
			}
			BaritoneBridge.stopQuietly();
			releaseMovementKeys(mc);
			mc.options.keyUse.setDown(false);
			shieldSuppressTicks = Math.max(shieldSuppressTicks, 3);
			// Jump AND throw the same tick: the charge explodes at the feet
			// while the jump is still accelerating, so the burst stacks onto
			// full jump velocity (an apex throw arrives late and weak).
			player.setXRot(90.0f); // straight down for the feet throw
			player.jumpFromGround();
			mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
			maceState = MACE_LAUNCHING;
			maceTimer = 0;
			maceSwingCooldown = 0;
			maceHitsLanded = 0;
			AIDashboardFrame.appendSystemLog("[MACE] Smash combo: jump + wind charge, falling mace hit.");
			return true;
		}

		maceTimer++;
		if (maceTimer > 80) { // ~4s — something went wrong, bail out
			abortMaceSmash(mc, player);
			return false;
		}
		switch (maceState) {
			case MACE_LAUNCHING -> {
				player.setXRot(90.0f);
				if (player.getDeltaMovement().y > 0.5) {
					// Boosted — swap to the mace while rising.
					InventoryHelper.selectInHotbar(mc, player, "mace");
					maceState = MACE_AIRBORNE;
					maceTimer = 0;
				} else if (maceTimer > 12) {
					abortMaceSmash(mc, player); // burst never arrived
				}
			}
			case MACE_AIRBORNE -> tickMaceAirborne(mc, player, target);
			case MACE_LANDING -> {
				if (player.onGround() || maceTimer > 10) {
					maceState = MACE_IDLE;
					maceTimer = 0;
					InventoryHelper.equipBestWeapon(mc, player);
				}
			}
			default -> { }
		}
		return maceState != MACE_IDLE;
	}

	/**
	 * Falling with the mace out: track the target's live position (re-aimed
	 * every tick — no velocity lead, that's projectile-only), drift toward it,
	 * and spam swings through the last seconds of the descent.
	 */
	private static void tickMaceAirborne(Minecraft mc, LocalPlayer player, LivingEntity target) {
		if (player.onGround()) {
			if (maceHitsLanded > 0) {
				// Connected during the fall: the smash negated the fall damage,
				// wrap up cleanly (MACE_LANDING keeps MLG from wasting a bucket).
				maceState = MACE_LANDING;
				maceTimer = 0;
				maceCooldown = com.itdragclick.client.config.SettingsPersistenceManager.get().maceComboCooldownTicks;
				mc.options.keyUp.setDown(false);
			} else {
				abortMaceSmash(mc, player); // landed without connecting
			}
			return;
		}
		Vec3 velocity = player.getDeltaMovement();
		if (velocity.y > 0) {
			// Still rising: already chase the target through the air — a
			// step backwards must not be a free dodge.
			player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
			mc.options.keyUp.setDown(true);
			movementKeysActive = true;
			return;
		}
		// Melee gets no movement prediction: aim at where the target IS and
		// keep correcting every tick — leading the aim only helps projectiles
		// (bow/crossbow keep aimProjectileAtPredicted).
		int ticksToImpact = estimatePlayerFallTicks(player.getY(), velocity.y, target.getY());
		player.lookAt(EntityAnchorArgument.Anchor.EYES,
				new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ()));
		double horizontalGap = Math.hypot(target.getX() - player.getX(), target.getZ() - player.getZ());
		mc.options.keyUp.setDown(horizontalGap > 0.3); // stay glued to the intercept
		movementKeysActive = true;

		boolean smashArmed = player.fallDistance > 1.5f; // MaceItem.canSmashAttack threshold
		boolean inReach = player.distanceTo(target) <= 3.0;
		// Clear miss with the ground coming up: hand the tick back so the MLG
		// water routine can still save the landing (only if nothing connected).
		if (ticksToImpact <= 3 && !inReach && maceHitsLanded == 0) {
			maceState = MACE_IDLE;
			maceCooldown = com.itdragclick.client.config.SettingsPersistenceManager.get().maceComboCooldownTicks;
			mc.options.keyUp.setDown(false);
			AIDashboardFrame.appendSystemLog("[MACE] Smash missed — bailing for the landing.");
			return;
		}
		if (!smashArmed || !inReach) {
			return;
		}
		// Spam the whole descent: swing every 2 ticks while armed and in reach
		// — ~4-5 hits on a full-height fall. The first connecting hit is the
		// smash (fall-distance bonus), the rest keep the pressure on until
		// touchdown.
		if (maceSwingCooldown > 0) {
			maceSwingCooldown--;
			return;
		}
		try {
			mc.gameMode.attack(player, target);
			player.swing(InteractionHand.MAIN_HAND);
		} catch (Exception e) {
			AmAI.LOGGER.error("[am-ai] Mace smash dispatch failed", e);
		}
		maceSwingCooldown = 2;
		maceHitsLanded++;
		if (maceHitsLanded == 1) {
			AIDashboardFrame.appendSystemLog("[MACE] Smash delivered from " + String.format("%.1f", player.fallDistance) + " blocks of fall — spamming until landing.");
		}
	}

	/** Ticks until the falling player reaches targetY (gravity 0.08, drag 0.98). */
	private static int estimatePlayerFallTicks(double y, double verticalSpeed, double targetY) {
		double height = y - targetY;
		double v = verticalSpeed;
		for (int t = 1; t <= 60; t++) {
			v = (v - 0.08) * 0.98;
			height += v;
			if (height <= 0) {
				return t;
			}
		}
		return 60;
	}

	private static void abortMaceSmash(Minecraft mc, LocalPlayer player) {
		maceState = MACE_IDLE;
		maceCooldown = com.itdragclick.client.config.SettingsPersistenceManager.get().maceComboCooldownTicks;
		maceTimer = 0;
		mc.options.keyUp.setDown(false);
		InventoryHelper.equipBestWeapon(mc, player);
	}

	// --------------------------------------------------- predictive aiming

	/**
	 * Aims at where the target WILL be when the projectile arrives, not where
	 * it is: iterate flight time against the target's synced velocity, then
	 * add the ballistic drop (arrows: speed ~3.0 blocks/tick at full draw,
	 * gravity 0.05/tick², drag 0.99 — the 1.1 factor folds the drag in).
	 */
	private static void aimProjectileAtPredicted(LocalPlayer player, LivingEntity target, double projectileSpeed) {
		Vec3 eye = player.getEyePosition();
		Vec3 aimBase = target.position().add(0, target.getBbHeight() * 0.6, 0);
		// Horizontal lead ONLY: leading a jump aims over the target's head —
		// gravity brings them right back down within the arrow's flight time,
		// so left/right strafing is the only movement worth predicting. The
		// ballistic drop below handles the vertical axis.
		Vec3 velocity = target.getDeltaMovement();
		velocity = new Vec3(velocity.x, 0, velocity.z);
		double flightTicks = aimBase.distanceTo(eye) / projectileSpeed;
		Vec3 predicted = aimBase;
		for (int i = 0; i < 3; i++) {
			predicted = aimBase.add(velocity.scale(flightTicks));
			flightTicks = predicted.distanceTo(eye) / projectileSpeed;
		}
		double drop = 0.5 * 0.05 * flightTicks * flightTicks * 1.1;
		player.lookAt(EntityAnchorArgument.Anchor.EYES, predicted.add(0, drop, 0));
	}

	/** True while the bow/crossbow loop owns the use key. */
	private static boolean rangedActive = false;

	/**
	 * Ranged combat loop: when the target is beyond comfortable chase range
	 * but still tracked, and the inventory holds a bow/crossbow plus arrows,
	 * stand still and shoot. Bow: hold use to full draw (20 ticks), release
	 * fires. Crossbow: hold to charge (25 ticks), release loads, one more use
	 * press fires. Returns false (after cleaning up) when ineligible so the
	 * caller falls back to melee.
	 */
	private static boolean tickRangedAttack(Minecraft mc, LocalPlayer player, LivingEntity target, double distance,
			com.itdragclick.client.config.AIModSettings cfg) {
		// Cheap reads first: no bow/crossbow in the inventory means no ranged
		// attempt at all — no arrow scan, no hotbar churn, straight to melee.
		// Never on a living mount: charging with the spear IS the ranged plan
		// there, and swapping to a bow would break the brace every time the
		// distance crosses the threshold.
		boolean eligible = cfg.useBowCrossbow && !eating && !gappleActive
				&& !(player.isPassenger() && player.getVehicle() instanceof LivingEntity)
				&& distance > CHASE_PATHFIND_RANGE && distance <= COMBAT_TRACKING_RADIUS
				&& InventoryHelper.hasRangedWeapon(player)
				&& InventoryHelper.countArrows(player) > 0
				&& player.hasLineOfSight(target)
				&& InventoryHelper.equipRangedWeapon(mc, player);
		if (!eligible) {
			if (rangedActive) {
				// Target closed the distance (or ammo ran out): release the
				// draw and swap back to the melee weapon.
				rangedActive = false;
				mc.options.keyUse.setDown(false);
				InventoryHelper.equipBestWeapon(mc, player);
			}
			return false;
		}
		if (!rangedActive) {
			rangedActive = true;
			BaritoneBridge.stopQuietly();
			AIDashboardFrame.appendSystemLog("[COMBAT] Target out of melee reach — switching to ranged weapon.");
		}
		releaseMovementKeys(mc);
		ItemStack held = player.getMainHandItem();
		boolean isCrossbow = InventoryHelper.itemIdOf(held).equals("crossbow");
		// Lead the shot every tick — including the release tick, so the
		// projectile leaves on the predicted vector.
		aimProjectileAtPredicted(player, target, isCrossbow ? 3.15 : 3.0);
		if (isCrossbow) {
			var loaded = held.get(DataComponents.CHARGED_PROJECTILES);
			boolean charged = loaded != null && !loaded.isEmpty();
			if (charged) {
				mc.options.keyUse.setDown(false);
				mc.gameMode.useItem(player, InteractionHand.MAIN_HAND); // fire
			} else if (player.isUsingItem() && player.getTicksUsingItem() >= 25) {
				mc.options.keyUse.setDown(false); // release loads the bolt
			} else {
				mc.options.keyUse.setDown(true);
			}
		} else {
			if (player.isUsingItem() && player.getTicksUsingItem() >= 20) {
				mc.options.keyUse.setDown(false); // full draw — loose the arrow
			} else {
				mc.options.keyUse.setDown(true);
			}
		}
		return true;
	}

	/** Baritone-path 5 blocks directly away from the threat. */
	private static void retreatFrom(LocalPlayer player, LivingEntity threat) {
		Vec3 away = player.position().subtract(threat.position());
		Vec3 direction = away.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : away.normalize();
		Vec3 retreat = player.position().add(direction.scale(COMBAT_RETREAT_BLOCKS));
		int x = (int) Math.floor(retreat.x);
		int z = (int) Math.floor(retreat.z);
		// Cornered against a wall the goal stops changing — re-issuing the
		// identical goal every second only spams the log.
		if (x == lastRetreatX && z == lastRetreatZ) {
			return;
		}
		lastRetreatX = x;
		lastRetreatZ = z;
		BaritoneBridge.goToCombat(x, player.blockPosition().getY(), z, false);
	}

	/** True while an MLG drop is in progress (falling or waiting to scoop). */
	private static boolean mlgActive = false;
	/** Post-landing countdown before scooping the water back (-1 = not landed yet). */
	private static int mlgPickupDelay = -1;

	/**
	 * MLG water bucket, MAIN hand only — offhand SWAP staging glitched items.
	 * Falling more than 2 blocks: select a water bucket in the hotbar (swap it
	 * in from the backpack if needed), look straight down, hold use. After
	 * landing, wait 10 ticks (~500ms) so the water spreads/settles, then scoop
	 * it back up with the now-empty bucket.
	 */
	private static void tickMlgWater(Minecraft mc, LocalPlayer player) {
		if (!com.itdragclick.client.config.SettingsPersistenceManager.get().allowMlgWater) {
			return;
		}
		// The mace dive is a CONTROLLED fall — swapping to a water bucket
		// mid-smash would ruin it (a landed hit negates the fall damage; a
		// clear miss aborts the state above, re-arming this routine).
		if (maceState != MACE_IDLE) {
			return;
		}

		if (player.fallDistance > 2.0f && player.getDeltaMovement().y < -0.5) {
			if (InventoryHelper.selectInHotbar(mc, player, "water_bucket")) {
				mlgActive = true;
				mlgPickupDelay = -1;
				player.setXRot(90.0f); // Look straight down
				mc.options.keyUse.setDown(true);
			}
			return;
		}

		if (!mlgActive || player.fallDistance != 0.0f) {
			return; // still airborne (or nothing to clean up)
		}

		// Landed: release use immediately, then delay the pickup.
		if (mlgPickupDelay < 0) {
			mc.options.keyUse.setDown(false);
			mlgPickupDelay = 10; // 10 ticks = 500ms
			return;
		}
		if (mlgPickupDelay > 0) {
			mlgPickupDelay--;
			return;
		}

		// Scoop the water back: aim at the actual placed water source (it is
		// not always straight below after sliding off the splash), then use.
		if (InventoryHelper.selectInHotbar(mc, player, "bucket")) {
			BlockPos water = findNearbyWaterSource(mc, player);
			if (water != null) {
				player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(water));
			} else {
				player.setXRot(90.0f); // fallback: straight down
			}
			mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		}
		mlgActive = false;
		mlgPickupDelay = -1;
	}

	/** Nearest water SOURCE block within 2 blocks of the feet (scoopable), or null. */
	private static BlockPos findNearbyWaterSource(Minecraft mc, LocalPlayer player) {
		BlockPos feet = player.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 1, 2))) {
			var fluid = mc.level.getFluidState(p);
			if (fluid.is(net.minecraft.tags.FluidTags.WATER) && fluid.isSource()) {
				double d = p.distSqr(feet);
				if (d < bestDist) {
					bestDist = d;
					best = p.immutable();
				}
			}
		}
		return best;
	}

	private static void releaseMovementKeys(Minecraft mc) {
		if (movementKeysActive) {
			mc.options.keyUp.setDown(false);
			mc.options.keyDown.setDown(false);
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
		rangedActive = false;
		shieldSuppressTicks = 0;
		passiveShieldActive = false;
		proactiveScanCooldown = 0;
		lanceBraceActive = false;
		lancePrevDistance = -1.0;
		lanceCharging = false;
		lanceRepressCooldown = 0;
		maceState = MACE_IDLE;
		maceTimer = 0;
		maceCooldown = 0;
		maceSwingCooldown = 0;
		maceHitsLanded = 0;
		maceShieldPunishTicks = 0;
		macePunishSwingsLeft = 0;
		spamSwingCooldown = 0;
		critJumpCooldown = 0;
		critWaitTicks = 0;
		targetWasBlocking = false;
		eatRetreatRepathCooldown = 0;
		eatChainRetryCooldown = 0;
		lastRetreatX = Integer.MIN_VALUE;
		lastRetreatZ = Integer.MIN_VALUE;
		mlgActive = false;
		mlgPickupDelay = -1;
		protectTarget = null;
		clearAttackOrder();
		lastHealth = -1.0f;
		deathReported = false;
	}
}
