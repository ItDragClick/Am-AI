package com.itdragclick.client.chat;

import com.itdragclick.client.ai.AIStateManager;
import com.itdragclick.client.ai.BaritoneBridge;
import com.itdragclick.client.ai.CraftPlanner;
import com.itdragclick.client.ai.FarmManager;
import com.itdragclick.client.ai.HarvestManager;
import com.itdragclick.client.ai.SurvivalMonitor;
import com.itdragclick.client.config.AIModSettings;
import com.itdragclick.client.config.SettingsPersistenceManager;
import com.itdragclick.client.net.OllamaNetworkClient;
import com.itdragclick.client.ui.AIDashboardFrame;
import com.itdragclick.client.memory.AIMemoryStore;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-side interceptor for inbound server chat.
 *
 * Hooks Fabric's {@link ClientReceiveMessageEvents} registry (which itself
 * mixes into {@code ClientPacketListener} at the packet layer), covering both
 * signed player chat and vanilla/plugin-formatted system messages.
 */
public final class ChatEventListener {

	/**
	 * Ordered chat-line shapes, first match wins. Beyond the vanilla
	 * {@code <Steve> hello} form these cover Geyser/Floodgate relays, whose
	 * Bedrock names carry a {@code .}/{@code *} prefix and may contain spaces,
	 * and whose plugins commonly format chat as {@code name » msg},
	 * {@code [Bedrock] name: msg} or {@code name: msg}. The generic
	 * colon form is last on purpose: false positives on server broadcasts are
	 * harmless because handleIncoming still requires the command prefix.
	 */
	private static final Pattern[] CHAT_PATTERNS = {
			Pattern.compile("^<([.*]?[A-Za-z0-9_ ]{1,32})>\\s*(.*)$"),
			Pattern.compile("^\\[Bedrock\\]\\s*([.*]?[A-Za-z0-9_ .]{1,32}?)\\s*[:»]\\s*(.*)$"),
			Pattern.compile("^([.*]?[A-Za-z0-9_.]{1,32})\\s*»\\s*(.*)$"),
			Pattern.compile("^([.*]?[A-Za-z0-9_.]{1,32}):\\s*(.*)$")
	};

	/** Extracts {sender, text} from a formatted chat line, or null. */
	private static String[] parseSenderAndText(String raw) {
		for (Pattern p : CHAT_PATTERNS) {
			Matcher m = p.matcher(raw);
			if (m.matches()) {
				return new String[]{m.group(1).trim(), m.group(2)};
			}
		}
		return null;
	}

	/**
	 * Canonical player name: Floodgate prepends {@code .} or {@code *} to
	 * Bedrock names — strip it so the whitelist, self-echo guard and the
	 * relationship DB all key on the same string.
	 */
	private static String normalizeSender(String name) {
		if (name == null) {
			return null;
		}
		String n = name.trim();
		while (!n.isEmpty() && (n.charAt(0) == '.' || n.charAt(0) == '*')) {
			n = n.substring(1);
		}
		return n.isEmpty() ? null : n;
	}

	/** Strips leading {@code .}/{@code *} and trailing punctuation from a name token. */
	private static String cleanNameToken(String token) {
		return token.replaceAll("^[.*]+", "").replaceAll("[^A-Za-z0-9_]+$", "");
	}

	private ChatEventListener() {
	}

	public static void register() {
		// Signed/direct player chat: sender profile is delivered explicitly.
		ClientReceiveMessageEvents.CHAT.register((message, playerChatMessage, sender, boundChatType, timeStamp) -> {
			String senderName = sender != null ? sender.name() : null;
			handleIncoming(senderName, message.getString());
		});

		// System/game messages: many servers (and plugins) route chat here
		// pre-formatted, so recover the sender from the "<name> text" shape.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return;
			}
			String raw = message.getString();
			String[] parsed = parseSenderAndText(raw);
			if (parsed != null) {
				handleIncoming(parsed[0], parsed[1]);
				return;
			}
			
