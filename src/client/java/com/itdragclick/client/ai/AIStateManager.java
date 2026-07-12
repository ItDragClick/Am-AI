package com.itdragclick.client.ai;

import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * LIFO task stack manager (main thread only). New player requests preempt
 * the running task: its progress is serialized into a {@link TaskContext}
 * and pushed; when the new task completes, the last context is popped and
 * resumed from where it left off.
 *
 * Also owns 'goto' journeys (arrival detection + continuation across death)
 * since Baritone emits no completion callback we can rely on.
 */
public final class AIStateManager {

	/** Serialized progress snapshot of an interrupted task. */
	public static final class TaskContext {
		public enum Type {HARVEST, HUNT, GOTO, FARM, CRAFT}

		public Type type;
		public String item;        // harvest block / hunt drop / craft target
		public String mob;         // hunt chains
		public int quantity;       // REMAINING amount when paused
		public String requester;
		public int[] chest;        // explicit chest coords or null
		public int[] dest;         // goto destination
		public int durationSeconds;

		@Override
		public String toString() {
			String base = type + (item != null ? " " + item : "") + (mob != null ? " (" + mob + ")" : "")
					+ (dest != null ? " -> (" + dest[0] + ", " + dest[1] + ", " + dest[2] + ")" : "");
			String qty = quantity > 0 ? " (need " + quantity + ")" : "";
			String dur = durationSeconds > 0 ? " [" + durationSeconds + "s limit]" : "";
			return base + qty + dur;
		}
	}

	private static final double ARRIVAL_RADIUS_SQ = 9.0; // 3 blocks
	private static final Deque<TaskContext> stack = new ArrayDeque<>();

	private static int[] activeGoto;
	/** Journey destination preserved across death (Module: travel defense). */
	private static int[] pendingJourney;

