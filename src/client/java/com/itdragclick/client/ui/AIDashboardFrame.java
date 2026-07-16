package com.itdragclick.client.ui;

import com.itdragclick.client.ai.AIActionBridge;
import com.itdragclick.client.ai.AIStateManager;
import com.itdragclick.client.ai.AIStats;
import com.itdragclick.client.ai.AIWhitelistManager;
import com.itdragclick.client.config.AIModSettings;
import com.itdragclick.client.config.SettingsPersistenceManager;
import com.itdragclick.client.net.OllamaNetworkClient;
import com.itdragclick.client.memory.AIMemoryStore;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class AIDashboardFrame extends JFrame {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_CONSOLE_CHARS = 200_000;

    private static volatile AIDashboardFrame instance;

    // Core Settings
    private final JTextField endpointField = new JTextField(20);
    private final JTextField modelField = new JTextField(12);
    private final JTextField prefixField = new JTextField(6);
    private final JComboBox<String> weaponPriorityBox = new JComboBox<>(new String[]{"Swords", "Axes", "Highest Damage"});

    // Combat & Survival Toggles
    private final CustomToggleSwitch combatBlocksCheck = new CustomToggleSwitch("Combat Blocks (Break/Place)");
    private final CustomToggleSwitch allowMlgWaterCheck = new CustomToggleSwitch("MLG Water");
    private final CustomToggleSwitch allowFollowBlockEditCheck = new CustomToggleSwitch("Follow Block Edit");
    private final CustomToggleSwitch useShieldCheck = new CustomToggleSwitch("Use Shield");
    private final CustomToggleSwitch useBowCrossbowCheck = new CustomToggleSwitch("Use Bow/Crossbow");
    private final CustomToggleSwitch fleeOnLowHealthCheck = new CustomToggleSwitch("Flee on Low HP");
    private final CustomToggleSwitch autoDefendWhileWorkingCheck = new CustomToggleSwitch("Auto-Defend While Working");
    private final CustomToggleSwitch autoSleepAtNightCheck = new CustomToggleSwitch("Auto-Sleep at Night");
    private final CustomToggleSwitch useMaceAttackCheck = new CustomToggleSwitch("Use Mace In Attack");
    private final CustomToggleSwitch useCritAttackCheck = new CustomToggleSwitch("Crit Hits (Jump Attack)");

    // Idle Behaviors Checkboxes
    private final CustomToggleSwitch allowIdleLookAroundCheck = new CustomToggleSwitch("Look Around");
    private final CustomToggleSwitch allowIdleStareCheck = new CustomToggleSwitch("Stare at Entities");
    private final CustomToggleSwitch allowIdleGiftCheck = new CustomToggleSwitch("Give Gifts");
    private final CustomToggleSwitch allowIdleExploreCheck = new CustomToggleSwitch("Explore/Flowers");
    private final CustomToggleSwitch allowIdleBigGoalCheck = new CustomToggleSwitch("Big Goals");
    private final CustomToggleSwitch allowIdleBlockBreakCheck = new CustomToggleSwitch("Idle Block Break");
    private final CustomToggleSwitch allowIdleBlockPlaceCheck = new CustomToggleSwitch("Idle Block Place");

    // Combat Tuning Sliders
    private JSlider spamDelaySlider;
    private JSlider macePunishHitsSlider;
    private JSlider lanceDistanceSlider;
    private JSlider shieldBreakRangeSlider;
    private JSlider maceCooldownSlider;
    private JSlider eatWarmupSlider;
    private JSlider critJumpCooldownSlider;
    private JSlider lowHealthSlider;

    // Status and Actions
    private final JRadioButton activeIndicator = new JRadioButton("Active");
    private final JLabel connectionStatus = new JLabel("Connection: untested");
    private final JTextArea console = new JTextArea();
    private final JTextArea miniConsole = new JTextArea(); // For bottom panel
    private final JTextField promptField = new JTextField();
    
    // UI Structure
    private final SlidingPanel slidingPanel = new SlidingPanel();
    private int currentTabIndex = 1; // Default to Settings
    private AnimatedTaskTracker taskTrackerPanel;

    // Live System Status rows (filled by the refresh timer, not mocked)
    private JLabel statusConnectionValue;
    private JLabel statusModelValue;
    private JLabel statusEndpointValue;
    private JLabel statusUptimeValue;

    // Live Activity Overview cards
    private StatCardPanel cardTasks;
    private StatCardPanel cardUptime;
    private StatCardPanel cardRequests;
    private StatCardPanel cardFights;

    // Presets
    private final DefaultComboBoxModel<String> presetModel = new DefaultComboBoxModel<>();
    private JComboBox<String> presetCombo;

    // Quick toggles mirror the Settings switches; this guard stops the two
    // sides from bouncing setSelected() off each other.
    private boolean syncingToggles = false;

    public static void open() {
        SwingUtilities.invokeLater(() -> {
            if (instance == null) {
                try {
                    com.formdev.flatlaf.themes.FlatMacDarkLaf.setup();
                    UIManager.put("Button.arc", 16);
                    UIManager.put("Component.arc", 16);
                    UIManager.put("ProgressBar.arc", 16);
                    UIManager.put("TextComponent.arc", 16);
                    UIManager.put("Component.accentColor", "#007AFF");
                    UIManager.put("TitlePane.unifiedBackground", false);
                    UIManager.put("Panel.background", new Color(10, 15, 26)); // Luna dark background
                } catch (Exception ex) {
                    // Fallback
                }
                try {
                    instance = new AIDashboardFrame();
                } catch (Throwable t) {
                    System.err.println("[am-ai] CRITICAL ERROR building Dashboard:");
                    t.printStackTrace();
                    throw t;
                }
            }
            if (instance != null) {
                instance.setVisible(true);
                instance.toFront();
            }
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
            
            // Append to both consoles
            frame.console.append(line);
            frame.miniConsole.append(line);
            
            int overflow = frame.console.getDocument().getLength() - MAX_CONSOLE_CHARS;
            if (overflow > 0) frame.console.replaceRange("", 0, overflow);
            frame.console.setCaretPosition(frame.console.getDocument().getLength());
            
            int overflowMini = frame.miniConsole.getDocument().getLength() - MAX_CONSOLE_CHARS;
            if (overflowMini > 0) frame.miniConsole.replaceRange("", 0, overflowMini);
            frame.miniConsole.setCaretPosition(frame.miniConsole.getDocument().getLength());
        });
    }

    private AIDashboardFrame() {
        super("am-ai Companion Dashboard - Luna UI");
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setMinimumSize(new Dimension(1200, 800)); // Larger minimum size for the new layout
        getContentPane().setBackground(new Color(10, 15, 26));
        setLayout(new BorderLayout(15, 15));
        ((JPanel)getContentPane()).setBorder(new EmptyBorder(15, 15, 15, 15));

        // Create main sections
        add(buildTopNav(), BorderLayout.NORTH);
        add(buildLeftSidebar(), BorderLayout.WEST);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildRightSidebar(), BorderLayout.EAST);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        loadSettingsIntoFields();
        pack();
        setLocationByPlatform(true);
        appendLater("Dashboard initialised (Theme Luna). Waiting for game events…");

        new Timer(1000, e -> {
            updateTaskTracker();
            updateLiveStatus();
        }).start();
    }

    private JPanel buildTopNav() {
        JPanel navContainer = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        navContainer.setOpaque(false);
        
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        nav.setBackground(new Color(26, 35, 51));
        nav.setBorder(BorderFactory.createLineBorder(new Color(40, 50, 70), 1));
        // Add rounded corners via custom border or FlatLaf client properties
        nav.putClientProperty("FlatLaf.style", "arc: 20");

        ButtonGroup group = new ButtonGroup();
        String[] tabs = {"Console", "Settings", "Whitelist"};
        
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            String name = tabs[i];
            JToggleButton btn = new JToggleButton(name);
            btn.setFont(btn.getFont().deriveFont(Font.BOLD, 14f));
            btn.setFocusPainted(false);
            btn.setBorder(new EmptyBorder(8, 24, 8, 24));
            
            if (i == 1) btn.setSelected(true); // Default to Settings
            
            btn.addActionListener(e -> {
                if (currentTabIndex == index) return;
                boolean slideLeft = index > currentTabIndex;
                currentTabIndex = index;
                slidingPanel.showPanel(name, slideLeft);
            });
            
            group.add(btn);
            nav.add(btn);
        }
        
        navContainer.add(nav);
        return navContainer;
    }

    private JPanel buildLeftSidebar() {
        JPanel panel = new JPanel(new BorderLayout(0, 15));
        panel.setPreferredSize(new Dimension(280, 0));
        panel.setOpaque(false);

        // Task Tracker Box
        JPanel trackerBox = createStyledBox("TASK TRACKER");
        taskTrackerPanel = new AnimatedTaskTracker();
        trackerBox.add(taskTrackerPanel, BorderLayout.CENTER);
        panel.add(trackerBox, BorderLayout.NORTH);

        // System Status Box
        JPanel statusBox = createStyledBox("SYSTEM STATUS");
        JPanel statusGrid = new JPanel(new GridLayout(4, 2, 5, 10));
        statusGrid.setOpaque(false);
        
        // Live values — refreshed every second by updateLiveStatus().
        statusConnectionValue = addStatusRow(statusGrid, "Connection", "untested", Color.GRAY);
        statusModelValue = addStatusRow(statusGrid, "AI Model", "-", Color.LIGHT_GRAY);
        statusEndpointValue = addStatusRow(statusGrid, "API Endpoint", "-", Color.LIGHT_GRAY);
        statusUptimeValue = addStatusRow(statusGrid, "Uptime", "00:00:00", Color.LIGHT_GRAY);

        statusBox.add(statusGrid, BorderLayout.CENTER);
        panel.add(statusBox, BorderLayout.CENTER);

        // Controls
        JPanel controls = new JPanel(new GridLayout(2, 1, 0, 10));
        controls.setOpaque(false);
        
        JButton cancelBtn = new JButton("Skip Task (Cancel)");
        cancelBtn.setForeground(Color.decode("#FF9F0A")); // iOS Orange
        cancelBtn.setFont(cancelBtn.getFont().deriveFont(Font.BOLD));
        cancelBtn.putClientProperty("JButton.buttonType", "roundRect");
        cancelBtn.addActionListener(e -> {
            appendLater("Manual override: Cancelling current task...");
            AIActionBridge.execute(new OllamaNetworkClient.AIDecision("", "cancel"), "Dashboard");
        });

        HoldButton stopBtn = new HoldButton("EMERGENCY STOP", 1000, Color.decode("#FF453A"));
        stopBtn.setForeground(Color.WHITE);
        stopBtn.setBackground(new Color(51, 16, 16)); // Dark red
        stopBtn.setFont(stopBtn.getFont().deriveFont(Font.BOLD));
        stopBtn.putClientProperty("JButton.buttonType", "roundRect");
        stopBtn.addActionListener(e -> {
            appendLater("Manual override: EMERGENCY STOP trigger!");
            AIActionBridge.execute(new OllamaNetworkClient.AIDecision("", "stop"), "Dashboard");
        });

        controls.add(cancelBtn);
        controls.add(stopBtn);
        panel.add(controls, BorderLayout.SOUTH);

        return panel;
    }

    /** Adds a status row and hands back the value label so it can be updated live. */
    private JLabel addStatusRow(JPanel grid, String label, String value, Color valueColor) {
        JLabel l1 = new JLabel(" " + label);
        l1.setForeground(new Color(150, 150, 160));
        JLabel l2 = new JLabel(value, SwingConstants.RIGHT);
        l2.setForeground(valueColor);
        grid.add(l1);
        grid.add(l2);
        return l2;
    }

    private JPanel buildCenterPanel() {
        // Console Tab
        JPanel consoleTab = new JPanel(new BorderLayout(0, 10));
        consoleTab.setOpaque(false);
        console.setEditable(false);
        console.setLineWrap(true);
        console.setWrapStyleWord(true);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        console.setBackground(new Color(20, 25, 35));
        console.setForeground(Color.LIGHT_GRAY);
        JScrollPane scrollConsole = new JScrollPane(console);
        scrollConsole.setBorder(BorderFactory.createLineBorder(new Color(40, 50, 70)));
        consoleTab.add(scrollConsole, BorderLayout.CENTER);

        // Settings Tab
        JPanel settingsTab = new JPanel(new BorderLayout());
        settingsTab.setOpaque(false);
        settingsTab.add(buildSettingsPanel(), BorderLayout.CENTER);

        // Whitelist Tab
        slidingPanel.addPanel("Console", consoleTab);
        slidingPanel.addPanel("Settings", settingsTab);
        slidingPanel.addPanel("Whitelist", buildWhitelistPanel());
        
        slidingPanel.showPanel("Settings", false); // Default

        JPanel container = createStyledBox("GENERAL SETTINGS");
        container.add(slidingPanel, BorderLayout.CENTER);
        return container;
    }

    private JComponent buildSettingsPanel() {
        JPanel corePanel = new JPanel(new GridBagLayout());
        corePanel.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(8, 10, 8, 10);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;

        // Core & Combat
        c.gridy = 0; c.gridx = 0;
        corePanel.add(createLabel("Endpoint URL:"), c);
        c.gridx = 1; c.weightx = 1.0; c.gridwidth = 3;
        corePanel.add(endpointField, c);
        
        c.weightx = 0; c.gridwidth = 1;
        c.gridy = 1; c.gridx = 0;
        corePanel.add(createLabel("Target Model:"), c);
        c.gridx = 1;
        corePanel.add(modelField, c);
        c.gridx = 2;
        corePanel.add(createLabel("Prefix:"), c);
        c.gridx = 3;
        corePanel.add(prefixField, c);

        c.gridy = 2; c.gridx = 0;
        corePanel.add(createLabel("Weapon Priority:"), c);
        c.gridx = 1; c.gridwidth = 1;
        corePanel.add(weaponPriorityBox, c);

        // Combat & Survival
        JPanel combatChecks = new JPanel(new GridLayout(4, 3, 10, 10));
        combatChecks.setOpaque(false);
        combatChecks.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(40, 50, 70)), 
            "Combat & Survival", TitledBorder.LEFT, TitledBorder.TOP, 
            new Font("SansSerif", Font.BOLD, 12), new Color(150, 150, 160)
        ));
        
        combatChecks.add(combatBlocksCheck);
        combatChecks.add(allowMlgWaterCheck);
        combatChecks.add(allowFollowBlockEditCheck);
        combatChecks.add(useShieldCheck);
        combatChecks.add(useBowCrossbowCheck);
        combatChecks.add(fleeOnLowHealthCheck);
        combatChecks.add(autoDefendWhileWorkingCheck);
        combatChecks.add(autoSleepAtNightCheck);
        combatChecks.add(useMaceAttackCheck);
        combatChecks.add(useCritAttackCheck);

        c.gridy = 3; c.gridx = 0; c.gridwidth = 4;
        corePanel.add(combatChecks, c);

        // Tuning
        JPanel tuningPanel = new JPanel(new GridBagLayout());
        tuningPanel.setOpaque(false);
        tuningPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(40, 50, 70)), 
            "Combat Tuning", TitledBorder.LEFT, TitledBorder.TOP, 
            new Font("SansSerif", Font.BOLD, 12), new Color(150, 150, 160)
        ));
        spamDelaySlider = addTuningSlider(tuningPanel, 0, "Axe/Mace Spam Delay", 1, 10, 4, v -> v + " ticks");
        macePunishHitsSlider = addTuningSlider(tuningPanel, 1, "Max Mace Punish Hits", 1, 10, 5, v -> v + " hits");
        lanceDistanceSlider = addTuningSlider(tuningPanel, 2, "Spear Charge Distance", 3, 16, 8, v -> v + " blocks");
        shieldBreakRangeSlider = addTuningSlider(tuningPanel, 3, "Shield Break Range", 25, 40, 32, v -> String.format("%.1f blocks", v / 10.0));
        maceCooldownSlider = addTuningSlider(tuningPanel, 4, "Mace Combo Cooldown", 20, 200, 60, v -> v + " ticks");
        eatWarmupSlider = addTuningSlider(tuningPanel, 5, "Eat Unshield Warmup", 0, 40, 20, v -> v + " ticks");
        critJumpCooldownSlider = addTuningSlider(tuningPanel, 6, "Crit Jump Cooldown", 6, 40, 12, v -> v + " ticks");
        lowHealthSlider = addTuningSlider(tuningPanel, 7, "Flee HP Threshold", 2, 20, 8, v -> v + " half-hearts");

        c.gridy = 4;
        corePanel.add(tuningPanel, c);

        // Idle Behaviors
        JPanel idlePanel = new JPanel(new GridLayout(3, 3, 10, 10));
        idlePanel.setOpaque(false);
        idlePanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(40, 50, 70)), 
            "Idle Behaviors", TitledBorder.LEFT, TitledBorder.TOP, 
            new Font("SansSerif", Font.BOLD, 12), new Color(150, 150, 160)
        ));
        
        idlePanel.add(allowIdleLookAroundCheck);
        idlePanel.add(allowIdleStareCheck);
        idlePanel.add(allowIdleGiftCheck);
        idlePanel.add(allowIdleExploreCheck);
        idlePanel.add(allowIdleBigGoalCheck);
        idlePanel.add(allowIdleBlockBreakCheck);
        idlePanel.add(allowIdleBlockPlaceCheck);
        
        c.gridy = 5;
        corePanel.add(idlePanel, c);

        // Actions
        JButton saveBtn = new JButton("Save");
        saveBtn.putClientProperty("JButton.buttonType", "roundRect");
        saveBtn.addActionListener(e -> onSaveConfig());
        
        JButton testBtn = new JButton("Test");
        testBtn.putClientProperty("JButton.buttonType", "roundRect");
        testBtn.addActionListener(e -> onTestConnection(testBtn));
        
        activeIndicator.addActionListener(e -> onToggleActive());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        actions.setOpaque(false);
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
        
        actions.add(resetMemBtn);
        actions.add(resetAffinityBtn);

        c.gridy = 6;
        corePanel.add(actions, c);

        JScrollPane scroll = new JScrollPane(corePanel);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JLabel createLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new Color(200, 200, 210));
        return l;
    }

    private JSlider addTuningSlider(JPanel panel, int row, String label,
                                    int min, int max, int initial, java.util.function.IntFunction<String> format) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 10, 4, 10);
        c.anchor = GridBagConstraints.WEST;
        c.gridy = row;

        c.gridx = 0;
        panel.add(createLabel(label + ":"), c);

        JLabel valueLabel = new JLabel(format.apply(initial));
        valueLabel.setForeground(Color.LIGHT_GRAY);
        valueLabel.setPreferredSize(new Dimension(80, 20));
        c.gridx = 1;
        panel.add(valueLabel, c);

        JSlider slider = new JSlider(min, max, initial);
        slider.setOpaque(false);
        slider.addChangeListener(e -> valueLabel.setText(format.apply(slider.getValue())));
        c.gridx = 2; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(slider, c);
        return slider;
    }

    private JPanel buildRightSidebar() {
        JPanel panel = new JPanel(new BorderLayout(0, 15));
        panel.setPreferredSize(new Dimension(280, 0));
        panel.setOpaque(false);

        // Presets: named snapshots of the whole settings file.
        JPanel presetsBox = createStyledBox("PRESETS");
        presetsBox.setPreferredSize(new Dimension(280, 120));
        presetCombo = new JComboBox<>(presetModel);
        presetsBox.add(presetCombo, BorderLayout.CENTER);

        JPanel presetButtons = new JPanel(new GridLayout(1, 3, 8, 0));
        presetButtons.setOpaque(false);
        JButton presetSaveBtn = new JButton("Save");
        presetSaveBtn.addActionListener(e -> openSavePresetDialog());
        JButton presetLoadBtn = new JButton("Load");
        presetLoadBtn.addActionListener(e -> onLoadPreset());
        JButton presetDeleteBtn = new JButton("Delete");
        presetDeleteBtn.setForeground(Color.decode("#FF453A"));
        presetDeleteBtn.addActionListener(e -> onDeletePreset());
        presetButtons.add(presetSaveBtn);
        presetButtons.add(presetLoadBtn);
        presetButtons.add(presetDeleteBtn);
        presetsBox.add(presetButtons, BorderLayout.SOUTH);
        refreshPresetList();
        panel.add(presetsBox, BorderLayout.NORTH);

        // Quick Toggles: same settings as the Settings tab, applied instantly.
        JPanel quickTogglesBox = createStyledBox("QUICK TOGGLES");
        JPanel togglesGrid = new JPanel(new GridLayout(7, 1, 0, 8));
        togglesGrid.setOpaque(false);

        togglesGrid.add(quickToggle("Auto-Defend", autoDefendWhileWorkingCheck));
        togglesGrid.add(quickToggle("Auto-Sleep", autoSleepAtNightCheck));
        togglesGrid.add(quickToggle("MLG Water", allowMlgWaterCheck));
        togglesGrid.add(quickToggle("Use Shield", useShieldCheck));
        togglesGrid.add(quickToggle("Use Bow/Crossbow", useBowCrossbowCheck));
        togglesGrid.add(quickToggle("Flee on Low HP", fleeOnLowHealthCheck));
        togglesGrid.add(quickToggle("Use Mace In Attack", useMaceAttackCheck));

        quickTogglesBox.add(togglesGrid, BorderLayout.CENTER);
        panel.add(quickTogglesBox, BorderLayout.CENTER);

        // About Box
        JPanel aboutBox = createStyledBox("ABOUT");
        JTextArea aboutText = new JTextArea("am-ai Companion Dashboard\nLuna UI\nVersion 1.0.0\n\nYour intelligent companion.\nAlways by your side.");
        aboutText.setEditable(false);
        aboutText.setOpaque(false);
        aboutText.setForeground(Color.GRAY);
        aboutText.setFont(aboutText.getFont().deriveFont(11f));
        aboutBox.add(aboutText, BorderLayout.CENTER);
        panel.add(aboutBox, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(15, 0));
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(0, 150));

        // Mini Console
        JPanel consoleBox = createStyledBox("SYSTEM CONSOLE");
        miniConsole.setEditable(false);
        miniConsole.setLineWrap(true);
        miniConsole.setWrapStyleWord(true);
        miniConsole.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        miniConsole.setBackground(new Color(15, 20, 30));
        miniConsole.setForeground(new Color(180, 190, 200));
        JScrollPane scroll = new JScrollPane(miniConsole);
        scroll.setBorder(null);
        consoleBox.add(scroll, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout(10, 0));
        inputPanel.setOpaque(false);
        inputPanel.setBorder(new EmptyBorder(10, 0, 0, 0));
        promptField.putClientProperty("JTextField.placeholderText", "Type command...");
        JButton sendBtn = new JButton("Send");
        sendBtn.addActionListener(e -> onManualSend());
        promptField.addActionListener(e -> onManualSend());
        inputPanel.add(promptField, BorderLayout.CENTER);
        inputPanel.add(sendBtn, BorderLayout.EAST);
        consoleBox.add(inputPanel, BorderLayout.SOUTH);

        panel.add(consoleBox, BorderLayout.CENTER);

        // Activity Overview
        JPanel statsBox = createStyledBox("ACTIVITY OVERVIEW");
        JPanel statsGrid = new JPanel(new GridLayout(1, 4, 10, 0));
        statsGrid.setOpaque(false);
        
        // Real session counters (AIStats), refreshed on the 1s timer.
        cardTasks = new StatCardPanel("Tasks Completed", "0", "this session", Color.decode("#32D74B"));
        cardUptime = new StatCardPanel("Play Time", "0m", "since launch", Color.decode("#0A84FF"));
        cardRequests = new StatCardPanel("LLM Requests", "0", "prompts sent", Color.decode("#FF9F0A"));
        cardFights = new StatCardPanel("Fights", "0", "engagements", Color.decode("#BF5AF2"));

        statsGrid.add(cardTasks);
        statsGrid.add(cardUptime);
        statsGrid.add(cardRequests);
        statsGrid.add(cardFights);

        statsBox.add(statsGrid, BorderLayout.CENTER);
        panel.add(statsBox, BorderLayout.EAST);

        return panel;
    }

    /**
     * A sidebar switch bound to one of the Settings-tab switches: flipping
     * either one updates the other and saves immediately (no Save click).
     */
    private CustomToggleSwitch quickToggle(String label, CustomToggleSwitch settingsTwin) {
        CustomToggleSwitch quick = new CustomToggleSwitch(label);
        quick.setSelected(settingsTwin.isSelected());
        quick.addActionListener(e -> {
            if (syncingToggles) return;
            syncingToggles = true;
            settingsTwin.setSelected(quick.isSelected());
            syncingToggles = false;
            onSaveConfig();
        });
        settingsTwin.addActionListener(e -> {
            if (syncingToggles) return;
            syncingToggles = true;
            quick.setSelected(settingsTwin.isSelected());
            syncingToggles = false;
        });
        return quick;
    }

    // ------------------------------------------------------------- presets

    private void refreshPresetList() {
        String selected = (String) presetModel.getSelectedItem();
        presetModel.removeAllElements();
        List<String> names = SettingsPersistenceManager.listPresets();
        if (names.isEmpty()) {
            presetModel.addElement("No presets saved");
        } else {
            for (String name : names) {
                presetModel.addElement(name);
            }
            if (selected != null && names.contains(selected)) {
                presetModel.setSelectedItem(selected);
            }
        }
    }

    /** Current combo selection, or null when the list is just the placeholder. */
    private String selectedPreset() {
        String name = (String) presetModel.getSelectedItem();
        if (name == null || name.equals("No presets saved")) {
            return null;
        }
        return name;
    }

    /** Small modal: name field, Save, and an X/Cancel that closes without saving. */
    private void openSavePresetDialog() {
        JDialog dialog = new JDialog(this, "Save Preset", true);
        dialog.setLayout(new BorderLayout(10, 10));
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(new EmptyBorder(15, 15, 15, 15));

        JTextField nameField = new JTextField(18);
        nameField.putClientProperty("JTextField.placeholderText", "Preset name");
        content.add(createLabel("Save the current settings as:"), BorderLayout.NORTH);
        content.add(nameField, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        JButton saveBtn = new JButton("Save");
        JButton cancelBtn = new JButton("Cancel");
        buttons.add(cancelBtn);
        buttons.add(saveBtn);
        content.add(buttons, BorderLayout.SOUTH);

        Runnable doSave = () -> {
            String name = nameField.getText().strip();
            if (name.isEmpty()) {
                appendLater("Preset needs a name.");
                return;
            }
            // Persist what's on screen first, so the preset matches the UI.
            onSaveConfig();
            if (SettingsPersistenceManager.savePreset(name)) {
                refreshPresetList();
                presetModel.setSelectedItem(name);
                appendLater("Preset '" + name + "' saved.");
                dialog.dispose();
            } else {
                appendLater("Failed to save preset '" + name + "'.");
            }
        };
        saveBtn.addActionListener(e -> doSave.run());
        nameField.addActionListener(e -> doSave.run());
        cancelBtn.addActionListener(e -> dialog.dispose()); // X on the window does the same

        dialog.add(content, BorderLayout.CENTER);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void onLoadPreset() {
        String name = selectedPreset();
        if (name == null) {
            appendLater("No preset selected.");
            return;
        }
        if (SettingsPersistenceManager.loadPreset(name)) {
            loadSettingsIntoFields();
            appendLater("Preset '" + name + "' loaded and applied.");
        } else {
            appendLater("Failed to load preset '" + name + "'.");
        }
    }

    private void onDeletePreset() {
        String name = selectedPreset();
        if (name == null) {
            appendLater("No preset selected.");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete preset '" + name + "'?", "Delete Preset", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        if (SettingsPersistenceManager.deletePreset(name)) {
            refreshPresetList();
            appendLater("Preset '" + name + "' deleted.");
        } else {
            appendLater("Failed to delete preset '" + name + "'.");
        }
    }

    // -------------------------------------------------- live status + stats

    /** Runs on the 1s timer: real uptime, endpoint/model, counters. */
    private void updateLiveStatus() {
        AIModSettings cfg = SettingsPersistenceManager.get();
        if (statusUptimeValue != null) {
            statusUptimeValue.setText(AIStats.uptimeText());
            statusModelValue.setText(cfg.modelId);
            statusEndpointValue.setText(shortEndpoint(cfg.endpointUrl));
            statusConnectionValue.setText(cfg.active ? "Active" : "Inactive");
            statusConnectionValue.setForeground(cfg.active ? Color.decode("#32D74B") : Color.decode("#FF453A"));
        }
        if (cardTasks != null) {
            cardTasks.update(String.valueOf(AIStats.tasksCompleted()), "this session");
            cardUptime.update(AIStats.uptimeShort(), "since launch");
            cardRequests.update(String.valueOf(AIStats.llmRequests()), "prompts sent");
            cardFights.update(String.valueOf(AIStats.fights()), "engagements");
        }
    }

    /** "http://localhost:11434/api/generate" -> "localhost:11434". */
    private static String shortEndpoint(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            return host == null ? url : (uri.getPort() > 0 ? host + ":" + uri.getPort() : host);
        } catch (Exception e) {
            return url;
        }
    }

    private JPanel createStyledBox(String title) {
        JPanel box = new JPanel(new BorderLayout(0, 10));
        box.setBackground(new Color(20, 25, 35));
        box.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(40, 50, 70), 1, true),
            new EmptyBorder(12, 15, 12, 15)
        ));
        
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 11f));
        titleLabel.setForeground(new Color(120, 130, 150));
        box.add(titleLabel, BorderLayout.NORTH);
        
        return box;
    }

    private JPanel buildWhitelistPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setOpaque(false);

        DefaultListModel<String> wlModel = new DefaultListModel<>();
        for (String name : AIWhitelistManager.getActive()) {
            wlModel.addElement(name);
        }
        JList<String> wlList = new JList<>(wlModel);
        wlList.setBackground(new Color(15, 20, 30));
        wlList.setForeground(Color.WHITE);
        JScrollPane scroll = new JScrollPane(wlList);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(40, 50, 70)));
        panel.add(scroll, BorderLayout.CENTER);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.setOpaque(false);
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

        controlPanel.add(createLabel("Player Name:"));
        controlPanel.add(addField);
        controlPanel.add(addBtn);
        controlPanel.add(removeBtn);

        panel.add(controlPanel, BorderLayout.SOUTH);
        return panel;
    }

    private void updateTaskTracker() {
        if (taskTrackerPanel == null) return;
        SwingUtilities.invokeLater(() -> {
            if (!AIStateManager.anythingActive()) {
                taskTrackerPanel.setActive(false, "IDLE", "No tasks running.");
            } else {
                String activeDesc = AIStateManager.getActiveTaskDescription();
                taskTrackerPanel.setActive(true, "ACTIVE", activeDesc);
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
        
        allowMlgWaterCheck.setSelected(cfg.allowMlgWater);
        allowFollowBlockEditCheck.setSelected(cfg.allowFollowBlockEdit);
        useShieldCheck.setSelected(cfg.useShieldWhileFighting);
        useBowCrossbowCheck.setSelected(cfg.useBowCrossbow);
        fleeOnLowHealthCheck.setSelected(cfg.fleeOnLowHealth);
        autoDefendWhileWorkingCheck.setSelected(cfg.autoDefendWhileWorking);
        autoSleepAtNightCheck.setSelected(cfg.autoSleepAtNight);
        useMaceAttackCheck.setSelected(cfg.useMaceAttack);
        useCritAttackCheck.setSelected(cfg.useCritAttack);
        
        allowIdleBlockBreakCheck.setSelected(cfg.allowIdleBlockBreak);
        allowIdleBlockPlaceCheck.setSelected(cfg.allowIdleBlockPlace);
        allowIdleLookAroundCheck.setSelected(cfg.allowIdleLookAround);
        allowIdleStareCheck.setSelected(cfg.allowIdleStare);
        allowIdleGiftCheck.setSelected(cfg.allowIdleGift);
        allowIdleExploreCheck.setSelected(cfg.allowIdleExplore);
        allowIdleBigGoalCheck.setSelected(cfg.allowIdleBigGoal);
        
        if (spamDelaySlider != null) {
            spamDelaySlider.setValue(cfg.spamSwingDelayTicks);
            macePunishHitsSlider.setValue(cfg.macePunishMaxHits);
            lanceDistanceSlider.setValue(cfg.lanceChargeDistance);
            shieldBreakRangeSlider.setValue(cfg.shieldBreakRangeTenths);
            maceCooldownSlider.setValue(cfg.maceComboCooldownTicks);
            eatWarmupSlider.setValue(cfg.eatWarmupTicks);
            critJumpCooldownSlider.setValue(cfg.critJumpCooldownTicks);
            lowHealthSlider.setValue(cfg.lowHealthThreshold);
        }
        syncActiveIndicator(cfg.active);
    }

    private void onSaveConfig() {
        AIModSettings cfg = SettingsPersistenceManager.get();
        cfg.endpointUrl = endpointField.getText().strip();
        cfg.modelId = modelField.getText().strip();
        cfg.commandPrefix = prefixField.getText().strip().isEmpty() ? "!ai" : prefixField.getText().strip();
        cfg.weaponPriority = (String) weaponPriorityBox.getSelectedItem();
        cfg.combatAllowBlocks = combatBlocksCheck.isSelected();
        
        cfg.allowMlgWater = allowMlgWaterCheck.isSelected();
        cfg.allowFollowBlockEdit = allowFollowBlockEditCheck.isSelected();
        cfg.useShieldWhileFighting = useShieldCheck.isSelected();
        cfg.useBowCrossbow = useBowCrossbowCheck.isSelected();
        cfg.fleeOnLowHealth = fleeOnLowHealthCheck.isSelected();
        cfg.autoDefendWhileWorking = autoDefendWhileWorkingCheck.isSelected();
        cfg.autoSleepAtNight = autoSleepAtNightCheck.isSelected();
        cfg.useMaceAttack = useMaceAttackCheck.isSelected();
        cfg.useCritAttack = useCritAttackCheck.isSelected();
        
        cfg.allowIdleBlockBreak = allowIdleBlockBreakCheck.isSelected();
        cfg.allowIdleBlockPlace = allowIdleBlockPlaceCheck.isSelected();
        cfg.allowIdleLookAround = allowIdleLookAroundCheck.isSelected();
        cfg.allowIdleStare = allowIdleStareCheck.isSelected();
        cfg.allowIdleGift = allowIdleGiftCheck.isSelected();
        cfg.allowIdleExplore = allowIdleExploreCheck.isSelected();
        cfg.allowIdleBigGoal = allowIdleBigGoalCheck.isSelected();
        
        if (spamDelaySlider != null) {
            cfg.spamSwingDelayTicks = spamDelaySlider.getValue();
            cfg.macePunishMaxHits = macePunishHitsSlider.getValue();
            cfg.lanceChargeDistance = lanceDistanceSlider.getValue();
            cfg.shieldBreakRangeTenths = shieldBreakRangeSlider.getValue();
            cfg.maceComboCooldownTicks = maceCooldownSlider.getValue();
            cfg.eatWarmupTicks = eatWarmupSlider.getValue();
            cfg.critJumpCooldownTicks = critJumpCooldownSlider.getValue();
            cfg.lowHealthThreshold = lowHealthSlider.getValue();
        }
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
        connectionStatus.setForeground(Color.GRAY);
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
