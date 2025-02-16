package org.app.backend;

import org.app.User;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeoutException;

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
    private static final int MAX_PORT_ATTEMPTS = 100; // Maximum number of port attempts
    private static final int PORT_RANGE_START = 50000; // Start of dynamic port range
    private static final int PORT_RANGE_END = 60000;   // End of dynamic port range
    private static final int SOCKET_TIMEOUT_MS = 30000; // 30 seconds timeout
    private final ConcurrentHashMap<Integer, Integer> chunkPortMap = new ConcurrentHashMap<>();

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

                // Wait for READY signal with proper error handling
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
     * Finds an available port for chunk transfer
     */
    private int findAvailablePort(int chunkIndex) {
        // First try the default port
        int defaultPort = RECEIVER_PORT + 1 + chunkIndex;
        if (isPortAvailable(defaultPort)) {
            return defaultPort;
        }

        // If default port is not available, try random ports in the range
        Random random = new Random();
        Set<Integer> triedPorts = new HashSet<>();
        
        while (triedPorts.size() < MAX_PORT_ATTEMPTS) {
            int port = PORT_RANGE_START + random.nextInt(PORT_RANGE_END - PORT_RANGE_START);
            
            if (!triedPorts.contains(port)) {
                triedPorts.add(port);
                if (isPortAvailable(port)) {
                    return port;
                }
            }
        }
        
        throw new RuntimeException("No available ports found after " + MAX_PORT_ATTEMPTS + " attempts");
    }

    /**
     * Checks if a port is available
     */
    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Modified sendFileChunks method to handle dynamic ports
     */
    private void sendFileChunks(String receiverIP, File file, int totalChunks, 
                              int optimalChunkSize, Consumer<Integer> progressCallback,
                              Consumer<String> statusCallback) throws Exception {
        int maxConcurrentChunks = Math.min(4, Runtime.getRuntime().availableProcessors());
        ExecutorService chunkExecutor = Executors.newFixedThreadPool(maxConcurrentChunks);
        CompletionService<Integer> completionService = new ExecutorCompletionService<>(chunkExecutor);
        
        try {
            // Allocate initial ports
            for (int i = 0; i < totalChunks; i++) {
                int port = findAvailablePort(i);
                chunkPortMap.put(i, port);
                statusCallback.accept(String.format("Allocated port %d for chunk %d", port, i + 1));
            }

            // Send port mapping and handle negotiation
            statusCallback.accept("Negotiating ports with receiver");
            try (Socket mappingSocket = new Socket(receiverIP, RECEIVER_PORT)) {
                mappingSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                
                // Send initial port map
                ObjectOutputStream oos = new ObjectOutputStream(mappingSocket.getOutputStream());
                oos.writeObject(new HashMap<>(chunkPortMap));
                oos.flush();
                
                // Get potentially modified port map from receiver
                ObjectInputStream ois = new ObjectInputStream(mappingSocket.getInputStream());
                Map<Integer, Integer> adjustedPorts = (Map<Integer, Integer>) ois.readObject();
                
                // Update our port map with receiver's adjustments
                chunkPortMap.clear();
                chunkPortMap.putAll(adjustedPorts);
                
                // Acknowledge the adjusted port map
                oos.writeObject("PORT_MAP_ACCEPTED");
                oos.flush();
            }

            // Submit all chunk tasks
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                final int chunkIndex = i;
                final long startPosition = (long) i * optimalChunkSize;
                final int currentChunkSize = (int) Math.min(optimalChunkSize, file.length() - startPosition);
                
                statusCallback.accept(String.format("Queuing chunk %d/%d on port %d", 
                    chunkIndex + 1, totalChunks, chunkPortMap.get(chunkIndex)));
                
                Future<Integer> future = completionService.submit(() -> sendSingleChunk(
                    receiverIP, file, chunkIndex, 
                    startPosition, currentChunkSize, totalChunks, 
                    chunkPortMap.get(chunkIndex), // Pass the allocated port
                    statusCallback));
                futures.add(future);
            }

            // Process completions
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
            chunkPortMap.clear();
            shutdownExecutor(chunkExecutor);
        }
    }

    /**
     * Modified sendSingleChunk method to use the allocated port
     */
    private Integer sendSingleChunk(String receiverIP, File file, int chunkIndex, 
                                  long startPosition, int chunkSize, int totalChunks,
                                  int allocatedPort, Consumer<String> statusCallback) 
                                  throws IOException, InterruptedException {
        int retryCount = 0;
        int maxRetries = 3;
        int baseDelay = 2000; // Increased base delay to 2 seconds
        IOException lastException = null;
        
        while (retryCount < maxRetries) {
            SocketChannel chunkChannel = null;
            try {
                chunkChannel = SocketChannel.open();
                // Configure socket with longer timeout
                chunkChannel.socket().setSoTimeout(60000); // Increased to 60 seconds
                chunkChannel.socket().setTcpNoDelay(true);
                chunkChannel.socket().setReceiveBufferSize(BUFFER_SIZE);
                chunkChannel.socket().setSendBufferSize(BUFFER_SIZE);
                
                // Add exponential backoff for retries
                if (retryCount > 0) {
                    int delay = baseDelay * (1 << (retryCount - 1));
                    statusCallback.accept(String.format("Waiting %d seconds before retry %d/%d for chunk %d", 
                        delay/1000, retryCount + 1, maxRetries, chunkIndex + 1));
                    Thread.sleep(delay);
                    statusCallback.accept(String.format("Retrying chunk %d (attempt %d/%d)", 
                        chunkIndex + 1, retryCount + 1, maxRetries));
                }
                
                // Connect with timeout
                chunkChannel.configureBlocking(true);
                if (!chunkChannel.connect(new InetSocketAddress(receiverIP, allocatedPort))) {
                    throw new IOException("Connection timeout");
                }
                
                // Send metadata using heap ByteBuffer
                ByteBuffer metadataBuffer = ByteBuffer.allocate(20);
                metadataBuffer.putInt(chunkIndex)
                             .putLong(startPosition)
                             .putInt(chunkSize)
                             .putInt(totalChunks)
                             .flip();
                
                long metadataStartTime = System.currentTimeMillis();
                while (metadataBuffer.hasRemaining()) {
                    if (System.currentTimeMillis() - metadataStartTime > 30000) { // 30 second timeout
                        throw new IOException("Metadata send timeout");
                    }
                    chunkChannel.write(metadataBuffer);
                }
                
                // Use zero-copy transfer for file data with timeout monitoring
                try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                    long transferred = 0;
                    long transferStartTime = System.currentTimeMillis();
                    int stallCount = 0;
                    
                    while (transferred < chunkSize) {
                        // Check for transfer stall
                        if (System.currentTimeMillis() - transferStartTime > 60000) { // 60 second timeout
                            throw new IOException("Transfer timeout - no progress for 60 seconds");
                        }
                        
                        long before = transferred;
                        long count = fileChannel.transferTo(
                            startPosition + transferred,
                            chunkSize - transferred,
                            chunkChannel
                        );
                        
                        if (count <= 0) {
                            stallCount++;
                            if (stallCount > 100) { // Allow up to 100 stalls before timeout
                                throw new IOException("Transfer stalled");
                            }
                            Thread.sleep(100);
                            continue;
                        }
                        
                        transferred += count;
                        stallCount = 0; // Reset stall counter on successful transfer
                        transferStartTime = System.currentTimeMillis(); // Reset timeout on progress
                    }
                    
                    if (transferred != chunkSize) {
                        throw new IOException("Incomplete chunk transfer: " + transferred + " of " + chunkSize);
                    }
                    
                    return chunkIndex;
                }
            } catch (IOException e) {
                lastException = e;
                statusCallback.accept(String.format("Error sending chunk %d: %s", 
                    chunkIndex + 1, e.getMessage()));
                retryCount++;
                
                if (retryCount < maxRetries) {
                    continue;
                }
            } finally {
                if (chunkChannel != null) {
                    try {
                        chunkChannel.close();
                    } catch (IOException e) {
                        // Log close error but don't throw
                        statusCallback.accept("Warning: Error closing chunk channel: " + e.getMessage());
                    }
                }
            }
        }
        
        String errorMsg = String.format("Failed to send chunk %d after %d retries. Last error: %s",
            chunkIndex + 1, maxRetries, lastException.getMessage());
        statusCallback.accept(errorMsg);
        throw new IOException(errorMsg, lastException);
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
            if (reader.ready() || reader.markSupported()) {
                int c;
                while ((c = reader.read()) != -1) {
                    if (c == '\n' || c == '\r') {
                        String result = response.toString().trim();
                        if (!result.isEmpty()) {
                            return result;
                        }
                        response.setLength(0);
                    } else {
                        response.append((char) c);
                    }
                }
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
