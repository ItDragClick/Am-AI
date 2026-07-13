package com.itdragclick.client.ui;

import com.itdragclick.client.ai.AIActionBridge;
import com.itdragclick.client.ai.AIStateManager;
import com.itdragclick.client.ai.AIWhitelistManager;
import com.itdragclick.client.config.AIModSettings;
import com.itdragclick.client.config.SettingsPersistenceManager;
import com.itdragclick.client.net.OllamaNetworkClient;
import com.itdragclick.client.memory.AIMemoryStore;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JToggleButton;
import javax.swing.Box;

/**
 * External desktop control dashboard for the AI companion.
 *
 * Fully overhauled to feature "Theme Luna" (FlatMacDarkLaf with ColorOS accents).
 */
public class AIDashboardFrame extends JFrame {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final int MAX_CONSOLE_CHARS = 200_000;

	private static volatile AIDashboardFrame instance;

	private final JTextField endpointField = new JTextField(20);
	private final JTextField modelField = new JTextField(12);
	private final JTextField prefixField = new JTextField(6);
	private final JRadioButton activeIndicator = new JRadioButton("Active");
	private final javax.swing.JCheckBox combatBlocksCheck = new javax.swing.JCheckBox("Combat Blocks (Break/Place)");
	private final JLabel connectionStatus = new JLabel("Connection: untested");
	private final JTextArea console = new JTextArea();
	private final JTextField promptField = new JTextField();
	private final DefaultListModel<String> taskListModel = new DefaultListModel<>();
	
	private final SlidingPanel slidingPanel = new SlidingPanel();
	private int currentTabIndex = 0;

	public static void open() {
		SwingUtilities.invokeLater(() -> {
			if (instance == null) {
				try {
					com.formdev.flatlaf.themes.FlatMacDarkLaf.setup();
					UIManager.put("Button.arc", 16);
					UIManager.put("Component.arc", 16);
					UIManager.put("ProgressBar.arc", 16);
					UIManager.put("TextComponent.arc", 16);
					UIManager.put("Component.accentColor", "#007AFF"); // iOS Blue / ColorOS vibe
					UIManager.put("TitlePane.unifiedBackground", false);
				} catch (Exception ex) {
					// Fallback if FlatLaf isn't available
				}
				instance = new AIDashboardFrame();
			}
			instance.setVisible(true);
			instance.toFront();
		});
	}

	public static void shutdown() {
		SwingUtilities.invokeLater(() -> {
			if (instance != null) {
				instance.dispose();
				instance = null;
			}
		});
	}

	public static void appendSystemLog(String message) {
		String line = "[" + LocalTime.now().format(TIME_FORMAT) + "] " + message + System.lineSeparator();
		SwingUtilities.invokeLater(() -> {
			AIDashboardFrame frame = instance;
			if (frame == null) return;
			JTextArea area = frame.console;
			area.append(line);
			int overflow = area.getDocument().getLength() - MAX_CONSOLE_CHARS;
			if (overflow > 0) area.replaceRange("", 0, overflow);
			area.setCaretPosition(area.getDocument().getLength());
		});
	}

