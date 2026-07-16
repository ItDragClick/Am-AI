package com.itdragclick.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

public class StatCardPanel extends JPanel {
    private final String title;
    private String value;
    private String subText;
    private final Color accentColor;

    public StatCardPanel(String title, String value, String subText, Color accentColor) {
        this.title = title;
        this.value = value;
        this.subText = subText;
        this.accentColor = accentColor;
        setOpaque(false);
        setPreferredSize(new Dimension(150, 100));
    }

    /** Live update from the dashboard's refresh timer (EDT only). */
    public void update(String value, String subText) {
        this.value = value;
        this.subText = subText;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        // Background
        g2.setColor(new Color(20, 25, 35));
        g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 16, 16));

        // Border (subtle outline matching accent)
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 50));
        g2.draw(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, 16, 16));

        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Title
        g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
        g2.setColor(new Color(150, 150, 160));
        FontMetrics fmTitle = g2.getFontMetrics();
        g2.drawString(title, (w - fmTitle.stringWidth(title)) / 2, 25);

        // Value
        g2.setFont(getFont().deriveFont(Font.BOLD, 28f));
        g2.setColor(Color.WHITE);
        FontMetrics fmValue = g2.getFontMetrics();
        g2.drawString(value, (w - fmValue.stringWidth(value)) / 2, 60);

        // SubText (with up arrow if positive)
        g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g2.setColor(accentColor);
        FontMetrics fmSub = g2.getFontMetrics();
        g2.drawString(subText, (w - fmSub.stringWidth(subText)) / 2, 85);

        g2.dispose();
    }
}
