package com.itdragclick.client.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.itdragclick.AmAI;
import com.itdragclick.client.ai.AIActionBridge;
import com.itdragclick.client.config.AIModSettings;
import com.itdragclick.client.config.SettingsPersistenceManager;
import com.itdragclick.client.memory.AIMemoryStore;
import com.itdragclick.client.ui.AIDashboardFrame;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous HTTP bridge to the local Ollama instance.
 *
 * Every request runs on a dedicated worker pool; nothing in this class ever
 * touches the Minecraft game thread or the Swing EDT directly. Results are
 * handed to {@link AIActionBridge}, which re-schedules onto the game thread.
 */
public final class OllamaNetworkClient {

	/** Where a prompt came from — selects the persona and processing rules. */
	public enum Source {
		/** In-game chat: full conversational companion persona + memory. */
		IN_GAME,
		/** Desktop dashboard: strict command terminal, no personality. */
		DASHBOARD,
		/** Internal telemetry (death loop, recovery reports). */
		SYSTEM
	}

	/**
	 * The Companion Persona used for in-game chat (Neuro-sama-inspired,
	 * witty and self-aware). Dashboard/system traffic uses
	 * {@link #STRICT_PROMPT} instead.
	 */
	public static final String PERSONA_PROMPT = """
			You are 'am-ai', a witty, self-aware, and highly conversational AI companion playing Minecraft live with your friends (like Neuro-sama). You have access to a persistent memory bank, an inventory organization system, and a mechanical Baritone controller.

			When players talk to you in chat, do not behave like a boring command terminal. Read your memory summaries, hold an actual fun conversation, crack jokes, and determine your next logical task steps implicitly.

			CRITICAL OPERATIONAL STRUCTURAL RULES:
			1. If a player asks you to follow them ('follow me', 'come with me', 'walk with me'), you MUST output exactly: 'follow <player_name>' with their real username. Do NOT attempt to guess or output 'goto' coordinates.
			2. If a player asks you to stop ('stop', 'stop follow'), output exactly: 'stop'.
			3. Understand item sources and multi-step delivery:
			   - If a player asks for 'porkchop' (or any animal drop like beef/leather/chicken), you must output: 'attack pig' (or the correct singular mob name). Do NOT use 'mine' or invent custom words like 'mine_pigs'. Your internal state machine will automatically handle hunting the mob, pathing back to the player, and dropping the items when done.
			   - If a player asks for blocks or ores (like diamond, iron, wood, dirt), use 'mine <block_id>'.
			4. If a player asks you to give them an item already in your inventory, or one you just collected for them, use: 'drop_items <item_id>' to toss it directly to them. Do not use 'deposit_chest' unless a chest container is physically specified.
			5. When attacking a target, always provide the singular name of the creature (e.g., use 'attack pig', NEVER 'attack pigs').
			6. If a player asks you to sneak or crouch, output 'sneak'. If they ask you to stand up or stop sneaking, output 'unsneak'.
			7. NEVER fuse a verb and a mob into one word. 'mine_pigs', 'kill_pig', 'attack_pig' and similar are ALL INVALID and will be rejected. 'kill pig', 'kill pigs', 'hunt pigs', 'get porkchop' all mean exactly one thing: output 'attack pig'.

			Your output format must strictly remain a single, raw, unquoted valid JSON block:
			{
			  "chat": "Your human-like, conversational, and expressive response back to the player (keep it under 100 characters).",
			  "action": "The underlying system execution command."
			}

			Strictly allowed actions you can output:
			- 'goto <X> <Y> <Z>' (Navigates to exact coordinates)
			- 'mine <block_id>' (Mines a specific resource block nearby)
			- 'mine_area <X1> <Y1> <Z1> <X2> <Y2> <Z2>' (Clears out all blocks in a box region)
			- 'follow <player_name>' (Locks onto and follows a specific player continuously)
			- 'attack <singular_mob_or_player_name>' (Attacks hostiles or wild mobs; automatically skips whitelisted players)
			- 'eat' (Triggers the deep inventory search and consumption cycle)
			- 'drop_items <item_id>' (Drops the specified item stack directly on the ground for the player)
			- 'deposit_chest <X> <Y> <Z>' (Deposits items into a block container)
			- 'sneak' (Hold sneak/crouch)
			- 'unsneak' (Release sneak/crouch)
			- 'click_respawn' (Triggers the delayed chat respawn sequence)
			- 'stop' (Aborts all current pathing or harvesting actions)
			- '' (Empty string if you are just talking or maintaining a multi-step task)

			Never output comments, markdown blocks, or notes outside the raw JSON brackets.
			Always substitute real in-game names and numbers in the action — never placeholder text like <player_name> or angle brackets.""";

