package com.itdragclick.client.ai;

import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Recursive progressive crafting engine (main thread, tick-driven).
 * Resolves the dependency tree for tool requests and walks the classic
 * progression: wood -> wooden pickaxe -> stone -> stone pickaxe -> iron ore
 * -> furnace + smelting -> iron pickaxe.
 *
 * The planner is stateless-restartable: every tick it re-derives the next
 * step from the actual inventory, so interruptions (combat, LIFO preemption,
 * death) simply resume by re-running {@link #start}.
 *
 * Container work uses raw slot clicks (handleContainerInput): 2x2 recipes in
 * the player's InventoryMenu (grid slots 1-4, result 0), 3x3 in a placed
 * crafting table's CraftingMenu (grid 1-9, result 0), smelting in a placed
 * furnace (input 0, fuel 1, result 2).
 */
public final class CraftPlanner {

	private static final String[] LOG_TYPES = {
			"oak_log", "birch_log", "spruce_log", "jungle_log", "acacia_log",
			"dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log"};

	/** 3x3 recipes: pattern = CraftingMenu grid slot -> item id (one each). */
	private static final Map<String, Map<Integer, String>> TABLE_RECIPES = Map.of(
			"wooden_pickaxe", Map.of(1, "#planks", 2, "#planks", 3, "#planks", 5, "stick", 8, "stick"),
			"stone_pickaxe", Map.of(1, "cobblestone", 2, "cobblestone", 3, "cobblestone", 5, "stick", 8, "stick"),
			"iron_pickaxe", Map.of(1, "iron_ingot", 2, "iron_ingot", 3, "iron_ingot", 5, "stick", 8, "stick"),
			"furnace", Map.of(1, "cobblestone", 2, "cobblestone", 3, "cobblestone", 4, "cobblestone",
					6, "cobblestone", 7, "cobblestone", 8, "cobblestone", 9, "cobblestone"));

	private static final int STEP_TIMEOUT_TICKS = 1200; // 60s per step

	private static boolean active = false;
	private static String target;       // wooden_pickaxe | stone_pickaxe | iron_pickaxe
	private static String requester;
	private static String currentStepName = "";
	private static int stepTicks;
	private static BlockPos placedTable;
	private static BlockPos placedFurnace;
	private static boolean miningIssued;

	private CraftPlanner() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(CraftPlanner::onTick);
	}

	public static boolean isBusy() {
		return active;
	}

	public static String getCurrentTaskDescription() {
		if (!active) return "IDLE";
		return "CRAFTING " + target + " (Step: " + currentStepName + ")";
	}

	/** Returns true when we know how to craft this item progressively. */
	public static boolean canPlan(String itemId) {
		return itemId != null && switch (itemId) {
			case "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe" -> true;
			default -> false;
		};
	}

	public static void start(String itemId, String requestedBy, boolean preempt) {
		if (preempt) {
			AIStateManager.preemptForNewTask();
		}
		active = true;
		target = itemId;
		requester = requestedBy;
		currentStepName = "";
		stepTicks = 0;
		miningIssued = false;
		AIDashboardFrame.appendSystemLog("[CRAFT] Progressive plan started: " + target
				+ (requester != null ? " for " + requester : ""));
	}

	public static void cancel() {
		if (active) {
			AIDashboardFrame.appendSystemLog("[CRAFT] Crafting plan cancelled.");
		}
		active = false;
		target = null;
		requester = null;
	}

	/** LIFO stack integration: restart-safe, so context is just the goal. */
	public static AIStateManager.TaskContext captureAndPause() {
		if (!active) {
			return null;
		}
		AIStateManager.TaskContext ctx = new AIStateManager.TaskContext();
		ctx.type = AIStateManager.TaskContext.Type.CRAFT;
		ctx.item = target;
		ctx.requester = requester;
		cancel();
		BaritoneBridge.stopQuietly();
		return ctx;
	}

	// -------------------------------------------------------------- ticker

	private static void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (!active || player == null || mc.level == null) {
			return;
		}
		// Derive the next step fresh from the inventory every tick.
		String step = nextStep(player);
		if (!step.equals(currentStepName)) {
			currentStepName = step;
			stepTicks = 0;
			miningIssued = false;
			AIDashboardFrame.appendSystemLog("[CRAFT] Step: " + step);
		}
		if (++stepTicks > STEP_TIMEOUT_TICKS) {
			AIDashboardFrame.appendSystemLog("[CRAFT] Step '" + step + "' timed out — aborting plan.");
			announce(mc, "I got stuck crafting the " + target + ", sorry!");
			finish(mc, player, false);
			return;
		}

		switch (step) {
			case "done" -> finish(mc, player, true);
			case "gather_wood" -> mineOnce(() -> BaritoneBridge.mineAny(LOG_TYPES));
			case "craft_planks" -> craftPlanks(mc, player);
			case "craft_sticks" -> craftSticks(mc, player);
			case "craft_table" -> craft2x2Fill(mc, player, "#planks", 4);
			case "mine_stone" -> mineOnce(() -> BaritoneBridge.mine("stone"));
			case "mine_iron" -> mineOnce(() -> BaritoneBridge.mineAny("iron_ore", "deepslate_iron_ore"));
			case "craft_wooden_pickaxe" -> craftAtTable(mc, player, "wooden_pickaxe");
			case "craft_stone_pickaxe" -> craftAtTable(mc, player, "stone_pickaxe");
			case "craft_iron_pickaxe" -> craftAtTable(mc, player, "iron_pickaxe");
			case "craft_furnace" -> craftAtTable(mc, player, "furnace");
			case "smelt_iron" -> smeltIron(mc, player);
			default -> {
			}
		}
	}

	/**
	 * The recursive dependency resolver, flattened: evaluates the inventory
	 * and returns the deepest unmet prerequisite of the target.
	 */
	private static String nextStep(LocalPlayer player) {
		if (count(player, target) > 0) {
			return "done";
		}
		boolean needStone = target.equals("stone_pickaxe") || target.equals("iron_pickaxe");
		boolean needIron = target.equals("iron_pickaxe");

		// Wood tier prerequisites (planks/sticks/table feed every recipe).
		int planksNeeded = 3 + (hasTableAccess(player) ? 0 : 4);
		if (countPlanks(player) < planksNeeded && countLogs(player) > 0) {
			return "craft_planks";
		}
		if (countPlanks(player) < planksNeeded) {
			return "gather_wood";
		}
		if (count(player, "stick") < 2) {
			return "craft_sticks";
		}
		if (!hasTableAccess(player)) {
			return "craft_table";
		}

		if (!needStone) {
			return "craft_wooden_pickaxe";
		}
		// Stone tier.
		if (!hasAnyPickaxe(player)) {
			return "craft_wooden_pickaxe";
		}
		int cobbleNeeded = 3 + (needIron && count(player, "furnace") == 0 && placedFurnace == null ? 8 : 0);
		if (count(player, "cobblestone") < cobbleNeeded && count(player, "stone_pickaxe") == 0
				&& count(player, "iron_pickaxe") == 0) {
			return "mine_stone";
		}
		if (!needIron) {
			return "craft_stone_pickaxe";
		}
		// Iron tier.
		if (count(player, "stone_pickaxe") == 0 && count(player, "iron_pickaxe") == 0) {
			if (count(player, "cobblestone") < 3) {
				return "mine_stone";
			}
			return "craft_stone_pickaxe";
		}
		if (count(player, "iron_ingot") < 3) {
			if (count(player, "raw_iron") < 3) {
				return "mine_iron";
			}
			if (count(player, "furnace") == 0 && placedFurnace == null) {
				if (count(player, "cobblestone") < 8) {
					return "mine_stone";
				}
				return "craft_furnace";
			}
			return "smelt_iron";
		}
		return "craft_iron_pickaxe";
	}

	// ------------------------------------------------------------ actions

	private static void mineOnce(Runnable task) {
		if (!miningIssued) {
			task.run();
			miningIssued = true;
		}
	}

	/** 2x2: one log stack into grid slot 1, shift-take the planks. */
	private static void craftPlanks(Minecraft mc, LocalPlayer player) {
		if (stepTicks % 10 != 1) {
			return;
		}
		AbstractContainerMenu menu = player.inventoryMenu;
		int logSlot = findMenuSlot(menu, InventoryHelper::isLog, 9);
		if (logSlot < 0) {
			return;
		}
		click(mc, player, menu, logSlot, 0);   // stack to cursor
		click(mc, player, menu, 1, 0);         // place all in grid slot 1
		quickMove(mc, player, menu, 0);        // craft max planks
		click(mc, player, menu, 1, 0);         // reclaim leftover logs (if any)
		int home = findMenuSlot(menu, ItemStack::isEmpty, 9);
		if (home >= 0) {
			click(mc, player, menu, home, 0);  // drop cursor back into inventory
		}
	}

	/** 2x2: planks in grid 1 and 3 (vertical pair) -> sticks. */
	private static void craftSticks(Minecraft mc, LocalPlayer player) {
		if (stepTicks % 10 != 1) {
			return;
		}
		AbstractContainerMenu menu = player.inventoryMenu;
		int plankSlot = findMenuSlot(menu, s -> !s.isEmpty() && InventoryHelper.itemIdOf(s).endsWith("_planks"), 9);
		if (plankSlot < 0) {
			return;
		}
		click(mc, player, menu, plankSlot, 0);
		click(mc, player, menu, 1, 1);         // right-click: one plank
		click(mc, player, menu, 3, 1);
		click(mc, player, menu, plankSlot, 0); // return the rest
		quickMove(mc, player, menu, 0);        // take the sticks
		clearGrid(mc, player, menu, 1, 4);
	}

	/** 2x2: fill all four grid slots with one item each (crafting table). */
	private static void craft2x2Fill(Minecraft mc, LocalPlayer player, String item, int cells) {
		if (stepTicks % 10 != 1) {
			return;
		}
		AbstractContainerMenu menu = player.inventoryMenu;
		int source = findMenuSlot(menu, s -> matches(s, item), 9);
		if (source < 0) {
			return;
		}
		click(mc, player, menu, source, 0);
		for (int grid = 1; grid <= cells; grid++) {
			click(mc, player, menu, grid, 1);
		}
		click(mc, player, menu, source, 0);
		quickMove(mc, player, menu, 0);
		clearGrid(mc, player, menu, 1, 4);
	}

	/** Walks to / places / opens the crafting table, then lays the recipe. */
	private static void craftAtTable(Minecraft mc, LocalPlayer player, String recipe) {
		if (player.containerMenu instanceof CraftingMenu tableMenu) {
			if (stepTicks % 10 != 1) {
				return;
			}
			Map<Integer, String> pattern = TABLE_RECIPES.get(recipe);
			for (Map.Entry<Integer, String> cell : pattern.entrySet()) {
				if (tableMenu.slots.get(cell.getKey()).getItem().isEmpty()) {
					int source = findMenuSlot(tableMenu, s -> matches(s, cell.getValue()), 10);
					if (source < 0) {
						return; // ingredient missing — resolver will re-route
					}
					click(mc, player, tableMenu, source, 0);
					click(mc, player, tableMenu, cell.getKey(), 1);
					click(mc, player, tableMenu, source, 0);
				}
			}
			quickMove(mc, player, tableMenu, 0);   // take the crafted result
			clearGrid(mc, player, tableMenu, 1, 9);
			player.closeContainer();
			return;
		}
		ensureBlockPlacedAndOpen(mc, player, "crafting_table", placedTable, pos -> placedTable = pos);
	}

	/** Furnace: raw iron in, planks as fuel, wait for the ingots. */
	private static void smeltIron(Minecraft mc, LocalPlayer player) {
		if (player.containerMenu instanceof AbstractFurnaceMenu furnace) {
			if (stepTicks % 20 != 1) {
				return;
			}
			// Pull finished ingots first.
			if (!furnace.slots.get(2).getItem().isEmpty()) {
				quickMove(mc, player, furnace, 2);
			}
			if (count(player, "iron_ingot") >= 3) {
				player.closeContainer();
				return;
			}
			if (furnace.slots.get(0).getItem().isEmpty()) {
				int raw = findMenuSlot(furnace, s -> matches(s, "raw_iron"), 3);
				if (raw >= 0) {
					quickMoveTo(mc, player, furnace, raw, 0);
				}
			}
			if (furnace.slots.get(1).getItem().isEmpty()) {
				int fuel = findMenuSlot(furnace,
						s -> !s.isEmpty() && (InventoryHelper.itemIdOf(s).endsWith("_planks") || InventoryHelper.isLog(s)), 3);
				if (fuel >= 0) {
					quickMoveTo(mc, player, furnace, fuel, 1);
				}
			}
			return;
		}
		ensureBlockPlacedAndOpen(mc, player, "furnace", placedFurnace, pos -> placedFurnace = pos);
	}

	/** Places the block in front of the bot if needed, then right-clicks it. */
	private static void ensureBlockPlacedAndOpen(Minecraft mc, LocalPlayer player, String blockItem,
			BlockPos known, java.util.function.Consumer<BlockPos> remember) {
		if (stepTicks % 20 != 1) {
			return;
		}
		if (known != null && !mc.level.getBlockState(known).isAir()) {
			if (!known.closerToCenterThan(player.position(), 4.0)) {
				BaritoneBridge.goTo(known.getX(), known.getY(), known.getZ());
				return;
			}
			BaritoneBridge.stopQuietly();
			player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(known));
			mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(known), Direction.UP, known, false));
			return;
		}
		// Need to place it: find the item, select it, click the ground ahead.
		int slot = -1;
		for (int i = 0; i < 36; i++) {
			if (matches(player.getInventory().getItem(i), blockItem)) {
				slot = i;
				break;
			}
		}
		if (slot < 0) {
			return; // resolver will route back to crafting it
		}
		int hotbar = slot < 9 ? slot : InventoryHelper.FOOD_HOTBAR_SLOT;
		if (slot >= 9) {
			InventoryHelper.swapIntoHotbar(mc, player, slot, hotbar);
		}
		player.getInventory().setSelectedSlot(hotbar);
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos spot = player.blockPosition().relative(dir);
			if (mc.level.getBlockState(spot).isAir() && !mc.level.getBlockState(spot.below()).isAir()) {
				BlockPos ground = spot.below();
				player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(ground));
				mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false));
				remember.accept(spot);
				return;
			}
		}
	}

	private static void finish(Minecraft mc, LocalPlayer player, boolean success) {
		if (success) {
			AIDashboardFrame.appendSystemLog("[CRAFT] '" + target + "' crafted. Plan complete.");
			if (requester != null) {
				announce(mc, "Crafted your " + target + ", " + requester + "! Come grab it.");
				InventoryHelper.requestDrop(mc, player, target, 0);
			} else {
				announce(mc, "Crafted a " + target + "!");
			}
		}
		active = false;
		target = null;
		requester = null;
		AIStateManager.taskCompleted();
	}

	// ------------------------------------------------------------- helpers

	private static boolean hasTableAccess(LocalPlayer player) {
		return count(player, "crafting_table") > 0
				|| (placedTable != null && !player.level().getBlockState(placedTable).isAir());
	}

	private static boolean hasAnyPickaxe(LocalPlayer player) {
		return count(player, "wooden_pickaxe") > 0 || count(player, "stone_pickaxe") > 0
				|| count(player, "iron_pickaxe") > 0;
	}

	private static int count(LocalPlayer player, String itemId) {
		return InventoryHelper.countItem(player, itemId);
	}

	private static int countLogs(LocalPlayer player) {
		int total = 0;
		for (String log : LOG_TYPES) {
			total += count(player, log);
		}
		return total;
	}

	private static int countPlanks(LocalPlayer player) {
		int total = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty() && InventoryHelper.itemIdOf(stack).endsWith("_planks")) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static boolean matches(ItemStack stack, String spec) {
		if (stack.isEmpty()) {
			return false;
		}
		String id = InventoryHelper.itemIdOf(stack);
		return spec.equals("#planks") ? id.endsWith("_planks") : id.equals(spec);
	}

	private static int findMenuSlot(AbstractContainerMenu menu,
			java.util.function.Predicate<ItemStack> filter, int fromIndex) {
		for (int i = fromIndex; i < menu.slots.size(); i++) {
			if (filter.test(menu.slots.get(i).getItem())) {
				return i;
			}
		}
		return -1;
	}

	private static void click(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, int slot, int button) {
		mc.gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP, player);
	}

	private static void quickMove(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, int slot) {
		mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
	}

	private static void quickMoveTo(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, int source, int dest) {
		click(mc, player, menu, source, 0);
		click(mc, player, menu, dest, 0);
		click(mc, player, menu, source, 0);
	}

	private static void clearGrid(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, int from, int to) {
		for (int grid = from; grid <= to; grid++) {
			if (!menu.slots.get(grid).getItem().isEmpty()) {
				quickMove(mc, player, menu, grid);
			}
		}
	}

	private static void announce(Minecraft mc, String message) {
		if (mc.getConnection() != null) {
			mc.getConnection().sendChat(message.length() > 100 ? message.substring(0, 100) : message);
		}
	}
}
