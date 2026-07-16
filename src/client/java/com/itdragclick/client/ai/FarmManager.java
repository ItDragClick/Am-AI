package com.itdragclick.client.ai;

import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * "#farm" agricultural module — thin wrapper around Baritone's native farm
 * process (harvest + replant, all crop types Baritone knows). This class only
 * tracks the task lifecycle: start/cancel, the optional time limit, combat
 * pauses (SurvivalMonitor's pauseForCombat cancels every Baritone process, so
 * the farm is re-issued when the fight ends), and completion detection.
 */
public final class FarmManager {

	/** 0 = Baritone default (unlimited range around the start point). */
	private static final int FARM_RANGE = 0;
	/** Ticks after (re)issuing farm() before "process inactive" means "done". */
	private static final int START_GRACE_TICKS = 40;

	private static boolean active = false;
	private static int maxDurationTicks;
	private static int activeTicks;
	private static int graceTicks;
	/** Set while combat has the farm process cancelled; re-issue on exit. */
	private static boolean pausedForCombat = false;

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
		return "FARMING (Baritone farm process)";
	}

	public static void start(boolean preempt, int durationSeconds) {
		if (preempt) {
			AIStateManager.preemptForNewTask();
		}
		active = true;
		activeTicks = 0;
		graceTicks = START_GRACE_TICKS;
		pausedForCombat = false;
		maxDurationTicks = durationSeconds > 0 ? durationSeconds * 20 : 0;
		BaritoneBridge.startFarm(FARM_RANGE);
	}

	public static void cancel() {
		if (active) {
			AIDashboardFrame.appendSystemLog("[FARM] Farming cancelled.");
			BaritoneBridge.stopQuietly();
		}
		active = false;
		pausedForCombat = false;
	}

	/** LIFO stack integration. */
	public static AIStateManager.TaskContext captureAndPause() {
		if (!active) {
			return null;
		}
		int remaining = maxDurationTicks > 0 ? Math.max(0, (maxDurationTicks - activeTicks) / 20) : 0;
		cancel();
		AIStateManager.TaskContext ctx = new AIStateManager.TaskContext();
		ctx.type = AIStateManager.TaskContext.Type.FARM;
		ctx.durationSeconds = remaining;
		return ctx;
	}

	private static void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (!active || player == null || mc.level == null) {
			return;
		}
		// Combat pause: pauseForCombat cancelled the farm process along with
		// everything else. Hold state (including the clock) and re-issue the
		// farm once the fight is over.
		if (SurvivalMonitor.isInCombat()) {
			pausedForCombat = true;
			return;
		}
		if (pausedForCombat) {
			pausedForCombat = false;
			graceTicks = START_GRACE_TICKS;
			BaritoneBridge.startFarm(FARM_RANGE);
			AIDashboardFrame.appendSystemLog("[FARM] Fight over — farm process re-issued.");
			return;
		}
		activeTicks++;
		if (maxDurationTicks > 0 && activeTicks >= maxDurationTicks) {
			AIDashboardFrame.appendSystemLog("[FARM] Time limit reached.");
			announce(mc, "Farm time's up!");
			BaritoneBridge.stopQuietly();
			active = false;
			AIStateManager.taskCompleted();
			return;
		}
		if (graceTicks > 0) {
			graceTicks--;
			return;
		}
		// The farm process went idle on its own: area fully handled.
		if (!BaritoneBridge.isFarmProcessActive()) {
			AIDashboardFrame.appendSystemLog("[FARM] Farm process finished — area handled.");
			announce(mc, "Farm's all tidy!");
			active = false;
			AIStateManager.taskCompleted();
		}
	}

	private static void announce(Minecraft mc, String message) {
		if (mc.getConnection() != null) {
			mc.getConnection().sendChat(message.length() > 100 ? message.substring(0, 100) : message);
		}
	}
}
