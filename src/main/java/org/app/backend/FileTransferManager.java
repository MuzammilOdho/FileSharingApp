package org.app.backend;

import org.app.User;

import java.io.File;
import java.net.Socket;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class FileTransferManager {
    private final Sender sender;
    private final Receiver receiver;
    private final ExecutorService transferExecutor;
    private final ScheduledExecutorService scheduledExecutor;
    private String currentSaveDirectory;
    private Consumer<Integer> progressCallback;
    private Consumer<String> statusCallback;
    private volatile CompletableFuture<?> discoveryFuture;

    public FileTransferManager() {
        this.sender = new Sender();
        this.receiver = new Receiver();
        // Create thread pool for file transfers
        this.transferExecutor = Executors.newFixedThreadPool(3); // Allow 3 concurrent transfers
        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    public boolean sendConnectionRequest(User receiver, String senderName, File[] files) {
        try {
            // Stop discovery and wait for it to complete
            sender.setListening(false);
            if (discoveryFuture != null) {
                discoveryFuture.get(5, TimeUnit.SECONDS); // Wait up to 5 seconds
            }
            System.out.println("Discovery stopped, sending connection request...");

            // Send connection request and wait for confirmation
            boolean accepted = sender.sendConnectionRequest(receiver, senderName, getFileInfo(files));

            if (accepted) {
                // Wait a bit to ensure receiver is ready
                Thread.sleep(1000);

                // Test connection to receiver (metadata connection on port 9090)
                try (Socket testSocket = new Socket(receiver.getIp(), 9090)) {
                    System.out.println("Connection test successful");
                    return true;
                } catch (Exception e) {
                    System.err.println("Connection test failed: " + e.getMessage());
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            System.err.println("Error in connection request: " + e.getMessage());
            return false;
        }
    }

    public void startSendingFiles(User receiver, String senderName, File[] files,
                                  Consumer<Integer> progressCallback,
                                  Consumer<String> statusCallback,
                                  Runnable onComplete) {
        CompletableFuture.runAsync(() -> {
            try {
                // Ensure discovery is stopped
                sender.setListening(false);
                if (discoveryFuture != null) {
                    try {
                        discoveryFuture.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        System.err.println("Warning: Discovery stop timed out");
                    }
                }

                System.out.println("Starting file transfer to: " + receiver.getUsername());
                statusCallback.accept("Starting file transfer...");
                int totalFiles = files.length;
                int filesSent = 0;

                for (int i = 0; i < totalFiles; i++) {
                    File file = files[i];
                    try {
                        statusCallback.accept("Sending file: " + file.getName());
                        // Pass true if this is the last file in the session.
                        boolean isLastFile = (i == totalFiles - 1);
                        int finalFilesSent = filesSent;
                        sender.sendFile(receiver.getIp(), file, progress -> {
                            int overallProgress = (int) (((finalFilesSent * 100.0) + progress) / totalFiles);
                            progressCallback.accept(overallProgress);
                        }, isLastFile);
                        filesSent++;
                        statusCallback.accept(String.format("Completed %d of %d files", filesSent, totalFiles));
                    } catch (Exception e) {
                        System.err.println("Error sending file " + file.getName() + ": " + e.getMessage());
                        throw e;
                    }
                }

                onComplete.run();
            } catch (Exception e) {
                statusCallback.accept("Error: " + e.getMessage());
                e.printStackTrace();
            }
        }, transferExecutor);
    }

    private String getFileInfo(File[] files) {
        StringBuilder info = new StringBuilder("<html><body>");
        info.append("<h2>Files to be sent:</h2><br>");
        long totalSize = 0;
        for (File file : files) {
            info.append(file.getName()).append("<br>");
            totalSize += file.length();
        }
        info.append("<br>Total size: ").append(formatFileSize(totalSize));
        info.append("</body></html>");
        return info.toString();
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

    public void startReceiving(String userName, String saveDirectory,
                               Consumer<Integer> progressCallback,
                               Consumer<String> statusCallback) {
        this.currentSaveDirectory = saveDirectory;
        this.progressCallback = progressCallback;
        this.statusCallback = statusCallback;

        receiver.setReceiving(true);

        // Start broadcaster in a separate thread
        CompletableFuture<Void> broadcasterFuture = CompletableFuture.runAsync(() ->
                        receiver.peerBroadcaster(userName),
                transferExecutor
        );

        // Start connection listener in another thread
        CompletableFuture<Void> listenerFuture = CompletableFuture.runAsync(() ->
                        receiver.listenForConnectionRequests(
                                this.currentSaveDirectory,
                                this.progressCallback,
                                this.statusCallback
                        ),
                transferExecutor
        );

        // Handle completion
        CompletableFuture.allOf(broadcasterFuture, listenerFuture)
                .thenRun(() -> {
                    System.out.println("File transfer session completed");
                    statusCallback.accept("Transfer session ended");
                });
    }

    private void cleanupInactiveConnections() {
        // Add logic to cleanup any stale connections
    }

    public void stopReceiving() {
        receiver.setReceiving(false);
    }

    public void shutdown() {
        try {
            sender.setListening(false);
            receiver.setReceiving(false);
            if (discoveryFuture != null) {
                discoveryFuture.cancel(true);
            }
            transferExecutor.shutdown();
            scheduledExecutor.shutdown();
            transferExecutor.awaitTermination(5, TimeUnit.SECONDS);
            scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            transferExecutor.shutdownNow();
            scheduledExecutor.shutdownNow();
        }
    }

    public void startDiscovery(List<User> discoveredReceivers, Consumer<List<User>> publishCallback) {
        // Cancel any existing discovery
        if (discoveryFuture != null) {
            discoveryFuture.cancel(true);
        }

        sender.setListening(true);
        discoveryFuture = CompletableFuture.runAsync(() -> {
            try {
                Set<String> processedIPs = new HashSet<>();
                List<User> newUsers = new ArrayList<>();

                sender.peerListener(discoveredReceivers, (user) -> {
                    synchronized (processedIPs) {
                        if (processedIPs.add(user.getIp())) {
                            synchronized (discoveredReceivers) {
                                discoveredReceivers.add(user);
                                newUsers.add(user);
                                publishCallback.accept(new ArrayList<>(discoveredReceivers));
                            }
                        }
                    }
                });
            } catch (Exception e) {
                System.err.println("Discovery error: " + e.getMessage());
                e.printStackTrace();
            }
        }, transferExecutor);
    }
}
