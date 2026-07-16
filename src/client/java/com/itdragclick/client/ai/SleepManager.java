package com.itdragclick.client.ai;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.itdragclick.client.ui.AIDashboardFrame;

public final class SleepManager {

    private static BlockPos targetBed = null;
    private static int interactionCooldown = 0;
    /** Throttle for the auto-sleep check (bed scan is a 61x21x61 sweep). */
    private static int autoCheckCooldown = 0;

    private SleepManager() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SleepManager::onTick);
    }

    public static void startSleep() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        
        BlockPos bestBed = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos center = mc.player.blockPosition();

        for (BlockPos p : BlockPos.betweenClosed(center.offset(-30, -10, -30), center.offset(30, 10, 30))) {
            String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).getPath();
            if (name.contains("_bed")) {
                double dist = p.distSqr(center);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestBed = p.immutable();
                }
            }
        }

        if (bestBed != null) {
            targetBed = bestBed;
            AIDashboardFrame.appendSystemLog("[SLEEP] Found bed at " + targetBed.toShortString() + ", pathing to it...");
            BaritoneBridge.goToIdle(targetBed.getX(), targetBed.getY(), targetBed.getZ());
        } else {
            AIDashboardFrame.appendSystemLog("[SLEEP] No bed found nearby!");
        }
    }

    public static void leaveBed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.isSleeping()) {
            mc.player.stopSleepInBed(true, true);
            AIDashboardFrame.appendSystemLog("[SLEEP] Left the bed.");
        }
    }

    private static void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (targetBed == null) {
            tickAutoSleep(mc);
            return;
        }
        
        if (mc.player.isSleeping()) {
            targetBed = null; // Successfully slept
            return;
        }

        if (interactionCooldown > 0) {
            interactionCooldown--;
            return;
        }

        double distSqr = mc.player.blockPosition().distSqr(targetBed);
        if (distSqr <= 9.0) { // within 3 blocks
            BaritoneBridge.stopQuietly(); // we arrived
            
            // right click the bed
            Vec3 hitVec = new Vec3(targetBed.getX() + 0.5, targetBed.getY() + 0.5, targetBed.getZ() + 0.5);
            BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, targetBed, false);
            
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
            interactionCooldown = 20; // try again in 1 second if failed
        }
    }

    /**
     * Auto-sleep (setting-gated): once it's sleepable night (or a
     * thunderstorm) and the bot is fully idle — no tasks, no combat, not
     * riding — walk to the nearest bed and sleep. Checked every 5 seconds.
     */
    private static void tickAutoSleep(Minecraft mc) {
        if (--autoCheckCooldown > 0) {
            return;
        }
        autoCheckCooldown = 100; // 5s between checks (and between failed bed scans)
        if (!com.itdragclick.client.config.SettingsPersistenceManager.get().autoSleepAtNight) {
            return;
        }
        if (mc.player.isSleeping() || mc.player.isPassenger()) {
            return;
        }
        long timeOfDay = mc.level.getOverworldClockTime() % 24000L;
        boolean sleepable = timeOfDay >= 12542 || mc.level.isThundering();
        if (!sleepable) {
            return;
        }
        // Truly idle only: no task (attack/follow orders count as tasks), no
        // combat, no live Baritone activity (pathing, farm/follow process, a
        // combat-paused goal waiting to resume), not mid-meal.
        if (AIStateManager.anythingActive() || SurvivalMonitor.isInCombat()
                || HarvestManager.isBusy() || FarmManager.isBusy()
                || CraftPlanner.isBusy() || MountManager.isBusy()
                || SurvivalMonitor.isEating()
                || BaritoneBridge.isAnyProcessActive()) {
            return;
        }
        AIDashboardFrame.appendSystemLog("[SLEEP] Night time and nothing to do — auto-sleeping.");
        startSleep();
    }
}
