package com.itdragclick.client.chat;

import com.itdragclick.client.ai.BaritoneBridge;
import com.itdragclick.client.ai.HarvestManager;
import com.itdragclick.client.ai.SurvivalMonitor;
import com.itdragclick.client.config.AIModSettings;
import com.itdragclick.client.config.SettingsPersistenceManager;
import com.itdragclick.client.net.OllamaNetworkClient;
import com.itdragclick.client.ui.AIDashboardFrame;
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

	/** Matches server-formatted chat lines like {@code <Steve> hello}. */
	private static final Pattern VANILLA_CHAT = Pattern.compile("^<([A-Za-z0-9_]{1,16})>\\s*(.*)$");

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
			Matcher m = VANILLA_CHAT.matcher(message.getString());
			if (m.matches()) {
				handleIncoming(m.group(1), m.group(2));
			}
		});
	}

	private static void handleIncoming(String senderName, String rawText) {
		AIModSettings cfg = SettingsPersistenceManager.get();
		if (!cfg.active) {
			return;
		}

		// If the raw text still carries a "<name> " prefix (CHAT event on some
		// servers), strip it before prefix matching.
		String text = rawText;
		Matcher m = VANILLA_CHAT.matcher(text);
		if (m.matches()) {
			if (senderName == null) {
				senderName = m.group(1);
			}
			text = m.group(2);
		}

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
		// never get trapped in a conversational round-trip.
		String lowered = prompt.toLowerCase(Locale.ROOT);
		if ((lowered.equals("stop") || lowered.startsWith("stop ")) && SurvivalMonitor.isFriendly(who)) {
			AIDashboardFrame.appendSystemLog("[OVERRIDE] Immediate stop from " + who + " — LLM bypassed.");
			mc.execute(() -> {
				BaritoneBridge.hardStop();
				HarvestManager.cancel();
				SurvivalMonitor.clearAllOrders();
			});
			return;
		}

		AIDashboardFrame.appendSystemLog("[CHAT] Task request from " + who + ": " + prompt);
		OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.IN_GAME, who, prompt);
	}
}
