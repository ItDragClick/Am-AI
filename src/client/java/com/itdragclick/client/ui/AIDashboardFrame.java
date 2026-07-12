package com.itdragclick.client.ui;

import com.itdragclick.client.config.AIModSettings;
import com.itdragclick.client.config.SettingsPersistenceManager;
import com.itdragclick.client.net.OllamaNetworkClient;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * External desktop control dashboard for the AI companion.
 *
 * Lives entirely on the Swing Event Dispatch Thread, fully decoupled from the
 * Minecraft render thread. Construct/show it only via {@link #open()}, which
 * routes through {@code SwingUtilities.invokeLater}.
 */
public class AIDashboardFrame extends JFrame {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final int MAX_CONSOLE_CHARS = 200_000;

	private static volatile AIDashboardFrame instance;

	private final JTextField endpointField = new JTextField(28);
	private final JTextField modelField = new JTextField(18);
	private final JTextField prefixField = new JTextField(8);
	private final JRadioButton activeIndicator = new JRadioButton();
	private final JLabel connectionStatus = new JLabel("Connection: untested");
	private final JTextArea console = new JTextArea();
	private final JTextField promptField = new JTextField();

	/** Creates (once) and shows the dashboard. Safe to call from any thread. */
	public static void open() {
		SwingUtilities.invokeLater(() -> {
			if (instance == null) {
				instance = new AIDashboardFrame();
			}
			instance.setVisible(true);
			instance.toFront();
		});
	}

	/** Disposes the window (called on client shutdown). Safe from any thread. */
	public static void shutdown() {
		SwingUtilities.invokeLater(() -> {
			if (instance != null) {
				instance.dispose();
				instance = null;
			}
		});
	}

	/**
	 * Thread-safe console logger callable from the game thread, HTTP workers,
	 * or the EDT itself. Messages posted before the window exists are dropped
	 * to the game log only by the callers' own slf4j logging.
	 */
	public static void appendSystemLog(String message) {
		String line = "[" + LocalTime.now().format(TIME_FORMAT) + "] " + message + System.lineSeparator();
		SwingUtilities.invokeLater(() -> {
			AIDashboardFrame frame = instance;
			if (frame == null) {
				return;
			}
			JTextArea area = frame.console;
			area.append(line);
			// Bound memory usage on very long sessions.
			int overflow = area.getDocument().getLength() - MAX_CONSOLE_CHARS;
			if (overflow > 0) {
				area.replaceRange("", 0, overflow);
			}
			area.setCaretPosition(area.getDocument().getLength());
		});
	}

	private AIDashboardFrame() {
		super("am-ai Companion Dashboard");
		setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE); // never kill the game client
		setMinimumSize(new Dimension(640, 480));
		setLayout(new GridBagLayout());

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.insets = new Insets(4, 8, 4, 8);
		gbc.fill = GridBagConstraints.BOTH;
		gbc.weightx = 1.0;

		gbc.gridy = 0;
		gbc.weighty = 0;
		add(buildSettingsPanel(), gbc);

		gbc.gridy = 1;
		gbc.weighty = 1.0;
		add(buildConsolePanel(), gbc);

		gbc.gridy = 2;
		gbc.weighty = 0;
		add(buildPromptPanel(), gbc);

		loadSettingsIntoFields();
		pack();
		setLocationByPlatform(true);
		appendLater("Dashboard initialised. Waiting for game events…");
	}

	// ------------------------------------------------------------------ UI

	private JPanel buildSettingsPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createTitledBorder("Settings"));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(2, 4, 2, 4);
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;

		c.gridy = 0;
		c.gridx = 0;
		panel.add(new JLabel("Ollama Endpoint URL:"), c);
		c.gridx = 1;
		c.weightx = 1.0;
		c.gridwidth = 3;
		panel.add(endpointField, c);
		c.gridwidth = 1;
		c.weightx = 0;

		c.gridy = 1;
		c.gridx = 0;
		panel.add(new JLabel("Target Model ID:"), c);
		c.gridx = 1;
		panel.add(modelField, c);
		c.gridx = 2;
		panel.add(new JLabel("Command Prefix:"), c);
		c.gridx = 3;
		panel.add(prefixField, c);

		JButton saveButton = new JButton("Save Config");
		saveButton.addActionListener(e -> onSaveConfig());

		JButton testButton = new JButton("Test Connection");
		testButton.addActionListener(e -> onTestConnection(testButton));

		activeIndicator.setText("Inactive");
		activeIndicator.addActionListener(e -> onToggleActive());

		c.gridy = 2;
		c.gridx = 0;
		panel.add(saveButton, c);
		c.gridx = 1;
		panel.add(testButton, c);
		c.gridx = 2;
		panel.add(activeIndicator, c);
		c.gridx = 3;
		panel.add(connectionStatus, c);

		return panel;
	}

	private JScrollPane buildConsolePanel() {
		console.setEditable(false);
		console.setLineWrap(true);
		console.setWrapStyleWord(true);
		console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		JScrollPane scroll = new JScrollPane(console,
				JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createTitledBorder("System Console Log"));
		scroll.setPreferredSize(new Dimension(600, 260));
		return scroll;
	}

	private JPanel buildPromptPanel() {
		JPanel panel = new JPanel(new BorderLayout(6, 0));
		panel.setBorder(BorderFactory.createTitledBorder("Manual Prompt"));
		JButton sendButton = new JButton("Send");
		sendButton.addActionListener(e -> onManualSend());
		promptField.addActionListener(e -> onManualSend()); // Enter key submits too
		panel.add(promptField, BorderLayout.CENTER);
		panel.add(sendButton, BorderLayout.EAST);
		return panel;
	}

	// ------------------------------------------------------------- actions

	private void loadSettingsIntoFields() {
		AIModSettings cfg = SettingsPersistenceManager.get();
		endpointField.setText(cfg.endpointUrl);
		modelField.setText(cfg.modelId);
		prefixField.setText(cfg.commandPrefix);
		syncActiveIndicator(cfg.active);
	}

	private void onSaveConfig() {
		AIModSettings cfg = SettingsPersistenceManager.get();
		cfg.endpointUrl = endpointField.getText().strip();
		cfg.modelId = modelField.getText().strip();
		cfg.commandPrefix = prefixField.getText().strip().isEmpty() ? "!ai" : prefixField.getText().strip();
		prefixField.setText(cfg.commandPrefix);
		SettingsPersistenceManager.update(cfg);
		appendLater("Configuration saved to config/ai_companion_settings.json");
	}

	private void onToggleActive() {
		boolean active = activeIndicator.isSelected();
		SettingsPersistenceManager.setActive(active);
		syncActiveIndicator(active);
		appendLater("AI system is now " + (active ? "ACTIVE" : "INACTIVE"));
	}

	private void syncActiveIndicator(boolean active) {
		activeIndicator.setSelected(active);
		activeIndicator.setText(active ? "Active" : "Inactive");
		activeIndicator.setForeground(active ? new Color(0, 128, 0) : Color.RED);
	}

	private void onTestConnection(JButton button) {
		String url = endpointField.getText().strip();
		button.setEnabled(false);
		connectionStatus.setText("Connection: testing…");
		connectionStatus.setForeground(Color.DARK_GRAY);
		// The HTTP probe runs on the network worker pool — the EDT never blocks.
		OllamaNetworkClient.testConnection(url).whenComplete((alive, err) -> {
			boolean ok = err == null && Boolean.TRUE.equals(alive);
			SwingUtilities.invokeLater(() -> {
				button.setEnabled(true);
				connectionStatus.setText(ok ? "Connection: ONLINE" : "Connection: OFFLINE");
				connectionStatus.setForeground(ok ? new Color(0, 128, 0) : Color.RED);
			});
			appendSystemLog(ok
					? "Ollama instance is alive at " + url
					: "Ollama instance unreachable at " + url);
		});
	}

	/** Action verbs the dashboard executes directly, bypassing the LLM. */
	private static final java.util.Set<String> DIRECT_ACTION_VERBS = java.util.Set.of(
			"goto", "mine", "mine_area", "follow", "attack", "eat", "drop_items",
			"deposit_chest", "sneak", "unsneak", "click_respawn", "stop");

	/**
	 * Dashboard prompts are structural overrides: when the text already IS an
	 * action ("goto 100 64 -200"), skip conversational processing entirely
	 * and execute it verbatim. Anything else goes to the LLM with the strict
	 * terminal persona (never the in-game companion persona).
	 */
	private void onManualSend() {
		String prompt = promptField.getText().strip();
		if (prompt.isEmpty()) {
			return;
		}
		promptField.setText("");
		String verb = prompt.split("\\s+")[0].toLowerCase();
		if (DIRECT_ACTION_VERBS.contains(verb)) {
			appendLater("Direct action override: " + prompt);
			com.itdragclick.client.ai.AIActionBridge.execute(
					new OllamaNetworkClient.AIDecision("", prompt), "DashboardOperator");
			return;
		}
		appendLater("Manual prompt dispatched: " + prompt);
		OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.DASHBOARD, "DashboardOperator", prompt);
	}

	/** Append helper for use when already on the EDT. */
	private void appendLater(String message) {
		appendSystemLog(message);
	}
}
