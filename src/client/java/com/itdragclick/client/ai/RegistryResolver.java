package com.itdragclick.client.ai;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Fuzzy mapping of LLM-generated name strings onto the formal game registry.
 * Handles hallucinated segment orders ("log_acacia" -> "acacia_log"),
 * namespace prefixes, spaces, and partial names. Read-only registry access —
 * safe from any thread once registries are frozen (they are, post-bootstrap).
 */
public final class RegistryResolver {

	private RegistryResolver() {
	}

	/** Resolves against the ITEM registry; null when nothing plausible. */
	public static String resolveItem(String raw) {
		return resolve(raw, true);
	}

	/** Resolves against the BLOCK registry; null when nothing plausible. */
	public static String resolveBlock(String raw) {
		return resolve(raw, false);
	}

	private static String resolve(String raw, boolean items) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String wanted = raw.toLowerCase(Locale.ROOT).strip()
				.replace("minecraft:", "").replace(' ', '_');

		// 1. Exact registry hit.
		if (exists(wanted, items)) {
			return wanted;
		}
		// 2. Trailing-s plural ("dirts", "oak_logs").
		if (wanted.endsWith("s") && exists(wanted.substring(0, wanted.length() - 1), items)) {
			return wanted.substring(0, wanted.length() - 1);
		}
		// 3. Segment permutation ("log_acacia" -> "acacia_log"): same word
		//    set, any order.
		Set<String> wantedParts = new HashSet<>(Arrays.asList(wanted.split("_")));
		String best = null;
		for (Identifier id : ids(items)) {
			String path = id.getPath();
			Set<String> parts = new HashSet<>(Arrays.asList(path.split("_")));
			if (parts.equals(wantedParts)) {
				return path;
			}
			// 4. Superset fallback ("iron" -> shortest id containing it as a
			//    full segment, e.g. iron_ingot): keep the shortest candidate.
			if (parts.containsAll(wantedParts) && (best == null || path.length() < best.length())) {
				best = path;
			}
		}
		return best;
	}

	private static boolean exists(String path, boolean items) {
		Identifier id = Identifier.fromNamespaceAndPath("minecraft", path);
		return items
				? BuiltInRegistries.ITEM.containsKey(id)
				: BuiltInRegistries.BLOCK.containsKey(id);
	}

	private static Iterable<Identifier> ids(boolean items) {
		return items ? BuiltInRegistries.ITEM.keySet() : BuiltInRegistries.BLOCK.keySet();
	}
}
