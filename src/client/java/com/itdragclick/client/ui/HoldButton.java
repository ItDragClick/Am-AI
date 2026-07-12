package com.itdragclick.client.ui;

import javax.swing.JButton;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class HoldButton extends JButton {
    
    private final int holdTimeMs;
    private final Color progressColor;
    
    private Timer timer;
    private long startTime;
    private float progress = 0f;
    private boolean completed = false;
    private boolean allowFire = false;

    public HoldButton(String text, int holdTimeMs, Color progressColor) {
        super(text);
        this.holdTimeMs = holdTimeMs;
        this.progressColor = progressColor;
        setContentAreaFilled(false);
        setFocusPainted(false);
        
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!isEnabled()) return;
                completed = false;
                progress = 0f;
                startTime = System.currentTimeMillis();
                if (timer != null) timer.stop();
                timer = new Timer(16, ev -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    progress = Math.min(1f, (float) elapsed / holdTimeMs);
                    repaint();
                    
                    if (progress >= 1f) {
                        timer.stop();
                        if (!completed) {
                            completed = true;
                            allowFire = true;
                            fireActionPerformed(new ActionEvent(HoldButton.this, ActionEvent.ACTION_PERFORMED, getActionCommand()));
                            allowFire = false;
                            // Flash effect
                            progress = 0f;
                            repaint();
                        }
                    }
                });
                timer.start();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                cancel();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                cancel();
            }
        });
    }

    @Override
    protected void fireActionPerformed(ActionEvent event) {
        if (allowFire) {
            super.fireActionPerformed(event);
        }
    }

    private void cancel() {
        if (timer != null) {
            timer.stop();
        }
        if (!completed) {
            progress = 0f;
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        int arc = 16;
        
        // Paint background based on state
        if (getModel().isArmed() || progress > 0) {
            g2.setColor(getBackground().darker());
        } else if (getModel().isRollover()) {
            g2.setColor(getBackground().brighter());
        } else {
            g2.setColor(getBackground());
        }
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);

        // Paint progress fill
        if (progress > 0 && !completed) {
            g2.setColor(progressColor);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
            int fillWidth = (int) (getWidth() * progress);
            g2.fillRoundRect(0, 0, fillWidth, getHeight(), arc, arc);
            // Fix corners if not fully filled by drawing a clip or just filling rect
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
        }

        super.paintComponent(g);
        g2.dispose();
    }
}
