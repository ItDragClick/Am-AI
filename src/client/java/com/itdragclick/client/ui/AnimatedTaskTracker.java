package com.itdragclick.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

public class AnimatedTaskTracker extends JPanel {
    private boolean isActive = false;
    private float glowAlpha = 0f;
    private final Timer glowTimer;
    private boolean glowingUp = true;
    private String statusText = "IDLE";
    private String subText = "No tasks running.";

    public AnimatedTaskTracker() {
        setOpaque(false);
        setPreferredSize(new Dimension(200, 200));

        glowTimer = new Timer(50, e -> {
            if (isActive) {
                if (glowingUp) {
                    glowAlpha += 0.05f;
                    if (glowAlpha >= 0.8f) glowingUp = false;
                } else {
                    glowAlpha -= 0.05f;
                    if (glowAlpha <= 0.2f) glowingUp = true;
                }
            } else {
                if (glowAlpha > 0) {
                    glowAlpha -= 0.1f;
                    if (glowAlpha < 0) glowAlpha = 0;
                }
            }
            repaint();
        });
        glowTimer.start();
    }

    public void setActive(boolean active, String mainText, String description) {
        this.isActive = active;
        this.statusText = mainText;
        this.subText = description;
        if (!active) {
            glowAlpha = 0f;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2 - 20;
        int radius = 40;

        // Draw Glow
        if (glowAlpha > 0) {
            int glowRadius = radius + 20 + (int)(glowAlpha * 10);
            RadialGradientPaint rgp = new RadialGradientPaint(
                new Point(centerX, centerY),
                glowRadius,
                new float[]{0f, 1f},
                new Color[]{new Color(0, 122, 255, (int)(glowAlpha * 100)), new Color(0, 122, 255, 0)}
            );
            g2.setPaint(rgp);
            g2.fillOval(centerX - glowRadius, centerY - glowRadius, glowRadius * 2, glowRadius * 2);
        }

        // Draw Bot Icon (stylized)
        g2.setColor(new Color(20, 30, 45));
        g2.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        
        g2.setColor(new Color(0, 122, 255));
        g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);

        // Eyes
        g2.setColor(Color.WHITE);
        g2.fillOval(centerX - 12, centerY - 5, 6, 6);
        g2.fillOval(centerX + 6, centerY - 5, 6, 6);
        // Smile
        g2.drawArc(centerX - 10, centerY, 20, 10, 180, 180);
        // Antenna
        g2.drawLine(centerX, centerY - radius + 5, centerX, centerY - radius - 10);
        g2.fillOval(centerX - 3, centerY - radius - 15, 6, 6);

        // Draw Text
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        g2.setFont(getFont().deriveFont(Font.BOLD, 16f));
        g2.setColor(isActive ? new Color(0, 122, 255) : new Color(150, 150, 160));
        FontMetrics fm1 = g2.getFontMetrics();
        g2.drawString(statusText, centerX - fm1.stringWidth(statusText) / 2, centerY + radius + 30);

        g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
        g2.setColor(new Color(120, 120, 130));
        FontMetrics fm2 = g2.getFontMetrics();
        g2.drawString(subText, centerX - fm2.stringWidth(subText) / 2, centerY + radius + 50);

        g2.dispose();
    }
}
