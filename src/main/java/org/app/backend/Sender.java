package org.app.backend;

import org.app.User;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

public class Sender {
    private volatile boolean isListening = true;

    public void setListening(boolean listening) {
        isListening = listening;
    }

    private static final int LISTENING_PORT = 9000;
    private static final int CONNECTION_PORT = 9080;
    private static final int RECEIVER_PORT = 9090;
    private static final int BUFFER_SIZE = 32768;
    void sendRequest() {

    }

    public void peerListener(List<User> discoveredReceivers, Consumer<User> onNewUser) {
        try {
            System.out.println("Starting peer listener...");

            try (DatagramChannel channel = DatagramChannel.open()) {
                channel.bind(new InetSocketAddress(LISTENING_PORT));
                channel.configureBlocking(false);

                ByteBuffer buffer = ByteBuffer.allocate(1024);

                while (isListening) {
                    buffer.clear();
                    SocketAddress address = channel.receive(buffer);
                    
                    if (address instanceof InetSocketAddress inetSocketAddress) {
                        buffer.flip();
                        String receiverIP = inetSocketAddress.getAddress().getHostAddress();
                        String receiverName = StandardCharsets.UTF_8.decode(buffer).toString().trim();

                        boolean exists = discoveredReceivers.stream()
                                .anyMatch(user -> user.getIp().equals(receiverIP));

                        if (!exists) {
                            User newUser = new User(receiverName, receiverIP);
                            onNewUser.accept(newUser);
                        }
                    }

                    Thread.sleep(100);
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

            // Send connection request
            writer.println(senderName + " wants to send you :" + fileInfo);

            // Wait for response (Yes or No)
            String response = reader.readLine();
            return "YES".equalsIgnoreCase(response);  // Proceed if accepted
        } catch (IOException e) {
            e.printStackTrace();
            return false; // Connection failed
        }
    }

    public void sendFile(String receiverIP, File file, Consumer<Integer> progressCallback) {
        try {
            System.out.println("Starting to send file: " + file.getName() + " (Size: " + file.length() + " bytes)");
            long fileSize = file.length();
            int optimalChunkSize = calculateOptimalChunkSize(fileSize);
            int totalChunks = (int) Math.ceil((double) fileSize / optimalChunkSize);
            
            System.out.println("Calculated chunks: Total=" + totalChunks + ", Size=" + optimalChunkSize + " bytes");
            
            // Send file metadata and wait for acknowledgment
            try (Socket metadataSocket = new Socket(receiverIP, RECEIVER_PORT)) {
                System.out.println("Connected to receiver for metadata on port " + RECEIVER_PORT);
                DataOutputStream metadataOut = new DataOutputStream(metadataSocket.getOutputStream());
                BufferedReader metadataIn = new BufferedReader(new InputStreamReader(metadataSocket.getInputStream()));
                
                // Send metadata
                metadataOut.writeLong(fileSize);
                byte[] nameBytes = file.getName().getBytes(StandardCharsets.UTF_8);
                metadataOut.writeInt(nameBytes.length);
                metadataOut.write(nameBytes);
                metadataOut.writeInt(totalChunks);
                metadataOut.flush();
                
                // Wait for receiver to be ready
                String response = metadataIn.readLine();
                if (!"READY".equals(response)) {
                    throw new IOException("Receiver not ready: " + response);
                }
                System.out.println("Receiver ready to accept chunks");
            }

            // Create thread pool for chunk transfers
            ExecutorService chunkExecutor = Executors.newFixedThreadPool(
                Math.min(Runtime.getRuntime().availableProcessors(), totalChunks));
            CompletionService<Integer> completionService = new ExecutorCompletionService<>(chunkExecutor);

            System.out.println("Starting chunk transfers...");
            // Submit chunk transfer tasks
            for (int i = 0; i < totalChunks; i++) {
                final int chunkIndex = i;
                long startPosition = (long) i * optimalChunkSize;
                int currentChunkSize = (int) Math.min(optimalChunkSize, fileSize - startPosition);

                completionService.submit(() -> {
                    try (Socket chunkSocket = new Socket(receiverIP, RECEIVER_PORT + 1 + chunkIndex)) {
                        System.out.println("Sending chunk " + chunkIndex + " on port " + (RECEIVER_PORT + 1 + chunkIndex));
                        DataOutputStream chunkOut = new DataOutputStream(chunkSocket.getOutputStream());
                        
                        // Send chunk metadata
                        chunkOut.writeInt(chunkIndex);
                        chunkOut.writeLong(startPosition);
                        chunkOut.writeInt(currentChunkSize);
                        
                        // Send chunk data
                        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                            raf.seek(startPosition);
                            byte[] buffer = new byte[BUFFER_SIZE];
                            int totalBytesRead = 0;
                            
                            while (totalBytesRead < currentChunkSize) {
                                int bytesRead = raf.read(buffer, 0, 
                                    Math.min(buffer.length, currentChunkSize - totalBytesRead));
                                if (bytesRead == -1) break;
                                chunkOut.write(buffer, 0, bytesRead);
                                totalBytesRead += bytesRead;
                            }
                            System.out.println("Completed sending chunk " + chunkIndex);
                        }
                        return chunkIndex;
                    }
                });
            }

            // Track progress
            int completedChunks = 0;
            while (completedChunks < totalChunks) {
                try {
                    Future<Integer> completedChunk = completionService.take();
                    completedChunks++;
                    
                    // Update progress
                    int progress = (int) ((completedChunks * 100.0) / totalChunks);
                    System.out.println("Progress: " + progress + "% (" + completedChunks + "/" + totalChunks + " chunks)");
                    SwingUtilities.invokeLater(() -> progressCallback.accept(progress));
                } catch (Exception e) {
                    System.err.println("Error tracking progress: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            chunkExecutor.shutdown();
            System.out.println("File sent successfully: " + file.getName());
            
            // Send completion signal
            try (Socket completionSocket = new Socket(receiverIP, RECEIVER_PORT)) {
                DataOutputStream completionOut = new DataOutputStream(completionSocket.getOutputStream());
                completionOut.writeLong(-1); // Signal for completion
                System.out.println("Sent completion signal");
            }
            
        } catch (Exception e) {
            System.err.println("Error in sendFile: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private int calculateOptimalChunkSize(long fileSize) {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        long optimalChunks = Math.min(availableProcessors * 2, fileSize / (1024 * 1024)); // Min 1MB per chunk
        return (int) Math.max(1024 * 1024, fileSize / Math.max(1, optimalChunks));
    }
}