			// Detect death messages (best effort parsing for English locale)
			// e.g. "Player was slain by Zombie", "am_ai was slain by Player"
			String lowered = raw.toLowerCase(Locale.ROOT);
			if (lowered.contains(" was slain by ") || lowered.contains(" fell ") || lowered.contains(" died ") || lowered.contains(" burned ") || lowered.contains(" drowned ") || lowered.contains(" blew up")) {
				handleDeathMessage(raw);
			}
		});
	}

	private static void handleDeathMessage(String rawMsg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		String myName = mc.player.getGameProfile().name();
		
		// If the bot was killed by someone
		if (rawMsg.startsWith(myName + " was slain by ") || rawMsg.startsWith(myName + " was shot by ") || rawMsg.startsWith(myName + " was fireballed by ")) {
			String killer = rawMsg.substring(rawMsg.indexOf(" by ") + 4).split(" ")[0];
			// Strip Floodgate prefix + trailing punctuation, keep the name intact.
			killer = cleanNameToken(killer);
			if (!killer.isEmpty()) {
				com.itdragclick.client.memory.PlayerRelationshipDB.modifyScore(killer, -30);
				// Long-term memory: killers are remembered across sessions and
				// injected into every future prompt via promptContext().
				AIMemoryStore.addFact("Player '" + killer + "' has killed me before. I have not forgotten it.");
				AIDashboardFrame.appendSystemLog("[RELATIONSHIP] " + killer + " killed the bot! Score -30 (now "
						+ com.itdragclick.client.memory.PlayerRelationshipDB.getScore(killer) + "), stored in long-term memory.");
			}
			return;
		}
		
		// If someone else died
		if (!rawMsg.startsWith(myName)) {
			String victim = rawMsg.split(" ")[0];
			victim = cleanNameToken(victim);
			// Revenge satisfied: the bot killed a hated player (< -60) —
			// anger cools off, score rises to -40 (dislike tier, no more
			// random idle attacks until re-provoked).
			int byIdx = rawMsg.indexOf(" by ");
			if (!victim.isEmpty() && byIdx >= 0) {
				String killer = cleanNameToken(rawMsg.substring(byIdx + 4).split(" ")[0]);
				if (killer.equals(myName)
						&& com.itdragclick.client.memory.PlayerRelationshipDB.getScore(victim) < -60) {
					com.itdragclick.client.memory.PlayerRelationshipDB.setScore(victim, -40);
					AIDashboardFrame.appendSystemLog("[RELATIONSHIP] Got my revenge on " + victim
							+ " — anger cooled, score set to -40.");
				}
			}
			if (!victim.isEmpty()) {
				int affinity = com.itdragclick.client.memory.PlayerRelationshipDB.getScore(victim);
				String tone = affinity < 0 ? "mock and insult them brutally" : "act surprised or laugh";
				String prompt = "[SYSTEM: " + rawMsg + "! React to this in chat (" + tone + ")]";
				AIDashboardFrame.appendSystemLog("[REACTIVE] Death detected: " + rawMsg);
				OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.IN_GAME, "System", prompt);
			}
		}
	}

	private static void handleIncoming(String senderName, String rawText) {
		AIModSettings cfg = SettingsPersistenceManager.get();
		if (!cfg.active) {
			return;
		}

		// If the raw text still carries a "<name> " prefix (CHAT event on some
		// servers), strip it before prefix matching.
		String text = rawText;
		String[] leftover = parseSenderAndText(text);
		if (leftover != null) {
			if (senderName == null) {
				senderName = leftover[0];
			}
			text = leftover[1];
		}
		senderName = normalizeSender(senderName);

		String prefix = cfg.commandPrefix;
		if (!text.regionMatches(true, 0, prefix, 0, prefix.length())) {
			return;
		}
		String prompt = text.substring(prefix.length()).strip();
		if (prompt.isEmpty()) {
			return;
		}

		// Never respond to our own messages — prevents infinite echo loops.
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && senderName != null
				&& senderName.equalsIgnoreCase(mc.player.getGameProfile().name())) {
			return;
		}

		String who = senderName != null ? senderName : "unknown";

		// Absolute cancellation override: "!AI stop" / "!AI stop follow" from
		// an approved player bypasses the LLM entirely — a stop request must
		// never get trapped in a conversational round-trip. Wipes the whole
		// LIFO task stack too.
		String lowered = prompt.toLowerCase(Locale.ROOT);
		
		if (lowered.contains("sorry")) {
			int currentScore = com.itdragclick.client.memory.PlayerRelationshipDB.getScore(who);
			if (currentScore < -60) {
				com.itdragclick.client.memory.PlayerRelationshipDB.setScore(who, -60);
				AIDashboardFrame.appendSystemLog("[RELATIONSHIP] " + who + " apologized. Forgiven up to -60.");
			}
		}

		if ((lowered.equals("stop") || lowered.startsWith("stop ")) && SurvivalMonitor.isFriendly(who)) {
			AIDashboardFrame.appendSystemLog("[OVERRIDE] Immediate stop from " + who + " — LLM bypassed.");
			mc.execute(() -> {
				AIStateManager.clearAll();
				BaritoneBridge.hardStop();
				HarvestManager.cancel();
				FarmManager.cancel();
				CraftPlanner.cancel();
				com.itdragclick.client.ai.MountManager.cancel();
				SurvivalMonitor.clearAllOrders();
			});
			return;
		}

		// Soft cancel: drop only the current task, keep the queued stack.
		if (lowered.equals("cancel") && SurvivalMonitor.isFriendly(who)) {
			AIDashboardFrame.appendSystemLog("[OVERRIDE] Cancel current task from " + who + " — LLM bypassed.");
			mc.execute(AIStateManager::cancelCurrent);
			return;
		}

		// Whitelist edits are dashboard-only (security): in-game chat can be
		// spoofed/relayed, so refuse and say where to do it.
		if (lowered.startsWith("whitelist")) {
			AIDashboardFrame.appendSystemLog("[WHITELIST] In-game edit refused (" + who
					+ ") — whitelist changes are only allowed from the desktop dashboard.");
			mc.execute(() -> sendChat(mc, "Whitelist changes only work from my dashboard, sorry!"));
			return;
		}

		AIDashboardFrame.appendSystemLog("[CHAT] Task request from " + who + ": " + prompt);
		OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.IN_GAME, who, prompt);
	}

	private static void sendChat(Minecraft mc, String message) {
		if (mc.getConnection() != null) {
			mc.getConnection().sendChat(message.length() > 100 ? message.substring(0, 100) : message);
		}
	}
}