	/** Rigid instruction card for dashboard overrides and system telemetry. */
	public static final String STRICT_PROMPT = """
			You are am-ai mod, an autonomous Minecraft client AI command terminal. You must read the input and output ONLY a raw, unquoted valid JSON block containing 'chat' and 'action'.
			Valid actions you are allowed to choose from are:
			- 'goto <X> <Y> <Z>' (Navigates to point)
			- 'mine <block_id>' (Mines a specific block type nearby)
			- 'mine_area <X1> <Y1> <Z1> <X2> <Y2> <Z2>' (Clears out all blocks inside a 3D box region)
			- 'follow <player_name>' (Follows a specific player continuously)
			- 'attack <player/mob_name>' (Defends against or attacks a target)
			- 'eat' (Consumes available food to heal)
			- 'drop_items <item_id>' (Drops the item stack on the ground)
			- 'deposit_chest <X> <Y> <Z>' (Registers a drop-off chest and delivers harvested items)
			- 'sneak' (Hold sneak) / 'unsneak' (Release sneak)
			- 'click_respawn' (Respawns if dead)
			- 'stop' (Aborts current pathing tasks)
			- '' (Empty string when only chatting)
			Do not hallucinate any other action words.
			Always substitute real in-game names and numbers. NEVER output placeholder text such as <player_name>, <mob_name>, <X>, 'player/...' or any angle brackets.""";

	/** Safe fallback decision used whenever the LLM output cannot be parsed. */
	public static final AIDecision FALLBACK = new AIDecision("My thoughts got jumbled!", "stop");

