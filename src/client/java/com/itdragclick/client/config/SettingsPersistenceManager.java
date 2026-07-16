package com.itdragclick.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.itdragclick.AmAI;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Static helper that loads/saves {@link AIModSettings} to
 * {@code config/ai_companion_settings.json} inside the game directory,
 * using the GSON library bundled with Minecraft.
 *
 * All access to the live settings object goes through {@link #get()} /
 * {@link #update} so background threads (Swing EDT, HTTP workers, game
 * thread) always observe a consistent snapshot.
 */
public final class SettingsPersistenceManager {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Object LOCK = new Object();

	private static volatile AIModSettings settings = new AIModSettings();

	private SettingsPersistenceManager() {
	}

	private static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("am-ai").resolve("ai_companion_settings.json");
	}

	/** Thread-safe snapshot of the current configuration. */
	public static AIModSettings get() {
		return settings.copy();
	}

	/** Replaces the live configuration and immediately persists it to disk. */
	public static void update(AIModSettings newSettings) {
		synchronized (LOCK) {
			settings = newSettings.copy();
			save();
		}
	}

	/** Flips only the active flag (used by the dashboard toggle) and persists. */
	public static void setActive(boolean active) {
		synchronized (LOCK) {
			AIModSettings copy = settings.copy();
			copy.active = active;
			settings = copy;
			save();
		}
	}

	public static void load() {
		synchronized (LOCK) {
			Path file = configFile();
			if (!Files.exists(file)) {
				AmAI.LOGGER.info("[am-ai] No config found, writing defaults to {}", file);
				save();
				return;
			}
			try {
				String json = Files.readString(file, StandardCharsets.UTF_8);
				AIModSettings loaded = GSON.fromJson(json, AIModSettings.class);
				if (loaded != null) {
					// Backfill any nulls from a hand-edited/partial file.
					AIModSettings defaults = new AIModSettings();
					if (loaded.endpointUrl == null || loaded.endpointUrl.isBlank()) loaded.endpointUrl = defaults.endpointUrl;
					if (loaded.modelId == null || loaded.modelId.isBlank()) loaded.modelId = defaults.modelId;
					if (loaded.commandPrefix == null || loaded.commandPrefix.isBlank()) loaded.commandPrefix = defaults.commandPrefix;
					if (loaded.weaponPriority == null || loaded.weaponPriority.isBlank()) loaded.weaponPriority = defaults.weaponPriority;
					settings = loaded;
				}
				AmAI.LOGGER.info("[am-ai] Loaded settings from {}", file);
			} catch (Exception e) {
				AmAI.LOGGER.error("[am-ai] Failed to read config, keeping defaults", e);
			}
		}
	}

	private static void save() {
		Path file = configFile();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(settings), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AmAI.LOGGER.error("[am-ai] Failed to save config to {}", file, e);
		}
	}

	// ------------------------------------------------------------- presets
	// Named full-settings snapshots under config/am-ai/presets/<name>.json.
	// The dashboard's Presets box saves/loads/deletes through these.

	private static Path presetDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("am-ai").resolve("presets");
	}

	/** Strips path separators and other filename hazards out of a user-typed name. */
	private static String sanitize(String name) {
		return name.strip().replaceAll("[^A-Za-z0-9 _-]", "_");
	}

	/** Preset names on disk, alphabetical. Never null. */
	public static java.util.List<String> listPresets() {
		Path dir = presetDir();
		if (!Files.isDirectory(dir)) {
			return java.util.List.of();
		}
		try (var stream = Files.list(dir)) {
			return stream
					.filter(p -> p.getFileName().toString().endsWith(".json"))
					.map(p -> p.getFileName().toString().replaceFirst("\\.json$", ""))
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.toList();
		} catch (IOException e) {
			AmAI.LOGGER.error("[am-ai] Failed to list presets in {}", dir, e);
			return java.util.List.of();
		}
	}

	/** Writes the CURRENT live settings out as a named preset. */
	public static boolean savePreset(String name) {
		String safe = sanitize(name);
		if (safe.isEmpty()) {
			return false;
		}
		Path file = presetDir().resolve(safe + ".json");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(settings), StandardCharsets.UTF_8);
			AmAI.LOGGER.info("[am-ai] Preset '{}' saved to {}", safe, file);
			return true;
		} catch (IOException e) {
			AmAI.LOGGER.error("[am-ai] Failed to save preset {}", file, e);
			return false;
		}
	}

	/** Applies a named preset to the live settings (and persists it as current). */
	public static boolean loadPreset(String name) {
		Path file = presetDir().resolve(sanitize(name) + ".json");
		if (!Files.exists(file)) {
			return false;
		}
		try {
			AIModSettings loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AIModSettings.class);
			if (loaded == null) {
				return false;
			}
			// Hand-edited presets may miss string keys — backfill from defaults.
			AIModSettings defaults = new AIModSettings();
			if (loaded.endpointUrl == null || loaded.endpointUrl.isBlank()) loaded.endpointUrl = defaults.endpointUrl;
			if (loaded.modelId == null || loaded.modelId.isBlank()) loaded.modelId = defaults.modelId;
			if (loaded.commandPrefix == null || loaded.commandPrefix.isBlank()) loaded.commandPrefix = defaults.commandPrefix;
			if (loaded.weaponPriority == null || loaded.weaponPriority.isBlank()) loaded.weaponPriority = defaults.weaponPriority;
			update(loaded);
			AmAI.LOGGER.info("[am-ai] Preset '{}' loaded", name);
			return true;
		} catch (Exception e) {
			AmAI.LOGGER.error("[am-ai] Failed to load preset {}", file, e);
			return false;
		}
	}

	public static boolean deletePreset(String name) {
		Path file = presetDir().resolve(sanitize(name) + ".json");
		try {
			return Files.deleteIfExists(file);
		} catch (IOException e) {
			AmAI.LOGGER.error("[am-ai] Failed to delete preset {}", file, e);
			return false;
		}
	}
}
