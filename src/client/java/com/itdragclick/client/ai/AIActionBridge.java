package com.itdragclick.client.ai;

import com.itdragclick.AmAI;
import com.itdragclick.client.net.OllamaNetworkClient.AIDecision;
import com.itdragclick.client.ui.AIDashboardFrame;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Translates a parsed {@link AIDecision} into live game actions.
 *
 * Decisions arrive on the HTTP worker thread; everything that touches the
 * client, the world, or Baritone is re-scheduled onto the main game thread
 * through {@code Minecraft.getInstance().execute(...)} to avoid
 * ConcurrentModificationException-class race conditions.
 */
public final class AIActionBridge {

	private static final int MAX_CHAT_LENGTH = 100;

	/** The full legal action grammar — anything else is model hallucination. */
	public static final java.util.Set<String> ALLOWED_VERBS = java.util.Set.of(
			"goto", "mine", "mine_area", "follow", "attack", "eat", "drop_items",
			"deposit_chest", "sneak", "unsneak", "click_respawn", "stop");

	/** Verbs small models substitute for "attack" when told to kill things. */
	private static final java.util.Set<String> ATTACK_ALIASES = java.util.Set.of(
			"kill", "hunt", "slay", "fight", "hit");

	private AIActionBridge() {
	}

	/**
	 * Normalizes a raw LLM action into the legal grammar, rescuing common
	 * small-model hallucinations instead of dropping them:
	 *   "mine_pigs" / "kill_pig" / "hunt pigs"  -> "attack pig"
	 *   "kill pig" / "slay cow"                 -> "attack pig" / "attack cow"
	 *   "mine pig" (mob given to mine)          -> "attack pig"
	 *   "attack Pigs"                           -> "attack pig" (singular)
	 * Returns "" for blank/chat-only, null when nothing legal can be made.
	 * Pure string function — safe on any thread, also used before actions are
	 * recorded into the memory bank so hallucinations never poison recall.
	 */
	public static String canonicalizeAction(String action) {
		if (action == null || action.isBlank()) {
			return "";
		}
		String[] tokens = action.strip().split("\\s+");
		String verb = tokens[0].toLowerCase(Locale.ROOT);

		if (ATTACK_ALIASES.contains(verb)) {
			verb = "attack";
		}

		// Fused hallucinations like "mine_pigs" / "attack_pig" / "kill_cow":
		// any underscore-joined word (that isn't a real verb) hiding a known
		// mob name becomes a proper attack order.
		if (!ALLOWED_VERBS.contains(verb) && verb.contains("_")) {
			for (String part : verb.split("_")) {
				if (HarvestManager.MOB_DROPS.containsKey(singular(part))) {
					return "attack " + singular(part);
				}
			}
			return null;
		}
		if (!ALLOWED_VERBS.contains(verb)) {
			return null;
		}

		// "mine pig" / "attack Pigs": mob names route to attack, singular.
		if ((verb.equals("attack") || verb.equals("mine")) && tokens.length >= 2) {
			String arg = singular(tokens[1].toLowerCase(Locale.ROOT));
			if (HarvestManager.MOB_DROPS.containsKey(arg)) {
				return "attack " + arg;
			}
		}

		StringBuilder rebuilt = new StringBuilder(verb);
		for (int i = 1; i < tokens.length; i++) {
			rebuilt.append(' ').append(tokens[i]);
		}
		return rebuilt.toString();
	}

	private static String singular(String word) {
		return word.length() > 1 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
	}

