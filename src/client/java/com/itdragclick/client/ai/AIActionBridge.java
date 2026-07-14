package com.itdragclick.client.ai;

import com.itdragclick.AmAI;
import com.itdragclick.client.net.OllamaNetworkClient.AIDecision;
import com.itdragclick.client.ui.AIDashboardFrame;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Translates a parsed {@link AIDecision} into live game actions.
 *
 * Decisions arrive on the HTTP worker thread; everything that touches the
 * client, the world, or Baritone is re-scheduled onto the main game thread
 * through {@code Minecraft.getInstance().execute(...)}.
 *
 * Extended schema: the verb may carry inline args ("goto 10 64 10") OR pull
 * them from the decision's structured fields (target / quantity /
 * chest_coords) — both forms are accepted, inline args win.
 */
public final class AIActionBridge {

	private static final int MAX_CHAT_LENGTH = 100;

	/** The full legal action grammar — anything else is model hallucination. */
	public static final java.util.Set<String> ALLOWED_VERBS = java.util.Set.of(
			"goto", "mine", "mine_area", "follow", "follow_protect", "attack", "eat",
			"drop_items", "deposit_chest", "farm", "sneak", "unsneak", "click_respawn",
			"cancel", "stop", "equip", "sleep", "leave_bed", "craft",
			"turn", "walk", "break", "place", "equip_offhand", "unequip");

	/** Verbs small models substitute for "attack" when told to kill things. */
	private static final java.util.Set<String> ATTACK_ALIASES = java.util.Set.of(
			"kill", "hunt", "slay", "fight", "hit");

	private AIActionBridge() {
	}

	/**
	 * Normalizes a raw LLM action verb into the legal grammar, rescuing
	 * common small-model hallucinations instead of dropping them
	 * ("mine_pigs" -> "attack pig", "#farm" -> "farm", "kill pig" ->
	 * "attack pig", plurals singularized). Returns "" for blank/chat-only,
	 * null when nothing legal can be made. Pure string function — safe on
	 * any thread, also used before actions are recorded into the memory
	 * bank so hallucinations never poison recall.
	 */
	public static String canonicalizeAction(String action) {
		if (action == null || action.isBlank()) {
			return "";
		}
		String[] tokens = action.strip().split("\\s+");
		String verb = tokens[0].toLowerCase(Locale.ROOT);
		if (verb.startsWith("#")) {
			verb = verb.substring(1);
		}

		if (ATTACK_ALIASES.contains(verb)) {
			verb = "attack";
		}

		// Fused hallucinations like "mine_pigs" / "kill_cow": any
		// underscore-joined word (that isn't a real verb) hiding a known mob
		// name becomes a proper attack order.
		if (!ALLOWED_VERBS.contains(verb) && verb.contains("_")) {
			for (String part : verb.split("_")) {
				if (HarvestManager.MOB_DROPS.containsKey(singular(part))) {
					return "attack " + singular(part);
				}
			}
			return null;
		}
		if (!ALLOWED_VERBS.contains(verb)) {
			return null;
		}

		// "mine pig" / "attack Pigs": mob names route to attack, singular.
		if ((verb.equals("attack") || verb.equals("mine")) && tokens.length >= 2) {
			String arg = singular(tokens[1].toLowerCase(Locale.ROOT));
			if (HarvestManager.MOB_DROPS.containsKey(arg)) {
				return "attack " + arg;
			}
		}

		StringBuilder rebuilt = new StringBuilder(verb);
		for (int i = 1; i < tokens.length; i++) {
			rebuilt.append(' ').append(tokens[i]);
		}
		return rebuilt.toString();
	}

	private static String singular(String word) {
		return word.length() > 1 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
	}

