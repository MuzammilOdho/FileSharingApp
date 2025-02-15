package org.app.gui;

import org.app.gui.theme.AppTheme;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class TransferProgressDialog extends JDialog {
    private final JProgressBar progressBar;
    private final JLabel statusLabel;
    private boolean isCloseable = false;
    private final JButton minimizeButton;
    
    public TransferProgressDialog(JFrame parent, String title) {
        super(parent, title, false); // Changed to non-modal
        
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        mainPanel.setBackground(AppTheme.SECONDARY_COLOR);
        
        // Header panel with minimize button
        JPanel headerPanel = new JPanel(new BorderLayout(5, 5));
        headerPanel.setBackground(AppTheme.SECONDARY_COLOR);
        
        statusLabel = new JLabel("Transferring...");
        statusLabel.setFont(AppTheme.REGULAR_FONT);
        
        minimizeButton = new JButton("▼");
        minimizeButton.setFont(AppTheme.REGULAR_FONT);
        minimizeButton.setBorderPainted(false);
        minimizeButton.setContentAreaFilled(false);
        minimizeButton.addActionListener(e -> {
            setVisible(false);
            // Transfer will continue in the dashboard
        });
        
        headerPanel.add(statusLabel, BorderLayout.CENTER);
        headerPanel.add(minimizeButton, BorderLayout.EAST);
        
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        
        mainPanel.add(headerPanel, BorderLayout.NORTH);
        mainPanel.add(progressBar, BorderLayout.CENTER);
        
        add(mainPanel);
        pack();
        setLocationRelativeTo(parent);
    }
    
    public void updateProgress(int progress) {
        progressBar.setValue(progress);
    }
    
    public void setStatus(String status) {
        statusLabel.setText(status);
    }
    
    public void setCloseable(boolean closeable) {
        this.isCloseable = closeable;
    }
} 