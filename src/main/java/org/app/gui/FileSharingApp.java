package org.app.gui;

import org.app.User;
import org.app.backend.FileTransferManager;
import org.app.gui.theme.AppTheme;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.event.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import javax.swing.border.MatteBorder;
import javax.swing.ImageIcon;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.util.*;
import java.awt.Dimension;
import java.awt.Toolkit;
import net.miginfocom.swing.MigLayout;
import org.app.gui.theme.ModernTheme;

public class FileSharingApp {
    private JFrame frame;
    private JTextField nameField;
    private JTextField saveDirectoryField;
    private TransferProgressDialog progressDialog;
    private  User  user;
    private File[] files;
    private List<User> availableReceivers;
    private final FileTransferManager transferManager;
    private JLabel connectionStatusLabel;
    private Timer statusTimer;
    private JPanel transfersPanel;
    private Map<String, TransferProgressPanel> activeTransfers;
    private boolean isTransfersPanelExpanded = true;
    private static final int SEARCH_TIMEOUT_MS = 30000; // 30 seconds timeout
    private Timer timeoutTimer;
    private int[] timeLeft;
    private final double scaleFactor;

    // Remove icon constants
//    private static final ImageIcon FOLDER_ICON = new ImageIcon(FileSharingApp.class.getResource("/icons/folder.png"));
//    private static final ImageIcon SEND_ICON = new ImageIcon(FileSharingApp.class.getResource("/icons/send.png"));
//    private static final ImageIcon RECEIVE_ICON = new ImageIcon(FileSharingApp.class.getResource("/icons/receive.png"));
//    private static final ImageIcon HELP_ICON = new ImageIcon(FileSharingApp.class.getResource("/icons/help.png"));

