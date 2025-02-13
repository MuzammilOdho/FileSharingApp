package org.app.gui;

import org.app.User;
import org.app.backend.Receiver;
import org.app.backend.Sender;
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

public class FileSharingApp {
    private JFrame frame;
    private JTextField nameField;
    private JTextField saveDirectoryField;
    private TransferProgressDialog progressDialog;
    private  User  user;
    private File[] files;
    private List<User> availableReceivers;

    public FileSharingApp() {
        frame = new JFrame("File Sharing App");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(500, 400);
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(AppTheme.SECONDARY_COLOR);
        
        initializeDashboard();
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
        // Create a custom dialog for receiver selection
        JDialog receiverDialog = new JDialog(frame, "Select Receiver", true);
        receiverDialog.setSize(300, 400);
        receiverDialog.setLocationRelativeTo(frame);
        receiverDialog.setLayout(new BorderLayout(10, 10));


        JPanel receiverPanel = new JPanel();
        receiverPanel.setLayout(new BoxLayout(receiverPanel, BoxLayout.Y_AXIS));
        receiverPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        receiverPanel.setBackground(AppTheme.SECONDARY_COLOR);

        availableReceivers = Collections.synchronizedList(new ArrayList<>());
        ButtonGroup group = new ButtonGroup();
        Sender sender = new Sender();

        // SwingWorker to handle receiver discovery
        SwingWorker<Void, User> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {

                // Run peerListener in a separate thread
                Thread discoveryThread = new Thread(() -> sender.peerListener(availableReceivers));
                discoveryThread.start();

                long startTime = System.currentTimeMillis();
                while ((System.currentTimeMillis() - startTime) < 15000) {
                    synchronized (availableReceivers) {
                        System.out.println(Thread.currentThread().getName());
                        for (User receiver : availableReceivers) {
                            publish(receiver); // Publish each new receiver
                        }
                    }
                    try {
                        Thread.sleep(500); // Check every 500ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                sender.setListening(false);// Stop listener after timeout
                receiverDialog.dispose();
                return null;
            }

            @Override
            protected void process(List<User> users) {
                receiverPanel.removeAll(); // Clear previous entries
                for (User receiver : users) {
                    System.out.println("adding to List " + receiver.getUsername());
                    JRadioButton radioButton = new JRadioButton(receiver.getUsername());
                    radioButton.setFont(AppTheme.REGULAR_FONT);
                    radioButton.setBackground(AppTheme.SECONDARY_COLOR);
                    group.add(radioButton);
                    receiverPanel.add(radioButton);
                    receiverPanel.add(Box.createRigidArea(new Dimension(0, 10)));
                }
                receiverPanel.revalidate(); // Refresh UI
                receiverPanel.repaint();
            }
        };

        worker.execute(); // Start discovering receivers
        System.out.println("Main" + Thread.currentThread().getName());
        JButton selectButton = AppTheme.createStyledButton("Select");
        selectButton.addActionListener(e -> {
            for (Component comp : receiverPanel.getComponents()) {
                if (comp instanceof JRadioButton) {
                    JRadioButton radio = (JRadioButton) comp;
                    if (radio.isSelected()) {
                        String selectedReceiverName = radio.getText();

                        // Find the selected receiver's details
                        User selectedReceiver = availableReceivers.stream()
                                .filter(user -> user.getUsername().equals(selectedReceiverName))
                                .findFirst()
                                .orElse(null);

                        if (selectedReceiver != null) {
                            System.out.println("Sending connection request to: " + selectedReceiver.getUsername());

                            if (confirmSend(selectedReceiver.getUsername(), files)) {
                                if (sender.sendConnectionRequest(selectedReceiver, user.getUsername(), fileInfo(files))) {
                                    receiverDialog.dispose();
                                    sendFiles(selectedReceiver.getUsername(), files);
                                }
                            }

                            // Proceed to file confirmation after connection request
                        }
                        break;
                    }
                }
            }
        });


        receiverDialog.add(new JScrollPane(receiverPanel), BorderLayout.CENTER);
        receiverDialog.add(selectButton, BorderLayout.SOUTH);
        receiverDialog.setVisible(true);
    }

    public String fileInfo(File[] files) {
        StringBuilder fileInfo = new StringBuilder("<html><body>");
        fileInfo.append("<h2>Files to be sent:</h2><br>");
        long totalSize = 0;
        for (File file : files) {
            fileInfo.append(file.getName()).append("<br>");
            totalSize += file.length();
        }
        fileInfo.append("<br>Total size: ").append(formatFileSize(totalSize));
        fileInfo.append("</body></html>");
        return fileInfo.toString();
    }
    private boolean confirmSend(String receiver,File[] files) {
               String  fileInfo = fileInfo(files);
        int confirm = JOptionPane.showConfirmDialog(frame,
                new JLabel(fileInfo),
                "Send files to " + receiver + "?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);

         return (confirm == JOptionPane.YES_OPTION);
    }

    private String formatFileSize(long size) {
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double fileSize = size;
        
        while (fileSize >= 1024 && unitIndex < units.length - 1) {
            fileSize /= 1024;
            unitIndex++;
        }
        
        return String.format("%.2f %s", fileSize, units[unitIndex]);
    }

    private void sendFiles(String receiver, File[] files) {
        progressDialog = new TransferProgressDialog(frame, "Sending Files");
        progressDialog.setVisible(true);
        try (ExecutorService executor = Executors.newFixedThreadPool(files.length)) {
            for (File file : files) {
                executor.execute(()->new Sender().sendFile(receiver, file));
            }
        }
    }

    private void waitForIncomingConnection() {
        if (nameField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please enter your name first!", "Warning",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        user.setUsername(nameField.getText());
        System.out.println("starting boradcast");
        Receiver receiver = new Receiver();
        new Thread(() -> receiver.peerBroadcaster(user.getUsername())).start();
        new Thread(receiver::listenForConnectionRequests).start();

        JDialog waitDialog = new JDialog(frame, "Waiting for Connection", true);
        waitDialog.setSize(300, 150);
        waitDialog.setLocationRelativeTo(frame);
        waitDialog.setLayout(new BorderLayout(10, 10));
        waitDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                receiver.setReceiving(false);
                waitDialog.dispose(); // Close the dialog
                System.out.println("User closed the dialog. Stopping broadcast and listener...");
            }
        });

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
            receiver.setReceiving(false);
            waitDialog.dispose();
        });
        
        waitDialog.add(waitPanel, BorderLayout.CENTER);
        waitDialog.add(cancelButton, BorderLayout.SOUTH);
        waitDialog.setVisible(true);

    }


    private void setSaveDirectory() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int result = fileChooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            String path = fileChooser.getSelectedFile().getAbsolutePath();
            saveDirectoryField = new JTextField(path);
            JOptionPane.showMessageDialog(frame,
                    "Save directory set to:\n" + path,
                    "Directory Set",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }


}