	private AIStateManager() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(AIStateManager::onTick);
	}

	// ---------------------------------------------------------- preemption

	/**
	 * Called before any new task starts: captures whatever is currently
	 * running into the stack. Safe to call when idle (no-op).
	 */
	public static void preemptForNewTask() {
		TaskContext current = captureActive();
		if (current != null) {
			stack.push(current);
			AIDashboardFrame.appendSystemLog("[STACK] Paused task [" + current + "] — "
					+ stack.size() + " task(s) queued.");
		}
	}

	private static TaskContext captureActive() {
		TaskContext ctx = CraftPlanner.captureAndPause();
		if (ctx == null) {
			ctx = HarvestManager.captureAndPause();
		}
		if (ctx == null) {
			ctx = FarmManager.captureAndPause();
		}
		if (ctx == null && activeGoto != null) {
			ctx = new TaskContext();
			ctx.type = TaskContext.Type.GOTO;
			ctx.dest = activeGoto;
			activeGoto = null;
			BaritoneBridge.stopQuietly();
		}
		return ctx;
	}

	/** Pushes an already-captured context (multi-step plan construction). */
	public static void pushTask(TaskContext ctx) {
		if (ctx != null) {
			stack.push(ctx);
			AIDashboardFrame.appendSystemLog("[STACK] Queued task [" + ctx + "] — " + stack.size() + " queued.");
		}
	}

	/**
	 * Cancels ONLY the currently running task, then resumes whatever is next
	 * on the stack ("cancel" command — softer than the "stop" kill switch).
	 */
	public static void cancelCurrent() {
		CraftPlanner.cancel();
		HarvestManager.cancel();
		FarmManager.cancel();
		if (activeGoto != null) {
			activeGoto = null;
		}
		BaritoneBridge.stopQuietly();
		AIDashboardFrame.appendSystemLog("[STACK] Current task cancelled — resuming queued work if any.");
		taskCompleted();
	}

	/**
	 * Called by whichever manager just finished its task: pops the last
	 * paused context and resumes it.
	 */
	public static void taskCompleted() {
		if (stack.isEmpty()) {
			return;
		}
		TaskContext ctx = stack.pop();
		AIDashboardFrame.appendSystemLog("[STACK] Resuming paused task [" + ctx + "] — "
				+ stack.size() + " left queued.");
		resume(ctx);
	}

	private static void resume(TaskContext ctx) {
		switch (ctx.type) {
			case HARVEST -> HarvestManager.startHarvest(ctx.item, ctx.quantity, ctx.requester, ctx.chest, ctx.durationSeconds, false);
			case HUNT -> HarvestManager.startHunt(ctx.mob, ctx.item, ctx.quantity, ctx.requester, ctx.durationSeconds, false);
			case GOTO -> startGoto(ctx.dest[0], ctx.dest[1], ctx.dest[2], false);
			case FARM -> FarmManager.start(false, ctx.durationSeconds);
			case CRAFT -> CraftPlanner.start(ctx.item, ctx.requester, false);
		}
	}

	/** Absolute kill switch: wipes the whole LIFO queue and active journey. */
	public static void clearAll() {
		if (!stack.isEmpty()) {
			AIDashboardFrame.appendSystemLog("[STACK] Kill switch — wiped " + stack.size() + " queued task(s).");
		}
		stack.clear();
		activeGoto = null;
		pendingJourney = null;
	}

	public static boolean anythingActive() {
		return activeGoto != null || HarvestManager.isBusy() || FarmManager.isBusy() || CraftPlanner.isBusy();
	}

	public static String getActiveTaskDescription() {
		if (activeGoto != null) {
			return "GOTO -> (" + activeGoto[0] + ", " + activeGoto[1] + ", " + activeGoto[2] + ")";
		}
		if (HarvestManager.isBusy()) {
			return HarvestManager.getCurrentTaskDescription();
		}
		if (FarmManager.isBusy()) {
			return FarmManager.getCurrentTaskDescription();
		}
		if (CraftPlanner.isBusy()) {
			return CraftPlanner.getCurrentTaskDescription();
		}
		return "IDLE - No tasks running.";
	}

	public static java.util.List<TaskContext> getQueue() {
		return new java.util.ArrayList<>(stack);
	}

	// ------------------------------------------------------------ journeys

	public static void startGoto(int x, int y, int z, boolean preempt) {
		if (preempt) {
			preemptForNewTask();
		}
		activeGoto = new int[]{x, y, z};
		BaritoneBridge.goTo(x, y, z);
	}

	private static void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || activeGoto == null) {
			return;
		}
		BlockPos dest = new BlockPos(activeGoto[0], activeGoto[1], activeGoto[2]);
		if (dest.distSqr(player.blockPosition()) <= ARRIVAL_RADIUS_SQ) {
			AIDashboardFrame.appendSystemLog("[GOTO] Arrived at (" + activeGoto[0] + ", "
					+ activeGoto[1] + ", " + activeGoto[2] + ").");
			activeGoto = null;
			taskCompleted();
		}
	}

	/**
	 * Death during a journey: remember the destination so the journey can
	 * continue after the respawn recovery routine finishes.
	 */
	public static void onDeath() {
		if (activeGoto != null) {
			pendingJourney = activeGoto;
			activeGoto = null;
			AIDashboardFrame.appendSystemLog("[GOTO] Journey destination cached across death: ("
					+ pendingJourney[0] + ", " + pendingJourney[1] + ", " + pendingJourney[2] + ")");
		}
		stack.clear();
	}

	/**
	 * After respawn: queue the interrupted journey behind the recovery task
	 * (the re-gear harvest pops it off the stack when it completes).
	 */
	public static void requeueJourneyAfterRespawn() {
		if (pendingJourney == null) {
			return;
		}
		TaskContext ctx = new TaskContext();
		ctx.type = TaskContext.Type.GOTO;
		ctx.dest = pendingJourney;
		pendingJourney = null;
		stack.push(ctx);
		AIDashboardFrame.appendSystemLog("[GOTO] Journey re-queued behind the recovery routine.");
	}
}
