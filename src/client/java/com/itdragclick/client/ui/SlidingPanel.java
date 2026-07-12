package com.itdragclick.client.ui;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

public class SlidingPanel extends JPanel {

    private final Map<String, Component> panels = new HashMap<>();
    private String currentKey = null;

    private BufferedImage imageOld;
    private BufferedImage imageNew;
    private float slideProgress = 0f;
    private boolean isAnimating = false;
    private boolean slideLeft = true;
    private Timer timer;

    public SlidingPanel() {
        setLayout(new BorderLayout());
    }

    public void addPanel(String key, Component comp) {
        panels.put(key, comp);
        if (currentKey == null) {
            currentKey = key;
            add(comp, BorderLayout.CENTER);
            revalidate();
            repaint();
        }
    }

    public void showPanel(String key, boolean slideToLeft) {
        if (isAnimating || key.equals(currentKey)) return;
        
        Component oldComp = panels.get(currentKey);
        Component newComp = panels.get(key);
        
        if (oldComp == null || newComp == null) return;
        
        this.slideLeft = slideToLeft;
        this.currentKey = key;

        // Ensure new component is sized correctly to capture it
        newComp.setSize(getSize());
        newComp.doLayout();

        // Capture images
        imageOld = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
        oldComp.paint(imageOld.getGraphics());

        imageNew = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
        newComp.paint(imageNew.getGraphics());

        // Remove actual components during animation
        removeAll();
        isAnimating = true;
        slideProgress = 0f;

        if (timer != null) timer.stop();
        
        long startTime = System.currentTimeMillis();
        int duration = 250; // 250ms animation

        timer = new Timer(16, e -> {
            long elapsed = System.currentTimeMillis() - startTime;
            slideProgress = Math.min(1f, (float) elapsed / duration);
            
            // Ease out cubic
            float t = slideProgress - 1;
            float eased = t * t * t + 1;
            slideProgress = eased;

            repaint();

            if (elapsed >= duration) {
                timer.stop();
                isAnimating = false;
                add(newComp, BorderLayout.CENTER);
                revalidate();
                repaint();
            }
        });
        timer.start();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        if (isAnimating && imageOld != null && imageNew != null) {
            Graphics2D g2 = (Graphics2D) g;
            int w = getWidth();
            int h = getHeight();
            
            int offset = (int) (w * slideProgress);
            
            if (slideLeft) {
                g2.drawImage(imageOld, -offset, 0, null);
                g2.drawImage(imageNew, w - offset, 0, null);
            } else {
                g2.drawImage(imageOld, offset, 0, null);
                g2.drawImage(imageNew, -w + offset, 0, null);
            }
        }
    }
}