    public FileSharingApp() {
        user = new User();
        // Initialize scale factor based on screen resolution
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        scaleFactor = Math.min(screenSize.getWidth() / 1920.0, screenSize.getHeight() / 1080.0);
        
        // Set modern look and feel
        setupLookAndFeel();
        
        transferManager = new FileTransferManager();
        frame = new JFrame("File Sharing");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        // Use MigLayout for better component positioning
        frame.setLayout(new MigLayout("insets 15, fillx, gap 10", "[grow]", "[]10[]10[]10[]"));
        frame.setSize(900, 600);
        frame.setMinimumSize(new Dimension(800, 500));
        
        activeTransfers = new ConcurrentHashMap<>();
        
        initializeComponents();
        setupGlobalDropTarget();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Add window listener for cleanup
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                transferManager.shutdown();
            }
        });
    }

    private void setupLookAndFeel() {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                FlatMacDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
            // Adjust UI scaling for high DPI displays
            System.setProperty("sun.java2d.uiScale", String.valueOf(scaleFactor));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int scale(int value) {
        return (int) (value * scaleFactor);
    }

    private float scale(float value) {
        return value * (float)scaleFactor;
    }

    private void initializeComponents() {
        // Use MigLayout with better spacing
        frame.setLayout(new MigLayout("insets 15, fillx, gap 10", "[grow]", "[]10[]10[]10[]"));
        
        // Header with status
        JPanel headerPanel = ModernTheme.createRoundedPanel();
        headerPanel.setLayout(new MigLayout("insets 15", "[grow][]10[]"));
        
        JLabel titleLabel = new JLabel("File Sharing");
        titleLabel.setFont(titleLabel.getFont().deriveFont(22f));
        headerPanel.add(titleLabel, "grow");
        
        // Add status indicator
        connectionStatusLabel = new JLabel("●", SwingConstants.CENTER);
        connectionStatusLabel.setForeground(ModernTheme.ACCENT_COLOR);
        connectionStatusLabel.setToolTipText("Ready to transfer");
        headerPanel.add(connectionStatusLabel);
        
        JButton helpButton = ModernTheme.createIconButton("?");
        helpButton.addActionListener(e -> showHelp());
        headerPanel.add(helpButton);
        
        // User info panel with better layout
        JPanel userPanel = ModernTheme.createRoundedPanel();
        userPanel.setLayout(new MigLayout("insets 15, fillx", "[][grow]"));
        
        JLabel nameLabel = new JLabel("Your Name:");
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
        userPanel.add(nameLabel);
        
        nameField = new JTextField(20);
        nameField.setText(System.getProperty("user.name"));
        nameField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                nameField.selectAll();
            }
        });
        userPanel.add(nameField, "grow");
        
        // Directory panel with better layout
        JPanel dirPanel = ModernTheme.createRoundedPanel();
        dirPanel.setLayout(new MigLayout("insets 15, fillx", "[][grow][]"));
        
        JLabel dirLabel = new JLabel("Save Location:");
        dirLabel.setFont(dirLabel.getFont().deriveFont(Font.BOLD));
        dirPanel.add(dirLabel);
        
        saveDirectoryField = new JTextField(System.getProperty("user.home"));
        saveDirectoryField.setEditable(false);
        dirPanel.add(saveDirectoryField, "grow");
        
        JButton browseButton = ModernTheme.createAccentButton("Browse");
        browseButton.addActionListener(e -> setSaveDirectory());
        dirPanel.add(browseButton);
        
        // Action buttons with better spacing
        JPanel actionPanel = ModernTheme.createRoundedPanel();
        actionPanel.setLayout(new MigLayout("insets 15", "[grow]10[grow]"));
        
        JButton sendButton = ModernTheme.createAccentButton("Send Files");
        JButton receiveButton = ModernTheme.createAccentButton("Receive Files");
        
        // Add tooltips
        sendButton.setToolTipText("Click to select files or drag and drop anywhere");
        receiveButton.setToolTipText("Start receiving files from other users");
        
        sendButton.addActionListener(e -> openFileSelection());
        receiveButton.addActionListener(e -> waitForIncomingConnection());
        
        actionPanel.add(sendButton, "grow");
        actionPanel.add(receiveButton, "grow");
        
        // Add components to frame with proper spacing
        frame.add(headerPanel, "grow, wrap");
        frame.add(userPanel, "grow, wrap");
        frame.add(dirPanel, "grow, wrap");
        frame.add(actionPanel, "grow, wrap");
        
        // Add transfers panel at the bottom
        transfersPanel = new JPanel();
        transfersPanel.setLayout(new BoxLayout(transfersPanel, BoxLayout.Y_AXIS));
        transfersPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        frame.add(transfersPanel, "grow, wrap");
    }

    private void setupGlobalDropTarget() {
        // Create drop target for the entire frame
        new DropTarget(frame, new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                if (isDragAcceptable(dtde)) {
                    frame.getRootPane().setBorder(BorderFactory.createLineBorder(ModernTheme.ACCENT_COLOR, 3));
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }
            
            @Override
            public void dragExit(DropTargetEvent dte) {
                frame.getRootPane().setBorder(null);
            }
            
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    frame.getRootPane().setBorder(null);
                    Transferable tr = dtde.getTransferable();
                    
                    if (tr.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY);
                        List<File> fileList = (List<File>) tr.getTransferData(DataFlavor.javaFileListFlavor);
                        files = fileList.toArray(new File[0]);
                        startReceiverDiscovery(files);
                        dtde.dropComplete(true);
                    } else {
                        dtde.rejectDrop();
                    }
                } catch (Exception e) {
                    dtde.rejectDrop();
                }
            }
        });
    }

    private void showHelp() {
        String helpMessage = """
            <html>
            <h2>File Sharing App Help</h2>
            <p><b>To Send Files:</b></p>
            <ol>
                <li>Enter your name</li>
                <li>Click "Send Files"</li>
                <li>Select the files you want to send</li>
                <li>Choose a receiver from the list</li>
            </ol>
            <p><b>To Receive Files:</b></p>
            <ol>
                <li>Enter your name</li>
                <li>Set a save directory (optional)</li>
                <li>Click "Receive Files"</li>
                <li>Wait for incoming connections</li>
            </ol>
            </html>
            """;
        
        JOptionPane.showMessageDialog(frame,
            helpMessage,
            "Help",
            JOptionPane.INFORMATION_MESSAGE);
    }

    private void cleanup() {
        if (statusTimer != null) {
            statusTimer.stop();
        }
        transferManager.shutdown();
        frame.dispose();
    }

    private void openFileSelection() {
        if (nameField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please enter your name first!", "Warning",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        user.setUsername(nameField.getText());
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setMultiSelectionEnabled(true);
        int result = fileChooser.showOpenDialog(frame);
        
        if (result == JFileChooser.APPROVE_OPTION) {
            files = fileChooser.getSelectedFiles();
            startReceiverDiscovery(files);
        }
    }

    private void startReceiverDiscovery(File[] files) {
        JDialog discoveryDialog = new JDialog(frame, "Searching for Receivers", true);
        discoveryDialog.setSize(scale(400), scale(500));
        discoveryDialog.setLocationRelativeTo(frame);

        JPanel mainPanel = new JPanel(new BorderLayout(scale(10), scale(10)));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(scale(10), scale(10), scale(10), scale(10)));
        mainPanel.setBackground(AppTheme.SECONDARY_COLOR);

        // Status panel
        JPanel statusPanel = new JPanel(new BorderLayout(scale(5), scale(5)));
        statusPanel.setBackground(AppTheme.SECONDARY_COLOR);
        JLabel statusLabel = new JLabel("Searching for available receivers...");
        statusLabel.setFont(AppTheme.REGULAR_FONT);
        JProgressBar searchProgress = new JProgressBar();
        searchProgress.setIndeterminate(true);
        
        statusPanel.add(statusLabel, BorderLayout.CENTER);
        statusPanel.add(searchProgress, BorderLayout.SOUTH);

        // Receivers panel
        JPanel receiverPanel = new JPanel();
        receiverPanel.setLayout(new BoxLayout(receiverPanel, BoxLayout.Y_AXIS));
        receiverPanel.setBackground(AppTheme.SECONDARY_COLOR);
        ButtonGroup group = new ButtonGroup();

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBackground(AppTheme.SECONDARY_COLOR);
        
        JButton connectButton = AppTheme.createStyledButton("Connect");
        JButton cancelButton = AppTheme.createStyledButton("Cancel");
        JButton retryButton = AppTheme.createStyledButton("Retry");
        retryButton.setVisible(false);

        // Layout assembly
        buttonPanel.add(retryButton);
        buttonPanel.add(connectButton);
        buttonPanel.add(cancelButton);
        
        mainPanel.add(statusPanel, BorderLayout.NORTH);
        mainPanel.add(new JScrollPane(receiverPanel), BorderLayout.CENTER);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        discoveryDialog.add(mainPanel);

        // Handle discovery process
        AtomicBoolean isCancelled = new AtomicBoolean(false);
        List<User> availableReceivers = Collections.synchronizedList(new ArrayList<>());

        // Connect button action
        connectButton.addActionListener(e -> {
            User selectedReceiver = getSelectedReceiver(receiverPanel, availableReceivers);
            if (selectedReceiver != null) {
                discoveryDialog.dispose();
                sendConnectionRequest(selectedReceiver, files);
            } else {
                JOptionPane.showMessageDialog(discoveryDialog,
                    "Please select a receiver",
                    "No Receiver Selected",
                    JOptionPane.WARNING_MESSAGE);
            }
        });

        // Cancel button action
        cancelButton.addActionListener(e -> {
            isCancelled.set(true);
            transferManager.stopDiscovery();
            discoveryDialog.dispose();
        });

        // Retry button action
        retryButton.addActionListener(e -> {
            statusLabel.setText("Searching for available receivers...");
            searchProgress.setIndeterminate(true);
            retryButton.setVisible(false);
            connectButton.setVisible(true);
            receiverPanel.removeAll();
            availableReceivers.clear();
            group.clearSelection();
            startDiscovery(availableReceivers, receiverPanel, group, statusLabel);
        });

        // Start discovery
        startDiscovery(availableReceivers, receiverPanel, group, statusLabel);
        
        discoveryDialog.setVisible(true);
    }

    private void sendConnectionRequest(User receiver, File[] files) {
        JDialog waitDialog = new JDialog(frame, "Connecting", true);
        waitDialog.setSize(scale(300), scale(150));
        waitDialog.setLocationRelativeTo(frame);
        
        JPanel panel = new JPanel(new BorderLayout(scale(10), scale(10)));
        panel.setBorder(BorderFactory.createEmptyBorder(scale(10), scale(10), scale(10), scale(10)));
        panel.setBackground(AppTheme.SECONDARY_COLOR);
        
        JLabel statusLabel = new JLabel("Sending connection request...");
        statusLabel.setFont(AppTheme.REGULAR_FONT);
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        
        panel.add(statusLabel, BorderLayout.CENTER);
        panel.add(progress, BorderLayout.SOUTH);
        waitDialog.add(panel);

        // Handle connection request in background
        SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return transferManager.sendConnectionRequest(receiver, user.getUsername(), files);
            }

            @Override
            protected void done() {
                try {
                    boolean accepted = get();
                    waitDialog.dispose();
                    
                    if (accepted) {
                        startFileTransfer(receiver, files);
                    } else {
                        JOptionPane.showMessageDialog(frame,
                            "Connection request was refused by the receiver.",
                            "Connection Refused",
                            JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception e) {
                    waitDialog.dispose();
                    String errorMsg = "Connection timed out. Would you like to retry?";
                    int choice = JOptionPane.showConfirmDialog(frame,
                        errorMsg,
                        "Connection Error",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.ERROR_MESSAGE);
                        
                    if (choice == JOptionPane.YES_OPTION) {
                        startReceiverDiscovery(files);
                    }
                }
            }
        };

        worker.execute();
        waitDialog.setVisible(true);
    }

    private void waitForIncomingConnection() {
        if (nameField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please enter your name first!", "Warning",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        user.setUsername(nameField.getText());

        // Initialize the main progress panel
        TransferProgressPanel mainProgressPanel = new TransferProgressPanel("Waiting for incoming files...", "main");
        transfersPanel.removeAll();
        transfersPanel.add(mainProgressPanel);
        transfersPanel.setVisible(true);
        frame.revalidate();
        frame.repaint();

        transferManager.startReceiving(user.getUsername(),
            saveDirectoryField.getText(),
            progress -> {
                SwingUtilities.invokeLater(() -> {
                    if (progress == -1) {
                        mainProgressPanel.updateStatus("Starting transfer...");
                        mainProgressPanel.updateProgress(0);
                    } else {
                        mainProgressPanel.updateProgress(progress);
                    }
                });
            },
            status -> {
                SwingUtilities.invokeLater(() -> {
                    if (status.contains("Connection request from")) {
                        String senderName = status.substring(status.indexOf("from") + 5);
                        int option = JOptionPane.showConfirmDialog(frame,
                            "Accept file transfer from " + senderName + "?",
                            "Connection Request",
                            JOptionPane.YES_NO_OPTION);
                        
                        if (option == JOptionPane.YES_OPTION) {
                            mainProgressPanel.updateStatus("Receiving files from " + senderName);
                            mainProgressPanel.updateCounter(0, 0);
                        }
                    } else if (status.startsWith("Receiving file:")) {
                        String fileName = status.substring(status.indexOf(":") + 1).trim();
                        mainProgressPanel.updateStatus("Receiving: " + fileName);
                    } else if (status.matches("Completed \\d+ of \\d+ files")) {
                        String[] parts = status.split(" ");
                        int current = Integer.parseInt(parts[1]);
                        int total = Integer.parseInt(parts[3]);
                        mainProgressPanel.updateCounter(current, total);
                        if (current == total) {
                            mainProgressPanel.updateStatus("Transfer complete");
                        }
                    } else if (status.contains("Total files:")) {
                        String[] parts = status.split(":");
                        int totalFiles = Integer.parseInt(parts[1].trim());
                        mainProgressPanel.updateCounter(0, totalFiles);
                    }
                });
            }
        );
    }

    private void setSaveDirectory() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("Select Save Directory");
        
        if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File selectedDir = fileChooser.getSelectedFile();
            if (!selectedDir.exists() && !selectedDir.mkdirs()) {
                JOptionPane.showMessageDialog(frame,
                    "Failed to create directory:\n" + selectedDir.getAbsolutePath(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
                return;
            }
            saveDirectoryField.setText(selectedDir.getAbsolutePath());
        }
    }

    private void updateUsername() {
        String newName = nameField.getText().trim();
        if (!newName.isEmpty()) {
            user.setUsername(newName);
            // Update connection status if needed
            if (connectionStatusLabel != null) {
                connectionStatusLabel.setText("Ready as: " + newName);
            }
        }
    }

    private void toggleTransfersPanel(JButton toggleButton) {
        isTransfersPanelExpanded = !isTransfersPanelExpanded;
        toggleButton.setText(isTransfersPanelExpanded ? "▼" : "▶");
        
        for (Component comp : transfersPanel.getComponents()) {
            if (comp instanceof TransferProgressPanel) {
                comp.setVisible(isTransfersPanelExpanded);
            }
        }
        frame.revalidate();
        frame.repaint();
    }

    // New class for individual transfer progress panels
    private class TransferProgressPanel extends JPanel {
        private final JProgressBar progressBar;
        private final JLabel statusLabel;
        private final JLabel counterLabel;
        private final String transferId;
        
        public TransferProgressPanel(String title, String transferId) {
            this.transferId = transferId;
            setLayout(new MigLayout("fillx, insets 5", "[grow][]"));
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ModernTheme.BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            ));
            
            JPanel infoPanel = new JPanel(new MigLayout("fillx, gap 2", "[grow]"));
            infoPanel.setOpaque(false);
            
            statusLabel = new JLabel(title);
            statusLabel.setFont(AppTheme.REGULAR_FONT.deriveFont(Font.BOLD));
            counterLabel = new JLabel("");
            counterLabel.setForeground(Color.GRAY);
            
            progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(true);
            
            infoPanel.add(statusLabel, "split 2");
            infoPanel.add(counterLabel, "gapleft 10");
            infoPanel.add(progressBar, "newline, growx");
            
            add(infoPanel, "grow");
        }
        
        public void updateProgress(int progress) {
            progressBar.setValue(progress);
            progressBar.setString(progress + "%");
        }
        
        public void updateStatus(String status) {
            statusLabel.setText(status);
        }
        
        public void updateCounter(int current, int total) {
            counterLabel.setText(String.format("(%d/%d)", current, total));
        }
    }

    // Move this method outside showAvailableReceivers
    private User getSelectedReceiver(JPanel receiverPanel, List<User> availableReceivers) {
        for (Component comp : receiverPanel.getComponents()) {
            if (comp instanceof JRadioButton radio && radio.isSelected()) {
                String selectedReceiverName = radio.getText().trim();
                synchronized (availableReceivers) {
                    return availableReceivers.stream()
                        .filter(user -> user.getUsername().equals(selectedReceiverName))
                        .findFirst()
                        .orElse(null);
                }
            }
        }
        return null;
    }

    // Helper method to start discovery
    private void startDiscovery(List<User> availableReceivers, JPanel receiverPanel, 
                              ButtonGroup group, JLabel statusLabel) {
        transferManager.startDiscovery(availableReceivers, users -> {
            SwingUtilities.invokeLater(() -> {
                receiverPanel.removeAll();
                
                if (users.isEmpty()) {
                    JLabel noReceiversLabel = new JLabel("No receivers found yet...");
                    noReceiversLabel.setFont(AppTheme.REGULAR_FONT);
                    noReceiversLabel.setForeground(Color.GRAY);
                    receiverPanel.add(noReceiversLabel);
                } else {
                    synchronized (availableReceivers) {
                        for (User receiver : users) {
                            JRadioButton radioButton = new JRadioButton(receiver.getUsername());
                            group.add(radioButton);
                            receiverPanel.add(radioButton);
                            receiverPanel.add(Box.createRigidArea(new Dimension(0, scale(5))));
                        }
                    }
                }
                
                receiverPanel.revalidate();
                receiverPanel.repaint();
                statusLabel.setText("Found " + users.size() + " receiver(s)");
                statusLabel.setForeground(AppTheme.PRIMARY_COLOR);
            });
        });
    }

    // Modify the file sending method to use the new progress panel
    private void startFileTransfer(User receiver, File[] files) {
        String transferId = UUID.randomUUID().toString();
        TransferProgressPanel progressPanel = new TransferProgressPanel(
            files.length > 1 ? files.length + " files" : files[0].getName(),
            transferId
        );
        
        activeTransfers.put(transferId, progressPanel);
        transfersPanel.add(progressPanel);
        transfersPanel.setVisible(true);
        
        if (!isTransfersPanelExpanded) {
            progressPanel.setVisible(false);
        }
        
        frame.revalidate();
        frame.repaint();
        
        transferManager.startSendingFiles(
            receiver,
            user.getUsername(),
            files,
            progress -> SwingUtilities.invokeLater(() -> {
                progressPanel.updateProgress(progress);
            }),
            status -> SwingUtilities.invokeLater(() -> {
                progressPanel.updateStatus(status);
            }),
            () -> SwingUtilities.invokeLater(() -> {
                progressPanel.updateStatus("Transfer Complete");
                JOptionPane.showMessageDialog(frame,
                    "Files sent successfully!",
                    "Transfer Complete",
                    JOptionPane.INFORMATION_MESSAGE);
            })
        );
    }

    private JButton createSmallButton(String text) {
        JButton button = new JButton(text);
        button.setFont(AppTheme.REGULAR_FONT);
        button.setForeground(AppTheme.PRIMARY_COLOR);
        button.setBackground(AppTheme.BUTTON_COLOR);
        button.setBorder(new CompoundBorder(
            new LineBorder(AppTheme.PRIMARY_COLOR.darker(), 1),
            new EmptyBorder(scale(5), scale(10), scale(5), scale(10))
        ));
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(AppTheme.BUTTON_HOVER_COLOR);
            }
            
            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(AppTheme.BUTTON_COLOR);
            }
        });
        
        return button;
    }

    private boolean isDragAcceptable(DropTargetDragEvent event) {
        return event.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }
}
