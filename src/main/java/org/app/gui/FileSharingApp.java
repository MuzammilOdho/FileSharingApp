package org.app.gui;

import org.app.User;
import org.app.backend.Receiver;
import org.app.backend.Sender;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;

public class FileSharingApp {
    private JFrame frame;
    private JTextField nameField;
    private JTextField saveDirectoryField;
    private TransferProgressDialog progressDialog;
    private  User  user;
    private File[] files;
    private List<User> availableReceivers;
    private final FileTransferManager transferManager;

    public FileSharingApp() {
        transferManager = new FileTransferManager();
        frame = new JFrame("File Sharing App");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(500, 400);
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(AppTheme.SECONDARY_COLOR);
        
        initializeDashboard();

        // Add window listener for cleanup
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                transferManager.shutdown();
            }
        });
    }

    private void initializeDashboard() {
        user = new User();
        // Main panel with padding
        JPanel mainPanel = new JPanel(new BorderLayout(20, 20));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        mainPanel.setBackground(AppTheme.SECONDARY_COLOR);

        // Header
        JLabel headerLabel = new JLabel("File Sharing Dashboard", SwingConstants.CENTER);
        headerLabel.setFont(AppTheme.HEADER_FONT);
        headerLabel.setForeground(AppTheme.PRIMARY_COLOR);
        mainPanel.add(headerLabel, BorderLayout.NORTH);

        // Center panel for controls
        JPanel controlsPanel = new JPanel(new GridBagLayout());
        controlsPanel.setBackground(AppTheme.SECONDARY_COLOR);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // Name field with label
        JPanel namePanel = new JPanel(new BorderLayout(5, 0));
        namePanel.setBackground(AppTheme.SECONDARY_COLOR);
        JLabel nameLabel = new JLabel("Your Name:");
        nameLabel.setFont(AppTheme.REGULAR_FONT);
        nameField = new JTextField(20);
        nameField.setFont(AppTheme.REGULAR_FONT);
        namePanel.add(nameLabel, BorderLayout.WEST);
        namePanel.add(nameField, BorderLayout.CENTER);

        // Buttons
        JButton sendButton = AppTheme.createStyledButton("Send File");
        JButton receiveButton = AppTheme.createStyledButton("Receive File");
        JButton setDirectoryButton = AppTheme.createStyledButton("Set Save Directory");

        // Add components to controls panel
        gbc.gridy = 0;
        controlsPanel.add(namePanel, gbc);
        gbc.gridy = 1;
        controlsPanel.add(sendButton, gbc);
        gbc.gridy = 2;
        controlsPanel.add(receiveButton, gbc);
        gbc.gridy = 3;
        controlsPanel.add(setDirectoryButton, gbc);

        mainPanel.add(controlsPanel, BorderLayout.CENTER);
        frame.add(mainPanel);

        // Add action listeners
        sendButton.addActionListener(e -> openFileSelection());
        receiveButton.addActionListener(e -> waitForIncomingConnection());
        setDirectoryButton.addActionListener(e -> setSaveDirectory());

        frame.setVisible(true);
    }

    private void openFileSelection(){

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
            showAvailableReceivers(files);
        }
    }
    private void showAvailableReceivers(File[] files) {
        JDialog receiverDialog = new JDialog(frame, "Select Receiver", true);
        receiverDialog.setSize(400, 500);
        receiverDialog.setLocationRelativeTo(frame);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.setBackground(AppTheme.SECONDARY_COLOR);

        // Status panel
        JPanel statusPanel = new JPanel(new BorderLayout(5, 5));
        statusPanel.setBackground(AppTheme.SECONDARY_COLOR);
        JLabel statusLabel = new JLabel("Searching for receivers...");
        statusLabel.setFont(AppTheme.REGULAR_FONT);
        JProgressBar searchProgress = new JProgressBar();
        searchProgress.setIndeterminate(true);
        statusPanel.add(statusLabel, BorderLayout.CENTER);
        statusPanel.add(searchProgress, BorderLayout.SOUTH);

        // Receivers panel
        JPanel receiverPanel = new JPanel();
        receiverPanel.setLayout(new BoxLayout(receiverPanel, BoxLayout.Y_AXIS));
        receiverPanel.setBackground(AppTheme.SECONDARY_COLOR);

        availableReceivers = Collections.synchronizedList(new ArrayList<>());
        ButtonGroup group = new ButtonGroup();
        
        // Buttons panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBackground(AppTheme.SECONDARY_COLOR);
        JButton refreshButton = AppTheme.createStyledButton("Refresh");
        JButton selectButton = AppTheme.createStyledButton("Select");
        JButton cancelButton = AppTheme.createStyledButton("Cancel");
        buttonPanel.add(refreshButton);
        buttonPanel.add(selectButton);
        buttonPanel.add(cancelButton);

        // Add panels to main panel
        mainPanel.add(statusPanel, BorderLayout.NORTH);
        mainPanel.add(new JScrollPane(receiverPanel), BorderLayout.CENTER);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // SwingWorker for discovery
        SwingWorker<Void, List<User>> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                transferManager.startDiscovery(availableReceivers, this::publish);
                return null;
            }

            @Override
            protected void process(List<List<User>> chunks) {
                if (!chunks.isEmpty()) {
                    List<User> users = chunks.get(chunks.size() - 1);
                    receiverPanel.removeAll();
                    for (User receiver : users) {
                        JRadioButton radioButton = new JRadioButton(receiver.getUsername() + " (" + receiver.getIp() + ")");
                        radioButton.setFont(AppTheme.REGULAR_FONT);
                        radioButton.setForeground(AppTheme.PRIMARY_COLOR);
                        radioButton.setBackground(AppTheme.SECONDARY_COLOR);
                        group.add(radioButton);
                        receiverPanel.add(radioButton);
                        receiverPanel.add(Box.createRigidArea(new Dimension(0, 5)));
                    }
                    receiverPanel.revalidate();
                    receiverPanel.repaint();
                    statusLabel.setText("Found " + users.size() + " receiver(s)");
                }
            }

            @Override
            protected void done() {
                searchProgress.setIndeterminate(false);
                searchProgress.setValue(100);
                statusLabel.setText("Search completed");
            }
        };

        // Button actions
        selectButton.addActionListener(e -> {
            User selectedReceiver = null;
            for (Component comp : receiverPanel.getComponents()) {
                if (comp instanceof JRadioButton radio && radio.isSelected()) {
                    String selectedReceiverName = radio.getText().split(" \\(")[0];
                    selectedReceiver = availableReceivers.stream()
                        .filter(user -> user.getUsername().equals(selectedReceiverName))
                        .findFirst()
                        .orElse(null);
                    break;
                }
            }

            if (selectedReceiver != null) {
                final User receiver = selectedReceiver;
                receiverDialog.dispose();

                // First send connection request
                boolean accepted = transferManager.sendConnectionRequest(receiver, user.getUsername(), files);
                
                if (accepted) {
                    progressDialog = new TransferProgressDialog(frame, "Sending Files");
                    progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);


                    // Start file transfer in a separate thread
                    CompletableFuture.runAsync(() -> {
                        transferManager.startSendingFiles(
                            receiver,
                            user.getUsername(),
                            files,
                            progress -> SwingUtilities.invokeLater(() -> progressDialog.updateProgress(progress)),
                            status -> SwingUtilities.invokeLater(() -> progressDialog.setStatus(status)),
                            () -> SwingUtilities.invokeLater(() -> {
                                progressDialog.setCloseable(true);
                                progressDialog.dispose();
                                JOptionPane.showMessageDialog(frame,
                                    "All files sent successfully!",
                                    "Transfer Complete",
                                    JOptionPane.INFORMATION_MESSAGE);
                            })
                        );
                    });
                    progressDialog.setVisible(true);
                } else {
                    JOptionPane.showMessageDialog(frame,
                        "Connection request was rejected or failed",
                        "Connection Failed",
                        JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        refreshButton.addActionListener(e -> {
            availableReceivers.clear();
            group.clearSelection();
            searchProgress.setIndeterminate(true);
            statusLabel.setText("Searching for receivers...");
            worker.execute();
        });

        cancelButton.addActionListener(e -> receiverDialog.dispose());

        receiverDialog.add(mainPanel);
        receiverDialog.setVisible(true);
        worker.execute();
    }

    private void waitForIncomingConnection() {
        if (nameField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please enter your name first!", "Warning",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        user.setUsername(nameField.getText());
        System.out.println("Starting receiver mode...");

        JDialog waitDialog = new JDialog(frame, "Waiting for Connection", true);
        waitDialog.setSize(300, 150);
        waitDialog.setLocationRelativeTo(frame);
        waitDialog.setLayout(new BorderLayout(10, 10));
        
        JPanel waitPanel = new JPanel(new BorderLayout(10, 10));
        waitPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        waitPanel.setBackground(AppTheme.SECONDARY_COLOR);

        JLabel waitLabel = new JLabel("Waiting for incoming connections...", SwingConstants.CENTER);
        waitLabel.setFont(AppTheme.REGULAR_FONT);
        
        JProgressBar waitProgress = new JProgressBar();
        waitProgress.setIndeterminate(true);
        
        waitPanel.add(waitLabel, BorderLayout.CENTER);
        waitPanel.add(waitProgress, BorderLayout.SOUTH);
        
        JButton cancelButton = AppTheme.createStyledButton("Cancel");
        cancelButton.addActionListener(e -> {
            transferManager.stopReceiving();
            waitDialog.dispose();
        });
        
        waitDialog.add(waitPanel, BorderLayout.CENTER);
        waitDialog.add(cancelButton, BorderLayout.SOUTH);

        // Start receiving using FileTransferManager
        String saveDir = saveDirectoryField != null ? saveDirectoryField.getText() : System.getProperty("user.home");
        
        // Create a reference to the dialog that can be closed from the callback
        final JDialog finalWaitDialog = waitDialog;
        
        transferManager.startReceiving(user.getUsername(),
            saveDir,
            progress -> {
                SwingUtilities.invokeLater(() -> {
                    if (progressDialog != null) {
                        progressDialog.updateProgress(progress);
                    }
                });
            },
            status -> {
                SwingUtilities.invokeLater(() -> {
                    if (status.contains("Connection accepted")) {
                        finalWaitDialog.dispose();
                    } else {
                        waitLabel.setText(status);
                    }
                });
            }
        );

        waitDialog.setVisible(true);
    }


    private void setSaveDirectory() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int result = fileChooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedDir = fileChooser.getSelectedFile();
            if (!selectedDir.exists() && !selectedDir.mkdirs()) {
                JOptionPane.showMessageDialog(frame,
                    "Failed to create directory:\n" + selectedDir.getAbsolutePath(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
                return;
            }
            String path = selectedDir.getAbsolutePath();
            saveDirectoryField = new JTextField(path);
            JOptionPane.showMessageDialog(frame,
                    "Save directory set to:\n" + path,
                    "Directory Set",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }


}
