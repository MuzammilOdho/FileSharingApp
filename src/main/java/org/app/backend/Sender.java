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
    public void sendFile(String receiverIP, File file, Consumer<Integer> progressCallback, boolean isLastFile) {
        try {
            Socket metadataSocket = new Socket(receiverIP, RECEIVER_PORT);
            metadataSocket.setSoTimeout(30000);
            try (DataOutputStream metadataOut = new DataOutputStream(
                    new BufferedOutputStream(metadataSocket.getOutputStream()));
                 BufferedReader metadataIn = new BufferedReader(
                         new InputStreamReader(metadataSocket.getInputStream()))) {

                long fileSize = file.length();
                int optimalChunkSize = calculateOptimalChunkSize(fileSize);
                int totalChunks = (int) Math.ceil((double) fileSize / optimalChunkSize);

                // Send file metadata: fileSize, totalChunks, filename length and name.
                metadataOut.writeLong(fileSize);
                metadataOut.writeInt(totalChunks);
                byte[] nameBytes = file.getName().getBytes(StandardCharsets.UTF_8);
                metadataOut.writeInt(nameBytes.length);
                metadataOut.write(nameBytes);
                metadataOut.flush();

                // Wait for READY signal from the receiver.
                String response = waitForResponse(metadataIn, 30000);
                if (!"READY".equals(response)) {
                    throw new IOException("Receiver not ready: " + response);
                }
                Thread.sleep(3000);
                // Send file chunks in parallel.
                sendFileChunks(receiverIP, file, totalChunks, optimalChunkSize, progressCallback);

                // If this is the last file, send a termination signal.
                if (isLastFile) {
                    try (Socket completionSocket = new Socket(receiverIP, RECEIVER_PORT)) {
                        DataOutputStream completionOut = new DataOutputStream(completionSocket.getOutputStream());
                        completionOut.writeLong(-1);
                        System.out.println("Sent termination signal");
                    }
                }
            } finally {
                metadataSocket.close();
            }
        } catch (Exception e) {
            System.err.println("Error in file transfer: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Sends file chunks in parallel using an ExecutorService.
     * Each chunk is sent over its own SocketChannel.
     */
    private void sendFileChunks(String receiverIP, File file, int totalChunks, int optimalChunkSize,
                                Consumer<Integer> progressCallback) throws Exception {
        // Use a more efficient thread pool size based on available processors
        int threadPoolSize = Math.min(totalChunks, 
            Math.max(2, Runtime.getRuntime().availableProcessors()));
        ExecutorService chunkExecutor = Executors.newFixedThreadPool(threadPoolSize);
        CompletionService<Integer> completionService = new ExecutorCompletionService<>(chunkExecutor);
        
        try {
            // Submit tasks for each chunk using a more efficient buffer strategy
            for (int i = 0; i < totalChunks; i++) {
                final int chunkIndex = i;
                final long startPosition = (long) i * optimalChunkSize;
                final int currentChunkSize = (int) Math.min(optimalChunkSize, file.length() - startPosition);
                
                completionService.submit(() -> sendSingleChunk(receiverIP, file, chunkIndex, 
                    startPosition, currentChunkSize, totalChunks));
            }

            // Track progress more efficiently
            int completedChunks = 0;
            while (completedChunks < totalChunks) {
                Future<Integer> future = completionService.take();
                future.get(); // Will throw if there was an error
                completedChunks++;
                progressCallback.accept((int) (((double) completedChunks / totalChunks) * 100));
            }
        } finally {
            shutdownExecutor(chunkExecutor);
        }
    }

    private Integer sendSingleChunk(String receiverIP, File file, int chunkIndex, 
                                  long startPosition, int chunkSize, int totalChunks) throws IOException, InterruptedException {
        int retryCount = 0;
        int maxRetries = 3;
        IOException lastException = null;
        
        while (retryCount < maxRetries) {
            try (SocketChannel chunkChannel = SocketChannel.open(
                    new InetSocketAddress(receiverIP, RECEIVER_PORT + 1 + chunkIndex))) {
                
                // Configure socket
                chunkChannel.socket().setSoTimeout(30000);
                chunkChannel.socket().setTcpNoDelay(true);
                
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
                    System.out.println("Retry " + retryCount + " for chunk " + chunkIndex + 
                        " after error: " + e.getMessage());
                    Thread.sleep(1000 * retryCount); // Exponential backoff
                }
            }
        }
        
        throw new IOException("Failed to send chunk after " + maxRetries + " retries. Last error: " + 
            lastException.getMessage(), lastException);
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
        if (fileSize < 16 * 1024 * 1024) {
            return (int) fileSize;
        }
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int optimalChunks = Math.min(availableProcessors * 2, (int) (fileSize / (16 * 1024 * 1024)));
        if (optimalChunks < 1) optimalChunks = 1;
        return (int) Math.max(16 * 1024 * 1024, fileSize / optimalChunks);
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
}
