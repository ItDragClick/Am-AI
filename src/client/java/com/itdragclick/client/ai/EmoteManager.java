package com.itdragclick.client.ai;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

public final class EmoteManager {

    private static int emoteTicks = 0;
    private static int sneakToggles = 0;
    private static boolean isSneaking = false;

    private EmoteManager() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(EmoteManager::onTick);
    }

    public static void startHappyDance() {
        emoteTicks = 20; // total duration 1 second (20 ticks)
        sneakToggles = 6; // sneak 3 times rapidly
    }

    private static void onTick(Minecraft mc) {
        if (mc.player == null) return;
        
        if (emoteTicks > 0) {
            emoteTicks--;
            
            // Toggle sneak every 3 ticks
            if (emoteTicks % 3 == 0 && sneakToggles > 0) {
                isSneaking = !isSneaking;
                mc.options.keyShift.setDown(isSneaking);
                sneakToggles--;
            }
            
            if (emoteTicks == 0 || sneakToggles == 0) {
                isSneaking = false;
                mc.options.keyShift.setDown(false);
                emoteTicks = 0;
                sneakToggles = 0;
            }
        }
    }
}
