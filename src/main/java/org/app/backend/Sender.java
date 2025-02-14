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
    // Use CHUNK_PORT for persistent chunk transfer.
    private static final int CHUNK_PORT = RECEIVER_PORT + 1;
    // Tune the buffer size for your environment.
    private static final int BUFFER_SIZE = 8 * 1024 * 1024; // 8MB

    public void peerListener(java.util.List<User> discoveredReceivers, Consumer<User> onNewUser) {
        try (var channel = java.nio.channels.DatagramChannel.open()) {
            channel.bind(new InetSocketAddress(LISTENING_PORT));
            channel.configureBlocking(false);
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            System.out.println("Starting peer listener...");
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
                Thread.sleep(100);
            }
            System.out.println("Peer listener stopped.");
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
     * Sends a file to the receiver. The isLastFile flag indicates whether this is the last file in the session.
     * If so, a termination signal (fileSize == -1) is sent afterward.
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

                // Send metadata: file size, totalChunks, and filename.
                metadataOut.writeLong(fileSize);
                metadataOut.writeInt(totalChunks);
                byte[] nameBytes = file.getName().getBytes(StandardCharsets.UTF_8);
                metadataOut.writeInt(nameBytes.length);
                metadataOut.write(nameBytes);
                metadataOut.flush();

                // Wait for "READY" from the receiver.
                String response = waitForResponse(metadataIn, 30000);
                if (!"READY".equals(response)) {
                    throw new IOException("Receiver not ready: " + response);
                }

                // Send file chunks using a persistent chunk connection.
                sendFileChunks(receiverIP, file, totalChunks, optimalChunkSize, progressCallback);

                // Only if this is the last file, send a termination signal.
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
     * Opens a persistent SocketChannel on CHUNK_PORT and sends all chunks sequentially.
     */
    private void sendFileChunks(String receiverIP, File file, int totalChunks,
                                int optimalChunkSize, Consumer<Integer> progressCallback)
            throws Exception {
        try (SocketChannel chunkChannel = SocketChannel.open(new InetSocketAddress(receiverIP, CHUNK_PORT));
             DataOutputStream chunkOut = new DataOutputStream(
                     new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(chunkChannel)))) {
            // Send the total number of chunks as a header.
            chunkOut.writeInt(totalChunks);
            chunkOut.flush();

            // For each chunk, send metadata and then file data using zero-copy.
            for (int i = 0; i < totalChunks; i++) {
                long startPosition = (long) i * optimalChunkSize;
                int currentChunkSize = (int) Math.min(optimalChunkSize, file.length() - startPosition);

                // Send chunk metadata: index, startPosition, and chunk size.
                chunkOut.writeInt(i);
                chunkOut.writeLong(startPosition);
                chunkOut.writeInt(currentChunkSize);
                chunkOut.flush();

                // Use zero-copy via FileChannel.transferTo.
                try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                    long position = startPosition;
                    long count = currentChunkSize;
                    while (count > 0) {
                        long transferred = fileChannel.transferTo(position, count, chunkChannel);
                        if (transferred <= 0) break;
                        position += transferred;
                        count -= transferred;
                    }
                }
                chunkOut.flush();
                int progress = (int) (((i + 1) * 100.0) / totalChunks);
                progressCallback.accept(progress);
                System.out.println("Completed sending chunk " + i + " (" + progress + "%)");
            }
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
