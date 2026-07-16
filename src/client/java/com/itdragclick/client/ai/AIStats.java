package com.itdragclick.client.ai;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Session counters behind the dashboard's Activity Overview. Incremented from
 * the game thread and the LLM worker threads, read from the Swing EDT — hence
 * the atomics. Everything resets when the game restarts; nothing is persisted.
 */
public final class AIStats {

	private static final long START_MILLIS = System.currentTimeMillis();

	private static final AtomicInteger TASKS_COMPLETED = new AtomicInteger();
	private static final AtomicInteger LLM_REQUESTS = new AtomicInteger();
	private static final AtomicInteger FIGHTS = new AtomicInteger();

	private AIStats() {
	}

	public static void taskCompleted() {
		TASKS_COMPLETED.incrementAndGet();
	}

	public static void llmRequest() {
		LLM_REQUESTS.incrementAndGet();
	}

	public static void fightStarted() {
		FIGHTS.incrementAndGet();
	}

	public static int tasksCompleted() {
		return TASKS_COMPLETED.get();
	}

	public static int llmRequests() {
		return LLM_REQUESTS.get();
	}

	public static int fights() {
		return FIGHTS.get();
	}

	/** Seconds since the client started. */
	public static long uptimeSeconds() {
		return (System.currentTimeMillis() - START_MILLIS) / 1000L;
	}

	/** Uptime as HH:mm:ss. */
	public static String uptimeText() {
		long seconds = uptimeSeconds();
		return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
	}

	/** Uptime as a compact "2h 34m" / "34m" label. */
	public static String uptimeShort() {
		long seconds = uptimeSeconds();
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
	}
}
