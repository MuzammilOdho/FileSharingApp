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
    private static final int BUFFER_SIZE = 8 * 1024 * 1024; // 8MB buffer size
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

                // Send file metadata
                metadataOut.writeLong(fileSize);
                metadataOut.writeInt(totalChunks);
                byte[] nameBytes = file.getName().getBytes(StandardCharsets.UTF_8);
                metadataOut.writeInt(nameBytes.length);
                metadataOut.write(nameBytes);
                metadataOut.flush();

                // Wait for ready signal
                String response = waitForResponse(metadataIn, 30000);
                if (!"READY".equals(response)) {
                    throw new IOException("Receiver not ready: " + response);
                }

                // Send file chunks
                sendFileChunks(receiverIP, file, totalChunks, optimalChunkSize, progressCallback);

                // Only for the last file, send the termination signal
                if (isLastFile) {
                    try (Socket completionSocket = new Socket(receiverIP, RECEIVER_PORT)) {
                        DataOutputStream completionOut = new DataOutputStream(completionSocket.getOutputStream());
                        completionOut.writeLong(-1); // Termination signal
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


    private void sendFileChunks(String receiverIP, File file, int totalChunks,
                                int optimalChunkSize, Consumer<Integer> progressCallback)
            throws Exception {
        ExecutorService chunkExecutor = Executors.newFixedThreadPool(
                Math.min(Runtime.getRuntime().availableProcessors(), totalChunks));
        CompletionService<Integer> completionService = new ExecutorCompletionService<>(chunkExecutor);

        try {
            // Submit chunk transfer tasks
            for (int i = 0; i < totalChunks; i++) {
                final int chunkIndex = i;
                long startPosition = (long) i * optimalChunkSize;
                int currentChunkSize = (int) Math.min(optimalChunkSize, file.length() - startPosition);

                completionService.submit(() -> {
                    transferChunk(receiverIP, file, chunkIndex, startPosition,
                            currentChunkSize, RECEIVER_PORT + 1 + chunkIndex);
                    return chunkIndex;
                });
            }

            // Track progress
            int completedChunks = 0;
            while (completedChunks < totalChunks) {
                Future<Integer> completedChunk = completionService.take();
                completedChunk.get();
                completedChunks++;
                int progress = (int) ((completedChunks * 100.0) / totalChunks);
                progressCallback.accept(progress);
            }
        } finally {
            chunkExecutor.shutdown();
            if (!chunkExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                chunkExecutor.shutdownNow();
            }
        }
    }

    private void transferChunk(String receiverIP, File file, int chunkIndex,
                               long startPosition, int currentChunkSize, int chunkPort)
            throws Exception {
        System.out.println("Connecting to chunk port " + chunkPort);
        Socket chunkSocket = new Socket(receiverIP, chunkPort);
        System.out.println("Connected for chunk " + chunkIndex);

        // Set socket options before sending data
        chunkSocket.setTcpNoDelay(true);
        chunkSocket.setReceiveBufferSize(BUFFER_SIZE);
        chunkSocket.setSendBufferSize(BUFFER_SIZE);
        chunkSocket.setPerformancePreferences(0, 1, 2);

        DataOutputStream chunkOut = new DataOutputStream(chunkSocket.getOutputStream());

        // Send chunk metadata
        chunkOut.writeInt(chunkIndex);
        chunkOut.writeLong(startPosition);
        chunkOut.writeInt(currentChunkSize);
        chunkOut.flush();

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
            chunkOut.flush();
            System.out.println("Completed sending chunk " + chunkIndex);
        }
    }

    private int calculateOptimalChunkSize(long fileSize) {
        if (fileSize < 16 * 1024 * 1024) {
            return (int) fileSize;
        }
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int optimalChunks = Math.min(availableProcessors * 2, (int)(fileSize / (16 * 1024 * 1024)));
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
