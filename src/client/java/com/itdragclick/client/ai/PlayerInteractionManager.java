package com.itdragclick.client.ai;

import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class PlayerInteractionManager {

    public enum InteractionType { NONE, INSPECTING, COPYCAT, HIDING }

    private static InteractionType currentInteraction = InteractionType.NONE;
    private static Player targetPlayer = null;
    private static int interactionTicks = 0;
    private static int phase = 0; // 0: pathing, 1: executing
    private static BlockPos hideSpot = null;

    private PlayerInteractionManager() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PlayerInteractionManager::onTick);
    }

    public static boolean isActive() {
        return currentInteraction != InteractionType.NONE;
    }

    public static void startInteraction(InteractionType type, Player target) {
        currentInteraction = type;
        targetPlayer = target;
        interactionTicks = 0;
        phase = 0;
        hideSpot = null;
        AIStateManager.preemptForNewTask(); // pause other tasks
        AIDashboardFrame.appendSystemLog("[INTERACT] Started " + type.name() + " with " + target.getName().getString());
    }

    public static void cancel() {
        if (isActive()) {
            AIDashboardFrame.appendSystemLog("[INTERACT] Interaction cancelled.");
            Minecraft.getInstance().options.keyShift.setDown(false);
            Minecraft.getInstance().options.keyJump.setDown(false);
            BaritoneBridge.stopQuietly();
        }
        currentInteraction = InteractionType.NONE;
        targetPlayer = null;
        AIStateManager.taskCompleted(); // resume previous tasks
    }

    public static AIStateManager.TaskContext captureAndPause() {
        if (!isActive()) return null;
        cancel();
        return null; // we don't resume random interactions if interrupted by combat/etc
    }

    private static void onTick(Minecraft mc) {
        if (!isActive()) return;

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || targetPlayer == null || targetPlayer.isRemoved()) {
            cancel();
            return;
        }

        interactionTicks++;

        // Failsafe timeout for any interaction (30 seconds)
        if (interactionTicks > 600) {
            cancel();
            return;
        }

        switch (currentInteraction) {
            case INSPECTING -> handleInspecting(mc, player);
            case COPYCAT -> handleCopycat(mc, player);
            case HIDING -> handleHiding(mc, player);
            default -> {}
        }
    }

    private static void handleInspecting(Minecraft mc, LocalPlayer player) {
        if (phase == 0) {
            if (player.distanceToSqr(targetPlayer) > 4.0) {
                if (interactionTicks % 20 == 1) {
                    BaritoneBridge.goTo(targetPlayer.getBlockX(), targetPlayer.getBlockY(), targetPlayer.getBlockZ());
                }
            } else {
                BaritoneBridge.stopQuietly();
                phase = 1;
                interactionTicks = 0; // reset for staring phase
                
                String[] msgs = {"Nice gear...", "Is that a new sword?", "Looking rich today.", "I like your style."};
                String msg = msgs[(int)(Math.random() * msgs.length)];
                announce(mc, msg);
            }
        } else if (phase == 1) {
            player.lookAt(EntityAnchorArgument.Anchor.EYES, targetPlayer.getEyePosition());
            if (interactionTicks > 60) { // stare for 3 seconds
                cancel(); // done
            }
        }
    }

    private static void handleCopycat(Minecraft mc, LocalPlayer player) {
        if (phase == 0) {
            if (player.distanceToSqr(targetPlayer) > 4.0) {
                if (interactionTicks % 20 == 1) {
                    BaritoneBridge.goTo(targetPlayer.getBlockX(), targetPlayer.getBlockY(), targetPlayer.getBlockZ());
                }
            } else {
                BaritoneBridge.stopQuietly();
                phase = 1;
                interactionTicks = 0; 
            }
        } else if (phase == 1) {
            // copy facing and actions
            player.lookAt(EntityAnchorArgument.Anchor.EYES, targetPlayer.getEyePosition());
            
            mc.options.keyShift.setDown(targetPlayer.isCrouching());
            if (targetPlayer.onGround() && !targetPlayer.isCrouching() && mc.options.keyJump.isDown()) {
                mc.options.keyJump.setDown(false); // reset
            } else if (!targetPlayer.onGround() && targetPlayer.getDeltaMovement().y > 0) {
                if (player.onGround()) player.jumpFromGround();
            }
            
            if (interactionTicks > 200) { // copy for 10 seconds
                mc.options.keyShift.setDown(false);
                mc.options.keyJump.setDown(false);
                String[] msgs = {"Stop copying me!", "Haha", "You're fun."};
                announce(mc, msgs[(int)(Math.random() * msgs.length)]);
                cancel();
            }
        }
    }

    private static void handleHiding(Minecraft mc, LocalPlayer player) {
        if (phase == 0) {
            announce(mc, "You can't find me!");
            
            // Pick a random spot 15-20 blocks away
            double angle = Math.random() * 2 * Math.PI;
            int dist = 15 + (int)(Math.random() * 5);
            int tx = player.getBlockX() + (int)(Math.cos(angle) * dist);
            int tz = player.getBlockZ() + (int)(Math.sin(angle) * dist);
            
            hideSpot = new BlockPos(tx, player.getBlockY(), tz);
            BaritoneBridge.goTo(tx, player.getBlockY(), tz);
            phase = 1;
            interactionTicks = 0;
        } else if (phase == 1) {
            if (hideSpot != null && player.blockPosition().closerToCenterThan(Vec3.atCenterOf(hideSpot), 3.0)) {
                BaritoneBridge.stopQuietly();
                phase = 2;
                interactionTicks = 0;
            } else if (interactionTicks > 200) { // 10s to find a spot
                BaritoneBridge.stopQuietly();
                phase = 2;
                interactionTicks = 0;
            }
        } else if (phase == 2) {
            mc.options.keyShift.setDown(true); // sneak to hide
            player.lookAt(EntityAnchorArgument.Anchor.EYES, targetPlayer.getEyePosition());
            if (interactionTicks > 300) { // hide for 15 seconds
                mc.options.keyShift.setDown(false);
                announce(mc, "Okay I'm bored of hiding.");
                cancel();
            }
        }
    }

    private static void announce(Minecraft mc, String message) {
        if (mc.getConnection() != null) {
            mc.getConnection().sendChat(message.length() > 100 ? message.substring(0, 100) : message);
        }
    }
}
