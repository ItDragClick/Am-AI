package com.itdragclick.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

public class CustomToggleSwitch extends JCheckBox {
    private float thumbPosition;
    private final Timer animator;
    private boolean isAnimating = false;
    private float targetPosition;
    
    private Color onColor = Color.decode("#007AFF");
    private Color offColor = Color.decode("#39393D");
    private Color thumbColor = Color.WHITE;

    public CustomToggleSwitch(String text) {
        super(text);
        setOpaque(false);
        setCursor(new Cursor(Cursor.HAND_CURSOR));
        setFocusPainted(false);
        
        thumbPosition = isSelected() ? 1f : 0f;
        targetPosition = thumbPosition;

        animator = new Timer(15, e -> {
            if (thumbPosition < targetPosition) {
                thumbPosition += 0.15f;
                if (thumbPosition >= targetPosition) {
                    thumbPosition = targetPosition;
                    ((Timer) e.getSource()).stop();
                    isAnimating = false;
                }
            } else if (thumbPosition > targetPosition) {
                thumbPosition -= 0.15f;
                if (thumbPosition <= targetPosition) {
                    thumbPosition = targetPosition;
                    ((Timer) e.getSource()).stop();
                    isAnimating = false;
                }
            }
            repaint();
        });

        addActionListener(e -> {
            targetPosition = isSelected() ? 1f : 0f;
            if (!isAnimating) {
                isAnimating = true;
                animator.start();
            }
        });
    }

    @Override
    public void setSelected(boolean b) {
        super.setSelected(b);
        thumbPosition = b ? 1f : 0f;
        targetPosition = thumbPosition;
        repaint();
    }

    @Override
    public void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int width = 36;
        int height = 20;
        int x = getWidth() - width - 5;
        int y = (getHeight() - height) / 2;

        // Background color interpolation
        int r = (int) (offColor.getRed() + (onColor.getRed() - offColor.getRed()) * thumbPosition);
        int gC = (int) (offColor.getGreen() + (onColor.getGreen() - offColor.getGreen()) * thumbPosition);
        int b = (int) (offColor.getBlue() + (onColor.getBlue() - offColor.getBlue()) * thumbPosition);
        Color bgColor = new Color(r, gC, b);

        g2.setColor(bgColor);
        g2.fill(new RoundRectangle2D.Float(x, y, width, height, height, height));

        // Thumb
        int thumbSize = 16;
        int thumbY = y + 2;
        int thumbXStart = x + 2;
        int thumbXEnd = x + width - thumbSize - 2;
        int currentThumbX = (int) (thumbXStart + (thumbXEnd - thumbXStart) * thumbPosition);

        g2.setColor(thumbColor);
        g2.fillOval(currentThumbX, thumbY, thumbSize, thumbSize);

        g2.dispose();

        // Draw text
        Graphics2D g2Text = (Graphics2D) g.create();
        g2Text.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2Text.setFont(getFont());
        g2Text.setColor(getForeground());
        FontMetrics fm = g2Text.getFontMetrics();
        int textY = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
        g2Text.drawString(getText(), 5, textY);
        g2Text.dispose();
    }
    
    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        FontMetrics fm = getFontMetrics(getFont());
        int textWidth = fm.stringWidth(getText());
        d.width = textWidth + 36 + 15;
        d.height = Math.max(d.height, 24);
        return d;
    }
}
