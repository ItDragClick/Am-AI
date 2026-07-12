package com.itdragclick.client.ai;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import com.itdragclick.client.net.OllamaNetworkClient;
import com.itdragclick.client.ui.AIDashboardFrame;

public final class ReactiveChatManager {

    private static float lastHealth = -1;
    private static int lastDiamondCount = -1;
    private static int cooldownTicks = 0;

    private ReactiveChatManager() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ReactiveChatManager::onTick);
    }

    private static void onTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            lastHealth = -1;
            lastDiamondCount = -1;
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
        }

        // Initialize state on first tick
        if (lastHealth == -1) {
            lastHealth = player.getHealth();
            lastDiamondCount = InventoryHelper.countItem(player, "diamond");
            return;
        }

        float currentHealth = player.getHealth();
        int currentDiamondCount = InventoryHelper.countItem(player, "diamond");

        // React to taking damage (only if significant or on fire, and not on cooldown)
        if (currentHealth < lastHealth && cooldownTicks == 0) {
            float damage = lastHealth - currentHealth;
            if (damage > 1.0f || player.isOnFire()) {
                String cause = player.isOnFire() ? "fire" : "something";
                String prompt = "[SYSTEM: You just took damage from " + cause + "! React to this in chat (complain, scream, or be confused)!]";
                AIDashboardFrame.appendSystemLog("[REACTIVE] Triggered damage reaction.");
                OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.IN_GAME, "System", prompt);
                cooldownTicks = 200; // 10 seconds cooldown
            }
        }
        lastHealth = currentHealth;

        // React to finding a diamond
        if (currentDiamondCount > lastDiamondCount && cooldownTicks == 0) {
            String prompt = "[SYSTEM: You just picked up a diamond! Brag about it or celebrate in chat!]";
            AIDashboardFrame.appendSystemLog("[REACTIVE] Triggered diamond reaction.");
            OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.IN_GAME, "System", prompt);
            cooldownTicks = 200; 
        }
        lastDiamondCount = currentDiamondCount;
    }
}
