package com.itdragclick.client;

import com.itdragclick.AmAI;
import com.itdragclick.client.ai.AIStateManager;
import com.itdragclick.client.ai.AIWhitelistManager;
import com.itdragclick.client.ai.CraftPlanner;
import com.itdragclick.client.ai.FarmManager;
import com.itdragclick.client.ai.HarvestManager;
import com.itdragclick.client.ai.SurvivalMonitor;
import com.itdragclick.client.chat.ChatEventListener;
import com.itdragclick.client.config.SettingsPersistenceManager;
import com.itdragclick.client.memory.AIMemoryStore;
import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

/**
 * Client entrypoint. Boot order matters:
 *  1. Load persisted settings (game thread, fast disk read).
 *  2. Launch the Swing dashboard explicitly on the isolated Event Dispatch
 *     Thread via {@code SwingUtilities.invokeLater} — construction never
 *     happens on (or blocks) the Minecraft render thread.
 *  3. Register the inbound chat interceptor.
 */
public class AmAIClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		SettingsPersistenceManager.load();
		AIMemoryStore.load();
		AIWhitelistManager.load();

		launchDashboard();

		ChatEventListener.register();
		SurvivalMonitor.register();
		HarvestManager.register();
		AIStateManager.register();
		FarmManager.register();
		CraftPlanner.register();
		com.itdragclick.client.ai.IdleBehaviorManager.register();
		com.itdragclick.client.ai.ReactiveChatManager.register();
		com.itdragclick.client.ai.SleepManager.register();

		// Tear the dashboard down with the client so the JVM can exit cleanly.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AIDashboardFrame.shutdown());

		AmAI.LOGGER.info("[am-ai] Client initialised — dashboard launching, chat hooks registered.");
	}

	/**
	 * Spawns the dashboard on the Swing EDT. Some launchers/JVMs start the
	 * game with AWT headless defaults, which silently prevents any window from
	 * ever appearing — force windowed AWT before the toolkit initialises.
	 */
	private static void launchDashboard() {
		System.setProperty("java.awt.headless", "false");
		if (GraphicsEnvironment.isHeadless()) {
			AmAI.LOGGER.error("[am-ai] AWT is headless — the dashboard window cannot be created on this JVM.");
			return;
		}
		SwingUtilities.invokeLater(() -> {
			try {
				AIDashboardFrame.open();
			} catch (Exception e) {
				AmAI.LOGGER.error("[am-ai] Failed to create the dashboard window", e);
			}
		});
	}
}
