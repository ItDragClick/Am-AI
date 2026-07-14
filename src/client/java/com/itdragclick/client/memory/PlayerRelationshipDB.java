package com.itdragclick.client.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.itdragclick.AmAI;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for the bot's relationship score with known players
 * (-100 to 100). Default is 0 (neutral) for newly met players.
 * If score < -60, the player is considered blacklisted/hated.
 * All feelings reads/writes must go through this class — AIMemoryStore's
 * affinity methods delegate here.
 */
public final class PlayerRelationshipDB {

    private static final Path DB_FILE = FabricLoader.getInstance().getConfigDir().resolve("am-ai").resolve("relationships.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // Map of Player Name -> Score
    private static final Map<String, Integer> relationships = new HashMap<>();

    private PlayerRelationshipDB() {}

    public static void init() {
        if (Files.exists(DB_FILE)) {
            try {
                String json = Files.readString(DB_FILE, StandardCharsets.UTF_8);
                Map<String, Integer> loaded = GSON.fromJson(json, new TypeToken<Map<String, Integer>>(){}.getType());
                if (loaded != null) {
                    relationships.putAll(loaded);
                }
            } catch (IOException e) {
                AmAI.LOGGER.error("[am-ai] Failed to load relationships", e);
            }
        }
    }

    private static void save() {
        try {
            Files.createDirectories(DB_FILE.getParent());
            Files.writeString(DB_FILE, GSON.toJson(relationships), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AmAI.LOGGER.error("[am-ai] Failed to save relationships", e);
        }
    }

    public static int getScore(String playerName) {
        return relationships.getOrDefault(playerName, 0);
    }

    public static void modifyScore(String playerName, int delta) {
        setScore(playerName, getScore(playerName) + delta);
    }

    public static void setScore(String playerName, int score) {
        relationships.put(playerName, Math.max(-100, Math.min(100, score)));
        save();
    }

    public static boolean isBlacklisted(String playerName) {
        return getScore(playerName) < -60;
    }

    public static java.util.Map<String, Integer> snapshot() {
        return new HashMap<>(relationships);
    }

    public static void clearAll() {
        relationships.clear();
        save();
    }
}