	/**
	 * @param senderName the player whose chat request produced this decision;
	 *                   used for delivery targets and placeholder rescue.
	 */
	public static void execute(AIDecision decision, String senderName) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player == null || mc.getConnection() == null) {
				AIDashboardFrame.appendSystemLog("[SKIP] Not connected to a world — action discarded.");
				return;
			}
			try {
				sendChat(mc, decision.chat());
				runAction(decision, senderName);
			} catch (Exception e) {
				AmAI.LOGGER.error("[am-ai] Action execution failed, stopping Baritone for safety", e);
				AIDashboardFrame.appendSystemLog("[ERROR] Action failed (" + e.getMessage() + ") — issuing stop.");
				BaritoneBridge.stop();
			}
		});
	}

	private static void sendChat(Minecraft mc, String chatText) {
		if (chatText == null || chatText.isBlank()) {
			return;
		}
		String trimmed = chatText.strip();
		if (trimmed.length() > MAX_CHAT_LENGTH) {
			trimmed = trimmed.substring(0, MAX_CHAT_LENGTH);
		}
		// The server rejects chat packets from dead players — defer to the
		// post-respawn queue instead of losing the message entirely.
		if (mc.player.isDeadOrDying()) {
			SurvivalMonitor.queueRespawnChat(trimmed);
			return;
		}
		mc.getConnection().sendChat(trimmed);
	}

	private static void runAction(AIDecision decision, String senderName) {
		String action = canonicalizeAction(decision.action());
		if (action == null) {
			AIDashboardFrame.appendSystemLog("[WARN] Unrecoverable action '" + decision.action() + "' — ignored.");
			return;
		}
		if (action.isBlank()) {
			return;
		}
		if (!action.equals(decision.action())) {
			AIDashboardFrame.appendSystemLog("[FIX] Rescued action '" + decision.action() + "' -> '" + action + "'");
		}
		String[] tokens = action.split("\\s+");
		String verb = tokens[0];
		int quantity = decision.quantity();
		int[] chest = parseCoords(decision.chestCoords(), senderName);

		com.itdragclick.client.config.AIModSettings cfg = com.itdragclick.client.config.SettingsPersistenceManager.get();

		if ("System".equals(senderName)) {
			// Goal-target breaking ('mine <flower>', 'farm' crop harvesting) is
			// the point of the idle action, not incidental clearing — it stays
			// allowed. Only arbitrary breaking/placing needs the idle toggles.
			// (Limitation: Baritone's mine process may still clear obstacle
			// blocks while pathing to the goal — Baritone settings are global.)
			if ((verb.equals("mine_area") || verb.equals("break")) && !cfg.allowIdleBlockBreak) {
				AIDashboardFrame.appendSystemLog("[IDLE] System tried to " + verb + " but idle breaking is disabled.");
				return;
			}
			if (verb.equals("place") && !cfg.allowIdleBlockPlace) {
				AIDashboardFrame.appendSystemLog("[IDLE] System tried to place blocks but idle placing is disabled.");
				return;
			}
		}

		switch (verb) {
			case "goto" -> {
				// Coordinates can arrive inline, in chest_coords, or in the
				// target field — models scatter them across all three.
				int[] dest = tokens.length >= 4 ? parseInts(tokens, 1, 3, action) : null;
				if (dest == null) {
					dest = chest;
				}
				if (dest == null) {
					dest = parseCoords(decision.target(), senderName);
				}
				if (dest == null) {
					AIDashboardFrame.appendSystemLog("[WARN] goto without usable coordinates — ignored.");
					return;
				}
				AIStateManager.startGoto(dest[0], dest[1], dest[2], true);
				AIDashboardFrame.appendSystemLog("[GOTO] Journey to (" + dest[0] + ", " + dest[1] + ", " + dest[2] + ")");
			}
			case "mine" -> {
				String raw = argOr(tokens, decision.target());
				if (raw == null) {
					AIDashboardFrame.appendSystemLog("[WARN] mine needs a block/item — ignored.");
					return;
				}
				String resolved = RegistryResolver.resolveItem(raw);
				// Progressive crafting: tool requests route to the planner.
				if (CraftPlanner.canPlan(resolved)) {
					CraftPlanner.start(resolved, senderName, true);
					return;
				}
				HarvestManager.startHarvest(raw, quantity, senderName, chest, decision.durationSeconds(), true);
			}
			case "mine_area" -> {
				int[] box = parseInts(tokens, 1, 6, action);
				if (box != null) {
					BaritoneBridge.mineArea(box[0], box[1], box[2], box[3], box[4], box[5]);
				}
			}
			case "follow" -> {
				String target = cleanTarget(argOr(tokens, decision.target()), senderName);
				if (target == null) {
					AIDashboardFrame.appendSystemLog("[WARN] follow needs a player name — ignored.");
					return;
				}
				// Following implies protecting: anyone (non-whitelisted) who
				// attacks the followed player gets engaged.
				SurvivalMonitor.requestFollowProtect(target);
			}
			case "follow_protect" -> {
				String target = cleanTarget(argOr(tokens, decision.target()), senderName);
				if (target == null) {
					AIDashboardFrame.appendSystemLog("[WARN] follow_protect needs a player name — ignored.");
					return;
				}
				SurvivalMonitor.requestFollowProtect(target);
			}
			case "attack" -> {
				String target = cleanTarget(argOr(tokens, decision.target()), senderName);
				if (target == null) {
					AIDashboardFrame.appendSystemLog("[WARN] attack needs a target name — ignored.");
					return;
				}
				// Porkchop Workflow: attacking a farm animal means the player
				// wants its drops — run the full hunt->return->deliver chain.
				String mob = singular(target.toLowerCase(Locale.ROOT));
				String drop = HarvestManager.MOB_DROPS.get(mob);
				if (drop != null && senderName != null) {
					HarvestManager.startHunt(mob, drop, quantity, senderName, decision.durationSeconds(), true);
				} else {
					SurvivalMonitor.requestAttack(target);
				}
			}
			case "drop_items" -> {
				String item = argOr(tokens, decision.target());
				if (item == null) {
					AIDashboardFrame.appendSystemLog("[WARN] drop_items needs an item id — ignored.");
					return;
				}
				String resolved = RegistryResolver.resolveItem(item);
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null && resolved != null) {
					InventoryHelper.requestDrop(mc, mc.player, resolved, decision.quantity());
				}
			}
			case "deposit_chest" -> {
				int[] pos = tokens.length >= 4 ? parseInts(tokens, 1, 3, action) : chest;
				if (pos == null) {
					AIDashboardFrame.appendSystemLog("[WARN] deposit_chest without coordinates — ignored.");
					return;
				}
				HarvestManager.depositAtChest(pos[0], pos[1], pos[2]);
			}
			case "farm" -> FarmManager.start(true, decision.durationSeconds());
			case "sneak" -> setSneaking(true);
			case "unsneak" -> setSneaking(false);
			case "eat" -> SurvivalMonitor.requestEat();
			case "craft" -> {
				String raw = argOr(tokens, decision.target());
				if (raw == null) {
					AIDashboardFrame.appendSystemLog("[WARN] craft needs an item — ignored.");
					return;
				}
				String resolved = RegistryResolver.resolveItem(raw);
				if (CraftPlanner.canPlan(resolved)) {
					CraftPlanner.start(resolved, senderName, true);
				} else {
					AIDashboardFrame.appendSystemLog("[WARN] Don't know how to craft '" + resolved + "'.");
				}
			}
			case "equip" -> {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null) {
					InventoryHelper.equipArmor(mc, mc.player);
				}
			}
			case "equip_offhand" -> {
				String raw = argOr(tokens, decision.target());
				if (raw != null) {
					String resolved = RegistryResolver.resolveItem(raw);
					if (resolved != null && Minecraft.getInstance().player != null) {
						InventoryHelper.equipOffhand(Minecraft.getInstance(), Minecraft.getInstance().player, resolved);
					}
				}
			}
			case "unequip" -> {
				if (Minecraft.getInstance().player != null) {
					InventoryHelper.unequipArmor(Minecraft.getInstance(), Minecraft.getInstance().player);
				}
			}
			case "turn" -> {
				float pitch = tokens.length >= 2 ? Float.parseFloat(tokens[1]) : 0f;
				float yaw = tokens.length >= 3 ? Float.parseFloat(tokens[2]) : 0f;
				int speed = tokens.length >= 4 ? Integer.parseInt(tokens[3]) : 20;
				ActionHelper.turnHead(pitch, yaw, speed);
			}
			case "walk" -> {
				String dir = tokens.length >= 2 ? tokens[1] : "forward";
				int dist = tokens.length >= 3 ? Integer.parseInt(tokens[2]) : 1;
				ActionHelper.walk(dir, dist);
			}
			case "break" -> {
				int[] pos = tokens.length >= 4 ? parseInts(tokens, 1, 3, action) : chest;
				if (pos != null) {
					ActionHelper.breakBlock(pos[0], pos[1], pos[2], decision.durationSeconds());
				}
			}
			case "place" -> {
				String block = argOr(tokens, decision.target());
				if (block != null) {
					ActionHelper.placeBlock(block, decision.durationSeconds());
				}
			}
			case "sleep" -> SleepManager.startSleep();
			case "leave_bed" -> SleepManager.leaveBed();
			case "click_respawn" -> SurvivalMonitor.clickRespawn();
			case "cancel" -> AIStateManager.cancelCurrent();
			case "stop" -> {
				AIStateManager.clearAll();
				HarvestManager.cancel();
				FarmManager.cancel();
				CraftPlanner.cancel();
				SurvivalMonitor.clearAllOrders();
				BaritoneBridge.hardStop();
			}
			default -> AIDashboardFrame.appendSystemLog("[WARN] Unknown action verb '" + verb + "' — ignored.");
		}
	}

	/** Holds/releases the sneak key (persists until the opposite order). */
	private static void setSneaking(boolean sneaking) {
		Minecraft mc = Minecraft.getInstance();
		mc.options.keyShift.setDown(sneaking);
		AIDashboardFrame.appendSystemLog("[MOVE] " + (sneaking ? "Sneaking." : "Stopped sneaking."));
	}

	/** Inline action args win; the structured 'target' field is the fallback. */
	private static String argOr(String[] tokens, String fieldValue) {
		if (tokens.length >= 2) {
			return String.join(" ", java.util.Arrays.copyOfRange(tokens, 1, tokens.length));
		}
		return fieldValue != null && !fieldValue.isBlank() ? fieldValue : null;
	}

	/**
	 * Parses "X Y Z" strings (chest_coords / target fields). Tolerates model
	 * decorations like "x:-742, y:66, z:692". Also handles "nearby" chest flags.
	 * Null when unusable.
	 */
	private static int[] parseCoords(String coords, String requester) {
		if (coords == null || coords.isBlank()) {
			return null;
		}
		String mode = coords.trim().toLowerCase(Locale.ROOT);
		if (mode.startsWith("nearby")) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.level == null) return null;
			
			net.minecraft.world.entity.player.Player target = null;
			if (mode.equals("nearby_player") && requester != null && !requester.isBlank()) {
				for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
					if (entity instanceof net.minecraft.world.entity.player.Player other && other.getName().getString().equalsIgnoreCase(requester)) {
						target = other;
						break;
					}
				}
			}
			net.minecraft.core.BlockPos center = target != null ? target.blockPosition() : mc.player.blockPosition();
			for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(center.offset(-10, -5, -10), center.offset(10, 5, 10))) {
				String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).getPath();
				if (name.contains("chest") || name.contains("barrel") || name.contains("shulker_box")) {
					return new int[]{p.getX(), p.getY(), p.getZ()};
				}
			}
			return null;
		}

		String[] parts = coords.strip().split("[,\\s]+");
		int[] out = new int[3];
		int found = 0;
		for (String part : parts) {
			String numeric = part.replaceAll("[^0-9.+-]", "");
			if (numeric.isEmpty() || numeric.equals("-") || numeric.equals("+")) {
				continue;
			}
			try {
				out[found++] = (int) Math.floor(Double.parseDouble(numeric));
				if (found == 3) {
					return out;
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return null;
	}

	/**
	 * Small local models routinely echo the instruction card's placeholder
	 * syntax instead of substituting real names ("follow <player_name>",
	 * "attack player/Steve"). Strip that noise; when the target is a
	 * placeholder or a self-reference ("me"), fall back to the requesting
	 * player's name. Returns null when no usable target remains.
	 */
	private static String cleanTarget(String raw, String senderName) {
		if (raw == null) {
			raw = "";
		}
		String target = raw.replaceAll("[<>\"'`]", "").strip();
		// "player/Steve" or "mob/zombie" — keep the part after the slash.
		int slash = target.lastIndexOf('/');
		if (slash >= 0) {
			target = target.substring(slash + 1).strip();
		}
		String normalized = target.toLowerCase(Locale.ROOT).replace(' ', '_');
		boolean placeholder = normalized.isBlank()
				|| normalized.contains("player_name") || normalized.contains("mob_name")
				|| normalized.equals("name") || normalized.equals("player") || normalized.equals("mob")
				|| normalized.equals("me") || normalized.equals("him") || normalized.equals("her")
				|| normalized.equals("you") || normalized.equals("target");
		
		if (placeholder || target.isEmpty()) {
			if (senderName != null && !senderName.isBlank()) {
				AIDashboardFrame.appendSystemLog("[FIX] Missing/placeholder target '" + raw
						+ "' — using requester '" + senderName + "'.");
				return senderName;
			}
			return null;
		}
		
		return target;
	}

	/**
	 * Parses exactly {@code count} integers from tokens[from..]; on any
	 * failure logs, issues a safety stop, and returns null. Decimals are
	 * floored to block coordinates.
	 */
	private static int[] parseInts(String[] tokens, int from, int count, String action) {
		if (tokens.length < from + count) {
			AIDashboardFrame.appendSystemLog("[WARN] '" + tokens[0] + "' needs " + count
					+ " coordinates, got: '" + action + "' — stopping.");
			BaritoneBridge.stop();
			return null;
		}
		int[] out = new int[count];
		try {
			for (int i = 0; i < count; i++) {
				// Strip "x:" / "y=" style decorations models attach.
				String numeric = tokens[from + i].replaceAll("[^0-9.+-]", "");
				out[i] = (int) Math.floor(Double.parseDouble(numeric));
			}
			return out;
		} catch (NumberFormatException e) {
			AIDashboardFrame.appendSystemLog("[WARN] Unparseable coordinates in '" + action + "' — stopping.");
			BaritoneBridge.stop();
			return null;
		}
	}
}
