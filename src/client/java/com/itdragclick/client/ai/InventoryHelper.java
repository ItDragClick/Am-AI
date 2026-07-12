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

	private InventoryHelper() {
	}

	// ------------------------------------------------------------- lookup

	public static String itemIdOf(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
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

	/**
	 * Weapon effectiveness score: WEAPON component required, ranked by the
	 * ATTACK_DAMAGE attribute total (26.2 has no SwordItem/tier classes — a
	 * diamond sword simply carries a bigger modifier than an iron axe).
	 */
	public static double weaponScore(ItemStack stack) {
		if (stack.isEmpty() || !stack.has(DataComponents.WEAPON)) {
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
	public static int dropAllOf(Minecraft mc, LocalPlayer player, String rawItemId) {
		String itemId = rawItemId.toLowerCase().replace("minecraft:", "").trim();
		int previousSlot = player.getInventory().getSelectedSlot();
		int dropped = 0;
		// Bounded loop: at most 36 stacks can match.
		for (int pass = 0; pass < 36; pass++) {
			int found = -1;
			for (int slot = 0; slot < 36; slot++) {
				ItemStack stack = player.getInventory().getItem(slot);
				if (!stack.isEmpty() && itemIdOf(stack).equals(itemId)) {
					found = slot;
					break;
				}
			}
			if (found < 0) {
				break;
			}
			int hotbarSlot = found < 9 ? found : FOOD_HOTBAR_SLOT;
			if (found >= 9) {
				swapIntoHotbar(mc, player, found, hotbarSlot);
			}
			player.getInventory().setSelectedSlot(hotbarSlot);
			dropped += player.getInventory().getSelectedItem().getCount();
			// true = drop the entire selected stack, not a single item.
			player.drop(true);
		}
		player.getInventory().setSelectedSlot(previousSlot);
		if (dropped > 0) {
			AIDashboardFrame.appendSystemLog("[INVENTORY] Dropped " + dropped + " x '" + itemId + "' at my feet.");
		} else {
			AIDashboardFrame.appendSystemLog("[INVENTORY] No '" + itemId + "' in the inventory to drop.");
		}
		return dropped;
	}
}
