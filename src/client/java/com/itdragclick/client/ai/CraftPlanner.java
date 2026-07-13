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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

public final class CraftPlanner {

    private static final String[] LOG_TYPES = {
            "oak_log", "birch_log", "spruce_log", "jungle_log", "acacia_log",
            "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log"};

    public record Recipe(boolean needsTable, Map<Integer, String> grid) {}
    public record SmeltRecipe(String input, String result) {}

    private static final Map<String, Recipe> RECIPES = Map.ofEntries(
        Map.entry("wooden_pickaxe", new Recipe(true, Map.of(1, "#planks", 2, "#planks", 3, "#planks", 5, "stick", 8, "stick"))),
        Map.entry("stone_pickaxe", new Recipe(true, Map.of(1, "cobblestone", 2, "cobblestone", 3, "cobblestone", 5, "stick", 8, "stick"))),
        Map.entry("iron_pickaxe", new Recipe(true, Map.of(1, "iron_ingot", 2, "iron_ingot", 3, "iron_ingot", 5, "stick", 8, "stick"))),
        Map.entry("diamond_pickaxe", new Recipe(true, Map.of(1, "diamond", 2, "diamond", 3, "diamond", 5, "stick", 8, "stick"))),
        Map.entry("iron_sword", new Recipe(true, Map.of(2, "iron_ingot", 5, "iron_ingot", 8, "stick"))),
        Map.entry("diamond_sword", new Recipe(true, Map.of(2, "diamond", 5, "diamond", 8, "stick"))),
        Map.entry("stone_sword", new Recipe(true, Map.of(2, "cobblestone", 5, "cobblestone", 8, "stick"))),
        Map.entry("iron_axe", new Recipe(true, Map.of(1, "iron_ingot", 2, "iron_ingot", 4, "iron_ingot", 5, "stick", 8, "stick"))),
        Map.entry("iron_chestplate", new Recipe(true, Map.of(1, "iron_ingot", 3, "iron_ingot", 4, "iron_ingot", 5, "iron_ingot", 6, "iron_ingot", 7, "iron_ingot", 8, "iron_ingot", 9, "iron_ingot"))),
        Map.entry("furnace", new Recipe(true, Map.of(1, "cobblestone", 2, "cobblestone", 3, "cobblestone", 4, "cobblestone", 6, "cobblestone", 7, "cobblestone", 8, "cobblestone", 9, "cobblestone"))),
        Map.entry("crafting_table", new Recipe(false, Map.of(1, "#planks", 2, "#planks", 3, "#planks", 4, "#planks"))),
        Map.entry("stick", new Recipe(false, Map.of(1, "#planks", 3, "#planks")))
    );

    private static final Map<String, SmeltRecipe> SMELT = Map.of(
        "iron_ingot", new SmeltRecipe("raw_iron", "iron_ingot"),
        "gold_ingot", new SmeltRecipe("raw_gold", "gold_ingot"),
        "copper_ingot", new SmeltRecipe("raw_copper", "copper_ingot")
    );

    private static final int STEP_TIMEOUT_TICKS = 1200; // 60s per step

    private static boolean active = false;
    private static String target;
    private static String requester;
    private static String currentStepName = "";
    private static int stepTicks;
    private static BlockPos placedTable;
    private static BlockPos placedFurnace;
    private static boolean miningIssued;

    private CraftPlanner() {}

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

