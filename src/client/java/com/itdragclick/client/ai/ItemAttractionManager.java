package com.itdragclick.client.ai;

import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import net.minecraft.commands.arguments.EntityAnchorArgument;

import java.util.List;
import java.util.Set;

public final class ItemAttractionManager {
    
    private static final Set<String> SHINY_ITEMS = Set.of(
        "minecraft:diamond", "minecraft:emerald", "minecraft:gold_ingot", 
        "minecraft:diamond_block", "minecraft:emerald_block", "minecraft:gold_block",
        "minecraft:nether_star", "minecraft:netherite_ingot"
    );

    private static boolean isAttracted = false;
    private static int attractionTimer = 0;
    private static Entity targetItem = null;

    private ItemAttractionManager() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ItemAttractionManager::onTick);
    }

    private static void onTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        if (isAttracted) {
            attractionTimer--;
            if (targetItem != null && targetItem.isAlive()) {
                player.lookAt(EntityAnchorArgument.Anchor.EYES, targetItem.position());
            }
            if (attractionTimer <= 0 || targetItem == null || !targetItem.isAlive()) {
                isAttracted = false;
                targetItem = null;
                AIDashboardFrame.appendSystemLog("[SHINY] Done staring at shiny object. Resuming previous task.");
                AIStateManager.taskCompleted(); // pop and resume
            }
            return; // block new scans while attracted
        }

        // Only scan every 20 ticks to save performance
        if (mc.level.getGameTime() % 20 != 0) return;

        List<Entity> items = mc.level.getEntities(player, player.getBoundingBox().inflate(15.0), e -> e instanceof ItemEntity);
        for (Entity e : items) {
            ItemEntity itemEnt = (ItemEntity) e;
            ItemStack stack = itemEnt.getItem();
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (SHINY_ITEMS.contains(id.toString())) {
                AIDashboardFrame.appendSystemLog("[SHINY] Ooh, shiny! Found " + id.getPath() + ". Pausing current task.");
                
                // Preempt current task
                AIStateManager.preemptForNewTask();
                
                // Walk to it
                BaritoneBridge.goTo(e.getBlockX(), e.getBlockY(), e.getBlockZ());
                
                isAttracted = true;
                attractionTimer = 200; // 10 seconds of staring (20 ticks/sec * 10)
                targetItem = e;
                return;
            }
        }
    }
}
