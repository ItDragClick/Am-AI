package com.itdragclick.client.ai;

import com.itdragclick.client.ui.AIDashboardFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Pre-action inventory preparation: full-array (slots 0-35) scans that stage
 * the best weapon in hotbar slot 0 and food in hotbar slot 1 before combat or
 * eating routines run, plus the drop_items ground-delivery system.
 *
 * All methods MUST be called on the main game thread — they issue container
 * click packets and mutate the selected hotbar slot.
 *
 * Hotbar conventions: slot 0 = weapon, slot 1 = food.
 * InventoryMenu slot mapping: inventory index N (0-35) occupies menu slot N
 * for the main inventory (9-35); hotbar indexes 0-8 sit at menu slots 36-44.
 */
public final class InventoryHelper {

	public static final int WEAPON_HOTBAR_SLOT = 0;
	public static final int FOOD_HOTBAR_SLOT = 1;
	/** Staging slot for utility items (MLG bucket) — keeps weapon/food slots intact. */
	public static final int UTILITY_HOTBAR_SLOT = 2;

	private InventoryHelper() {
	}

	// ------------------------------------------------------------- lookup

	public static String itemIdOf(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
	}

	public static boolean isLog(ItemStack stack) {
		return !stack.isEmpty() && itemIdOf(stack).endsWith("_log");
	}

