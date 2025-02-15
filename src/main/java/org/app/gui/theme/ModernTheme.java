package org.app.gui.theme;

import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMaterialDeepOceanIJTheme;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class ModernTheme {
    // Modern color scheme
    public static final Color ACCENT_COLOR = new Color(82, 148, 226);
    public static final Color ACCENT_HOVER_COLOR = new Color(100, 160, 230);
    public static final Color BORDER_COLOR = new Color(60, 60, 60);
    
    public static void setup() {
        try {
            // Set modern theme
            FlatMaterialDeepOceanIJTheme.setup();
            
            // Configure global UI properties
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 10);
            UIManager.put("ProgressBar.arc", 10);
            UIManager.put("TextComponent.arc", 10);
            
            UIManager.put("Button.foreground", ACCENT_COLOR);
            UIManager.put("Button.hoverBackground", ACCENT_HOVER_COLOR);
            UIManager.put("Button.default.boldText", true);
            
            // Set default font
            Font defaultFont = new Font("Segoe UI", Font.PLAIN, 13);
            UIManager.put("defaultFont", defaultFont);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static JButton createAccentButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(ACCENT_COLOR);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        return button;
    }
    
    public static JPanel createRoundedPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1, true));
        panel.setOpaque(false);
        return panel;
    }
    
    public static JButton createIconButton(String text) {
        JButton button = new JButton(text);
        button.setFont(button.getFont().deriveFont(14f));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setFocusPainted(false);
        
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setForeground(ACCENT_HOVER_COLOR);
            }
            
            @Override
            public void mouseExited(MouseEvent e) {
                button.setForeground(UIManager.getColor("Button.foreground"));
            }
        });
        
        return button;
    }
} 