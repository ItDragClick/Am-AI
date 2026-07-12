package com.itdragclick.client.ai;

import com.itdragclick.AmAI;
import com.itdragclick.client.memory.AIMemoryStore;
import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;

/**
 * Unified multi-step state machine for resource workflows. Everything runs
 * on the main game thread via {@code ClientTickEvents.END_CLIENT_TICK}; the
 * heavy lifting (pathing, digging, chasing) is Baritone's / the combat
 * loop's, so no tick is ever blocked.
 *
 * Block Harvesting Chain ("i want some logs"):
 *   MINING -> RETURN_TO_PLAYER -> drop_items at their feet -> IDLE
 * Mob Drops Chain — the Porkchop Workflow ("find me porkchop" => attack pig):
 *   HUNTING (combat loop kills matching animals until the drops are in the
 *   inventory) -> RETURN_TO_PLAYER -> drop_items within 2 blocks -> IDLE
 * deposit_chest remains the explicit container delivery path.
 */
public final class HarvestManager {

	private enum Phase {IDLE, MINING, HUNTING, RETURN_TO_PLAYER, GOTO_CHEST, DEPOSITING}

	/** Animal -> the drop we're actually after (the Porkchop Workflow map). */
	public static final Map<String, String> MOB_DROPS = Map.of(
			"pig", "porkchop",
			"cow", "beef",
			"chicken", "chicken",
			"sheep", "mutton",
			"rabbit", "rabbit");

	private static final int DEFAULT_BLOCK_TARGET = 16;
	private static final int DEFAULT_MOB_DROP_TARGET = 3;
	private static final double DELIVERY_RADIUS = 2.0;
	private static final double CHEST_REACH = 4.0;
	private static final int OPEN_RETRY_TICKS = 60;
	private static final int RETURN_REPATH_INTERVAL_TICKS = 40;
	private static final int RETURN_PATIENCE_TICKS = 1200; // 60s to find the player

	private static Phase phase = Phase.IDLE;
	private static String targetItemId;
	private static String huntMobName;
	private static int targetCount;
	private static int baselineCount;
	private static String requester;
	private static int phaseTicks;
	private static int repathCooldown;

