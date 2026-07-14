package com.itdragclick.client.ai;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import com.itdragclick.client.ui.AIDashboardFrame;
import com.itdragclick.client.net.OllamaNetworkClient;
import com.itdragclick.client.memory.AIMemoryStore;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import java.util.List;

public final class IdleBehaviorManager {

    private static int idleTicks = 0;
    private static int actionCooldown = 0;
    private static boolean active = false;

    private IdleBehaviorManager() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(IdleBehaviorManager::onTick);
    }

    private static void onTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }

        // A running task (or combat) fully disables idle: reset the idle
        // counter AND arm a fresh cooldown so nothing idle fires the instant
        // the task finishes — the count starts over from zero every time.
        if (AIStateManager.anythingActive() || SurvivalMonitor.isInCombat()) {
            idleTicks = 0;
            actionCooldown = 600; // 30s grace after the task ends
            if (active) {
                active = false;
                AIDashboardFrame.appendSystemLog("[IDLE] Idle behavior interrupted by active task.");
            }
            return;
        }

        idleTicks++;
        if (actionCooldown > 0) {
            actionCooldown--;
        }

        // 0.1% chance per tick to initiate a random player interaction —
        // only once genuinely idle (30s+) and off cooldown, same as the
        // other idle events.
        if (idleTicks > 600 && actionCooldown == 0 && Math.random() < 0.001 && !PlayerInteractionManager.isActive()) {
            List<Entity> players = mc.level.getEntities(player, player.getBoundingBox().inflate(15.0), e -> e instanceof net.minecraft.world.entity.player.Player);
            if (!players.isEmpty()) {
                Entity p = players.get((int)(Math.random() * players.size()));
                PlayerInteractionManager.InteractionType[] types = {
                    PlayerInteractionManager.InteractionType.INSPECTING, 
                    PlayerInteractionManager.InteractionType.COPYCAT, 
                    PlayerInteractionManager.InteractionType.HIDING
                };
                PlayerInteractionManager.startInteraction(types[(int)(Math.random() * types.length)], (net.minecraft.world.entity.player.Player) p);
                return;
            }
        }

        // Trigger an idle behavior if we've been idle for 30 seconds and cooldown is finished.
        if (idleTicks > 600 && actionCooldown == 0) {
            triggerIdleBehavior(mc, player);
            // Cooldown for 30-60 seconds before doing something else randomly.
            actionCooldown = 600 + (int)(Math.random() * 600); 
        }
    }

    private static void triggerIdleBehavior(Minecraft mc, LocalPlayer player) {
        com.itdragclick.client.config.AIModSettings cfg = com.itdragclick.client.config.SettingsPersistenceManager.get();
        active = true;
        
        // Check if it's night time and bot is outside/idle
        long timeOfDay = mc.level.getOverworldClockTime() % 24000L;
        if (timeOfDay > 13000 && timeOfDay < 23000) {
            if (Math.random() < 0.3) {
                String prompt = "[SYSTEM: It is dark outside and you are tired! Complain about it or say you want to go to sleep!]";
                OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.IN_GAME, "System", prompt);
                SleepManager.startSleep();
                return;
            }
        }
        
        // Weather reaction
        if (mc.level.isRaining() && mc.level.canSeeSky(player.blockPosition())) {
            if (Math.random() < 0.2) {
                String prompt = "[SYSTEM: It is raining and you are getting wet! Complain about the rain!]";
                OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.IN_GAME, "System", prompt);
                return;
            }
        }
        
        // Check for nearby players
        List<Entity> players = mc.level.getEntities(player, player.getBoundingBox().inflate(10.0), e -> e instanceof net.minecraft.world.entity.player.Player);
        for (Entity e : players) {
            String name = e.getName().getString();
            int score = com.itdragclick.client.memory.PlayerRelationshipDB.getScore(name);

            if (score < -60 && Math.random() < 0.3) {
                String prompt = "[SYSTEM: You hate " + name + " (score: " + score + ") and they are nearby! Output action 'attack' targeting them and say something mean!]";
                OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.IN_GAME, "System", prompt);
                return;
            }

            if (score >= 50) {
                // Spontaneous gift giving
                if (cfg.allowIdleGift && Math.random() < 0.2) {
                    player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, e.getEyePosition());
                    String prompt = "[SYSTEM: You feel affectionate towards " + name + ". Drop a CHEAP item as a gift (using action 'drop_items') — flowers, food, seeds, cobblestone. NEVER gift diamonds, emeralds, iron, gold, or other valuables. Say something sweet!]";
                    OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.IN_GAME, "System", prompt);
                    EmoteManager.startHappyDance();
                    return;
                }
                
                // Pet-like drifting
                double dist = player.distanceTo(e);
                if (dist > 4.0 && dist < 10.0 && Math.random() < 0.5) {
                    player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, e.getEyePosition());
                    BaritoneBridge.goToIdle(e.getBlockX(), e.getBlockY(), e.getBlockZ());
                    AIDashboardFrame.appendSystemLog("[IDLE] Drifting towards favorite player " + name);
                    return;
                }
            }
        }

        int roll = (int) (Math.random() * 5);

        switch (roll) {
            case 0 -> {
                // Look around randomly
                if (!cfg.allowIdleLookAround) return;
                float targetYaw = player.getYRot() + (float)((Math.random() - 0.5) * 180);
                float targetPitch = (float)((Math.random() - 0.5) * 60);
                player.setYRot(targetYaw);
                player.setXRot(targetPitch);
                AIDashboardFrame.appendSystemLog("[IDLE] Looked around randomly.");
            }
            case 1 -> {
                // Stare at nearest animal
                if (!cfg.allowIdleStare) return;
                List<Entity> entities = mc.level.getEntities(player, player.getBoundingBox().inflate(15.0));
                Entity nearest = null;
                double nearestDistSq = Double.MAX_VALUE;
                for (Entity e : entities) {
                    if (e instanceof Animal) {
                        double distSq = player.distanceToSqr(e);
                        if (distSq < nearestDistSq) {
                            nearestDistSq = distSq;
                            nearest = e;
                        }
                    }
                }
                if (nearest != null) {
                    player.lookAt(EntityAnchorArgument.Anchor.EYES, nearest.getEyePosition());
                    AIDashboardFrame.appendSystemLog("[IDLE] Staring at " + nearest.getName().getString());
                } else {
                    player.setXRot(player.getXRot() - 30); // look at sky
                    AIDashboardFrame.appendSystemLog("[IDLE] Staring at the sky.");
                }
            }
            case 2 -> {
                // Jump (fake parkour)
                if (player.onGround()) {
                    player.jumpFromGround();
                    AIDashboardFrame.appendSystemLog("[IDLE] Jumped randomly.");
                }
            }
            case 3 -> {
                // Autonomous exploring / grass touching
                if (!cfg.allowIdleExplore) return;
                String[] targets = {"short_grass", "tall_grass", "dandelion", "poppy"};
                String target = targets[(int)(Math.random() * targets.length)];
                AIDashboardFrame.appendSystemLog("[IDLE] Starting autonomous exploring/flower picking: " + target);
                
                String chatMsg = "";
                
                // Route through AIActionBridge to use standard systems
                OllamaNetworkClient.AIDecision dec = new OllamaNetworkClient.AIDecision(
                        chatMsg, 
                        "mine " + target, 
                        "", 
                        2, // only break 2 so it doesn't do it forever
                        "", 
                        0
                );
                AIActionBridge.execute(dec, "System");
            }
            case 4 -> {
                // BIG GOAL!
                if (idleTicks > 3600 && cfg.allowIdleBigGoal) { // If idle for more than 3 minutes, do a big goal
                    String[] bigGoals = {
                        "I am going to chop down a bunch of trees and get wood!",
                        "I want to explore far away from here!",
                        "I think I need more food, I am going hunting."
                    };
                    String chatMsg = bigGoals[(int)(Math.random() * bigGoals.length)];
                    AIDashboardFrame.appendSystemLog("[IDLE] Starting BIG GOAL: " + chatMsg);
                    String prompt = "[SYSTEM: You are very bored and just said: '" + chatMsg + "'. Decide on a long-term action (like 'mine oak_log', 'walk forward 100', 'attack pig' with duration_seconds=300) to accomplish this goal! Do NOT go mining for diamonds or other ores.]";
                    OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.IN_GAME, "System", prompt);
                    actionCooldown = 3600; // 3 minute cooldown
                } else {
                    // Fallback to jumping if not idle long enough for a big goal
                    if (player.onGround()) {
                        player.jumpFromGround();
                    }
                }
            }
        }
    }
}
