package com.itdragclick.client.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.itdragclick.AmAI;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent long/short-term memory engine, backed by
 * {@code config/am_ai_memory.json} (GSON).
 *
 * Layout:
 *  - short_term_history: rolling queue of the last {@value #SHORT_TERM_LIMIT}
 *    interactions (player input -> executed action).
 *  - long_term_declarative: explicitly remembered facts / preferences /
 *    world setups ("Chest drop-off point established at ...").
 *  - known_chest: structured drop-off chest coordinates, when established.
 *
 * All access is synchronized on the class lock; callers may be on the game
 * thread, the HTTP worker pool, or the Swing EDT. Disk writes happen inline
 * (the file is tiny) — never call from a per-tick hot path, only on actual
 * interactions.
 */
public final class AIMemoryStore {

	private static final int SHORT_TERM_LIMIT = 15;
	private static final int LONG_TERM_LIMIT = 60;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Object LOCK = new Object();

	private static MemoryData data = new MemoryData();

	private AIMemoryStore() {
	}

	// ------------------------------------------------------------- schema

	public static final class MemoryData {
		@SerializedName("short_term_history")
		List<Interaction> shortTermHistory = new ArrayList<>();
		@SerializedName("long_term_declarative")
		List<String> longTermDeclarative = new ArrayList<>();
		@SerializedName("known_chest")
		ChestPos knownChest;
		@SerializedName("player_affinities")
		java.util.Map<String, Integer> playerAffinities = new java.util.HashMap<>();
	}

	public static final class Interaction {
		String player;
		String input;
		String action;

		Interaction(String player, String input, String action) {
			this.player = player;
			this.input = input;
			this.action = action;
		}
	}

	public static final class ChestPos {
		public int x;
		public int y;
		public int z;

		ChestPos(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	// -------------------------------------------------------------- I/O

	private static Path getConfigFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("am-ai").resolve("am_ai_memory.json");
	}

	public static void load() {
		synchronized (LOCK) {
			Path path = getConfigFile();
			if (!Files.exists(path)) {
				save();
				return;
			}
			try {
				MemoryData loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), MemoryData.class);
				if (loaded != null) {
					if (loaded.shortTermHistory == null) loaded.shortTermHistory = new ArrayList<>();
					if (loaded.longTermDeclarative == null) loaded.longTermDeclarative = new ArrayList<>();
					if (loaded.playerAffinities == null) loaded.playerAffinities = new java.util.HashMap<>();
					// Scrub previously recorded hallucinated actions
					// ("mine_pigs") — replaying them in the prompt teaches
					// the model to repeat its own mistakes forever.
					int scrubbed = 0;
					for (Interaction i : loaded.shortTermHistory) {
						String canonical = com.itdragclick.client.ai.AIActionBridge.canonicalizeAction(i.action);
						String clean = canonical != null ? canonical : "";
						if (!clean.equals(i.action)) {
							i.action = clean;
							scrubbed++;
						}
					}
					data = loaded;
					// One-time migration: earlier builds kept a second score map
					// here; merge it into PlayerRelationshipDB and empty it so
					// the two stores can never diverge again.
					if (!loaded.playerAffinities.isEmpty()) {
						java.util.Map<String, Integer> existing = PlayerRelationshipDB.snapshot();
						for (java.util.Map.Entry<String, Integer> e : loaded.playerAffinities.entrySet()) {
							if (!existing.containsKey(e.getKey())) {
								PlayerRelationshipDB.setScore(e.getKey(), e.getValue());
							}
						}
						AmAI.LOGGER.info("[am-ai] Migrated {} affinity score(s) into PlayerRelationshipDB",
								loaded.playerAffinities.size());
						loaded.playerAffinities.clear();
						save();
					}
					if (scrubbed > 0) {
						AmAI.LOGGER.info("[am-ai] Scrubbed {} hallucinated action(s) from memory", scrubbed);
						save();
					}
				}
				AmAI.LOGGER.info("[am-ai] Memory loaded: {} interactions, {} facts",
						data.shortTermHistory.size(), data.longTermDeclarative.size());
			} catch (Exception e) {
				AmAI.LOGGER.error("[am-ai] Failed to read memory file, starting fresh", e);
			}
		}
	}

	private static void save() {
		try {
			Files.createDirectories(getConfigFile().getParent());
			Files.writeString(getConfigFile(), GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AmAI.LOGGER.error("[am-ai] Failed to save memory file", e);
		}
	}

	// ----------------------------------------------------------- mutation

	/** Rolling short-term record: what a player said and what the bot did. */
	public static void recordInteraction(String player, String input, String action) {
		synchronized (LOCK) {
			data.shortTermHistory.add(new Interaction(player, input, action));
			while (data.shortTermHistory.size() > SHORT_TERM_LIMIT) {
				data.shortTermHistory.remove(0);
			}
			save();
		}
	}

	/** Durable declarative fact ("Player X asked for wood", chest locations…). */
	public static void addFact(String fact) {
		synchronized (LOCK) {
			if (data.longTermDeclarative.contains(fact)) {
				return;
			}
			data.longTermDeclarative.add(fact);
			while (data.longTermDeclarative.size() > LONG_TERM_LIMIT) {
				data.longTermDeclarative.remove(0);
			}
			save();
		}
	}

	public static void setKnownChest(int x, int y, int z) {
		synchronized (LOCK) {
			data.knownChest = new ChestPos(x, y, z);
			save();
		}
		addFact("Chest drop-off point established at X:" + x + " Y:" + y + " Z:" + z);
	}

	/** Null when no drop-off chest has been established yet. */
	public static ChestPos getKnownChest() {
		synchronized (LOCK) {
			return data.knownChest;
		}
	}

	// ---------------------------------------------------- feelings/affinities
	// PlayerRelationshipDB is the single feelings store; these delegates exist
	// so older call sites keep working without a second score map diverging.

	public static void modifyAffinity(String player, int amount) {
		PlayerRelationshipDB.modifyScore(player, amount);
	}

	public static int getAffinity(String player) {
		return PlayerRelationshipDB.getScore(player);
	}

	// ---------------------------------------------------- dashboard resets

	public static void clearMemories() {
		synchronized (LOCK) {
			data.shortTermHistory.clear();
			data.longTermDeclarative.clear();
			data.knownChest = null;
			save();
			AmAI.LOGGER.info("[am-ai] Memory banks wiped by user.");
		}
	}

	public static void clearAffinities() {
		synchronized (LOCK) {
			data.playerAffinities.clear();
			save();
		}
		PlayerRelationshipDB.clearAll();
		AmAI.LOGGER.info("[am-ai] All feelings and affinities reset to neutral.");
	}

	// ---------------------------------------------------- prompt injection

	/**
	 * Renders the memory bank as a text block injected into every Ollama
	 * system window so the model can recall past commands and context.
	 */
	public static String promptContext() {
		synchronized (LOCK) {
			StringBuilder sb = new StringBuilder("\n\nYOUR MEMORY BANK (real recorded history, trust it):\n");
			if (data.knownChest != null) {
				sb.append("Drop-off chest: X:").append(data.knownChest.x)
						.append(" Y:").append(data.knownChest.y)
						.append(" Z:").append(data.knownChest.z).append('\n');
			}
			if (!data.longTermDeclarative.isEmpty()) {
				sb.append("Long-term facts:\n");
				for (String fact : data.longTermDeclarative) {
					sb.append("- ").append(fact).append('\n');
				}
			}
			if (!data.shortTermHistory.isEmpty()) {
				sb.append("Recent interactions (oldest first):\n");
				for (Interaction i : data.shortTermHistory) {
					sb.append("- ").append(i.player).append(" said \"").append(i.input)
							.append("\" -> you executed \"").append(i.action).append("\"\n");
				}
			}
			java.util.Map<String, Integer> feelings = PlayerRelationshipDB.snapshot();
			if (!feelings.isEmpty()) {
				sb.append("Your feelings towards players (Affinity scale: -100 to 100):\n");
				for (java.util.Map.Entry<String, Integer> e : feelings.entrySet()) {
					String mood = "Neutral";
					if (e.getValue() > 50) mood = "You love them";
					else if (e.getValue() > 20) mood = "You like them";
					else if (e.getValue() < -50) mood = "You HATE them";
					else if (e.getValue() < -20) mood = "You dislike them";
					sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append(" (").append(mood).append(")\n");
				}
			}
			return sb.toString();
		}
	}
}
