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

    private static final Set<String> FLOWERS = Set.of(
        "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid", 
        "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip",
        "minecraft:orange_tulip", "minecraft:white_tulip", "minecraft:pink_tulip",
        "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley"
    );

    private static boolean isAttracted = false;
    private static int attractionTimer = 0;
    private static Entity targetItem = null;
    /** Cooldown after deciding NOT to chase a shiny item (no re-roll spam). */
    private static int shinyIgnoreCooldown = 0;
    /** Chance to actually get distracted when a shiny item is spotted. */
    private static final double SHINY_ATTRACTION_CHANCE = 0.25;

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
        if (shinyIgnoreCooldown > 0) shinyIgnoreCooldown -= 20;

        List<Entity> items = mc.level.getEntities(player, player.getBoundingBox().inflate(15.0), e -> e instanceof ItemEntity);
        for (Entity e : items) {
            ItemEntity itemEnt = (ItemEntity) e;
            ItemStack stack = itemEnt.getItem();
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (SHINY_ITEMS.contains(id.toString())) {
                // Not greedy every time: only sometimes gets distracted. A
                // failed roll ignores shiny items for a while instead of
                // re-rolling every second.
                if (shinyIgnoreCooldown > 0) continue;
                if (Math.random() >= SHINY_ATTRACTION_CHANCE) {
                    shinyIgnoreCooldown = 1200; // ignore shinies for 60s
                    continue;
                }
                AIDashboardFrame.appendSystemLog("[SHINY] Ooh, shiny! Found " + id.getPath() + ". Pausing current task.");
                
                // Preempt current task
                AIStateManager.preemptForNewTask();
                
                // Walk to it
                BaritoneBridge.goToIdle(e.getBlockX(), e.getBlockY(), e.getBlockZ());
                
                isAttracted = true;
                attractionTimer = 200; // 10 seconds of staring (20 ticks/sec * 10)
                targetItem = e;
                return;
            } else if (FLOWERS.contains(id.toString())) {
                net.minecraft.world.entity.player.Player thrower = mc.level.getNearestPlayer(e.getX(), e.getY(), e.getZ(), 5.0, false);
                if (thrower != null && thrower != player) {
                    String name = thrower.getName().getString();
                    int currentScore = com.itdragclick.client.memory.PlayerRelationshipDB.getScore(name);
                    if (currentScore < -60) {
                        // Full forgiveness: a flower resets a hated player to neutral.
                        com.itdragclick.client.memory.PlayerRelationshipDB.setScore(name, 0);
                        AIDashboardFrame.appendSystemLog("[RELATIONSHIP] " + name + " gave a flower! Forgiven. Score reset to 0.");
                    } else {
                        com.itdragclick.client.memory.PlayerRelationshipDB.modifyScore(name, 10);
                        AIDashboardFrame.appendSystemLog("[RELATIONSHIP] " + name + " gave a flower! Score is now " + com.itdragclick.client.memory.PlayerRelationshipDB.getScore(name));
                    }
                    e.remove(Entity.RemovalReason.DISCARDED);
                }
            }
        }
    }
}
