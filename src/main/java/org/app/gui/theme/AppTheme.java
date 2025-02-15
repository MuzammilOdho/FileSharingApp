package org.app.gui.theme;

import javax.swing.*;
import java.awt.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.CompoundBorder;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.UIManager;

public class AppTheme {
    // Colors that work well with both light and dark themes
    public static final Color PRIMARY_COLOR = new Color(0, 122, 255);
    public static final Color SECONDARY_COLOR = UIManager.getColor("Panel.background");
    public static final Color PANEL_COLOR = UIManager.getColor("Panel.background").brighter();
    public static final Color BUTTON_COLOR = UIManager.getColor("Button.background");
    public static final Color BUTTON_HOVER_COLOR = PRIMARY_COLOR.brighter();
    
    // Use system fonts for better platform integration
    public static final Font HEADER_FONT = UIManager.getFont("Label.font").deriveFont(Font.BOLD);
    public static final Font REGULAR_FONT = UIManager.getFont("Label.font");
    
    // Custom button style
    public static JButton createStyledButton(String text) {
        JButton button = new JButton(text);
        button.setFont(REGULAR_FONT);
        button.setForeground(PRIMARY_COLOR);
        button.setBackground(BUTTON_COLOR);
        button.setBorder(new CompoundBorder(
            new LineBorder(PRIMARY_COLOR.darker(), 1),
            new EmptyBorder(8, 15, 8, 15)
        ));
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(BUTTON_HOVER_COLOR);
            }
            
            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(BUTTON_COLOR);
            }
        });
        
        return button;
    }

    // Prevent instantiation
    private AppTheme() {}
} 