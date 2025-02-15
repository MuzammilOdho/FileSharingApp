package org.app;

import org.app.gui.FileSharingApp;
import org.app.gui.theme.ModernTheme;
import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ModernTheme.setup();
            new FileSharingApp();
        });
    }
}