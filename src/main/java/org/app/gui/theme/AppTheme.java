package org.app.gui.theme;

import javax.swing.*;
import java.awt.*;

public class AppTheme {
    // Colors
    public static final Color PRIMARY_COLOR = new Color(70, 130, 180);
    public static final Color SECONDARY_COLOR = new Color(240, 248, 255);
    public static final Color ACCENT_COLOR = new Color(30, 144, 255);
    
    // Fonts
    public static final Font HEADER_FONT = new Font("Arial", Font.BOLD, 20);
    public static final Font REGULAR_FONT = new Font("Arial", Font.PLAIN, 14);
    
    // Custom button style
    public static JButton createStyledButton(String text) {
        JButton button = new JButton(text);
        button.setFont(REGULAR_FONT);
        button.setForeground(Color.WHITE);
        button.setBackground(PRIMARY_COLOR);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setOpaque(true);
        
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                button.setBackground(ACCENT_COLOR);
            }
            public void mouseExited(java.awt.event.MouseEvent evt) {
                button.setBackground(PRIMARY_COLOR);
            }
        });
        
        return button;
    }
} 