    public static boolean canPlan(String itemId) {
        return itemId != null && (RECIPES.containsKey(itemId) || SMELT.containsKey(itemId) || itemId.equals("#planks"));
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
        AIDashboardFrame.appendSystemLog("[CRAFT] Dynamic plan started: " + target
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

    public static AIStateManager.TaskContext captureAndPause() {
        if (!active) return null;
        AIStateManager.TaskContext ctx = new AIStateManager.TaskContext();
        ctx.type = AIStateManager.TaskContext.Type.CRAFT;
        ctx.item = target;
        ctx.requester = requester;
        cancel();
        BaritoneBridge.stopQuietly();
        return ctx;
    }

    private static void onTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (!active || player == null || mc.level == null) return;
        
        String step = resolveDeepestNeed(player, target, 1);
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

        if (step.equals("done")) {
            finish(mc, player, true);
        } else if (step.equals("craft_planks")) {
            craftPlanks(mc, player);
        } else if (step.startsWith("craft:")) {
            String item = step.substring(6);
            craftGeneric(mc, player, RECIPES.get(item), item);
        } else if (step.startsWith("smelt:")) {
            String item = step.substring(6);
            smeltGeneric(mc, player, SMELT.get(item), item);
        } else if (step.startsWith("mine:")) {
            String mineTarget = step.substring(5);
            if (mineTarget.equals("oak_log")) mineOnce(() -> BaritoneBridge.mineAny(LOG_TYPES));
            else if (mineTarget.equals("cobblestone")) mineOnce(() -> BaritoneBridge.mine("stone"));
            else {
                String ore = mineTarget.replace("raw_", "") + "_ore";
                String deepOre = "deepslate_" + ore;
                if (mineTarget.equals("diamond")) { ore = "diamond_ore"; deepOre = "deepslate_diamond_ore"; }
                final String fOre = ore;
                final String fDeepOre = deepOre;
                mineOnce(() -> BaritoneBridge.mineAny(fOre, fDeepOre));
            }
        }
    }

    private static String resolveDeepestNeed(LocalPlayer player, String itemNeeded, int requiredCount) {
        if (count(player, itemNeeded) >= requiredCount) return "done";
        
        if (SMELT.containsKey(itemNeeded)) {
            SmeltRecipe smelt = SMELT.get(itemNeeded);
            int missing = requiredCount - count(player, itemNeeded);
            if (count(player, smelt.input) < missing) return resolveDeepestNeed(player, smelt.input, missing);
            if (count(player, "furnace") == 0 && placedFurnace == null) return resolveDeepestNeed(player, "furnace", 1);
            return "smelt:" + itemNeeded;
        }
        
        if (RECIPES.containsKey(itemNeeded) || itemNeeded.equals("#planks")) {
            if (itemNeeded.equals("#planks")) {
                if (countPlanks(player) >= requiredCount) return "done";
                if (countLogs(player) == 0) return resolveDeepestNeed(player, "oak_log", 1);
                return "craft_planks";
            }
            
            Recipe recipe = RECIPES.get(itemNeeded);
            Map<String, Integer> reqs = new HashMap<>();
            for (String i : recipe.grid().values()) {
                reqs.put(i, reqs.getOrDefault(i, 0) + 1);
            }
            
            for (Map.Entry<String, Integer> req : reqs.entrySet()) {
                String subItem = req.getKey();
                int current = subItem.equals("#planks") ? countPlanks(player) : count(player, subItem);
                if (current < req.getValue()) return resolveDeepestNeed(player, subItem, req.getValue());
            }
            
            if (recipe.needsTable() && !hasTableAccess(player)) {
                return resolveDeepestNeed(player, "crafting_table", 1);
            }
            return "craft:" + itemNeeded;
        }
        
        String requiredPick = HarvestManager.getRequiredPickaxe(itemNeeded);
        if (requiredPick != null && !hasPickaxeTier(player, requiredPick)) {
            return resolveDeepestNeed(player, requiredPick, 1);
        }
        
        return "mine:" + itemNeeded;
    }

    private static void mineOnce(Runnable task) {
        if (!miningIssued) {
            task.run();
            miningIssued = true;
        }
    }

    private static void craftPlanks(Minecraft mc, LocalPlayer player) {
        if (stepTicks % 10 != 1) return;
        AbstractContainerMenu menu = player.inventoryMenu;
        int logSlot = findMenuSlot(menu, InventoryHelper::isLog, 9);
        if (logSlot < 0) return;
        click(mc, player, menu, logSlot, 0);
        click(mc, player, menu, 1, 0);
        quickMove(mc, player, menu, 0);
        click(mc, player, menu, 1, 0);
        int home = findMenuSlot(menu, ItemStack::isEmpty, 9);
        if (home >= 0) click(mc, player, menu, home, 0);
    }

    private static void craftGeneric(Minecraft mc, LocalPlayer player, Recipe recipe, String targetId) {
        if (recipe.needsTable()) {
            if (player.containerMenu instanceof CraftingMenu tableMenu) {
                if (stepTicks % 10 != 1) return;
                for (Map.Entry<Integer, String> cell : recipe.grid().entrySet()) {
                    if (tableMenu.slots.get(cell.getKey()).getItem().isEmpty()) {
                        int source = findMenuSlot(tableMenu, s -> matches(s, cell.getValue()), 10);
                        if (source < 0) return;
                        click(mc, player, tableMenu, source, 0);
                        click(mc, player, tableMenu, cell.getKey(), 1);
                        click(mc, player, tableMenu, source, 0);
                    }
                }
                quickMove(mc, player, tableMenu, 0);
                clearGrid(mc, player, tableMenu, 1, 9);
                player.closeContainer();
                return;
            }
            ensureBlockPlacedAndOpen(mc, player, "crafting_table", placedTable, pos -> placedTable = pos);
        } else {
            if (stepTicks % 10 != 1) return;
            AbstractContainerMenu menu = player.inventoryMenu;
            for (Map.Entry<Integer, String> cell : recipe.grid().entrySet()) {
                if (menu.slots.get(cell.getKey()).getItem().isEmpty()) {
                    int source = findMenuSlot(menu, s -> matches(s, cell.getValue()), 9);
                    if (source < 0) return;
                    click(mc, player, menu, source, 0);
                    click(mc, player, menu, cell.getKey(), 1);
                    click(mc, player, menu, source, 0);
                }
            }
            quickMove(mc, player, menu, 0);
            clearGrid(mc, player, menu, 1, 4);
        }
    }

    private static void smeltGeneric(Minecraft mc, LocalPlayer player, SmeltRecipe recipe, String targetId) {
        if (player.containerMenu instanceof AbstractFurnaceMenu furnace) {
            if (stepTicks % 20 != 1) return;
            if (!furnace.slots.get(2).getItem().isEmpty()) {
                quickMove(mc, player, furnace, 2);
            }
            if (count(player, targetId) > 0) {
                player.closeContainer();
                return;
            }
            if (furnace.slots.get(0).getItem().isEmpty()) {
                int raw = findMenuSlot(furnace, s -> matches(s, recipe.input()), 3);
                if (raw >= 0) quickMoveTo(mc, player, furnace, raw, 0);
            }
            if (furnace.slots.get(1).getItem().isEmpty()) {
                int fuel = findMenuSlot(furnace, s -> !s.isEmpty() && (InventoryHelper.itemIdOf(s).endsWith("_planks") || InventoryHelper.isLog(s)), 3);
                if (fuel >= 0) quickMoveTo(mc, player, furnace, fuel, 1);
            }
            return;
        }
        ensureBlockPlacedAndOpen(mc, player, "furnace", placedFurnace, pos -> placedFurnace = pos);
    }

    private static void ensureBlockPlacedAndOpen(Minecraft mc, LocalPlayer player, String blockItem,
            BlockPos known, java.util.function.Consumer<BlockPos> remember) {
        if (stepTicks % 20 != 1) return;
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
        int slot = -1;
        for (int i = 0; i < 36; i++) {
            if (matches(player.getInventory().getItem(i), blockItem)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return;
        int hotbar = slot < 9 ? slot : InventoryHelper.FOOD_HOTBAR_SLOT;
        if (slot >= 9) InventoryHelper.swapIntoHotbar(mc, player, slot, hotbar);
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

    private static boolean hasTableAccess(LocalPlayer player) {
        return count(player, "crafting_table") > 0
                || (placedTable != null && !player.level().getBlockState(placedTable).isAir());
    }

    private static boolean hasPickaxeTier(LocalPlayer player, String needed) {
        String[] atLeast = switch (needed) {
            case "iron_pickaxe" -> new String[]{"iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe"};
            case "stone_pickaxe" -> new String[]{"stone_pickaxe", "iron_pickaxe", "golden_pickaxe", "diamond_pickaxe", "netherite_pickaxe"};
            default -> new String[]{"wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "golden_pickaxe", "diamond_pickaxe", "netherite_pickaxe"};
        };
        for (String pick : atLeast) {
            if (count(player, pick) > 0) return true;
        }
        return false;
    }

    private static int count(LocalPlayer player, String itemId) {
        return InventoryHelper.countItem(player, itemId);
    }

    private static int countLogs(LocalPlayer player) {
        int total = 0;
        for (String log : LOG_TYPES) total += count(player, log);
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
        if (stack.isEmpty()) return false;
        String id = InventoryHelper.itemIdOf(stack);
        return spec.equals("#planks") ? id.endsWith("_planks") : id.equals(spec);
    }

    private static int findMenuSlot(AbstractContainerMenu menu, java.util.function.Predicate<ItemStack> filter, int fromIndex) {
        for (int i = fromIndex; i < menu.slots.size(); i++) {
            if (filter.test(menu.slots.get(i).getItem())) return i;
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