	public static int countItem(LocalPlayer player, String itemId) {
		int total = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty() && itemIdOf(stack).equals(itemId)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** Sums every arrow variant a bow/crossbow can fire (arrow, spectral, tipped). */
	public static int countArrows(LocalPlayer player) {
		int total = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			String id = itemIdOf(stack);
			if (id.equals("arrow") || id.equals("spectral_arrow") || id.equals("tipped_arrow")) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** Pure read: does the inventory hold a bow or crossbow? No container clicks. */
	public static boolean hasRangedWeapon(LocalPlayer player) {
		for (int slot = 0; slot < 36; slot++) {
			String id = itemIdOf(player.getInventory().getItem(slot));
			if (id.equals("bow") || id.equals("crossbow")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 26.x spear — usable from horseback. Exact id uncertain across drops
	 * (spear / wooden_spear / copper_spear...), so match by suffix; absent
	 * item just means this never returns true and mounted combat falls back
	 * to the regular best weapon.
	 */
	public static boolean isSpear(ItemStack stack) {
		return !stack.isEmpty() && stack.has(DataComponents.WEAPON) && itemIdOf(stack).endsWith("spear");
	}

	/**
	 * Silk-touch check on an item's ENCHANTMENTS component. Isolated here on
	 * purpose: the 26.2 ItemEnchantments accessor shape is the only uncertain
	 * bit, so a rename is a one-line fix.
	 */
	public static boolean hasSilkTouch(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		var enchantments = stack.get(DataComponents.ENCHANTMENTS);
		if (enchantments == null) {
			return false;
		}
		for (var holder : enchantments.keySet()) {
			if (holder.unwrapKey().map(k -> k.identifier().getPath().equals("silk_touch")).orElse(false)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Weapon effectiveness score: WEAPON component required, ranked by the
	 * ATTACK_DAMAGE attribute total (26.2 has no SwordItem/tier classes — a
	 * diamond sword simply carries a bigger modifier than an iron axe).
	 */
	public static double weaponScore(ItemStack stack) {
		if (stack.isEmpty() || !stack.has(DataComponents.WEAPON)) {
			return 0.0;
		}
		// The mace is a smash-combo weapon (fall-distance damage), near-useless
		// as a walking melee pick. Only the dedicated wind-charge routine in
		// SurvivalMonitor ever equips it.
		if (itemIdOf(stack).equals("mace")) {
			return 0.0;
		}
		double damage = 0.0;
		ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
		if (modifiers != null) {
			for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
				if (entry.attribute().equals(Attributes.ATTACK_DAMAGE)) {
					damage += entry.modifier().amount();
				}
			}
		}

		String priority = com.itdragclick.client.config.SettingsPersistenceManager.get().weaponPriority;
		String id = itemIdOf(stack);
		if ("Swords".equalsIgnoreCase(priority) && id.endsWith("_sword")) {
			damage += 5.0; // Artificial boost to always pick swords
		} else if ("Axes".equalsIgnoreCase(priority) && id.endsWith("_axe")) {
			damage += 5.0; // Artificial boost to always pick axes
		}

		// Mounted bias: on horseback the spear beats everything (reach +
		// charge damage), so outbid the priority boosts.
		LocalPlayer rider = Minecraft.getInstance().player;
		if (rider != null && rider.isPassenger()
				&& rider.getVehicle() instanceof net.minecraft.world.entity.LivingEntity
				&& isSpear(stack)) {
			damage += 10.0;
		}

		return 1.0 + damage;
	}

	// -------------------------------------------------------------- swaps

	/**
	 * Swaps inventory index {@code from} (0-35) into hotbar slot
	 * {@code hotbar} (0-8) via a SWAP container click on the survival
	 * inventory menu. No-op when already in place.
	 */
	public static void swapIntoHotbar(Minecraft mc, LocalPlayer player, int from, int hotbar) {
		if (from == hotbar) {
			return;
		}
		// Menu slot for hotbar index H is 36+H; main inventory index N is N.
		int menuSlot = from < 9 ? 36 + from : from;
		mc.gameMode.handleContainerInput(player.inventoryMenu.containerId,
				menuSlot, hotbar, ContainerInput.SWAP, player);
	}

	/**
	 * Best Weapon Selector: scans slots 0-35, stages the highest-damage
	 * weapon in hotbar slot 0, and selects it. Returns false when the
	 * inventory holds no weapon at all (fists it is).
	 */
	public static boolean equipBestWeapon(Minecraft mc, LocalPlayer player) {
		if (SurvivalMonitor.isEating()) return false;
		int bestSlot = -1;
		double bestScore = 0.0;
		for (int slot = 0; slot < 36; slot++) {
			double score = weaponScore(player.getInventory().getItem(slot));
			if (score > bestScore) {
				bestScore = score;
				bestSlot = slot;
			}
		}
		if (bestSlot < 0) {
			return false;
		}
		if (bestSlot != WEAPON_HOTBAR_SLOT) {
			swapIntoHotbar(mc, player, bestSlot, WEAPON_HOTBAR_SLOT);
		}
		if (player.getInventory().getSelectedSlot() != WEAPON_HOTBAR_SLOT) {
			player.getInventory().setSelectedSlot(WEAPON_HOTBAR_SLOT);
		}
		return true;
	}

	/**
	 * Selects the named item in the MAIN hand via the hotbar: if it's already
	 * on the hotbar just select that slot; otherwise swap it in from the
	 * backpack to the utility slot and select it. Never touches the offhand
	 * (offhand SWAP clicks under latency glitch/desync items). Returns false
	 * when the item is nowhere in the inventory.
	 */
	/**
	 * Makes sure the item sits somewhere in the hotbar WITHOUT selecting it —
	 * pays the backpack swap-click cost ahead of time so a later
	 * {@link #selectInHotbar} is a pure slot selection (instant). Returns false
	 * when the item is nowhere in the inventory.
	 */
	public static boolean stageInHotbar(Minecraft mc, LocalPlayer player, String itemId) {
		for (int slot = 0; slot < 9; slot++) {
			if (itemIdOf(player.getInventory().getItem(slot)).equals(itemId)) {
				return true;
			}
		}
		for (int slot = 9; slot < 36; slot++) {
			if (itemIdOf(player.getInventory().getItem(slot)).equals(itemId)) {
				swapIntoHotbar(mc, player, slot, UTILITY_HOTBAR_SLOT);
				return true;
			}
		}
		return false;
	}

	public static boolean selectInHotbar(Minecraft mc, LocalPlayer player, String itemId) {
		for (int slot = 0; slot < 9; slot++) {
			if (itemIdOf(player.getInventory().getItem(slot)).equals(itemId)) {
				if (player.getInventory().getSelectedSlot() != slot) {
					player.getInventory().setSelectedSlot(slot);
				}
				return true;
			}
		}
		for (int slot = 9; slot < 36; slot++) {
			if (itemIdOf(player.getInventory().getItem(slot)).equals(itemId)) {
				swapIntoHotbar(mc, player, slot, UTILITY_HOTBAR_SLOT);
				player.getInventory().setSelectedSlot(UTILITY_HOTBAR_SLOT);
				return true;
			}
		}
		return false;
	}

	/**
	 * Stages a bow (preferred) or crossbow in hotbar slot 0 and selects it.
	 * Returns false when the inventory holds neither. Ammo checks are the
	 * caller's job ({@code countItem(player, "arrow")}).
	 */
	public static boolean equipRangedWeapon(Minecraft mc, LocalPlayer player) {
		if (SurvivalMonitor.isEating()) return false;
		String heldId = itemIdOf(player.getInventory().getSelectedItem());
		if (heldId.equals("bow") || heldId.equals("crossbow")) {
			return true;
		}
		int bowSlot = -1;
		int crossbowSlot = -1;
		for (int slot = 0; slot < 36; slot++) {
			String id = itemIdOf(player.getInventory().getItem(slot));
			if (id.equals("bow") && bowSlot < 0) {
				bowSlot = slot;
			} else if (id.equals("crossbow") && crossbowSlot < 0) {
				crossbowSlot = slot;
			}
		}
		int chosen = bowSlot >= 0 ? bowSlot : crossbowSlot;
		if (chosen < 0) {
			return false;
		}
		if (chosen != WEAPON_HOTBAR_SLOT) {
			swapIntoHotbar(mc, player, chosen, WEAPON_HOTBAR_SLOT);
		}
		player.getInventory().setSelectedSlot(WEAPON_HOTBAR_SLOT);
		return true;
	}

	public static boolean equipBestAxe(Minecraft mc, LocalPlayer player) {
		if (SurvivalMonitor.isEating()) return false;
		int bestSlot = -1;
		double bestScore = 0.0;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (itemIdOf(stack).endsWith("_axe")) {
				double score = weaponScore(stack);
				if (score > bestScore) {
					bestScore = score;
					bestSlot = slot;
				}
			}
		}
		if (bestSlot < 0) return false;
		if (bestSlot != WEAPON_HOTBAR_SLOT) swapIntoHotbar(mc, player, bestSlot, WEAPON_HOTBAR_SLOT);
		if (player.getInventory().getSelectedSlot() != WEAPON_HOTBAR_SLOT) player.getInventory().setSelectedSlot(WEAPON_HOTBAR_SLOT);
		return true;
	}

	/**
	 * Deep Inventory Food Finder: scans all 36 slots for anything edible
	 * (26.2: {@code stack.has(DataComponents.FOOD)} — isEdible() is gone),
	 * stages it in hotbar slot 1, and selects it. Returns false when the
	 * whole inventory holds no food.
	 */
	public static boolean stageFoodInHotbar(Minecraft mc, LocalPlayer player) {
		int foodSlot = -1;
		for (int slot = 0; slot < 36; slot++) {
			if (player.getInventory().getItem(slot).has(DataComponents.FOOD)) {
				foodSlot = slot;
				break;
			}
		}
		if (foodSlot < 0) {
			return false;
		}
		if (foodSlot != FOOD_HOTBAR_SLOT) {
			swapIntoHotbar(mc, player, foodSlot, FOOD_HOTBAR_SLOT);
		}
		player.getInventory().setSelectedSlot(FOOD_HOTBAR_SLOT);
		return true;
	}

	// --------------------------------------------------------- drop_items

	/**
	 * drop_items system: finds every stack matching the item id, selects its
	 * slot (swapping backpack stacks into the hotbar first), and drops the
	 * whole stack at the bot's feet so nearby players can pick it up.
	 * Returns the number of items dropped.
	 */
	private static final java.util.Queue<DropAction> dropQueue = new java.util.LinkedList<>();
	private static int dropDelayCounter = 0;

	private record DropAction(int slot, boolean dropWholeStack) {}

	public static void tickDrops(Minecraft mc, LocalPlayer player) {
		if (dropQueue.isEmpty()) {
			return;
		}
		if (dropDelayCounter > 0) {
			dropDelayCounter--;
			return;
		}
		dropDelayCounter = 5; // 250ms delay between drops
		DropAction action = dropQueue.poll();
		int menuSlot = action.slot < 9 ? 36 + action.slot : action.slot;
		int button = action.dropWholeStack ? 1 : 0; // 0 = 1 item, 1 = whole stack
		mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, button, ContainerInput.THROW, player);
	}

	public static int requestDrop(Minecraft mc, LocalPlayer player, String rawItemId, int quantity) {
		String itemId = rawItemId.toLowerCase().replace("minecraft:", "").trim();
		int toDrop = quantity <= 0 ? Integer.MAX_VALUE : quantity;
		int queued = 0;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && itemIdOf(stack).equals(itemId)) {
				int stackCount = stack.getCount();
				if (toDrop >= stackCount) {
					dropQueue.add(new DropAction(slot, true));
					queued += stackCount;
					toDrop -= stackCount;
				} else {
					for (int i = 0; i < toDrop; i++) {
						dropQueue.add(new DropAction(slot, false));
					}
					queued += toDrop;
					toDrop = 0;
				}
				if (toDrop == 0) {
					break;
				}
			}
		}
		if (queued > 0) {
			if (dropQueue.size() == queued) {
				dropDelayCounter = 10; // initial delay of 500ms
			}
			AIDashboardFrame.appendSystemLog("[INVENTORY] Queued drop of " + queued + " x '" + itemId + "'.");
		} else {
			AIDashboardFrame.appendSystemLog("[INVENTORY] No '" + itemId + "' in the inventory to drop.");
		}
		return queued;
	}

	public static void equipArmor(Minecraft mc, LocalPlayer player) {
		int equipped = 0;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && stack.has(DataComponents.EQUIPPABLE)) {
				int menuSlot = slot < 9 ? 36 + slot : slot;
				mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, 0, ContainerInput.QUICK_MOVE, player);
				equipped++;
			}
		}
		if (equipped > 0) {
			AIDashboardFrame.appendSystemLog("[INVENTORY] Equipped " + equipped + " armor pieces.");
		}
	}