	/**
	 * @param senderName the player whose chat request produced this decision;
	 *                   used to resolve placeholder targets the LLM failed to
	 *                   substitute (e.g. "follow &lt;player_name&gt;").
	 */
	public static void execute(AIDecision decision, String senderName) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player == null || mc.getConnection() == null) {
				AIDashboardFrame.appendSystemLog("[SKIP] Not connected to a world — action discarded.");
				return;
			}
			try {
				sendChat(mc, decision.chat());
				runAction(decision.action(), senderName);
			} catch (Exception e) {
				AmAI.LOGGER.error("[am-ai] Action execution failed, stopping Baritone for safety", e);
				AIDashboardFrame.appendSystemLog("[ERROR] Action failed (" + e.getMessage() + ") — issuing stop.");
				BaritoneBridge.stop();
			}
		});
	}

	private static void sendChat(Minecraft mc, String chatText) {
		if (chatText == null || chatText.isBlank()) {
			return;
		}
		String trimmed = chatText.strip();
		if (trimmed.length() > MAX_CHAT_LENGTH) {
			trimmed = trimmed.substring(0, MAX_CHAT_LENGTH);
		}
		// The server rejects chat packets from dead players — defer to the
		// post-respawn queue instead of losing the message entirely.
		if (mc.player.isDeadOrDying()) {
			SurvivalMonitor.queueRespawnChat(trimmed);
			return;
		}
		mc.getConnection().sendChat(trimmed);
	}

	/**
	 * Maps the action grammar onto the Baritone bridge and survival monitor:
	 * goto / mine / mine_area / follow / attack / eat / click_respawn / stop.
	 */
	private static void runAction(String rawAction, String senderName) {
		String action = canonicalizeAction(rawAction);
		if (action == null) {
			AIDashboardFrame.appendSystemLog("[WARN] Unrecoverable action '" + rawAction + "' — ignored.");
			return;
		}
		if (action.isBlank()) {
			return;
		}
		if (!action.equals(rawAction)) {
			AIDashboardFrame.appendSystemLog("[FIX] Rescued hallucinated action '" + rawAction + "' -> '" + action + "'");
		}
		String[] tokens = action.split("\\s+");
		String verb = tokens[0];

		switch (verb) {
			case "goto" -> {
				int[] xyz = parseInts(tokens, 1, 3, action);
				if (xyz != null) {
					BaritoneBridge.goTo(xyz[0], xyz[1], xyz[2]);
				}
			}
			case "mine" -> {
				if (tokens.length < 2) {
					AIDashboardFrame.appendSystemLog("[WARN] mine needs a block name — stopping.");
					BaritoneBridge.stop();
					return;
				}
				// Route through the multi-step harvest plan: mine until the
				// quantity target is met, then deliver to the known chest.
				HarvestManager.startHarvest(tokens[1], senderName);
			}
			case "deposit_chest" -> {
				int[] pos = parseInts(tokens, 1, 3, action);
				if (pos != null) {
					HarvestManager.depositAtChest(pos[0], pos[1], pos[2]);
				}
			}
			case "mine_area" -> {
				int[] box = parseInts(tokens, 1, 6, action);
				if (box != null) {
					BaritoneBridge.mineArea(box[0], box[1], box[2], box[3], box[4], box[5]);
				}
			}
			case "follow" -> {
				String target = cleanTarget(joinArgs(tokens), senderName);
				if (target == null) {
					AIDashboardFrame.appendSystemLog("[WARN] follow needs a player name — ignored.");
					return;
				}
				BaritoneBridge.follow(target);
			}
			case "attack" -> {
				String target = cleanTarget(joinArgs(tokens), senderName);
				if (target == null) {
					AIDashboardFrame.appendSystemLog("[WARN] attack needs a target name — ignored.");
					return;
				}
				// Porkchop Workflow: attacking a farm animal means the player
				// wants its drops — run the full hunt->return->deliver chain.
				String mob = target.toLowerCase(Locale.ROOT).replaceAll("s$", "");
				String drop = HarvestManager.MOB_DROPS.get(mob);
				if (drop != null && senderName != null) {
					HarvestManager.startHunt(mob, drop, senderName);
				} else {
					SurvivalMonitor.requestAttack(target);
				}
			}
			case "drop_items" -> {
				if (tokens.length < 2) {
					AIDashboardFrame.appendSystemLog("[WARN] drop_items needs an item id — ignored.");
					return;
				}
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null) {
					InventoryHelper.dropAllOf(mc, mc.player, tokens[1]);
				}
			}
			case "sneak" -> setSneaking(true);
			case "unsneak" -> setSneaking(false);
			case "eat" -> SurvivalMonitor.requestEat();
			case "click_respawn" -> SurvivalMonitor.clickRespawn();
			case "stop" -> {
				HarvestManager.cancel();
				BaritoneBridge.stop();
			}
			default -> AIDashboardFrame.appendSystemLog("[WARN] Unknown action verb '" + verb + "' — ignored.");
		}
	}

	/** Holds/releases the sneak key (persists until the opposite order). */
	private static void setSneaking(boolean sneaking) {
		Minecraft mc = Minecraft.getInstance();
		mc.options.keyShift.setDown(sneaking);
		AIDashboardFrame.appendSystemLog("[MOVE] " + (sneaking ? "Sneaking." : "Stopped sneaking."));
	}

	/** Joins tokens[1..] into one target string ("iron golem" arrives split). */
	private static String joinArgs(String[] tokens) {
		return tokens.length < 2 ? ""
				: String.join(" ", java.util.Arrays.copyOfRange(tokens, 1, tokens.length));
	}

	/**
	 * Small local models routinely echo the instruction card's placeholder
	 * syntax instead of substituting real names ("follow <player_name>",
	 * "attack player/Steve"). Strip that noise; when the target is a
	 * placeholder or a self-reference ("me"), fall back to the requesting
	 * player's name. Returns null when no usable target remains.
	 */
	private static String cleanTarget(String raw, String senderName) {
		String target = raw.replaceAll("[<>\"'`]", "").strip();
		// "player/Steve" or "mob/zombie" — keep the part after the slash.
		int slash = target.lastIndexOf('/');
		if (slash >= 0) {
			target = target.substring(slash + 1).strip();
		}
		String normalized = target.toLowerCase(Locale.ROOT).replace(' ', '_');
		boolean placeholder = normalized.isBlank()
				|| normalized.contains("player_name") || normalized.contains("mob_name")
				|| normalized.equals("name") || normalized.equals("player") || normalized.equals("mob")
				|| normalized.equals("me") || normalized.equals("him") || normalized.equals("her")
				|| normalized.equals("you") || normalized.equals("target");
		if (!placeholder) {
			return target;
		}
		if (senderName != null && !senderName.isBlank()) {
			AIDashboardFrame.appendSystemLog("[FIX] LLM emitted placeholder target '" + raw
					+ "' — using requester '" + senderName + "'.");
			return senderName;
		}
		return null;
	}

	/**
	 * Parses exactly {@code count} integers from tokens[from..]; on any
	 * failure logs, issues a safety stop, and returns null. Decimals are
	 * floored to block coordinates.
	 */
	private static int[] parseInts(String[] tokens, int from, int count, String action) {
		if (tokens.length < from + count) {
			AIDashboardFrame.appendSystemLog("[WARN] '" + tokens[0] + "' needs " + count
					+ " coordinates, got: '" + action + "' — stopping.");
			BaritoneBridge.stop();
			return null;
		}
		int[] out = new int[count];
		try {
			for (int i = 0; i < count; i++) {
				out[i] = (int) Math.floor(Double.parseDouble(tokens[from + i]));
			}
			return out;
		} catch (NumberFormatException e) {
			AIDashboardFrame.appendSystemLog("[WARN] Unparseable coordinates in '" + action + "' — stopping.");
			BaritoneBridge.stop();
			return null;
		}
	}
}
