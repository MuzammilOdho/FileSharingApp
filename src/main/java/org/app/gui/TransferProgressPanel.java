package org.app.gui;

import javax.swing.*;
import java.awt.*;

public class TransferProgressPanel extends JPanel {
    public void addCancelButton(JButton cancelButton) {
        // Create a button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setOpaque(false);
        buttonPanel.add(cancelButton);
        
        // Add button panel to the main panel
        add(buttonPanel, "gapbefore push, wrap");
        revalidate();
        repaint();
    }
} 