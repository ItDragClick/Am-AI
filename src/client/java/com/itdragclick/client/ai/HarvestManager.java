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
 * Unified multi-step gathering state machine (main thread, tick-driven).
 *
 * Block Harvesting Chain: MINING until the requested quantity is met (no
 * hardcoded caps — the LLM's "quantity" field drives it), then delivery.
 * Mob Drops Chain (Porkchop Workflow): HUNTING keeps the combat loop fed and
 * vacuums ground drops between kills until the quantity is met.
 * Delivery: explicit chest coordinates (LLM "chest_coords") take priority,
 * then the requesting player (walk back + drop at their feet), then the
 * memory-bank chest, else hold.
 *
 * Integrates with {@link AIStateManager}'s LIFO stack: new tasks preempt via
 * captureAndPause(); completion pops the next queued task.
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

	/**
	 * Requested item -> the ore blocks Baritone must actually mine. Ores
	 * don't drop themselves ("mine diamond_ore" never puts 'diamond_ore' in
	 * the inventory), so counting always tracks the DROP item.
	 */
	private static final Map<String, String[]> ORE_SOURCES = Map.ofEntries(
			Map.entry("diamond", new String[]{"diamond_ore", "deepslate_diamond_ore"}),
			Map.entry("emerald", new String[]{"emerald_ore", "deepslate_emerald_ore"}),
			Map.entry("coal", new String[]{"coal_ore", "deepslate_coal_ore"}),
			Map.entry("redstone", new String[]{"redstone_ore", "deepslate_redstone_ore"}),
			Map.entry("lapis_lazuli", new String[]{"lapis_ore", "deepslate_lapis_ore"}),
			Map.entry("raw_iron", new String[]{"iron_ore", "deepslate_iron_ore"}),
			Map.entry("raw_gold", new String[]{"gold_ore", "deepslate_gold_ore"}),
			Map.entry("raw_copper", new String[]{"copper_ore", "deepslate_copper_ore"}));

	/** Ore block -> its drop (for requests phrased as the block name). */
	private static final Map<String, String> BLOCK_DROPS = Map.ofEntries(
			Map.entry("diamond_ore", "diamond"), Map.entry("deepslate_diamond_ore", "diamond"),
			Map.entry("emerald_ore", "emerald"), Map.entry("deepslate_emerald_ore", "emerald"),
			Map.entry("coal_ore", "coal"), Map.entry("deepslate_coal_ore", "coal"),
			Map.entry("redstone_ore", "redstone"), Map.entry("deepslate_redstone_ore", "redstone"),
			Map.entry("lapis_ore", "lapis_lazuli"), Map.entry("deepslate_lapis_ore", "lapis_lazuli"),
			Map.entry("iron_ore", "raw_iron"), Map.entry("deepslate_iron_ore", "raw_iron"),
			Map.entry("gold_ore", "raw_gold"), Map.entry("deepslate_gold_ore", "raw_gold"),
			Map.entry("copper_ore", "raw_copper"), Map.entry("stone", "cobblestone"));

	/** Drop item -> the minimum pickaxe tier needed to mine its ore. */
	private static final Map<String, String> REQUIRED_PICKAXE = Map.of(
			"diamond", "iron_pickaxe", "emerald", "iron_pickaxe",
			"redstone", "iron_pickaxe", "raw_gold", "iron_pickaxe",
			"raw_iron", "stone_pickaxe", "lapis_lazuli", "stone_pickaxe",
			"raw_copper", "stone_pickaxe",
			"coal", "wooden_pickaxe", "cobblestone", "wooden_pickaxe");

	public static final int DEFAULT_BLOCK_TARGET = 16;
	public static final int DEFAULT_MOB_DROP_TARGET = 3;
	private static final double DELIVERY_RADIUS = 2.0;
	private static final double CHEST_REACH = 4.0;
	private static final int OPEN_RETRY_TICKS = 60;
	private static final int RETURN_REPATH_INTERVAL_TICKS = 40;
	private static final int RETURN_PATIENCE_TICKS = 1200; // 60s to find the player

	private static Phase phase = Phase.IDLE;
	private static String targetItemId;
	/** Blocks Baritone actually digs (differs from targetItemId for ores). */
	private static String[] mineBlockIds;
	private static String huntMobName;
	private static int targetCount;
	private static int baselineCount;
	private static String requester;
	private static int[] chestOverride;
	private static int phaseTicks;
	private static int repathCooldown;
	private static int maxDurationTicks;

	private HarvestManager() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(HarvestManager::onTick);
	}

	public static boolean isBusy() {
		return phase != Phase.IDLE;
	}

	public static String getCurrentTaskDescription() {
		if (phase == Phase.IDLE) return "IDLE";
		String target = targetCount + " x " + targetItemId;
		return switch (phase) {
			case MINING -> "MINING " + target;
			case HUNTING -> "HUNTING for " + target;
			case RETURN_TO_PLAYER -> "DELIVERING " + target + " to " + requester;
			case GOTO_CHEST -> "MOVING to chest with " + target;
			case DEPOSITING -> "DEPOSITING " + target + " into chest";
			default -> "HARVESTING " + target;
		};
	}

	// -------------------------------------------------------------- orders

	public static void startHarvest(String blockId, int quantity, String requestedBy, int[] chest, int durationSeconds, boolean preempt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		String resolved = RegistryResolver.resolveBlock(blockId);
		if (resolved == null) {
			resolved = RegistryResolver.resolveItem(blockId);
		}
		if (resolved == null) {
			AIDashboardFrame.appendSystemLog("[HARVEST] Unknown block '" + blockId + "' — no registry match, ignoring.");
			return;
		}
		// Ore chains: mine the ore blocks, count the DROP item.
		String countItem = BLOCK_DROPS.getOrDefault(resolved, resolved);
		String[] sources = ORE_SOURCES.get(countItem);
		if (sources == null) {
			sources = new String[]{resolved};
		}

		if (preempt) {
			AIStateManager.preemptForNewTask();
		}
		targetItemId = countItem;
		mineBlockIds = sources;
		huntMobName = null;
		targetCount = quantity > 0 ? quantity : DEFAULT_BLOCK_TARGET;
		requester = requestedBy;
		chestOverride = chest;
		maxDurationTicks = durationSeconds > 0 ? durationSeconds * 20 : 0;
		baselineCount = InventoryHelper.countItem(mc.player, targetItemId);

		// Tool gating: no adequate pickaxe means the plan can't succeed —
		// park this harvest on the LIFO stack and craft the tool first.
		String neededPick = REQUIRED_PICKAXE.get(countItem);
		if (neededPick != null && !hasPickaxeTier(mc.player, neededPick) && CraftPlanner.canPlan(neededPick)) {
			AIDashboardFrame.appendSystemLog("[HARVEST] Plan for " + targetCount + " x '" + countItem + "':");
			AIDashboardFrame.appendSystemLog("  1. Craft a " + neededPick + " (progressive chain)");
			AIDashboardFrame.appendSystemLog("  2. Mine " + String.join("/", sources));
			AIDashboardFrame.appendSystemLog("  3. Deliver"
					+ (chest != null ? " to chest (" + chest[0] + ", " + chest[1] + ", " + chest[2] + ")" : " to " + requestedBy));
			announce(mc, "I need a " + neededPick + " first — crafting one, then I'll get your " + countItem + "!");
			AIStateManager.TaskContext pending = captureAndPause();
			com.itdragclick.client.ai.AIStateManager.pushTask(pending);
			CraftPlanner.start(neededPick, null, false);
			return;
		}

		phase = Phase.MINING;
		phaseTicks = 0;
		BaritoneBridge.mineAny(mineBlockIds);
		AIDashboardFrame.appendSystemLog("[HARVEST] Plan started: gather " + targetCount + " x '"
				+ targetItemId + "' (mining " + String.join("/", sources) + ") for "
				+ (requester != null ? requester : "the base")
				+ (chest != null ? " -> chest (" + chest[0] + ", " + chest[1] + ", " + chest[2] + ")" : "") 
				+ (maxDurationTicks > 0 ? " [Limit: " + (maxDurationTicks / 20) + "s]" : "") + ".");
		if (requester != null) {
			AIMemoryStore.addFact("Player " + requester + " asked for " + targetCount + " " + targetItemId);
		}
	}

	/** True when the inventory holds this pickaxe tier or better. */
	private static boolean hasPickaxeTier(LocalPlayer player, String needed) {
		String[] atLeast = switch (needed) {
			case "iron_pickaxe" -> new String[]{"iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe"};
			case "stone_pickaxe" -> new String[]{"stone_pickaxe", "iron_pickaxe", "golden_pickaxe",
					"diamond_pickaxe", "netherite_pickaxe"};
			default -> new String[]{"wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "golden_pickaxe",
					"diamond_pickaxe", "netherite_pickaxe"};
		};
		for (String pick : atLeast) {
			if (InventoryHelper.countItem(player, pick) > 0) {
				return true;
			}
		}
		return false;
	}

	/** Back-compat entry for the re-gear loop. */
	public static void startHarvest(String blockId, String requestedBy) {
		startHarvest(blockId, DEFAULT_BLOCK_TARGET, requestedBy, null, 0, true);
	}

	public static void startHunt(String mobName, String dropItemId, int quantity, String requestedBy, int durationSeconds, boolean preempt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		if (preempt) {
			AIStateManager.preemptForNewTask();
		}
		huntMobName = mobName.toLowerCase(Locale.ROOT);
		targetItemId = dropItemId;
		targetCount = quantity > 0 ? quantity : DEFAULT_MOB_DROP_TARGET;
		requester = requestedBy;
		chestOverride = null;
		maxDurationTicks = durationSeconds > 0 ? durationSeconds * 20 : 0;
		baselineCount = InventoryHelper.countItem(mc.player, targetItemId);
		phase = Phase.HUNTING;
		phaseTicks = 0;
		SurvivalMonitor.requestAttack(huntMobName);
		AIDashboardFrame.appendSystemLog("[HUNT] Plan started: hunt '" + huntMobName + "' until "
				+ targetCount + " x '" + targetItemId + "' collected for " + requester + (maxDurationTicks > 0 ? " [Limit: " + (maxDurationTicks / 20) + "s]" : "") + ".");
		if (requester != null) {
			AIMemoryStore.addFact("Player " + requester + " asked for " + targetItemId);
		}
	}

	/** deposit_chest action: remember the chest and deliver current haul. */
	public static void depositAtChest(int x, int y, int z) {
		AIMemoryStore.setKnownChest(x, y, z);
		AIDashboardFrame.appendSystemLog("[HARVEST] Drop-off chest registered at (" + x + ", " + y + ", " + z + ")");
		if (targetItemId != null) {
			chestOverride = new int[]{x, y, z};
			phase = Phase.GOTO_CHEST;
			phaseTicks = 0;
			BaritoneBridge.goTo(x, y, z);
		}
	}

	/** Combat interrupted us: re-issue the current milestone's Baritone task. */
	public static void reissueCurrentStep() {
		switch (phase) {
			case MINING -> BaritoneBridge.mineAny(mineBlockIds != null ? mineBlockIds : new String[]{targetItemId});
			case HUNTING -> SurvivalMonitor.requestAttack(huntMobName);
			case RETURN_TO_PLAYER -> repathCooldown = 0;
			case GOTO_CHEST -> {
				int[] chest = deliveryChest();
				if (chest != null) {
					BaritoneBridge.goTo(chest[0], chest[1], chest[2]);
				}
			}
			default -> {
			}
		}
	}

	/** LIFO stack integration: serialize remaining progress and go quiet. */
	public static AIStateManager.TaskContext captureAndPause() {
		if (phase == Phase.IDLE) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		AIStateManager.TaskContext ctx = new AIStateManager.TaskContext();
		ctx.item = targetItemId;
		ctx.requester = requester;
		ctx.chest = chestOverride;
		int remaining = targetCount;
		if (mc.player != null && targetItemId != null) {
			remaining = Math.max(1, targetCount - (InventoryHelper.countItem(mc.player, targetItemId) - baselineCount));
		}
		ctx.quantity = remaining;
		ctx.durationSeconds = maxDurationTicks > 0 ? Math.max(0, (maxDurationTicks - phaseTicks) / 20) : 0;
		if (huntMobName != null) {
			ctx.type = AIStateManager.TaskContext.Type.HUNT;
			ctx.mob = huntMobName;
		} else {
			ctx.type = AIStateManager.TaskContext.Type.HARVEST;
		}
		phase = Phase.IDLE;
		targetItemId = null;
		mineBlockIds = null;
		huntMobName = null;
		requester = null;
		chestOverride = null;
		BaritoneBridge.stopQuietly();
		SurvivalMonitor.clearAllOrders();
		return ctx;
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
		chestOverride = null;
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
		if (maxDurationTicks > 0 && phaseTicks >= maxDurationTicks) {
			AIDashboardFrame.appendSystemLog("[" + (hunting ? "HUNT" : "HARVEST") + "] Time limit reached.");
			if (hunting) {
				SurvivalMonitor.clearAllOrders();
			} else {
				BaritoneBridge.stop();
			}
			beginDelivery();
			return;
		}
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

	/** Delivery priority: explicit chest coords > requester > memory chest. */
	private static void beginDelivery() {
		int[] chest = chestOverride;
		if (chest != null) {
			phase = Phase.GOTO_CHEST;
			phaseTicks = 0;
			BaritoneBridge.goTo(chest[0], chest[1], chest[2]);
			AIDashboardFrame.appendSystemLog("[DELIVER] Heading to the specified chest ("
					+ chest[0] + ", " + chest[1] + ", " + chest[2] + ").");
			return;
		}
		if (requester != null) {
			phase = Phase.RETURN_TO_PLAYER;
			phaseTicks = 0;
			repathCooldown = 0;
			AIDashboardFrame.appendSystemLog("[DELIVER] Returning to " + requester + " with the goods.");
			return;
		}
		AIMemoryStore.ChestPos memoryChest = AIMemoryStore.getKnownChest();
		if (memoryChest != null) {
			chestOverride = new int[]{memoryChest.x, memoryChest.y, memoryChest.z};
			beginDelivery();
			return;
		}
		AIDashboardFrame.appendSystemLog("[DELIVER] No chest and no requester — holding items, going idle.");
		finishPlan();
	}

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
			if (--repathCooldown <= 0) {
				BlockPos pos = target.blockPosition();
				BaritoneBridge.goTo(pos.getX(), pos.getY(), pos.getZ());
				repathCooldown = RETURN_REPATH_INTERVAL_TICKS;
			}
			return;
		}
		BaritoneBridge.stop();
		int dropped = InventoryHelper.requestDrop(mc, player, targetItemId, 0); // drop all gathered
		announce(mc, dropped > 0
				? "Here you go, " + requester + "! Dropped your " + targetItemId + "."
				: "Huh, I seem to have lost the " + targetItemId + " somewhere...");
		finishPlan();
	}

	private static int[] deliveryChest() {
		if (chestOverride != null) {
			return chestOverride;
		}
		AIMemoryStore.ChestPos chest = AIMemoryStore.getKnownChest();
		return chest != null ? new int[]{chest.x, chest.y, chest.z} : null;
	}

	private static boolean isContainer(Minecraft mc, BlockPos pos) {
		String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).getPath();
		return name.contains("chest") || name.contains("barrel") || name.contains("shulker_box");
	}

	private static void tickGotoChest(Minecraft mc, LocalPlayer player) {
		int[] chest = deliveryChest();
		if (chest == null) {
			finishPlan();
			return;
		}
		BlockPos pos = new BlockPos(chest[0], chest[1], chest[2]);
		if (!pos.closerToCenterThan(player.position(), CHEST_REACH)) {
			return; // Baritone still travelling
		}
		BaritoneBridge.stop();

		if (!isContainer(mc, pos)) {
			BlockPos found = null;
			for (BlockPos p : BlockPos.betweenClosed(pos.offset(-3, -3, -3), pos.offset(3, 3, 3))) {
				if (isContainer(mc, p)) {
					found = p.immutable();
					break;
				}
			}
			if (found != null) {
				pos = found;
				chestOverride = new int[]{pos.getX(), pos.getY(), pos.getZ()};
				AIDashboardFrame.appendSystemLog("[HARVEST] Chest moved — updated target to " + pos.toShortString());
			} else {
				int dropped = InventoryHelper.requestDrop(mc, player, targetItemId, 0);
				announce(mc, "The chest is missing! I dropped " + dropped + " '" + targetItemId + "' right here.");
				AIDashboardFrame.appendSystemLog("[HARVEST] Chest missing. Dropped items as fallback. Plan complete.");
				finishPlan();
				return;
			}
		}

		// Open the chest: right-click it.
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
		announce(mc, "Delivered the " + targetItemId + " to the chest!"
				+ (requester != null ? " Enjoy, " + requester + "!" : ""));
		finishPlan();
	}

	// ------------------------------------------------------------- helpers

	private static void finishPlan() {
		phase = Phase.IDLE;
		targetItemId = null;
		mineBlockIds = null;
		huntMobName = null;
		requester = null;
		chestOverride = null;
		AIStateManager.taskCompleted();
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
