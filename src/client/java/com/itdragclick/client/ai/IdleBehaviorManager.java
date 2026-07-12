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

        // If a real task is running, reset idle counters.
        if (AIStateManager.anythingActive()) {
            idleTicks = 0;
            actionCooldown = 0;
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

        // Trigger an idle behavior if we've been idle for 5 seconds and cooldown is finished.
        if (idleTicks > 100 && actionCooldown == 0) {
            triggerIdleBehavior(mc, player);
            // Cooldown for 10-30 seconds before doing something else randomly.
            actionCooldown = 200 + (int)(Math.random() * 400); 
        }
    }

    private static void triggerIdleBehavior(Minecraft mc, LocalPlayer player) {
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
            if (AIMemoryStore.getAffinity(name) >= 50) {
                // Spontaneous gift giving
                if (Math.random() < 0.2) {
                    player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, e.getEyePosition());
                    String prompt = "[SYSTEM: You feel affectionate towards " + name + ". Drop an item as a gift (using action 'drop_items') and say something sweet!]";
                    OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.IN_GAME, "System", prompt);
                    EmoteManager.startHappyDance();
                    return;
                }
                
                // Pet-like drifting
                double dist = player.distanceTo(e);
                if (dist > 4.0 && dist < 10.0 && Math.random() < 0.5) {
                    player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, e.getEyePosition());
                    BaritoneBridge.goTo(e.getBlockX(), e.getBlockY(), e.getBlockZ());
                    AIDashboardFrame.appendSystemLog("[IDLE] Drifting towards favorite player " + name);
                    return;
                }
            }
        }

        int roll = (int) (Math.random() * 4);

        switch (roll) {
            case 0 -> {
                // Look around randomly
                float targetYaw = player.getYRot() + (float)((Math.random() - 0.5) * 180);
                float targetPitch = (float)((Math.random() - 0.5) * 60);
                player.setYRot(targetYaw);
                player.setXRot(targetPitch);
                AIDashboardFrame.appendSystemLog("[IDLE] Looked around randomly.");
            }
            case 1 -> {
                // Stare at nearest animal
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
                String[] targets = {"short_grass", "tall_grass", "dandelion", "poppy"};
                String target = targets[(int)(Math.random() * targets.length)];
                AIDashboardFrame.appendSystemLog("[IDLE] Starting autonomous exploring/flower picking: " + target);
                
                String chatMsg = "";
                if (target.equals("dandelion") || target.equals("poppy")) {
                    chatMsg = "Ooh, a pretty flower!";
                } else {
                    chatMsg = "I'm bored, I'm gonna go touch some grass!";
                }
                
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
        }
    }
}
