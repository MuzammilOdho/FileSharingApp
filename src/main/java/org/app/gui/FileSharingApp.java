package org.app.gui;

import org.app.User;
import org.app.backend.Receiver;
import org.app.backend.Sender;
import org.app.gui.theme.AppTheme;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FileSharingApp {
    private JFrame frame;
    private JTextField nameField;
    private JTextField saveDirectoryField;
    private TransferProgressDialog progressDialog;
    private  User  user;
    private File[] files;
    private List<User> availableReceivers;
    private String saveDirectory;

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

    private void showAvailableReceivers(File[] files){
        // Create a custom dialog for receiver selection
        JDialog receiverDialog = new JDialog(frame, "Select Receiver", true);
        receiverDialog.setSize(300, 400);
        receiverDialog.setLocationRelativeTo(frame);
        receiverDialog.setLayout(new BorderLayout(10, 10));

        JPanel receiverPanel = new JPanel();
        receiverPanel.setLayout(new BoxLayout(receiverPanel, BoxLayout.Y_AXIS));
        receiverPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        receiverPanel.setBackground(AppTheme.SECONDARY_COLOR);
        availableReceivers = new ArrayList<>();
        new Thread(()->{
            new Sender().peerListener(availableReceivers,true);
        }).start();

        ButtonGroup group = new ButtonGroup();
     new Thread(()->{
         if (availableReceivers.isEmpty()){
             try {
                 Thread.sleep(1000);
             }catch (InterruptedException e){
                 e.printStackTrace();
             }
         }
         for (User receiver : availableReceivers) {
             JRadioButton radioButton = new JRadioButton(receiver.getUsername());
             radioButton.setFont(AppTheme.REGULAR_FONT);
             radioButton.setBackground(AppTheme.SECONDARY_COLOR);
             group.add(radioButton);
             receiverPanel.add(radioButton);
             receiverPanel.add(Box.createRigidArea(new Dimension(0, 10)));
         }
     }).start();

        JButton selectButton = AppTheme.createStyledButton("Select");
        selectButton.addActionListener(e -> {
            for (Component comp : receiverPanel.getComponents()) {
                if (comp instanceof JRadioButton) {
                    JRadioButton radio = (JRadioButton) comp;
                    if (radio.isSelected()) {
                        receiverDialog.dispose();

                        confirmSend(radio.getText(), files);
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
    private void confirmSend(String receiver,File[] files) {
               String  fileInfo = fileInfo(files);
        int confirm = JOptionPane.showConfirmDialog(frame,
                new JLabel(fileInfo),
                "Send files to " + receiver + "?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
                
        if (confirm == JOptionPane.YES_OPTION) {
//            sendFiles(receiver, File[]);
        }
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
        
        // Simulate file transfer (replace with actual transfer logic)
        new Thread(() -> {
            for (int i = 0; i <= 100; i++) {
                final int progress = i;
                SwingUtilities.invokeLater(() -> progressDialog.updateProgress(progress));
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            SwingUtilities.invokeLater(() -> {
                progressDialog.dispose();
                JOptionPane.showMessageDialog(frame, "Files sent successfully!");
            });
        }).start();
    }

    private void waitForIncomingConnection() {
        if (nameField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please enter your name first!", "Warning",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        user.setUsername(nameField.getText());
        System.out.println("starting boradcast");
        new Thread(() -> {
            new Receiver().peerBroadcaster(user.getUsername(),true);
        }).start();

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
        cancelButton.addActionListener(e -> waitDialog.dispose());
        
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