	public static void equipOffhand(Minecraft mc, LocalPlayer player, String itemId) {
		String cleanId = itemId.toLowerCase().replace("minecraft:", "").trim();
		if (itemIdOf(player.getOffhandItem()).equals(cleanId)) return;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && itemIdOf(stack).equals(cleanId)) {
				int menuSlot = slot < 9 ? 36 + slot : slot;
				mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, 40, ContainerInput.SWAP, player);
				AIDashboardFrame.appendSystemLog("[INVENTORY] Equipped " + cleanId + " in offhand.");
				return;
			}
		}
	}

	public static void unequipArmor(Minecraft mc, LocalPlayer player) {
		// Armor slots in InventoryMenu: 5 (head), 6 (chest), 7 (legs), 8 (feet). Offhand: 45.
		for (int slot : new int[]{5, 6, 7, 8, 45}) {
			ItemStack stack = player.inventoryMenu.getSlot(slot).getItem();
			if (!stack.isEmpty()) {
				mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
			}
		}
		AIDashboardFrame.appendSystemLog("[INVENTORY] Unequipped armor and offhand.");
	}

	public static String getInventorySummary(LocalPlayer player) {
		java.util.Map<String, Integer> counts = new java.util.HashMap<>();
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty()) {
				String id = itemIdOf(stack);
				counts.put(id, counts.getOrDefault(id, 0) + stack.getCount());
			}
		}
		if (counts.isEmpty()) {
			return "empty";
		}
		// Cap the list so a packed inventory can't bloat the prompt: biggest
		// stacks first, tail summarized.
		java.util.List<java.util.Map.Entry<String, Integer>> sorted = new java.util.ArrayList<>(counts.entrySet());
		sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		java.util.List<String> parts = new java.util.ArrayList<>();
		int shown = 0;
		for (java.util.Map.Entry<String, Integer> e : sorted) {
			if (shown >= 40) {
				parts.add("- (+" + (sorted.size() - shown) + " more item types)");
				break;
			}
			parts.add("- " + e.getValue() + " x " + e.getKey());
			shown++;
		}
		return "\n" + String.join("\n", parts);
	}
}