	private AIDashboardFrame() {
		super("am-ai Companion Dashboard - Luna UI");
		setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
		setMinimumSize(new Dimension(860, 560));
		setLayout(new BorderLayout(10, 10));
		((JPanel)getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

		add(buildSidebar(), BorderLayout.WEST);
		
		JPanel contentContainer = new JPanel(new BorderLayout());
		contentContainer.add(buildTopNav(), BorderLayout.NORTH);
		contentContainer.add(buildMainArea(), BorderLayout.CENTER);
		
		add(contentContainer, BorderLayout.CENTER);

		loadSettingsIntoFields();
		pack();
		setLocationByPlatform(true);
		appendLater("Dashboard initialised (Theme Luna). Waiting for game events…");

		// Task Polling Timer (every 1 second)
		new Timer(1000, e -> updateTaskTracker()).start();
	}

	private JPanel buildSidebar() {
		JPanel panel = new JPanel(new BorderLayout(0, 10));
		panel.setPreferredSize(new Dimension(240, 0));

		JLabel title = new JLabel("Task Tracker");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
		panel.add(title, BorderLayout.NORTH);

		JList<String> taskList = new JList<>(taskListModel);
		taskList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		taskList.setCellRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
				Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (index == 0 && value.toString().contains("ACTIVE")) {
					c.setForeground(Color.decode("#32D74B")); // iOS Green
				}
				return c;
			}
		});

		JScrollPane scroll = new JScrollPane(taskList);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		panel.add(scroll, BorderLayout.CENTER);

		// Big control buttons
		JPanel controls = new JPanel(new GridLayout(2, 1, 0, 8));
		
		JButton cancelBtn = new JButton("Skip Task (Cancel)");
		cancelBtn.setForeground(Color.decode("#FF9F0A")); // iOS Orange
		cancelBtn.setFont(cancelBtn.getFont().deriveFont(Font.BOLD));
		cancelBtn.addActionListener(e -> {
			appendLater("Manual override: Cancelling current task...");
			AIActionBridge.execute(new OllamaNetworkClient.AIDecision("", "cancel"), "Dashboard");
		});

		HoldButton stopBtn = new HoldButton("EMERGENCY STOP", 1000, Color.decode("#FF453A"));
		stopBtn.setForeground(Color.WHITE);
		stopBtn.setBackground(Color.decode("#331010")); // Dark red background
		stopBtn.setFont(stopBtn.getFont().deriveFont(Font.BOLD));
		stopBtn.addActionListener(e -> {
			appendLater("Manual override: EMERGENCY STOP trigger!");
			AIActionBridge.execute(new OllamaNetworkClient.AIDecision("", "stop"), "Dashboard");
		});

		controls.add(cancelBtn);
		controls.add(stopBtn);
		panel.add(controls, BorderLayout.SOUTH);

		return panel;
	}

	private JComboBox<String> weaponPriorityBox;

	private JPanel buildTopNav() {
		JPanel nav = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
		nav.setBorder(new EmptyBorder(0, 0, 10, 0));
		
		ButtonGroup group = new ButtonGroup();
		String[] tabs = {"Console", "Settings", "Whitelist"};
		
		for (int i = 0; i < tabs.length; i++) {
			final int index = i;
			String name = tabs[i];
			JToggleButton btn = new JToggleButton(name);
			btn.setFont(btn.getFont().deriveFont(Font.BOLD, 14f));
			btn.setFocusPainted(false);
			if (i == 0) btn.setSelected(true);
			
			btn.addActionListener(e -> {
				if (currentTabIndex == index) return;
				boolean slideLeft = index > currentTabIndex;
				currentTabIndex = index;
				slidingPanel.showPanel(name, slideLeft);
			});
			
			group.add(btn);
			nav.add(btn);
		}
		
		return nav;
	}

	private Component buildMainArea() {
		// Tab 1: Console
		JPanel consoleTab = new JPanel(new BorderLayout(0, 10));
		console.setEditable(false);
		console.setLineWrap(true);
		console.setWrapStyleWord(true);
		console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		console.setBorder(new EmptyBorder(5, 5, 5, 5));
		JScrollPane scroll = new JScrollPane(console, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createTitledBorder("System Console Log"));
		consoleTab.add(scroll, BorderLayout.CENTER);

		JPanel promptPanel = new JPanel(new BorderLayout(8, 0));
		promptPanel.setBorder(BorderFactory.createTitledBorder("Manual Command"));
		JButton sendBtn = new JButton("Send");
		sendBtn.addActionListener(e -> onManualSend());
		promptField.addActionListener(e -> onManualSend());
		promptPanel.add(promptField, BorderLayout.CENTER);
		promptPanel.add(sendBtn, BorderLayout.EAST);
		consoleTab.add(promptPanel, BorderLayout.SOUTH);

		slidingPanel.addPanel("Console", consoleTab);

		// Tab 2: Settings
		JPanel settingsTab = new JPanel(new BorderLayout());
		settingsTab.add(buildSettingsPanel(), BorderLayout.NORTH);
		slidingPanel.addPanel("Settings", settingsTab);

		// Tab 3: Whitelist
		slidingPanel.addPanel("Whitelist", buildWhitelistPanel());

		return slidingPanel;
	}

	private JPanel buildWhitelistPanel() {
		JPanel panel = new JPanel(new BorderLayout(10, 10));
		panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		DefaultListModel<String> wlModel = new DefaultListModel<>();
		for (String name : AIWhitelistManager.getActive()) {
			wlModel.addElement(name);
		}
		JList<String> wlList = new JList<>(wlModel);
		JScrollPane scroll = new JScrollPane(wlList);
		scroll.setBorder(BorderFactory.createTitledBorder("Active Whitelist"));
		panel.add(scroll, BorderLayout.CENTER);

		JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JTextField addField = new JTextField(15);
		JButton addBtn = new JButton("Add");
		JButton removeBtn = new JButton("Remove Selected");

		addBtn.addActionListener(e -> {
			String name = addField.getText().strip();
			if (!name.isEmpty()) {
				if (AIWhitelistManager.add(name)) {
					wlModel.addElement(name);
					addField.setText("");
					appendLater("Dashboard added " + name + " to whitelist.");
				}
			}
		});

		removeBtn.addActionListener(e -> {
			String selected = wlList.getSelectedValue();
			if (selected != null) {
				if (AIWhitelistManager.remove(selected)) {
					wlModel.removeElement(selected);
					appendLater("Dashboard removed " + selected + " from whitelist.");
				}
			}
		});

		controlPanel.add(new JLabel("Player Name:"));
		controlPanel.add(addField);
		controlPanel.add(addBtn);
		controlPanel.add(removeBtn);

		panel.add(controlPanel, BorderLayout.SOUTH);
		return panel;
	}

	private JPanel buildSettingsPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createTitledBorder("Configuration"));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 6, 4, 6);
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.WEST;

		c.gridy = 0; c.gridx = 0;
		panel.add(new JLabel("Endpoint URL:"), c);
		c.gridx = 1; c.weightx = 1.0; c.gridwidth = 3;
		panel.add(endpointField, c);
		
		c.weightx = 0; c.gridwidth = 1;
		c.gridy = 1; c.gridx = 0;
		panel.add(new JLabel("Target Model:"), c);
		c.gridx = 1;
		panel.add(modelField, c);
		c.gridx = 2;
		panel.add(new JLabel("Prefix:"), c);
		c.gridx = 3;
		panel.add(prefixField, c);

		c.gridy = 2; c.gridx = 0;
		panel.add(new JLabel("Weapon Priority:"), c);
		weaponPriorityBox = new JComboBox<>(new String[]{"Swords", "Axes", "Highest Damage"});
		c.gridx = 1; c.gridwidth = 1;
		panel.add(weaponPriorityBox, c);
		c.gridx = 2; c.gridwidth = 2;
		panel.add(combatBlocksCheck, c);

		JButton saveBtn = new JButton("Save");
		saveBtn.addActionListener(e -> onSaveConfig());
		JButton testBtn = new JButton("Test");
		testBtn.addActionListener(e -> onTestConnection(testBtn));
		activeIndicator.addActionListener(e -> onToggleActive());

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
		actions.add(saveBtn);
		actions.add(testBtn);
		actions.add(activeIndicator);
		actions.add(connectionStatus);

		HoldButton resetMemBtn = new HoldButton("Reset Memory (Hold)", 1000, Color.decode("#FF9F0A"));
		resetMemBtn.addActionListener(e -> {
			AIMemoryStore.clearMemories();
			appendLater("Memory banks wiped.");
		});
		
		HoldButton resetAffinityBtn = new HoldButton("Reset Feelings (Hold)", 1000, Color.decode("#FF9F0A"));
		resetAffinityBtn.addActionListener(e -> {
			AIMemoryStore.clearAffinities();
			appendLater("Feelings and affinities reset.");
		});
		
		JPanel resetPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
		resetPanel.add(resetMemBtn);
		resetPanel.add(resetAffinityBtn);

		c.gridy = 3; c.gridx = 0; c.gridwidth = 4;
		panel.add(actions, c);
		
		c.gridy = 4;
		panel.add(resetPanel, c);

		return panel;
	}

	private void updateTaskTracker() {
		if (taskListModel == null) return;
		SwingUtilities.invokeLater(() -> {
			taskListModel.clear();
			if (!AIStateManager.anythingActive()) {
				taskListModel.addElement("IDLE - No tasks running.");
			} else {
				taskListModel.addElement("▶ ACTIVE: " + AIStateManager.getActiveTaskDescription());
				// The queue elements
				List<AIStateManager.TaskContext> tasks = AIStateManager.getQueue();
				if (tasks != null) {
					int i = 1;
					for (AIStateManager.TaskContext ctx : tasks) {
						taskListModel.addElement(i + ". [PAUSED] " + ctx.toString());
						i++;
					}
				}
			}
		});
	}

	private void loadSettingsIntoFields() {
		AIModSettings cfg = SettingsPersistenceManager.get();
		endpointField.setText(cfg.endpointUrl);
		modelField.setText(cfg.modelId);
		prefixField.setText(cfg.commandPrefix);
		weaponPriorityBox.setSelectedItem(cfg.weaponPriority);
		combatBlocksCheck.setSelected(cfg.combatAllowBlocks);
		syncActiveIndicator(cfg.active);
	}

	private void onSaveConfig() {
		AIModSettings cfg = SettingsPersistenceManager.get();
		cfg.endpointUrl = endpointField.getText().strip();
		cfg.modelId = modelField.getText().strip();
		cfg.commandPrefix = prefixField.getText().strip().isEmpty() ? "!ai" : prefixField.getText().strip();
		cfg.weaponPriority = (String) weaponPriorityBox.getSelectedItem();
		cfg.combatAllowBlocks = combatBlocksCheck.isSelected();
		prefixField.setText(cfg.commandPrefix);
		SettingsPersistenceManager.update(cfg);
		appendLater("Configuration saved.");
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
		activeIndicator.setForeground(active ? Color.decode("#32D74B") : Color.decode("#FF453A"));
	}

	private void onTestConnection(JButton button) {
		String url = endpointField.getText().strip();
		button.setEnabled(false);
		connectionStatus.setText("Connection: testing…");
		connectionStatus.setForeground(Color.DARK_GRAY);
		OllamaNetworkClient.testConnection(url).whenComplete((alive, err) -> {
			boolean ok = err == null && Boolean.TRUE.equals(alive);
			SwingUtilities.invokeLater(() -> {
				button.setEnabled(true);
				connectionStatus.setText(ok ? "ONLINE" : "OFFLINE");
				connectionStatus.setForeground(ok ? Color.decode("#32D74B") : Color.decode("#FF453A"));
			});
			appendSystemLog(ok ? "Ollama instance alive." : "Ollama instance unreachable.");
		});
	}

	private static final java.util.Set<String> DIRECT_ACTION_VERBS = java.util.Set.of(
			"goto", "mine", "mine_area", "follow", "follow_protect", "attack", "eat", "drop_items",
			"deposit_chest", "farm", "#farm", "sneak", "unsneak", "click_respawn", "cancel", "stop");

	private void onManualSend() {
		String prompt = promptField.getText().strip();
		if (prompt.isEmpty()) return;
		promptField.setText("");
		String verb = prompt.split("\\s+")[0].toLowerCase();
		if (verb.equals("whitelist")) {
			String[] parts = prompt.split("\\s+");
			if (parts.length >= 3 && parts[1].equalsIgnoreCase("add")) {
				appendLater(AIWhitelistManager.add(parts[2]) ? "Added " + parts[2] : parts[2] + " already whitelisted.");
			} else if (parts.length >= 3 && parts[1].equalsIgnoreCase("remove")) {
				appendLater(AIWhitelistManager.remove(parts[2]) ? "Removed " + parts[2] : parts[2] + " wasn't on whitelist.");
			}
			return;
		}
		if (DIRECT_ACTION_VERBS.contains(verb)) {
			appendLater("Direct action override: " + prompt);
			AIActionBridge.execute(new OllamaNetworkClient.AIDecision("", prompt), "DashboardOperator");
			return;
		}
		appendLater("Manual prompt dispatched: " + prompt);
		OllamaNetworkClient.submitPrompt(OllamaNetworkClient.Source.DASHBOARD, "DashboardOperator", prompt);
	}

	private void appendLater(String message) {
		appendSystemLog(message);
	}
}
