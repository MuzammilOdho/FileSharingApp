package org.app.backend;

import org.app.User;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Future;

public class Sender {
    private volatile boolean isListening = true;

    public void setListening(boolean listening) {
        isListening = listening;
    }

    private static final int LISTENING_PORT = 9000;
    private static final int CONNECTION_PORT = 9080;
    private static final int RECEIVER_PORT = 9090;
    // We'll use individual chunk ports starting from RECEIVER_PORT + 1 for parallel transfer.
    // Buffer size remains 8MB (adjust as needed)
    private static final int BUFFER_SIZE = 8 * 1024 * 1024;
    private static final int CHUNK_SIZE = 64 * 1024 * 1024; // 64MB chunks

    public void peerListener(java.util.List<User> discoveredReceivers, Consumer<User> onNewUser) {
        try {
            System.out.println("Starting peer listener...");
            try (var channel = java.nio.channels.DatagramChannel.open()) {
                channel.bind(new InetSocketAddress(LISTENING_PORT));
                channel.configureBlocking(false);
                var buffer = java.nio.ByteBuffer.allocate(1024);
                while (isListening) {
                    buffer.clear();
                    var address = channel.receive(buffer);
                    if (address instanceof InetSocketAddress inetSocketAddress) {
                        buffer.flip();
                        String receiverName = StandardCharsets.UTF_8.decode(buffer).toString().trim();
                        String receiverIP = inetSocketAddress.getAddress().getHostAddress();
                        boolean exists = discoveredReceivers.stream()
                                .anyMatch(user -> user.getIp().equals(receiverIP));
                        if (!exists) {
                            onNewUser.accept(new User(receiverName, receiverIP));
                        }
                    }
                    Thread.sleep(1000);
                }
                System.out.println("Peer listener stopped.");
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Peer listener error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public boolean sendConnectionRequest(User receiver, String senderName, String fileInfo) {
        try (Socket socket = new Socket(receiver.getIp(), CONNECTION_PORT);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            writer.println(senderName + " wants to send you :" + fileInfo);
            String response = reader.readLine();
            return "YES".equalsIgnoreCase(response);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Sends a file to the receiver.
     * @param isLastFile indicates if this is the final file (termination signal will be sent only then).
     */
    public void sendFile(String receiverIP, File file, Consumer<Integer> progressCallback, 
                        Consumer<String> statusCallback, boolean isLastFile) {
        try {
            statusCallback.accept("Connecting to receiver at " + receiverIP);
            Socket metadataSocket = new Socket(receiverIP, RECEIVER_PORT);
            metadataSocket.setSoTimeout(30000);

            try (DataOutputStream metadataOut = new DataOutputStream(
                    new BufferedOutputStream(metadataSocket.getOutputStream()));
                 BufferedReader metadataIn = new BufferedReader(
                         new InputStreamReader(metadataSocket.getInputStream()))) {

                long fileSize = file.length();
                int optimalChunkSize = calculateOptimalChunkSize(fileSize);
                int totalChunks = (int) Math.ceil((double) fileSize / optimalChunkSize);

                statusCallback.accept(String.format("Preparing to send: %s (Size: %s)", 
                    file.getName(), formatFileSize(fileSize)));
                statusCallback.accept("Dividing file into " + totalChunks + " chunks");

                // Send metadata
                metadataOut.writeLong(fileSize);
                metadataOut.writeInt(totalChunks);
                byte[] nameBytes = file.getName().getBytes(StandardCharsets.UTF_8);
                metadataOut.writeInt(nameBytes.length);
                metadataOut.write(nameBytes);
                metadataOut.flush();

                statusCallback.accept("Waiting for receiver ready signal...");
                String response = waitForResponse(metadataIn, 30000);
                if (!"READY".equals(response)) {
                    throw new IOException("Receiver not ready: " + response);
                }
                statusCallback.accept("Receiver ready, starting transfer");

                // Send chunks
                sendFileChunks(receiverIP, file, totalChunks, optimalChunkSize, 
                    progressCallback, statusCallback);

                if (isLastFile) {
                    statusCallback.accept("Sending termination signal");
                    try (Socket completionSocket = new Socket(receiverIP, RECEIVER_PORT)) {
                        DataOutputStream completionOut = new DataOutputStream(completionSocket.getOutputStream());
                        completionOut.writeLong(-1);
                    }
                }
            }
        } catch (Exception e) {
            statusCallback.accept("Error in file transfer: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Sends file chunks in parallel using an ExecutorService.
     * Each chunk is sent over its own SocketChannel.
     */
    private void sendFileChunks(String receiverIP, File file, int totalChunks, 
                              int optimalChunkSize, Consumer<Integer> progressCallback,
                              Consumer<String> statusCallback) throws Exception {
        // Limit concurrent transfers to avoid overwhelming network
        int maxConcurrentChunks = Math.min(4, Runtime.getRuntime().availableProcessors());
        ExecutorService chunkExecutor = Executors.newFixedThreadPool(maxConcurrentChunks);
        CompletionService<Integer> completionService = new ExecutorCompletionService<>(chunkExecutor);
        
        try {
            // Submit all chunk tasks first
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                final int chunkIndex = i;
                final long startPosition = (long) i * optimalChunkSize;
                final int currentChunkSize = (int) Math.min(optimalChunkSize, 
                    file.length() - startPosition);
                
                statusCallback.accept(String.format("Queuing chunk %d/%d", 
                    chunkIndex + 1, totalChunks));
                
                Future<Integer> future = completionService.submit(() -> sendSingleChunk(
                    receiverIP, file, chunkIndex, 
                    startPosition, currentChunkSize, totalChunks, statusCallback));
                futures.add(future);
            }

            // Track completed chunks for progress updates
            int completedChunks = 0;
            while (completedChunks < totalChunks) {
                try {
                    Future<Integer> completed = completionService.poll(30, TimeUnit.SECONDS);
                    if (completed == null) {
                        throw new TimeoutException("Chunk transfer timed out");
                    }
                    
                    int chunkIndex = completed.get();
                    completedChunks++;
                    
                    int progress = (int) ((completedChunks * 100.0) / totalChunks);
                    progressCallback.accept(progress);
                    statusCallback.accept(String.format("Completed chunk %d/%d (%d%%)", 
                        chunkIndex + 1, totalChunks, progress));
                    
                } catch (Exception e) {
                    // Cancel all remaining transfers if any chunk fails
                    for (Future<Integer> future : futures) {
                        future.cancel(true);
                    }
                    throw new IOException("Transfer failed: " + e.getMessage(), e);
                }
            }
        } finally {
            shutdownExecutor(chunkExecutor);
        }
    }

    private Integer sendSingleChunk(String receiverIP, File file, int chunkIndex, 
                                  long startPosition, int chunkSize, int totalChunks,
                                  Consumer<String> statusCallback) throws IOException, InterruptedException {
        int retryCount = 0;
        int maxRetries = 3;
        int baseDelay = 1000; // 1 second base delay
        IOException lastException = null;
        
        while (retryCount < maxRetries) {
            try (SocketChannel chunkChannel = SocketChannel.open()) {
                // Configure socket before connecting
                chunkChannel.socket().setSoTimeout(30000);
                chunkChannel.socket().setTcpNoDelay(true);
                
                // Add exponential backoff for retries
                if (retryCount > 0) {
                    int delay = baseDelay * (1 << (retryCount - 1));
                    Thread.sleep(delay);
                    statusCallback.accept(String.format("Retrying chunk %d (attempt %d/%d)", 
                        chunkIndex + 1, retryCount + 1, maxRetries));
                }
                
                // Connect with timeout
                if (!chunkChannel.connect(new InetSocketAddress(receiverIP, RECEIVER_PORT + 1 + chunkIndex))) {
                    throw new IOException("Connection timeout");
                }
                
                // Send metadata using heap ByteBuffer
                ByteBuffer metadataBuffer = ByteBuffer.allocate(20);
                metadataBuffer.putInt(chunkIndex)
                             .putLong(startPosition)
                             .putInt(chunkSize)
                             .putInt(totalChunks)
                             .flip();
                while (metadataBuffer.hasRemaining()) {
                    chunkChannel.write(metadataBuffer);
                }
                
                // Use zero-copy transfer for file data
                try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                    long transferred = 0;
                    while (transferred < chunkSize) {
                        long count = fileChannel.transferTo(
                            startPosition + transferred,
                            chunkSize - transferred,
                            chunkChannel
                        );
                        if (count <= 0) {
                            // Add small delay before retry
                            Thread.sleep(100);
                            continue;
                        }
                        transferred += count;
                    }
                    
                    if (transferred != chunkSize) {
                        throw new IOException("Failed to transfer complete chunk data");
                    }
                    
                    return chunkIndex;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Transfer interrupted", e);
                }
            } catch (IOException e) {
                lastException = e;
                retryCount++;
                if (retryCount < maxRetries) {
                    statusCallback.accept(String.format("Chunk %d failed: %s. Retrying...", 
                        chunkIndex + 1, e.getMessage()));
                    continue;
                }
            }
        }
        
        throw new IOException("Failed to send chunk after " + maxRetries + " retries", lastException);
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private int calculateOptimalChunkSize(long fileSize) {
        // Base chunk size: 64MB
        final long BASE_CHUNK_SIZE = 64L * 1024 * 1024;
        
        if (fileSize < BASE_CHUNK_SIZE) {
            return (int) fileSize;
        }
        
        // For larger files, aim for 8-16 chunks or BASE_CHUNK_SIZE, whichever is larger
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int targetChunks = Math.min(availableProcessors * 2, 16);
        long calculatedChunkSize = Math.max(BASE_CHUNK_SIZE, fileSize / targetChunks);
        
        // Cap maximum chunk size at 256MB to avoid memory issues
        long maxChunkSize = 256L * 1024 * 1024;
        return (int) Math.min(calculatedChunkSize, maxChunkSize);
    }

    private String waitForResponse(BufferedReader reader, int timeoutMs) throws IOException {
        long startTime = System.currentTimeMillis();
        StringBuilder response = new StringBuilder();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (reader.ready()) {
                int c;
                while ((c = reader.read()) != -1) {
                    if (c == '\n') break;
                    response.append((char) c);
                }
                return response.toString().trim();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for response");
            }
        }
        throw new IOException("Timeout waiting for receiver response");
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        int z = (63 - Long.numberOfLeadingZeros(size)) / 10;
        return String.format("%.1f %sB", (double)size / (1L << (z*10)), " KMGTPE".charAt(z));
    }
}