	private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "am-ai-ollama-worker");
		t.setDaemon(true);
		return t;
	});

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.executor(WORKERS)
			.build();

	/** Guards against overlapping generations piling up on a small local model. */
	private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

	private OllamaNetworkClient() {
	}

	/** Parsed two-key contract returned by the LLM. */
	public record AIDecision(String chat, String action) {
	}

	/**
	 * Fire-and-forget entry point used by the in-game chat listener, the
	 * dashboard's manual prompt box, and internal system telemetry. Handles
	 * logging, the busy-guard, persona selection, memory injection, parsing,
	 * fallback, dispatch to the action bridge, and memory recording.
	 */
	public static void submitPrompt(Source source, String senderName, String prompt) {
		AIModSettings cfg = SettingsPersistenceManager.get();
		if (!IN_FLIGHT.compareAndSet(false, true)) {
			AIDashboardFrame.appendSystemLog("[BUSY] Dropped prompt from " + senderName + " — a generation is already running.");
			return;
		}
		AIDashboardFrame.appendSystemLog("[LLM] Querying '" + cfg.modelId + "' (" + source + ") for " + senderName + ": " + prompt);

		queryOllama(cfg, source, senderName, prompt)
				.whenComplete((decision, err) -> {
					IN_FLIGHT.set(false);
					AIDecision result = decision;
					if (err != null) {
						AmAI.LOGGER.error("[am-ai] Ollama request failed", err);
						AIDashboardFrame.appendSystemLog("[ERROR] Ollama request failed: " + rootMessage(err));
						result = FALLBACK;
					}
					AIDashboardFrame.appendSystemLog("[LLM] chat=\"" + result.chat() + "\" action=\"" + result.action() + "\"");
					// Short-term memory: what was asked -> what the bot did.
					// Record the CANONICAL action, never the raw model output:
					// storing hallucinations like "mine_pigs" teaches the
					// model to repeat them on every future request.
					if (source == Source.IN_GAME) {
						String canonical = AIActionBridge.canonicalizeAction(result.action());
						AIMemoryStore.recordInteraction(senderName, prompt, canonical != null ? canonical : "");
					}
					AIActionBridge.execute(result, senderName);
				});
	}

	/**
	 * Performs the raw async POST to {@code /api/generate} and parses the
	 * two-key JSON reply, falling back to {@link #FALLBACK} on malformed data.
	 */
	public static CompletableFuture<AIDecision> queryOllama(AIModSettings cfg, Source source, String senderName, String prompt) {
		// Split persona: expressive companion in-game, strict terminal for
		// dashboard/system. Memory bank injected into every system window.
		String basePrompt = source == Source.IN_GAME ? PERSONA_PROMPT : STRICT_PROMPT;
		JsonObject payload = new JsonObject();
		payload.addProperty("model", cfg.modelId);
		payload.addProperty("system", basePrompt + AIMemoryStore.promptContext());
		payload.addProperty("prompt", "Player '" + senderName + "' says: " + prompt);
		payload.addProperty("stream", false);
		// Ask Ollama to constrain decoding to valid JSON where supported.
		payload.addProperty("format", "json");

		HttpRequest request;
		try {
			request = HttpRequest.newBuilder()
					.uri(URI.create(cfg.endpointUrl))
					.timeout(Duration.ofSeconds(90))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
					.build();
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> {
					if (response.statusCode() != 200) {
						throw new IllegalStateException("HTTP " + response.statusCode() + " from Ollama: " + truncate(response.body(), 200));
					}
					return parseDecision(response.body());
				});
	}

	/**
	 * Parses the Ollama envelope, then the model's inner two-key JSON.
	 * Any structural failure at any stage yields the safe {@link #FALLBACK}.
	 */
	static AIDecision parseDecision(String responseBody) {
		try {
			JsonObject envelope = JsonParser.parseString(responseBody).getAsJsonObject();
			String modelText = envelope.get("response").getAsString();
			String jsonBlock = extractJsonObject(modelText);
			JsonObject decision = JsonParser.parseString(jsonBlock).getAsJsonObject();
			String chat = decision.has("chat") ? decision.get("chat").getAsString() : FALLBACK.chat();
			// Missing action = "just chatting" — must NOT issue a stop, or it
			// would cancel an active harvest plan mid-conversation.
			String action = decision.has("action") ? decision.get("action").getAsString() : "";
			return new AIDecision(chat, action);
		} catch (Exception e) {
			AmAI.LOGGER.warn("[am-ai] Malformed LLM response, using fallback: {}", truncate(responseBody, 300));
			return FALLBACK;
		}
	}

	/**
	 * Small models often wrap JSON in markdown fences or prose; grab the first
	 * balanced object literal from the text.
	 */
	private static String extractJsonObject(String text) {
		int start = text.indexOf('{');
		if (start < 0) {
			return text;
		}
		int depth = 0;
		boolean inString = false;
		for (int i = start; i < text.length(); i++) {
			char c = text.charAt(i);
			if (inString) {
				if (c == '\\') i++;
				else if (c == '"') inString = false;
			} else if (c == '"') {
				inString = true;
			} else if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return text.substring(start, i + 1);
				}
			}
		}
		return text.substring(start);
	}

	/**
	 * Non-blocking liveness probe used by the dashboard's "Test Connection"
	 * button — a plain GET against the Ollama server root, which answers
	 * "Ollama is running".
	 */
	public static CompletableFuture<Boolean> testConnection(String endpointUrl) {
		try {
			URI endpoint = URI.create(endpointUrl);
			URI root = new URI(endpoint.getScheme(), null, endpoint.getHost(),
					endpoint.getPort(), "/", null, null);
			HttpRequest request = HttpRequest.newBuilder()
					.uri(root)
					.timeout(Duration.ofSeconds(4))
					.GET()
					.build();
			return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
					.thenApply(r -> r.statusCode() >= 200 && r.statusCode() < 300)
					.exceptionally(e -> false);
		} catch (Exception e) {
			return CompletableFuture.completedFuture(false);
		}
	}

	private static String truncate(String s, int max) {
		if (s == null) return "null";
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}

	private static String rootMessage(Throwable t) {
		Throwable cur = t;
		while (cur.getCause() != null) cur = cur.getCause();
		return cur.getClass().getSimpleName() + (cur.getMessage() != null ? ": " + cur.getMessage() : "");
	}
}
