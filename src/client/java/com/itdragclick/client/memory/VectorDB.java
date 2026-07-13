package com.itdragclick.client.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.itdragclick.AmAI;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class VectorDB {

    private static final Path DB_FILE = FabricLoader.getInstance().getConfigDir().resolve("am_ai_vectors.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<MemoryRecord> memories = new ArrayList<>();

    public record MemoryRecord(String text, float[] vector) {}

    private VectorDB() {}

    public static synchronized void init() {
        if (!Files.exists(DB_FILE)) return;
        try (Reader reader = Files.newBufferedReader(DB_FILE)) {
            List<MemoryRecord> loaded = GSON.fromJson(reader, new TypeToken<List<MemoryRecord>>(){}.getType());
            if (loaded != null) {
                memories.clear();
                memories.addAll(loaded);
            }
        } catch (Exception e) {
            AmAI.LOGGER.error("[am-ai] Failed to load vector db", e);
        }
    }

    private static synchronized void save() {
        try (Writer writer = Files.newBufferedWriter(DB_FILE)) {
            GSON.toJson(memories, writer);
        } catch (Exception e) {
            AmAI.LOGGER.error("[am-ai] Failed to save vector db", e);
        }
    }

    public static synchronized void addMemory(String text, float[] vector) {
        memories.add(new MemoryRecord(text, vector));
        // Keep it bounded if it gets too large (e.g. 1000 memories max)
        if (memories.size() > 1000) {
            memories.remove(0);
        }
        save();
    }

    public static synchronized List<String> search(float[] query, int topK) {
        if (memories.isEmpty() || query == null || query.length == 0) {
            return List.of();
        }

        List<ScoreRecord> scores = new ArrayList<>();
        for (MemoryRecord m : memories) {
            scores.add(new ScoreRecord(m.text, cosineSimilarity(query, m.vector)));
        }

        scores.sort((a, b) -> Double.compare(b.score, a.score)); // Descending

        List<String> results = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scores.size()); i++) {
            // Only include matches above a certain similarity threshold
            if (scores.get(i).score > 0.5) {
                results.add(scores.get(i).text);
            }
        }
        return results;
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record ScoreRecord(String text, double score) {}
}