	private HarvestManager() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(HarvestManager::onTick);
	}

	public static boolean isBusy() {
		return phase != Phase.IDLE;
	}

	// -------------------------------------------------------------- orders

	/** Block Harvesting Chain, step 1: Baritone digs; we track accumulation. */
	public static void startHarvest(String blockId, String requestedBy) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		targetItemId = blockId.toLowerCase(Locale.ROOT).replace("minecraft:", "");
		huntMobName = null;
		targetCount = DEFAULT_BLOCK_TARGET;
		requester = requestedBy;
		baselineCount = InventoryHelper.countItem(mc.player, targetItemId);
		phase = Phase.MINING;
		phaseTicks = 0;
		BaritoneBridge.mine(targetItemId);
		AIDashboardFrame.appendSystemLog("[HARVEST] Plan started: gather " + targetCount + " x '"
				+ targetItemId + "' for " + (requester != null ? requester : "the base") + ".");
		if (requester != null) {
			AIMemoryStore.addFact("Player " + requester + " asked for " + targetItemId);
		}
	}

	/**
	 * Porkchop Workflow, step 1 (Hunt): combat loop chases + kills matching
	 * animals while we watch the drop count in the inventory.
	 */
	public static void startHunt(String mobName, String dropItemId, String requestedBy) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		huntMobName = mobName.toLowerCase(Locale.ROOT);
		targetItemId = dropItemId;
		targetCount = DEFAULT_MOB_DROP_TARGET;
		requester = requestedBy;
		baselineCount = InventoryHelper.countItem(mc.player, targetItemId);
		phase = Phase.HUNTING;
		phaseTicks = 0;
		SurvivalMonitor.requestAttack(huntMobName);
		AIDashboardFrame.appendSystemLog("[HUNT] Plan started: hunt '" + huntMobName + "' until "
				+ targetCount + " x '" + targetItemId + "' collected for " + requester + ".");
		if (requester != null) {
			AIMemoryStore.addFact("Player " + requester + " asked for " + targetItemId);
		}
	}

	/** deposit_chest action: remember the chest and deliver current haul. */
	public static void depositAtChest(int x, int y, int z) {
		AIMemoryStore.setKnownChest(x, y, z);
		AIDashboardFrame.appendSystemLog("[HARVEST] Drop-off chest registered at (" + x + ", " + y + ", " + z + ")");
		if (targetItemId != null) {
			phase = Phase.GOTO_CHEST;
			phaseTicks = 0;
			BaritoneBridge.goTo(x, y, z);
		}
	}

	/** Combat interrupted us: re-issue the current milestone's Baritone task. */
	public static void reissueCurrentStep() {
		switch (phase) {
			case MINING -> BaritoneBridge.mine(targetItemId);
			case HUNTING -> SurvivalMonitor.requestAttack(huntMobName);
			case RETURN_TO_PLAYER -> repathCooldown = 0;
			case GOTO_CHEST -> {
				AIMemoryStore.ChestPos chest = AIMemoryStore.getKnownChest();
				if (chest != null) {
					BaritoneBridge.goTo(chest.x, chest.y, chest.z);
				}
			}
			default -> {
			}
		}
	}

	/** stop action / death: abandon the plan cleanly. */
	public static void cancel() {
		if (phase != Phase.IDLE) {
			AIDashboardFrame.appendSystemLog("[HARVEST] Plan cancelled.");
		}
		phase = Phase.IDLE;
		targetItemId = null;
		huntMobName = null;
		requester = null;
	}

	// -------------------------------------------------------------- ticker

	private static void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (phase == Phase.IDLE || player == null || mc.level == null) {
			return;
		}
		phaseTicks++;
		switch (phase) {
			case MINING -> tickGathering(player, false);
			case HUNTING -> tickGathering(player, true);
			case RETURN_TO_PLAYER -> tickReturnToPlayer(mc, player);
			case GOTO_CHEST -> tickGotoChest(mc, player);
			case DEPOSITING -> tickDepositing(mc, player);
			default -> {
			}
		}
	}

	/** Shared accumulation watcher for MINING and HUNTING (checked 1x/sec). */
	private static void tickGathering(LocalPlayer player, boolean hunting) {
		if (phaseTicks % 20 != 0) {
			return;
		}
		int gathered = InventoryHelper.countItem(player, targetItemId) - baselineCount;
		if (gathered >= targetCount) {
			AIDashboardFrame.appendSystemLog("[" + (hunting ? "HUNT" : "HARVEST") + "] Quantity met ("
					+ gathered + " x '" + targetItemId + "').");
			if (hunting) {
				SurvivalMonitor.clearAllOrders();
			} else {
				BaritoneBridge.stop();
			}
			beginDelivery();
			return;
		}
		if (hunting && !SurvivalMonitor.isInCombat()) {
			// Kills leave the drops lying on the ground — walk over the
			// nearest matching item entity to vacuum it up before chasing
			// the next animal, or the inventory count never grows.
			ItemEntity drop = findNearestDrop(player, targetItemId, 24.0);
			if (drop != null) {
				BlockPos pos = drop.blockPosition();
				BaritoneBridge.goTo(pos.getX(), pos.getY(), pos.getZ());
				return;
			}
			// No loose drops left: keep the combat loop fed with fresh orders.
			SurvivalMonitor.requestAttack(huntMobName);
		}
	}

	private static ItemEntity findNearestDrop(LocalPlayer player, String itemId, double radius) {
		Minecraft mc = Minecraft.getInstance();
		ItemEntity nearest = null;
		double nearestDistance = radius;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof ItemEntity item) || !item.isAlive()) {
				continue;
			}
			if (!InventoryHelper.itemIdOf(item.getItem()).equals(itemId)) {
				continue;
			}
			double distance = player.distanceTo(item);
			if (distance <= nearestDistance) {
				nearest = item;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	private static void beginDelivery() {
		// Preferred: walk back to the requesting player and drop at their feet.
		if (requester != null) {
			phase = Phase.RETURN_TO_PLAYER;
			phaseTicks = 0;
			repathCooldown = 0;
			AIDashboardFrame.appendSystemLog("[DELIVER] Returning to " + requester + " with the goods.");
			return;
		}
		// No requester (e.g. re-gear loop): use the chest if one is known.
		AIMemoryStore.ChestPos chest = AIMemoryStore.getKnownChest();
		if (chest != null) {
			phase = Phase.GOTO_CHEST;
			phaseTicks = 0;
			BaritoneBridge.goTo(chest.x, chest.y, chest.z);
			return;
		}
		AIDashboardFrame.appendSystemLog("[DELIVER] No requester and no chest — holding items, going idle.");
		phase = Phase.IDLE;
	}

	/**
	 * Steps 2+3 of both chains: track the requester's live position, path to
	 * them, and drop the goods within 2 blocks of their feet.
	 */
	private static void tickReturnToPlayer(Minecraft mc, LocalPlayer player) {
		Player target = findPlayerByName(mc, requester);
		if (target == null) {
			if (phaseTicks > RETURN_PATIENCE_TICKS) {
				announce(mc, "Can't find you, " + requester + "! Holding your " + targetItemId + ".");
				AIDashboardFrame.appendSystemLog("[DELIVER] Requester not found — holding items, going idle.");
				finishPlan();
			}
			return;
		}
		double distance = player.distanceTo(target);
		if (distance > DELIVERY_RADIUS) {
			// Chase the player's real-time coordinates (repath throttled).
			if (--repathCooldown <= 0) {
				BlockPos pos = target.blockPosition();
				BaritoneBridge.goTo(pos.getX(), pos.getY(), pos.getZ());
				repathCooldown = RETURN_REPATH_INTERVAL_TICKS;
			}
			return;
		}
		BaritoneBridge.stop();
		int dropped = InventoryHelper.dropAllOf(mc, player, targetItemId);
		announce(mc, dropped > 0
				? "Here you go, " + requester + "! Dropped your " + targetItemId + "."
				: "Huh, I seem to have lost the " + targetItemId + " somewhere...");
		finishPlan();
	}

	private static void tickGotoChest(Minecraft mc, LocalPlayer player) {
		AIMemoryStore.ChestPos chestPos = AIMemoryStore.getKnownChest();
		if (chestPos == null) {
			phase = Phase.IDLE;
			return;
		}
		BlockPos pos = new BlockPos(chestPos.x, chestPos.y, chestPos.z);
		if (!pos.closerToCenterThan(player.position(), CHEST_REACH)) {
			return; // Baritone still travelling
		}
		BaritoneBridge.stop();
		// Open the chest: right-click its top face.
		Vec3 center = Vec3.atCenterOf(pos);
		player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, center);
		BlockHitResult hit = new BlockHitResult(center, Direction.UP, pos, false);
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		phase = Phase.DEPOSITING;
		phaseTicks = 0;
	}

	private static void tickDepositing(Minecraft mc, LocalPlayer player) {
		AbstractContainerMenu menu = player.containerMenu;
		if (!(menu instanceof ChestMenu chestMenu)) {
			// Screen not open yet; retry the click a few times, then give up.
			if (phaseTicks > OPEN_RETRY_TICKS) {
				AIDashboardFrame.appendSystemLog("[HARVEST] Could not open the drop-off chest — holding items.");
				announce(mc, "Chest won't open — I'm keeping the " + targetItemId + " for now.");
				finishPlan();
			} else if (phaseTicks % 20 == 0) {
				phase = Phase.GOTO_CHEST; // re-approach + re-click
			}
			return;
		}
		int chestSlots = chestMenu.getRowCount() * 9;
		int moved = 0;
		// Player inventory slots sit after the chest slots in the chest menu.
		for (int slotIndex = chestSlots; slotIndex < menu.slots.size(); slotIndex++) {
			ItemStack stack = menu.slots.get(slotIndex).getItem();
			if (!stack.isEmpty() && InventoryHelper.itemIdOf(stack).equals(targetItemId)) {
				mc.gameMode.handleContainerInput(menu.containerId, slotIndex, 0, ContainerInput.QUICK_MOVE, player);
				moved++;
			}
		}
		player.closeContainer();
		AIDashboardFrame.appendSystemLog("[HARVEST] Deposited " + moved + " stack(s) of '" + targetItemId + "'. Plan complete.");
		announce(mc, "Delivered the " + targetItemId + " to the chest!");
		finishPlan();
	}

	// ------------------------------------------------------------- helpers

	private static void finishPlan() {
		phase = Phase.IDLE;
		targetItemId = null;
		huntMobName = null;
		requester = null;
	}

	private static Player findPlayerByName(Minecraft mc, String name) {
		if (name == null) {
			return null;
		}
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof Player other
					&& other.getName().getString().equalsIgnoreCase(name)
					&& other.isAlive()) {
				return other;
			}
		}
		return null;
	}

	private static void announce(Minecraft mc, String message) {
		try {
			if (mc.getConnection() != null) {
				mc.getConnection().sendChat(message.length() > 100 ? message.substring(0, 100) : message);
			}
		} catch (Exception e) {
			AmAI.LOGGER.error("[am-ai] Delivery announcement failed", e);
		}
	}
}
