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
    import java.util.Arrays;
    import java.io.IOException;
    import java.io.PrintWriter;
    import java.io.BufferedReader;
    import java.io.InputStreamReader;
    import java.net.InetSocketAddress;

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

        public boolean sendConnectionRequest(User receiver, String senderName, File[] files) throws IOException {
           return sender.sendConnectionRequest(receiver,senderName,getFileInfo(files));
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

                    for (int i = 0; i < files.length; i++) {
                        final int fileIndex = i;  // Create effectively final variable
                        File file = files[i];
                        if (!file.exists() || !file.canRead()) {
                            throw new IOException("Cannot read file: " + file.getName());
                        }

                        try {
                            statusCallback.accept("Sending file: " + file.getName());
                            boolean isLastFile = (fileIndex == files.length - 1);
                            sender.sendFile(receiver.getIp(), file, progress -> {
                                int overallProgress = (int) (((fileIndex * 100.0) + progress) / files.length);
                                progressCallback.accept(overallProgress);
                            }, statusCallback, isLastFile);

                            statusCallback.accept(String.format("Completed %d of %d files", fileIndex + 1, files.length));
                        } catch (Exception e) {
                            throw new IOException("Error sending file " + file.getName() + ": " + e.getMessage(), e);
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
            info.append("<h3>Files to be received:</h3><br>");
            long totalSize = 0;

            for (File file : files) {
                info.append("• ").append(file.getName()).append("<br>");
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

        public void startReceiving(String username, String saveDirectory,
                Consumer<Integer> progressCallback, Consumer<String> statusCallback) {
            this.currentSaveDirectory = saveDirectory;
            this.progressCallback = progressCallback;
            this.statusCallback = statusCallback;

            receiver.setReceiving(true);
            receiver.setAcceptingConnections(true);


            // Start broadcaster in a separate thread
            CompletableFuture<Void> broadcasterFuture = CompletableFuture.runAsync(() ->
                            receiver.peerBroadcaster(username),
                    transferExecutor
            );

            // Start connection listener in another thread
            CompletableFuture<Void> listenerFuture = CompletableFuture.runAsync(() ->
                            receiver.listenForConnectionRequests(
                                    this.currentSaveDirectory,
                                    (progress) -> {
                                        progressCallback.accept(progress);
                                    },
                                    (status) -> {
                                        if (status.startsWith("Connection from")) {
                                            String senderName = status.split(":")[1].trim();
                                            statusCallback.accept("Connection request from " + senderName);
                                        } else if (status.startsWith("Files:")) {
                                            // Extract total files count
                                            int totalFiles = Integer.parseInt(status.split(":")[1].trim());
                                            statusCallback.accept("Total files:" + totalFiles);
                                            statusCallback.accept(status);
                                        } else {
                                            statusCallback.accept(status);
                                        }
                                    }
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
            try {
                receiver.setReceiving(false);
                receiver.stopReceiving();
                sender.setListening(false);
                cleanupInactiveConnections();
                statusCallback.accept("Transfer cancelled");
            } catch (Exception e) {
                System.err.println("Error stopping receiver: " + e.getMessage());
                statusCallback.accept("Error while cancelling: " + e.getMessage());
            }
        }

        public void shutdown() {
            try {
                stopReceiving();

                if (discoveryFuture != null) {
                    discoveryFuture.cancel(true);
                }

                shutdownExecutors();
            } catch (Exception e) {
                System.err.println("Error during shutdown: " + e.getMessage());
            }
        }

        private void shutdownExecutors() {
            List<ExecutorService> executors = Arrays.asList(transferExecutor, scheduledExecutor);

            // First attempt graceful shutdown
            executors.forEach(ExecutorService::shutdown);

            try {
                // Wait for tasks to complete
                for (ExecutorService executor : executors) {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                }
            } catch (InterruptedException e) {
                // Re-interrupt the thread and force shutdown
                Thread.currentThread().interrupt();
                executors.forEach(ExecutorService::shutdownNow);
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

        public void stopDiscovery() {
            if (discoveryFuture != null) {
                discoveryFuture.cancel(true);
            }
            sender.setListening(false);
        }
    }
