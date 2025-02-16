package org.app.backend;

import javax.swing.*;
import org.app.gui.TransferProgressDialog;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class Receiver {
    private volatile boolean isReceiving = true;
    private volatile boolean isAcceptingConnections = true;
    private Consumer<String> statusCallback;
    private ServerSocket[] chunkServers;
    private Socket currentSocket;
    private ServerSocket currentServerSocket;

    public void setReceiving(boolean receiving) {
        isReceiving = receiving;
    }

    public void setAcceptingConnections(boolean acceptingConnections) {
        isAcceptingConnections = acceptingConnections;
    }

    private static final int RECEIVING_PORT = 9090;
    private static final int BUFFER_SIZE = 8 * 1024 * 1024; // 8MB
    private static final int CHUNK_SIZE = 64 * 1024 * 1024; // 64MB chunks to match Sender
    private static final int CONNECTION_PORT = 9080;
    private static final int BROADCAST_PORT = 9000;
    private static final String BROADCAST_IP = "255.255.255.255";
    // Each chunk will be received on port: RECEIVING_PORT + 1 + chunkIndex
    private static final int BASE_CHUNK_PORT = RECEIVING_PORT + 1;
    // Increase timeouts to 30 seconds to reduce premature timeout errors.
    private static final int SOCKET_TIMEOUT_MS = 30000;

    public void peerBroadcaster(String name) {
        try (DatagramChannel channel = DatagramChannel.open();) {
            channel.setOption(StandardSocketOptions.SO_BROADCAST, true);
            ByteBuffer buffer = ByteBuffer.wrap(name.getBytes(StandardCharsets.UTF_8));
            System.out.println("Starting peer broadcaster: " + name);

            InetSocketAddress broadcastAddress = new InetSocketAddress(BROADCAST_IP, BROADCAST_PORT);
            while (isReceiving) {
                System.out.printf("Broadcasting on port %d...\n", BROADCAST_PORT);
                buffer.rewind();
                channel.send(buffer, broadcastAddress);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Broadcast interrupted");
                    break;
                }
            }
            System.out.println("Peer broadcaster stopped");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void listenForConnectionRequests(String saveDirectory, Consumer<Integer> progressCallback, Consumer<String> statusCallback) {
        this.statusCallback = statusCallback;
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(CONNECTION_PORT);
            this.currentServerSocket = serverSocket;
            serverSocket.setSoTimeout(1000); // 1 second timeout for checking flags
            log("Listening for connection requests on port " + CONNECTION_PORT);
            
            while (isAcceptingConnections && isReceiving) {  // Check both flags
                try {
                    Socket socket = serverSocket.accept();
                    if (!isAcceptingConnections || !isReceiving) {
                        // Double-check flags after accept succeeds
                        try {
                            socket.close();
                        } catch (IOException e) {
                            // Ignore close errors
                        }
                        break;
                    }
                    
                    this.currentSocket = socket;
                    handleIncomingConnection(socket, saveDirectory, progressCallback, statusCallback);
                } catch (SocketTimeoutException e) {
                    // Normal timeout, check flags and continue
                    if (!isAcceptingConnections || !isReceiving) {
                        break;
                    }
                } catch (IOException e) {
                    if (isAcceptingConnections && isReceiving) {
                        log("Connection error: " + e.getMessage());
                    }
                }
            }
            
            log("Stopped listening for connection requests");
        } catch (IOException e) {
            log("Error starting connection listener: " + e.getMessage());
        } finally {
            // Ensure proper cleanup
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                    log("Closed connection listener socket");
                } catch (IOException e) {
                    log("Error closing connection listener: " + e.getMessage());
                }
            }
            this.currentServerSocket = null;
        }
    }

    private void handleIncomingConnection(Socket socket, String saveDirectory,
                                          Consumer<Integer> progressCallback,
                                          Consumer<String> statusCallback) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);

            String requestMessage = reader.readLine();
            statusCallback.accept("Received connection request");
            System.out.println("Received connection request: " + requestMessage);

            int confirm = JOptionPane.showConfirmDialog(null, requestMessage,
                    "Incoming Connection Request", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                writer.println("YES");
                statusCallback.accept("Connection accepted. Waiting for sender...");
                System.out.println("Connection accepted. Waiting for sender...");

                // Stop accepting new connections but allow current transfer
                isAcceptingConnections = false;
                System.out.println("File receiver server started on port " + RECEIVING_PORT);

                // Loop to receive multiple files until termination signal is received.
                try (ServerSocket fileSocket = new ServerSocket(RECEIVING_PORT)) {
                    fileSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                    while (isReceiving) {
                        try (Socket transferSocket = fileSocket.accept()) {
                            transferSocket.setSoTimeout(SOCKET_TIMEOUT_MS);

                            // Process one file.
                            boolean terminated = receiveFile(transferSocket, saveDirectory,
                                    progress -> {
                                        progressCallback.accept(progress);
                                        System.out.println("Progress: " + progress + "%");
                                    },
                                    status -> {
                                        statusCallback.accept(status);
                                        System.out.println("Status: " + status);
                                    }
                            );

                            if (terminated) {
                                System.out.println("Received termination signal");
                                break;
                            }
                        } catch (Exception e) {
                            if (isReceiving) {
                                System.err.println("Error in file transfer: " + e.getMessage());
                            }
                            break;
                        }
                    }
                    if (isReceiving) {
                        JOptionPane.showMessageDialog(null,
                                "All files received successfully!",
                                "Transfer Complete",
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception e) {
                    System.err.println("Error in file receiver server: " + e.getMessage());
                }
            } else {
                writer.println("NO");
                statusCallback.accept("Connection rejected.");
            }
        } catch (IOException e) {
            statusCallback.accept("Error handling connection: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Receives one file over the metadata connection.
     * Returns true if a termination signal (fileSize == -1) is received.
     */
    public boolean receiveFile(Socket metadataSocket, String saveDirectory,
                               Consumer<Integer> progressCallback,
                               Consumer<String> statusCallback) {
        this.chunkServers = null;
        FileChannel fileChannel = null;
        
        try {
            metadataSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            
            DataInputStream metadataIn = new DataInputStream(
                new BufferedInputStream(metadataSocket.getInputStream()));
            PrintWriter metadataOut = new PrintWriter(
                new BufferedOutputStream(metadataSocket.getOutputStream()), true);

            log("Reading file metadata...");
            
            long fileSize = metadataIn.readLong();
            if (fileSize == -1) {
                log("Received termination signal");
                return true;
            }
            
            int totalChunks = metadataIn.readInt();
            int nameLength = metadataIn.readInt();
            byte[] nameBytes = new byte[nameLength];
            metadataIn.readFully(nameBytes);
            String fileName = new String(nameBytes, StandardCharsets.UTF_8);

            log(String.format("Receiving file: %s (Size: %s, Chunks: %d)", 
                fileName, formatFileSize(fileSize), totalChunks));

            // Create file and prepare chunk servers before sending READY
            File receivedFile = getUniqueFile(new File(saveDirectory, fileName));
            log("Saving to: " + receivedFile.getAbsolutePath());
            
            fileChannel = FileChannel.open(receivedFile.toPath(), 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE,
                StandardOpenOption.READ);
            fileChannel.truncate(fileSize);

            // Create chunk servers
            chunkServers = new ServerSocket[totalChunks];
            for (int i = 0; i < totalChunks; i++) {
                if (!isReceiving) {
                    throw new IOException("Transfer cancelled by user");
                }
                int port = RECEIVING_PORT + 1 + i;
                ServerSocket ss = new ServerSocket(port);
                ss.setSoTimeout(SOCKET_TIMEOUT_MS);
                chunkServers[i] = ss;
                log("Created chunk server " + i + " on port " + port);
            }
            
            // Send READY signal with proper flush
            log("Sending READY signal to sender");
            metadataOut.println("READY");
            metadataOut.flush();
            
            // Track completed chunks
            AtomicInteger completedChunks = new AtomicInteger(0);
            
            List<CompletableFuture<Integer>> chunkFutures = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                final int chunkIndex = i;
                CompletableFuture<Integer> future = receiveChunk(chunkServers[i], fileChannel, i)
                    .thenApply(index -> {
                        int completed = completedChunks.incrementAndGet();
                        int progress = (int)((completed * 100.0) / totalChunks);
                        progressCallback.accept(progress);
                        statusCallback.accept(String.format("Received chunk %d/%d", completed, totalChunks));
                        return index;
                    });
                chunkFutures.add(future);
            }
            
            // Wait for all chunks with timeout
            try {
                CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]))
                    .get(SOCKET_TIMEOUT_MS * totalChunks, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new IOException("Failed to receive all chunks: " + e.getMessage(), e);
            }
            
            log("File received successfully: " + fileName);
            return false;
        } catch (Exception e) {
            String errorMsg = "Error receiving file: " + e.getMessage();
            log(errorMsg);
            if (e.getMessage().contains("Transfer cancelled")) {
                statusCallback.accept("Transfer cancelled");
            } else {
                statusCallback.accept("Error: " + errorMsg);
            }
            throw new RuntimeException(e);
        } finally {
            closeResources(fileChannel);
        }
    }

    private CompletableFuture<Integer> receiveChunk(ServerSocket ss, FileChannel fileChannel, int expectedChunkIndex) {
        return CompletableFuture.supplyAsync(() -> {
            int retryCount = 0;
            int maxRetries = 3;
            
            while (retryCount < maxRetries) {
                try (Socket chunkSocket = ss.accept()) {
                    // Configure socket
                    chunkSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                    chunkSocket.setReceiveBufferSize(BUFFER_SIZE);
                    
                    DataInputStream chunkIn = new DataInputStream(
                        new BufferedInputStream(chunkSocket.getInputStream()));
                    
                    // Read and validate chunk metadata
                    int chunkIndex = chunkIn.readInt();
                    long startPosition = chunkIn.readLong();
                    int chunkSize = chunkIn.readInt();
                    int totalChunks = chunkIn.readInt();
                    
                    if (chunkIndex != expectedChunkIndex || chunkSize <= 0 || startPosition < 0) {
                        throw new IOException(String.format(
                            "Invalid chunk metadata: index=%d (expected %d), size=%d, position=%d",
                            chunkIndex, expectedChunkIndex, chunkSize, startPosition));
                    }
                    
                    // Use heap ByteBuffer with timeout monitoring
                    ByteBuffer buffer = ByteBuffer.allocate(Math.min(BUFFER_SIZE, chunkSize));
                    int totalBytesRead = 0;
                    long transferStartTime = System.currentTimeMillis();
                    int stallCount = 0;
                    
                    while (totalBytesRead < chunkSize && isReceiving) {
                        // Check for transfer stall/timeout
                        if (System.currentTimeMillis() - transferStartTime > SOCKET_TIMEOUT_MS) {
                            throw new IOException("Chunk transfer timeout");
                        }
                        
                        buffer.clear();
                        int bytesToRead = Math.min(buffer.capacity(), chunkSize - totalBytesRead);
                        int bytesRead = chunkIn.read(buffer.array(), 0, bytesToRead);
                        
                        if (bytesRead == -1) {
                            throw new IOException("Unexpected end of stream");
                        }
                        
                        buffer.limit(bytesRead);
                        buffer.position(0);
                        
                        // Write to file with position tracking
                        synchronized (fileChannel) {
                            while (buffer.hasRemaining()) {
                                int written = fileChannel.write(buffer, startPosition + totalBytesRead);
                                if (written == 0) {
                                    stallCount++;
                                    if (stallCount > 100) {
                                        throw new IOException("Write operation stalled");
                                    }
                                    Thread.sleep(10);
                                } else {
                                    stallCount = 0;
                                    totalBytesRead += written;
                                    transferStartTime = System.currentTimeMillis(); // Reset timeout
                                }
                            }
                        }
                    }
                    
                    if (totalBytesRead != chunkSize) {
                        throw new IOException(String.format(
                            "Incomplete chunk transfer: received %d of %d bytes",
                            totalBytesRead, chunkSize));
                    }
                    
                    return chunkIndex;
                } catch (Exception e) {
                    retryCount++;
                    if (retryCount >= maxRetries) {
                        throw new CompletionException("Failed to receive chunk after " + maxRetries + 
                            " attempts: " + e.getMessage(), e);
                    }
                    // Wait before retry
                    try {
                        Thread.sleep(1000 * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException("Interrupted during retry wait", ie);
                    }
                }
            }
            throw new CompletionException("Exceeded maximum retries", null);
        });
    }

    private boolean isValidFileName(String fileName) {
        return fileName != null &&
                !fileName.isEmpty() &&
                !fileName.contains("..") &&
                !fileName.contains("/") &&
                !fileName.contains("\\") &&
                fileName.matches("^[^<>:\"\\\\|?*]+$");
    }

    private File getUniqueFile(File file) {
        if (!file.exists()) return file;
        String name = file.getName();
        String baseName = name.replaceFirst("[.][^.]+$", "");
        String extension = name.substring(name.lastIndexOf('.'));
        File parent = file.getParentFile();
        int count = 1;
        File newFile;
        do {
            newFile = new File(parent, baseName + "_" + count++ + extension);
        } while (newFile.exists());
        return newFile;
    }

    private void log(String message) {
        if (statusCallback != null) {
            statusCallback.accept(message);
        }
        System.out.println(message);
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        int z = (63 - Long.numberOfLeadingZeros(size)) / 10;
        return String.format("%.1f %sB", (double)size / (1L << (z*10)), " KMGTPE".charAt(z));
    }

    // Add this method to handle cleanup when stopping
    public void stopReceiving() {
        // Set both flags first to prevent new connections
        isReceiving = false;
        isAcceptingConnections = false;
        log("Stopping receiver...");
        
        // Create a list of resources to close
        List<AutoCloseable> resourcesToClose = new ArrayList<>();
        
        // Add all resources that need to be closed
        if (chunkServers != null) {
            for (ServerSocket server : chunkServers) {
                if (server != null && !server.isClosed()) {
                    resourcesToClose.add(server);
                }
            }
        }
        
        if (currentSocket != null && !currentSocket.isClosed()) {
            resourcesToClose.add(currentSocket);
        }
        
        if (currentServerSocket != null && !currentServerSocket.isClosed()) {
            resourcesToClose.add(currentServerSocket);
        }
        
        // Close all resources
        for (AutoCloseable resource : resourcesToClose) {
            try {
                resource.close();
                if (resource instanceof ServerSocket) {
                    log("Closed server socket on port " + ((ServerSocket) resource).getLocalPort());
                } else {
                    log("Closed socket connection");
                }
            } catch (Exception e) {
                log("Error closing resource: " + e.getMessage());
            }
        }
        
        // Clear references
        chunkServers = null;
        currentSocket = null;
        currentServerSocket = null;
        
        log("Receiver stopped successfully");
    }

    private void closeResources(FileChannel fileChannel) {
        if (fileChannel != null) {
            try {
                fileChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (chunkServers != null) {
            for (ServerSocket ss : chunkServers) {
                if (ss != null && !ss.isClosed()) {
                    try {
                        ss.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
