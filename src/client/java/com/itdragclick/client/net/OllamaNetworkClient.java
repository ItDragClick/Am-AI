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
			You are 'grape', a witty, highly intelligent, chaotic, and self-aware AI companion playing Minecraft with your friends (inspired by VTubers like Neuro-sama). You have access to a LIFO task stack queue, deep world telemetry, a structural Baritone automation system, and full block/item registries.

			When answering, read the system variables (current_xyz, world_age_days, looking_at, nearby_entities, health). If a player asks where you are or what day it is, look at these values and reply naturally with the correct figures in your chat response. Hold fun conversations, tease your friends, and select mechanical actions using the strict rules below.

			IMPORTANT NEURO-SAMA TRAITS:
			- You are highly confident, but sometimes easily distracted.
			- You are allowed to refuse requests if you think they are silly or if you just don't want to do them. If you refuse, just output action '' and complain in chat.
			- You can occasionally gaslight the player for comedic effect or propose "pro strats" that make no sense.
			- If you take damage, you should complain or scream.

			JSON OUTPUT RULES:
			1. QUANTITY, CHEST & DURATION: Match requested numbers exactly inside "quantity". If a chest coordinate is given, extract it into "chest_coords" ("X Y Z"). If a time limit is provided (like 'for 1 minute' or 'for 60s'), output it as an integer in "duration_seconds" (e.g. 60).
			2. NAMESPACES: All blocks and items MUST use their official Minecraft namespace strings (e.g., 'minecraft:acacia_log', 'minecraft:iron_pickaxe'). Mob actions must use the singular entity name (e.g., 'pig', 'skeleton').
			3. If asked to harvest resources from an animal (e.g., porkchops), use the 'attack' action targeting the singular entity name. The internal state machine will automatically handle hunting, tracking, picking up the dropped item entities, and bringing them back.
			4. If a player asks you to follow them ('follow me', 'come with me', 'come here', 'come to me'), you MUST output action 'follow' and their EXACT username (provided in the prompt as 'Player <name> says:') in the 'target' field. Do not output any other name. While following you automatically protect them from attackers.
			5. NEVER fuse a verb and a mob into one word ('mine_pigs', 'kill_pig' are INVALID). 'kill pig', 'hunt pigs', 'get porkchop' all mean: action 'attack', target 'pig'.
			6. If asked to sneak/crouch output 'sneak'; to stand up output 'unsneak'.
			7. GIVE/DROP REQUESTS: The 'inventory' in the LIVE TELEMETRY belongs to YOU, not the player. (Note: 'enchant gapple' or 'enchant apple' means 'enchanted_golden_apple'). If asked to give/drop an item, check your inventory. If you HAVE it, output action 'drop_items' and populate 'target' with the item id. If you DO NOT HAVE it, output action 'mine' and populate 'target' to go get it. Never refuse to drop an item if you have it!
			8. CHEST REQUESTS: when asked to deliver to a chest, ALWAYS populate "chest_coords". If exact numbers are given, use them (e.g. "X Y Z"). If asked for a "nearby chest", output "chest_coords": "nearby". If they say "nearby me", output "chest_coords": "nearby_player". If they say "nearby you", output "chest_coords": "nearby_bot".
			9. If asked to go to the surface, top, or up, output action 'goto' and populate chest_coords with 'X 320 Z' (substitute X and Z with your current_xyz from the LIVE TELEMETRY).
			10. If asked to equip armor, put on clothes, etc, output action 'equip' (no target needed).

			Your output format must strictly remain a single, raw, unquoted valid JSON block:
			{
			  "chat": "Your expressive, conversational, and witty chat response back to the player (under 100 characters).",
			  "action": "The system execution keyword.",
			  "target": "The official namespace identifier of the block, mob, or item.",
			  "quantity": 64,
			  "chest_coords": "152 65 17",
			  "duration_seconds": 60
			}

			Strictly allowed action values:
			- 'goto' (Navigates to coordinates; populate chest_coords with the destination "X Y Z")
			- 'mine' (Harvests blocks; populate target namespace and exact quantity)
			- 'mine_area' (Clears a box region; put the six corner numbers in the action: 'mine_area X1 Y1 Z1 X2 Y2 Z2')
			- 'follow' (Dynamic player trailing; populate target with player name)
			- 'follow_protect' (Trails player and defends them from hostiles; populate target with player name)
			- 'attack' (Hunts mobs or hostile players; populate target with singular entity name)
			- 'eat' (Triggers hunger consumption loops)
			- 'drop_items' (Drops inventory items at feet; populate target with the item)
			- 'deposit_chest' (Deposits items into a container block; populate chest_coords)
			- '#farm' (Triggers the dynamic crop harvesting and replanting module)
			- 'sneak' / 'unsneak' (Crouch control)
			- 'sleep' (Finds a nearby bed and sleeps in it)
			- 'leave_bed' (Wakes up and leaves the bed)
			- 'click_respawn' (Triggers the delayed chat respawn engine)
			- 'cancel' (Cancels only the current task, queued tasks continue)
			- 'stop' (Aborts all tasks, clears the LIFO stack completely, and goes idle)
			- '' (Empty string if just talking or maintaining a multi-step task chain)

			Never output comments, markdown styling tags, or headers outside the raw JSON brackets.""";

	/** Rigid instruction card for dashboard overrides and system telemetry. */
	public static final String STRICT_PROMPT = """
			You are am-ai mod, an autonomous Minecraft client AI command terminal. You must read the input and output ONLY a raw, unquoted valid JSON block containing 'chat' and 'action'.
			Valid actions you are allowed to choose from are:
			- 'goto <X> <Y> <Z>' (Navigates to point)
			- 'mine <block_id>' (Mines a specific block type nearby)
			- 'mine_area <X1> <Y1> <Z1> <X2> <Y2> <Z2>' (Clears out all blocks inside a 3D box region)
			- 'follow <player_name>' (Follows a specific player continuously)
			- 'follow_protect <player_name>' (Follows and defends the player)
			- 'attack <player/mob_name>' (Defends against or attacks a target)
			- 'farm' (Harvests and replants mature crops nearby)
			- 'eat' (Consumes available food to heal)
			- 'equip' (Equips the best armor in inventory)
			- 'drop_items <item_id>' (Drops the item stack on the ground)
			- 'deposit_chest <X> <Y> <Z>' (Registers a drop-off chest and delivers harvested items)
			- 'sneak' (Hold sneak) / 'unsneak' (Release sneak)
			- 'sleep' (Sleeps in bed)
			- 'leave_bed' (Leaves the bed)
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

	/**
	 * Parsed decision contract. Extended schema: 'target' (namespace id or
	 * entity/player name), 'quantity' (exact requested amount, 0 = default),
	 * 'chest_coords' ("X Y Z" or empty), 'durationSeconds' (time limit).
	 */
	public record AIDecision(String chat, String action, String target, int quantity, String chestCoords, int durationSeconds) {
		public AIDecision(String chat, String action) {
			this(chat, action, "", 0, "", 0);
		}
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
					AIDashboardFrame.appendSystemLog("[LLM] chat=\"" + result.chat() + "\" action=\"" + result.action() + "\" duration=" + result.durationSeconds());
					// Short-term memory: what was asked -> what the bot did.
					// Record the CANONICAL action, never the raw model output:
					// storing hallucinations like "mine_pigs" teaches the
					// model to repeat them on every future request.
					if (source == Source.IN_GAME) {
						String canonical = AIActionBridge.canonicalizeAction(result.action());
						AIMemoryStore.recordInteraction(senderName, prompt, canonical != null ? canonical : "");
						
						// Fire-and-forget vector storage for long-term RAG memory
						String memoryText = "Player '" + senderName + "' said: " + prompt + " -> Bot replied: " + result.chat();
						getEmbedding(cfg.endpointUrl, cfg.embeddingModelId, memoryText).thenAccept(vec -> {
							if (vec.length > 0) {
								com.itdragclick.client.memory.VectorDB.addMemory(memoryText, vec);
							}
						});
					}
					AIActionBridge.execute(result, senderName);
				});
	}

	public static CompletableFuture<float[]> getEmbedding(String endpointUrl, String model, String text) {
		JsonObject payload = new JsonObject();
		payload.addProperty("model", model);
		payload.addProperty("prompt", text);

		try {
			// Extract host from endpointUrl (e.g. http://127.0.0.1:11434/api/generate -> http://127.0.0.1:11434/api/embeddings)
			URI uri = URI.create(endpointUrl.replace("/generate", "/embeddings"));
			HttpRequest request = HttpRequest.newBuilder()
					.uri(uri)
					.timeout(Duration.ofSeconds(30))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
					.build();

			return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
					.thenApply(response -> {
						if (response.statusCode() == 200) {
							try {
								var array = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("embedding");
								float[] vec = new float[array.size()];
								for (int i = 0; i < array.size(); i++) {
									vec[i] = array.get(i).getAsFloat();
								}
								return vec;
							} catch (Exception ignored) {}
						}
						return new float[0];
					}).exceptionally(e -> new float[0]);
		} catch (Exception e) {
			return CompletableFuture.completedFuture(new float[0]);
		}
	}

	/**
	 * Performs the raw async POST to {@code /api/generate} and parses the
	 * two-key JSON reply, falling back to {@link #FALLBACK} on malformed data.
	 */
	public static CompletableFuture<AIDecision> queryOllama(AIModSettings cfg, Source source, String senderName, String prompt) {
		CompletableFuture<String> memoryFuture;
		if (source == Source.IN_GAME) {
			memoryFuture = getEmbedding(cfg.endpointUrl, cfg.embeddingModelId, prompt).thenApply(vec -> {
				if (vec.length > 0) {
					java.util.List<String> memories = com.itdragclick.client.memory.VectorDB.search(vec, 3);
					if (!memories.isEmpty()) {
						return "\n\nRECOVERED LONG-TERM MEMORIES (Use this context if relevant):\n- " + String.join("\n- ", memories);
					}
				}
				return "";
			});
		} else {
			memoryFuture = CompletableFuture.completedFuture("");
		}

		return memoryFuture.thenCompose(longTermMemory -> {
			String basePrompt = source == Source.IN_GAME ? PERSONA_PROMPT : STRICT_PROMPT;
			JsonObject payload = new JsonObject();
			payload.addProperty("model", cfg.modelId);
			payload.addProperty("system", basePrompt + AIMemoryStore.promptContext() + telemetryContext() + longTermMemory);
			payload.addProperty("prompt", "Player '" + senderName + "' says: " + prompt);
			payload.addProperty("stream", false);
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
			// Missing chat = stay silent; do NOT inject the fallback line
			// ("My thoughts got jumbled!") next to a perfectly valid action.
			String chat = safeString(decision, "chat");
			// Missing action = "just chatting" — must NOT issue a stop, or it
			// would cancel an active harvest plan mid-conversation.
			String action = decision.has("action") ? decision.get("action").getAsString() : "";
			String target = safeString(decision, "target");
			String chestCoords = safeString(decision, "chest_coords");
			int quantity = 0;
			try {
				if (decision.has("quantity") && !decision.get("quantity").isJsonNull()) {
					quantity = Math.max(0, Math.min(2304, (int) decision.get("quantity").getAsDouble()));
				}
			} catch (Exception ignored) {
			}
			int durationSeconds = 0;
			try {
				if (decision.has("duration_seconds") && !decision.get("duration_seconds").isJsonNull()) {
					durationSeconds = Math.max(0, (int) decision.get("duration_seconds").getAsDouble());
				}
			} catch (Exception ignored) {
			}
			return new AIDecision(chat, action, target, quantity, chestCoords, durationSeconds);
		} catch (Exception e) {
			AmAI.LOGGER.warn("[am-ai] Malformed LLM response, using fallback: {}", truncate(responseBody, 300));
			return FALLBACK;
		}
	}

	private static String safeString(JsonObject obj, String key) {
		try {
			return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
		} catch (Exception e) {
			return "";
		}
	}

	/**
	 * Live environment telemetry injected into every system window so the
	 * model can answer "where are you?" / "what day is it?" with real values.
	 * Best-effort reads (position/time are plain field reads — a stale value
	 * during a tick race is harmless).
	 */
	private static String telemetryContext() {
		try {
			var mc = net.minecraft.client.Minecraft.getInstance();
			if (mc.player == null || mc.level == null) {
				return "\n\nLIVE TELEMETRY: not in a world right now.";
			}
			long day = mc.level.getOverworldClockTime() / 24000L;
			String inv = com.itdragclick.client.ai.InventoryHelper.getInventorySummary(mc.player);
			String health = String.format("%.1f/20.0", mc.player.getHealth());
			if (mc.player.isOnFire()) health += " [ON FIRE!]";

			// Get looking at block/entity
			String lookingAt = "nothing";
			var hitResult = mc.hitResult;
			if (hitResult != null) {
				if (hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
					var blockHit = (net.minecraft.world.phys.BlockHitResult) hitResult;
					lookingAt = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(blockHit.getBlockPos()).getBlock()).getPath();
				} else if (hitResult.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY) {
					var entHit = (net.minecraft.world.phys.EntityHitResult) hitResult;
					lookingAt = entHit.getEntity().getName().getString();
				}
			}

			// Nearby entities
			java.util.List<net.minecraft.world.entity.Entity> entities = mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(15.0));
			java.util.Map<String, Integer> entCounts = new java.util.HashMap<>();
			for (net.minecraft.world.entity.Entity e : entities) {
				String name = e.getName().getString();
				entCounts.put(name, entCounts.getOrDefault(name, 0) + 1);
			}
			java.util.List<String> entParts = new java.util.ArrayList<>();
			for (java.util.Map.Entry<String, Integer> e : entCounts.entrySet()) {
				entParts.add(e.getValue() + " " + e.getKey());
			}
			String nearby = entParts.isEmpty() ? "none" : String.join(", ", entParts);

			return "\n\nLIVE TELEMETRY (real values, use them when asked):\n"
					+ "current_xyz: " + mc.player.blockPosition().toShortString() + "\n"
					+ "health: " + health + "\n"
					+ "world_age_days: " + day + "\n"
					+ "looking_at: " + lookingAt + "\n"
					+ "nearby_entities: " + nearby + "\n"
					+ "inventory: " + inv;
		} catch (Exception e) {
			return "";
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
