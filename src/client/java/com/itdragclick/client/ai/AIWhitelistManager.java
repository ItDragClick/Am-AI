package com.itdragclick.client.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.itdragclick.AmAI;
import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Dynamic runtime whitelist backed by {@code config/am_ai_whitelist.json}.
 *
 * The combat loop queries {@link #isWhitelisted} every tick, so the active
 * set lives in memory (CopyOnWriteArraySet — lock-free reads); the file is
 * rewritten only on add/remove. Names compare case-insensitively.
 */
public final class AIWhitelistManager {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final List<String> DEFAULTS = List.of("Golden_Allay", "taffer2630");

	private static final Set<String> active = new CopyOnWriteArraySet<>();

	private AIWhitelistManager() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("am_ai_whitelist.json");
	}

	public static void load() {
		try {
			if (Files.exists(file())) {
				List<String> loaded = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
						new TypeToken<List<String>>() {
						}.getType());
				if (loaded != null && !loaded.isEmpty()) {
					active.clear();
					active.addAll(loaded);
					AmAI.LOGGER.info("[am-ai] Whitelist loaded: {}", active);
					return;
				}
			}
		} catch (Exception e) {
			AmAI.LOGGER.error("[am-ai] Failed to read whitelist file, using defaults", e);
		}
		active.clear();
		active.addAll(DEFAULTS);
		save();
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(new ArrayList<>(active)), StandardCharsets.UTF_8);
		} catch (Exception e) {
			AmAI.LOGGER.error("[am-ai] Failed to save whitelist file", e);
		}
	}

	public static Set<String> getActive() {
		return active; // CopyOnWriteArraySet is safe to return directly for reading
	}

	public static boolean isWhitelisted(String name) {
		if (name == null) {
			return false;
		}
		String stripped = name.strip();
		for (String entry : active) {
			if (entry.equalsIgnoreCase(stripped)) {
				return true;
			}
		}
		return false;
	}

	/** Returns false when the player was already whitelisted. */
	public static boolean add(String name) {
		if (isWhitelisted(name)) {
			return false;
		}
		active.add(name.strip());
		save();
		AIDashboardFrame.appendSystemLog("[WHITELIST] Added '" + name + "'. Active: " + active);
		return true;
	}

	/** Returns false when the player wasn't on the list. */
	public static boolean remove(String name) {
		String match = null;
		for (String entry : active) {
			if (entry.equalsIgnoreCase(name.strip())) {
				match = entry;
				break;
			}
		}
		if (match == null) {
			return false;
		}
		active.remove(match);
		save();
		AIDashboardFrame.appendSystemLog("[WHITELIST] Removed '" + match + "'. Active: " + active);
		return true;
	}
}
