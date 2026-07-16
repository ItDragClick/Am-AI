package com.itdragclick.client.ai;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import com.itdragclick.AmAI;
import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

/**
 * Thread-affine bridge into the official Baritone API. Every public method
 * here MUST be called on the main game thread (they are, via
 * {@link AIActionBridge} / {@link SurvivalMonitor}, both of which run on it).
 *
 * Calls the API directly — no reflection. This requires:
 *  - compile time: libs/baritone-api-fabric-26.2-SNAPSHOT.jar (see build.gradle)
 *  - runtime: the baritone-api-fabric (or unoptimized) build in the mods
 *    folder. The *standalone* build is ProGuard-obfuscated (API methods are
 *    renamed to 'a'/'b') and will throw NoSuchMethodError — do not use it.
 */
public final class BaritoneBridge {

	private static final boolean BARITONE_PRESENT =
			FabricLoader.getInstance().isModLoaded("baritone")
			|| FabricLoader.getInstance().isModLoaded("baritone-meteor");

	/** Goal saved when combat pauses pathing, restored once threats clear. */
	private static Goal rememberedGoal;

	private BaritoneBridge() {
	}

	/** True when a Baritone mod (official or meteor fork) is loaded. */
	public static boolean isAvailable() {
		return BARITONE_PRESENT;
	}

	/**
	 * Pathfinds to absolute block coordinates via CustomGoalProcess + GoalBlock.
	 * Block edits allowed — use ONLY for explicit work tasks (player-ordered
	 * goto journeys, harvest/craft/farm travel). Idle and social movement must
	 * go through {@link #goToIdle} instead.
	 */
	public static void goTo(int x, int y, int z) {
		pathTo(x, y, z, true, true, "task");
	}

	/**
	 * The shared "can I break/place right now?" gate for all idle/social
	 * movement (idle drifting, shiny-object walks, mini-games, bed pathing).
	 * Break/place permissions come straight from the idle settings, default
	 * false — an idle bot must never carve through someone's house.
	 */
	public static void goToIdle(int x, int y, int z) {
		com.itdragclick.client.config.AIModSettings cfg = com.itdragclick.client.config.SettingsPersistenceManager.get();
		pathTo(x, y, z, cfg.allowIdleBlockBreak, cfg.allowIdleBlockPlace, "idle");
	}

	public static void goToCombat(int x, int y, int z, boolean allowBlocks) {
		pathTo(x, y, z, allowBlocks, allowBlocks, "combat");
	}

