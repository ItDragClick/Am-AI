package com.itdragclick.client.ai;

import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * "#farm" agricultural module (main thread, tick-driven). Scans an 18-block
 * horizontal radius for mature crops, walks to each (Baritone), breaks it
 * (crops are instant-break), and replants the matching seed on the vacated
 * farmland. Repeats until no mature crops remain in the area.
 */
public final class FarmManager {

	/** Crop block id -> the seed item that replants it. */
	private static final Map<String, String> CROP_SEEDS = Map.of(
			"wheat", "wheat_seeds",
			"carrots", "carrot",
			"potatoes", "potato",
			"beetroots", "beetroot_seeds");

	private static final int SCAN_RADIUS = 18;
	private static final int SCAN_VERTICAL = 6;
	private static final double WORK_REACH = 4.0;
	private static final int STUCK_TIMEOUT_TICKS = 400;

	private static boolean active = false;
	private static BlockPos currentCrop;
	private static int cropTicks;
	private static int harvested;
	private static int maxDurationTicks;
	private static int activeTicks;

	private FarmManager() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(FarmManager::onTick);
	}

	public static boolean isBusy() {
		return active;
	}

	public static String getCurrentTaskDescription() {
		if (!active) return "IDLE";
		return "FARMING (" + harvested + " crops harvested)";
	}

	public static void start(boolean preempt, int durationSeconds) {
		if (preempt) {
			AIStateManager.preemptForNewTask();
		}
		active = true;
		currentCrop = null;
		harvested = 0;
		activeTicks = 0;
		maxDurationTicks = durationSeconds > 0 ? durationSeconds * 20 : 0;
		AIDashboardFrame.appendSystemLog("[FARM] Crop scan started (radius " + SCAN_RADIUS + ").");
	}

	public static void cancel() {
		if (active) {
			AIDashboardFrame.appendSystemLog("[FARM] Farming cancelled.");
		}
		active = false;
		currentCrop = null;
	}

	/** LIFO stack integration. */
	public static AIStateManager.TaskContext captureAndPause() {
		if (!active) {
			return null;
		}
		cancel();
		BaritoneBridge.stopQuietly();
		AIStateManager.TaskContext ctx = new AIStateManager.TaskContext();
		ctx.type = AIStateManager.TaskContext.Type.FARM;
		ctx.durationSeconds = maxDurationTicks > 0 ? Math.max(0, (maxDurationTicks - activeTicks) / 20) : 0;
		return ctx;
	}

	private static void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (!active || player == null || mc.level == null) {
			return;
		}
		activeTicks++;
		if (maxDurationTicks > 0 && activeTicks >= maxDurationTicks) {
			AIDashboardFrame.appendSystemLog("[FARM] Time limit reached.");
			announce(mc, "Farm time's up! Harvested " + harvested + " crops.");
			active = false;
			AIStateManager.taskCompleted();
			return;
		}

		if (currentCrop == null) {
			currentCrop = findMatureCrop(mc, player);
			cropTicks = 0;
			if (currentCrop == null) {
				AIDashboardFrame.appendSystemLog("[FARM] Area handled — " + harvested + " crop(s) harvested and replanted.");
				announce(mc, "Farm's all tidy! Harvested " + harvested + " crops.");
				active = false;
				AIStateManager.taskCompleted();
				return;
			}
			BaritoneBridge.goTo(currentCrop.getX(), currentCrop.getY(), currentCrop.getZ());
			return;
		}

		if (++cropTicks > STUCK_TIMEOUT_TICKS) {
			AIDashboardFrame.appendSystemLog("[FARM] Couldn't reach crop at " + currentCrop.toShortString() + " — skipping.");
			currentCrop = null;
			return;
		}

		BlockState state = mc.level.getBlockState(currentCrop);
		if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) {
			currentCrop = null; // broken/regressed meanwhile
			return;
		}
		if (!currentCrop.closerToCenterThan(player.position(), WORK_REACH)) {
			return; // Baritone still travelling
		}

		// In reach: break the crop (instant), replant, move on.
		BaritoneBridge.stopQuietly();
		String cropId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(currentCrop));
		mc.gameMode.destroyBlock(currentCrop);
		replant(mc, player, currentCrop, CROP_SEEDS.get(cropId));
		harvested++;
		currentCrop = null;
	}

	/** Look down and plant the seed back onto the vacated farmland block. */
	private static void replant(Minecraft mc, LocalPlayer player, BlockPos cropPos, String seedId) {
		if (seedId == null) {
			return;
		}
		int seedSlot = -1;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && InventoryHelper.itemIdOf(stack).equals(seedId)) {
				seedSlot = slot;
				break;
			}
		}
		if (seedSlot < 0) {
			return; // out of seeds — leave the plot empty
		}
		int hotbar = seedSlot < 9 ? seedSlot : InventoryHelper.FOOD_HOTBAR_SLOT;
		if (seedSlot >= 9) {
			InventoryHelper.swapIntoHotbar(mc, player, seedSlot, hotbar);
		}
		player.getInventory().setSelectedSlot(hotbar);
		BlockPos farmland = cropPos.below();
		player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(farmland));
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(farmland), Direction.UP, farmland, false);
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
	}

	private static BlockPos findMatureCrop(Minecraft mc, LocalPlayer player) {
		BlockPos center = player.blockPosition();
		BlockPos nearest = null;
		double nearestDistSq = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(
				center.offset(-SCAN_RADIUS, -SCAN_VERTICAL, -SCAN_RADIUS),
				center.offset(SCAN_RADIUS, SCAN_VERTICAL, SCAN_RADIUS))) {
			BlockState state = mc.level.getBlockState(pos);
			Block block = state.getBlock();
			if (!(block instanceof CropBlock crop) || !crop.isMaxAge(state)) {
				continue;
			}
			if (!CROP_SEEDS.containsKey(BuiltInRegistries.BLOCK.getKey(block).getPath())) {
				continue;
			}
			double distSq = pos.distSqr(center);
			if (distSq < nearestDistSq) {
				nearestDistSq = distSq;
				nearest = pos.immutable();
			}
		}
		return nearest;
	}

	private static void announce(Minecraft mc, String message) {
		if (mc.getConnection() != null) {
			mc.getConnection().sendChat(message.length() > 100 ? message.substring(0, 100) : message);
		}
	}
}
