package com.itdragclick.client.ai;

import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Handles smooth tweening and duration-based actions.
 */
public final class ActionHelper {

    private static float targetPitch = 0;
    private static float targetYaw = 0;
    private static int turnTicks = 0;
    private static float startPitch = 0;
    private static float startYaw = 0;
    private static int maxTurnTicks = 0;

    private static String walkDir = null;
    private static int walkTicks = 0;

    private static int breakTicks = 0;
    private static int[] breakPos = null;

    private static int placeTicks = 0;
    private static String placeBlock = null;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ActionHelper::onTick);
    }

    private ActionHelper() {}

    public static void turnHead(float pitch, float yaw, int speedTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            startPitch = mc.player.getXRot();
            startYaw = mc.player.getYRot();
            targetPitch = pitch;
            targetYaw = yaw;
            maxTurnTicks = speedTicks > 0 ? speedTicks : 1;
            turnTicks = 0;
            AIDashboardFrame.appendSystemLog("[ACTION] Turning to pitch " + pitch + ", yaw " + yaw + " over " + maxTurnTicks + " ticks.");
        }
    }

    public static void walk(String direction, int distanceBlocks) {
        walkDir = direction.toLowerCase();
        walkTicks = distanceBlocks * 4; // Roughly 4 ticks per block walking
        AIDashboardFrame.appendSystemLog("[ACTION] Walking " + walkDir + " for " + walkTicks + " ticks.");
    }

    public static void breakBlock(int x, int y, int z, int durationSeconds) {
        breakPos = new int[]{x, y, z};
        breakTicks = durationSeconds > 0 ? durationSeconds * 20 : 20; // default 1 second
        AIDashboardFrame.appendSystemLog("[ACTION] Breaking block at " + x + " " + y + " " + z + " for " + breakTicks + " ticks.");
    }

    public static void placeBlock(String block, int durationSeconds) {
        placeBlock = block;
        placeTicks = durationSeconds > 0 ? durationSeconds * 20 : 20; // default 1 second
        AIDashboardFrame.appendSystemLog("[ACTION] Placing " + block + " for " + placeTicks + " ticks.");
    }

    public static void cancelAll() {
        turnTicks = maxTurnTicks;
        walkTicks = 0;
        breakTicks = 0;
        placeTicks = 0;
        releaseWalkKeys();
    }

    private static void releaseWalkKeys() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
        }
    }

    private static void onTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        // Turn Tweening
        if (turnTicks < maxTurnTicks) {
            turnTicks++;
            float progress = (float) turnTicks / maxTurnTicks;
            // Ease-in-out cubic
            float ease = progress < 0.5f ? 4 * progress * progress * progress : 1 - (float)Math.pow(-2 * progress + 2, 3) / 2;
            player.setXRot(startPitch + (targetPitch - startPitch) * ease);
            // Handle yaw wrap-around shortest path
            float yawDiff = (targetYaw - startYaw) % 360;
            if (yawDiff > 180) yawDiff -= 360;
            if (yawDiff < -180) yawDiff += 360;
            player.setYRot(startYaw + yawDiff * ease);
        }

        // Walking
        if (walkTicks > 0) {
            walkTicks--;
            mc.options.keyUp.setDown(walkDir.equals("forward"));
            mc.options.keyDown.setDown(walkDir.equals("backward") || walkDir.equals("back"));
            mc.options.keyLeft.setDown(walkDir.equals("left"));
            mc.options.keyRight.setDown(walkDir.equals("right"));
            if (walkTicks == 0) {
                releaseWalkKeys();
            }
        }

        // Breaking
        if (breakTicks > 0 && breakPos != null) {
            breakTicks--;
            BlockPos pos = new BlockPos(breakPos[0], breakPos[1], breakPos[2]);
            if (mc.level != null && !mc.level.getBlockState(pos).isAir()) {
                mc.gameMode.continueDestroyBlock(pos, Direction.UP);
                player.swing(InteractionHand.MAIN_HAND);
            }
            if (breakTicks == 0) {
                mc.gameMode.stopDestroyBlock();
            }
        }

        // Placing
        if (placeTicks > 0 && placeBlock != null) {
            placeTicks--;
            if (placeTicks % 10 == 0) { // Try to place every 10 ticks
                // Simple placement in front of player
                Vec3 look = player.getViewVector(1.0f);
                BlockPos target = player.blockPosition().offset((int)Math.round(look.x), (int)Math.round(look.y), (int)Math.round(look.z));
                
                // Equip the item first
                String cleanId = placeBlock.toLowerCase().replace("minecraft:", "").trim();
                for (int slot = 0; slot < 36; slot++) {
                    if (!player.getInventory().getItem(slot).isEmpty() && InventoryHelper.itemIdOf(player.getInventory().getItem(slot)).equals(cleanId)) {
                        InventoryHelper.swapIntoHotbar(mc, player, slot, InventoryHelper.WEAPON_HOTBAR_SLOT);
                        player.getInventory().setSelectedSlot(InventoryHelper.WEAPON_HOTBAR_SLOT);
                        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false));
                        player.swing(InteractionHand.MAIN_HAND);
                        break;
                    }
                }
            }
        }
    }
}
