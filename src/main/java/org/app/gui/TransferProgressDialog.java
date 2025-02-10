package org.app.gui;

import org.app.gui.theme.AppTheme;
import javax.swing.*;
import java.awt.*;

public class TransferProgressDialog extends JDialog {
    private JProgressBar progressBar;
    private JLabel statusLabel;
    
    public TransferProgressDialog(JFrame parent, String title) {
        super(parent, title, true);
        initComponents();
    }
    
    private void initComponents() {
        setLayout(new BorderLayout(10, 10));
        setSize(400, 150);
        setLocationRelativeTo(getParent());
        
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        mainPanel.setBackground(AppTheme.SECONDARY_COLOR);
        
        statusLabel = new JLabel("Transferring files...");
        statusLabel.setFont(AppTheme.REGULAR_FONT);
        
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setForeground(AppTheme.PRIMARY_COLOR);
        
        mainPanel.add(statusLabel, BorderLayout.NORTH);
        mainPanel.add(progressBar, BorderLayout.CENTER);
        
        add(mainPanel);
    }
    
    public void updateProgress(int progress) {
        progressBar.setValue(progress);
    }
    
    public void setStatus(String status) {
        statusLabel.setText(status);
    }
} 