	private static void pathTo(int x, int y, int z, boolean allowBreak, boolean allowPlace, String context) {
		if (!checkPresent("goto " + x + " " + y + " " + z)) {
			return;
		}
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			BaritoneAPI.getSettings().allowBreak.value = allowBreak;
			BaritoneAPI.getSettings().allowPlace.value = allowPlace;
			baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z));
			AIDashboardFrame.appendSystemLog("[BARITONE] Pathing to (" + x + ", " + y + ", " + z + ") (" + context
					+ ", break=" + allowBreak + ", place=" + allowPlace + ")");
		} catch (LinkageError e) {
			reportObfuscatedJar("goto", e);
		}
	}

	/**
	 * Starts a Baritone mine task for the given block id via MineProcess.
	 * allowPlace stays FALSE during mining: cobblestone/dirt are Baritone
	 * throwaway blocks, so with placing enabled it pillars/bridges with the
	 * very blocks it just gathered — a "mine 8 stone" crafting step would
	 * collect 8, place them climbing back, arrive with 0, and loop forever.
	 */
	public static void mine(String blockName) {
		String id = normalizeBlockId(blockName);
		if (!checkPresent("mine " + id)) {
			return;
		}
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			BaritoneAPI.getSettings().allowPlace.value = false;
			BaritoneAPI.getSettings().allowBreak.value = true;
			baritone.getMineProcess().mineByName(id);
			AIDashboardFrame.appendSystemLog("[BARITONE] Mining '" + id + "' (place disabled)");
		} catch (LinkageError e) {
			reportObfuscatedJar("mine", e);
		}
	}

	/** Mine task accepting multiple block ids (any log type, both iron ores). Same no-place rule as {@link #mine}. */
	public static void mineAny(String... blockIds) {
		if (!checkPresent("mine " + String.join("/", blockIds))) {
			return;
		}
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			BaritoneAPI.getSettings().allowPlace.value = false;
			BaritoneAPI.getSettings().allowBreak.value = true;
			baritone.getMineProcess().mineByName(blockIds);
			AIDashboardFrame.appendSystemLog("[BARITONE] Mining any of: " + String.join(", ", blockIds) + " (place disabled)");
		} catch (LinkageError e) {
			reportObfuscatedJar("mineAny", e);
		}
	}

	/**
	 * Clears every block inside the 3D box spanned by the two corners, using
	 * Baritone's builder process (the engine behind the "#sel cleararea" box
	 * selection flow).
	 */
	public static void mineArea(int x1, int y1, int z1, int x2, int y2, int z2) {
		if (!checkPresent("mine_area")) {
			return;
		}
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			BaritoneAPI.getSettings().allowPlace.value = true;
			BaritoneAPI.getSettings().allowBreak.value = true;
			baritone.getBuilderProcess().clearArea(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2));
			AIDashboardFrame.appendSystemLog("[BARITONE] Clearing box (" + x1 + ", " + y1 + ", " + z1
					+ ") -> (" + x2 + ", " + y2 + ", " + z2 + ")");
		} catch (LinkageError e) {
			reportObfuscatedJar("mine_area", e);
		}
	}

	/**
	 * Starts Baritone's native farm process (harvest + replant, the engine
	 * behind "#farm"). range 0 = Baritone default (unlimited around start).
	 */
	public static void startFarm(int range) {
		if (!checkPresent("farm")) {
			return;
		}
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			BaritoneAPI.getSettings().allowBreak.value = true;
			BaritoneAPI.getSettings().allowPlace.value = true;
			baritone.getFarmProcess().farm(range, null);
			AIDashboardFrame.appendSystemLog("[BARITONE] Farm process started (range "
					+ (range <= 0 ? "unlimited" : String.valueOf(range)) + ").");
		} catch (LinkageError e) {
			reportObfuscatedJar("farm", e);
		}
	}

	/** True while Baritone's farm process is running. */
	public static boolean isFarmProcessActive() {
		if (!BARITONE_PRESENT) {
			return false;
		}
		try {
			return BaritoneAPI.getProvider().getPrimaryBaritone().getFarmProcess().isActive();
		} catch (LinkageError e) {
			return false;
		}
	}

	/**
	 * True while ANY Baritone activity is live: an actual path being walked,
	 * the farm or follow process, or a combat-paused goal still waiting to be
	 * resumed. The auto-sleep gate uses this — a bot with live pathing work
	 * is not "idle" no matter what the task managers say.
	 */
	public static boolean isAnyProcessActive() {
		if (!BARITONE_PRESENT) {
			return rememberedGoal != null;
		}
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			return rememberedGoal != null
					|| baritone.getPathingBehavior().isPathing()
					|| baritone.getPathingBehavior().hasPath()
					|| baritone.getFarmProcess().isActive()
					|| baritone.getFollowProcess().isActive();
		} catch (LinkageError e) {
			return rememberedGoal != null;
		}
	}

	/** Continuously trails the named player via Baritone's FollowProcess. */
	public static void follow(String playerName) {
		String targetPlayer = playerName.trim();
		if (!checkPresent("follow " + targetPlayer)) {
			return;
		}
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			boolean edit = com.itdragclick.client.config.SettingsPersistenceManager.get().allowFollowBlockEdit;
			BaritoneAPI.getSettings().allowPlace.value = edit;
			BaritoneAPI.getSettings().allowBreak.value = edit;
			baritone.getFollowProcess().follow(entity ->
					entity.getName().getString().equalsIgnoreCase(targetPlayer));
			AIDashboardFrame.appendSystemLog("[BARITONE] Following '" + targetPlayer + "'");
		} catch (LinkageError e) {
			reportObfuscatedJar("follow", e);
		}
	}

	/**
	 * Re-asserts the follow block-edit policy. Baritone settings are global:
	 * any mine/goto call flips allowBreak/allowPlace and an already-running
	 * follow process silently inherits them. SurvivalMonitor calls this every
	 * tick while a follow/follow_protect escort is active (outside combat) so
	 * `allowFollowBlockEdit` actually holds for the whole journey.
	 */
	public static void enforceFollowBlockPolicy() {
		if (!BARITONE_PRESENT) {
			return;
		}
		try {
			boolean edit = com.itdragclick.client.config.SettingsPersistenceManager.get().allowFollowBlockEdit;
			BaritoneAPI.getSettings().allowBreak.value = edit;
			BaritoneAPI.getSettings().allowPlace.value = edit;
		} catch (LinkageError e) {
			reportObfuscatedJar("followPolicy", e);
		}
	}

	/**
	 * Absolute cancellation override: cancels every process INCLUDING the
	 * follow filter (cancelEverything alone leaves the follow target set on
	 * some Baritone builds). Used by the "!AI stop" immediate packet hook.
	 */
	public static void hardStop() {
		rememberedGoal = null;
		if (!checkPresent("hard stop")) {
			return;
		}
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			baritone.getPathingBehavior().cancelEverything();
			baritone.getFollowProcess().cancel();
			AIDashboardFrame.appendSystemLog("[BARITONE] HARD STOP — all processes and follow filters cancelled.");
		} catch (LinkageError e) {
			reportObfuscatedJar("hard stop", e);
		}
	}

	/** Cancels all pathing/mining immediately, freezing the player safely. */
	public static void stop() {
		rememberedGoal = null;
		if (!checkPresent("stop")) {
			return;
		}
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			baritone.getPathingBehavior().cancelEverything();
			AIDashboardFrame.appendSystemLog("[BARITONE] All processes cancelled.");
		} catch (LinkageError e) {
			reportObfuscatedJar("stop", e);
		}
	}

	/**
	 * Silent cancel used inside the combat chase loop when switching from
	 * Baritone pathing to manual sprint inputs: no dashboard log spam, and
	 * the remembered pre-combat goal stays intact for post-combat resumption.
	 */
	public static void stopQuietly() {
		if (!BARITONE_PRESENT) {
			return;
		}
		try {
			BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
		} catch (LinkageError e) {
			reportObfuscatedJar("stopQuietly", e);
		}
	}

	/**
	 * Combat interrupt: remembers the current custom goal (if any) and halts
	 * all Baritone processes so the bot stops blindly pathing while under
	 * attack. Restore with {@link #resumeRememberedGoal()}.
	 */
	public static void pauseForCombat() {
		if (!BARITONE_PRESENT) {
			return;
		}
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			Goal current = baritone.getCustomGoalProcess().getGoal();
			if (current != null) {
				rememberedGoal = current;
			}
			baritone.getPathingBehavior().cancelEverything();
		} catch (LinkageError e) {
			reportObfuscatedJar("pause", e);
		}
	}

	/** Re-issues the goal that was active when combat interrupted pathing. */
	public static void resumeRememberedGoal() {
		if (!BARITONE_PRESENT || rememberedGoal == null) {
			return;
		}
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			baritone.getCustomGoalProcess().setGoalAndPath(rememberedGoal);
			AIDashboardFrame.appendSystemLog("[BARITONE] Threats cleared — resuming previous goal " + rememberedGoal);
			rememberedGoal = null;
		} catch (LinkageError e) {
			reportObfuscatedJar("resume", e);
		}
	}

	private static String normalizeBlockId(String blockName) {
		String id = blockName.trim().toLowerCase().replace(' ', '_');
		if (id.startsWith("minecraft:")) {
			id = id.substring("minecraft:".length());
		}
		return id;
	}

	private static boolean checkPresent(String command) {
		if (BARITONE_PRESENT) {
			return true;
		}
		AmAI.LOGGER.error("[am-ai] Baritone is not installed — cannot execute '{}'", command);
		AIDashboardFrame.appendSystemLog("[ERROR] Baritone mod not found — install baritone-api-fabric for 26.2.");
		return false;
	}

	/**
	 * NoSuchMethodError / NoClassDefFoundError here means the mods folder
	 * contains the ProGuard-obfuscated *standalone* Baritone build instead of
	 * the api build — the API names this class links against don't exist in it.
	 */
	private static void reportObfuscatedJar(String verb, LinkageError e) {
		AmAI.LOGGER.error("[am-ai] Baritone API linkage failed on '{}'", verb, e);
		AIDashboardFrame.appendSystemLog(
				"[ERROR] Baritone API mismatch — replace baritone-standalone-fabric with baritone-api-fabric in the mods folder.");
	}
}
