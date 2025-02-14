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

public class Receiver {
    private volatile boolean isReceiving = true;

    public void setReceiving(boolean receiving) {
        isReceiving = receiving;
    }

    private static final int RECEIVING_PORT = 9090;
    private static final int BUFFER_SIZE = 8 * 1024 * 1024; // 8MB
    private static final int CONNECTION_PORT = 9080;
    private static final int BROADCAST_PORT = 9000;
    private static final String BROADCAST_IP = "255.255.255.255";
    // Each chunk will be received on port: RECEIVING_PORT + 1 + chunkIndex
    private static final int BASE_CHUNK_PORT = RECEIVING_PORT + 1;
    // Increase timeouts to 30 seconds to reduce premature timeout errors.
    private static final int SOCKET_TIMEOUT_MS = 30000;

    public void peerBroadcaster(String name) {
        try {
            System.out.println("Starting peer broadcaster: " + name);
            DatagramChannel channel = DatagramChannel.open();
            channel.setOption(StandardSocketOptions.SO_BROADCAST, true);
            ByteBuffer buffer = ByteBuffer.wrap(name.getBytes(StandardCharsets.UTF_8));
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
            channel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void listenForConnectionRequests(String saveDirectory, Consumer<Integer> progressCallback, Consumer<String> statusCallback) {
        try (ServerSocket serverSocket = new ServerSocket(CONNECTION_PORT)) {
            statusCallback.accept("Listening for connection requests on port " + CONNECTION_PORT);
            System.out.println("Listening for connection requests on port " + CONNECTION_PORT);
            while (isReceiving) {
                try {
                    Socket socket = serverSocket.accept();
                    handleIncomingConnection(socket, saveDirectory, progressCallback, statusCallback);
                } catch (IOException e) {
                    if (isReceiving) {
                        statusCallback.accept("Connection error: " + e.getMessage());
                    }
                }
            }
            System.out.println("Closing Listening for connection requests on port " + CONNECTION_PORT);
        } catch (IOException e) {
            e.printStackTrace();
            statusCallback.accept("Error: " + e.getMessage());
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

                // Stop further connections for this session.
                isReceiving = false;
                System.out.println("File receiver server started on port " + RECEIVING_PORT);

                JFrame parentFrame = new JFrame();
                TransferProgressDialog progressDialog = new TransferProgressDialog(parentFrame, "Receiving Files");
                progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
                progressDialog.setResizable(false);

                // Loop to receive multiple files until termination signal is received.
                try (ServerSocket fileSocket = new ServerSocket(RECEIVING_PORT)) {
                    fileSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                    while (true) {
                        try (Socket transferSocket = fileSocket.accept()) {
                            transferSocket.setSoTimeout(SOCKET_TIMEOUT_MS);

                            // Process one file.
                            boolean terminated = receiveFile(transferSocket, saveDirectory,
                                    progress -> SwingUtilities.invokeLater(() -> {
                                        progressDialog.updateProgress(progress);
                                        System.out.println("Progress: " + progress + "%");
                                    }),
                                    status -> SwingUtilities.invokeLater(() -> {
                                        progressDialog.setStatus(status);
                                        System.out.println("Status: " + status);
                                    })
                            );

                            SwingUtilities.invokeLater(() -> {
                                progressDialog.setVisible(true);
                                progressDialog.updateProgress(0);
                            });

                            if (terminated) {
                                System.out.println("Received termination signal");
                                break;
                            }
                        } catch (Exception e) {
                            System.err.println("Error in file transfer: " + e.getMessage());
                            break;
                        }
                    }
                    SwingUtilities.invokeLater(() -> {
                        progressDialog.setCloseable(true);
                        progressDialog.dispose();
                        parentFrame.dispose();
                        JOptionPane.showMessageDialog(null,
                                "All files received successfully!",
                                "Transfer Complete",
                                JOptionPane.INFORMATION_MESSAGE);
                    });
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
        ServerSocket[] chunkServers = null;
        FileChannel fileChannel = null;
        try {
            metadataSocket.setSoTimeout(SOCKET_TIMEOUT_MS);

            // Ensure save directory exists.
            File saveDir = new File(saveDirectory);
            if (!saveDir.exists() && !saveDir.mkdirs()) {
                throw new IOException("Failed to create save directory: " + saveDirectory);
            }

            DataInputStream metadataIn = new DataInputStream(new BufferedInputStream(metadataSocket.getInputStream()));
            PrintWriter metadataOut = new PrintWriter(new BufferedOutputStream(metadataSocket.getOutputStream()), true);

            System.out.println("Reading metadata");
            
            // Add check for available bytes before reading
            if (metadataIn.available() <= 0) {
                // This is likely a connection test or cleanup connection, not an error
                System.out.println("No metadata available, skipping connection");
                return false;
            }

            long fileSize = metadataIn.readLong();
            // Termination signal: fileSize == -1.
            if (fileSize == -1) {
                System.out.println("Received termination signal");
                return true;
            }
            if (fileSize <= 0) {
                throw new IOException("Invalid file size: " + fileSize);
            }
            int totalChunks = metadataIn.readInt();
            if (totalChunks <= 0) {
                throw new IOException("Invalid chunk count: " + totalChunks);
            }
            int nameLength = metadataIn.readInt();
            if (nameLength <= 0 || nameLength > 1024) {
                throw new IOException("Invalid filename length: " + nameLength);
            }
            byte[] nameBytes = new byte[nameLength];
            metadataIn.readFully(nameBytes);
            String fileName = new String(nameBytes, StandardCharsets.UTF_8);
            if (!isValidFileName(fileName)) {
                throw new IOException("Invalid filename: " + fileName);
            }

            System.out.println("Receiving file: " + fileName + " (Size: " + fileSize + " bytes, Chunks: " + totalChunks + ")");
            File receivedFile = getUniqueFile(new File(saveDirectory, fileName));
            
            // Open a single FileChannel for the entire transfer
            fileChannel = FileChannel.open(receivedFile.toPath(), 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE,
                StandardOpenOption.READ);
            fileChannel.truncate(fileSize);

            // Create chunk servers
            chunkServers = new ServerSocket[totalChunks];
            List<CompletableFuture<Integer>> chunkFutures = new ArrayList<>();
            
            for (int i = 0; i < totalChunks; i++) {
                int port = RECEIVING_PORT + 1 + i;
                ServerSocket ss = new ServerSocket(port);
                ss.setSoTimeout(SOCKET_TIMEOUT_MS);
                chunkServers[i] = ss;
                
                // Pass FileChannel to receiveChunk
                chunkFutures.add(receiveChunk(ss, fileChannel, i));
            }
            
            // Send READY signal
            metadataOut.println("READY");
            metadataOut.flush();
            
            // Wait for all chunks and track progress
            int completedChunks = 0;
            for (CompletableFuture<Integer> future : chunkFutures) {
                try {
                    future.get();
                    completedChunks++;
                    int progress = (int) ((completedChunks * 100.0) / totalChunks);
                    progressCallback.accept(progress);
                    statusCallback.accept("Receiving file: " + fileName + " (" + progress + "%)");
                } catch (Exception e) {
                    throw new IOException("Error receiving chunk: " + e.getMessage(), e);
                }
            }
            
            System.out.println("File received successfully: " + fileName);
            statusCallback.accept("File received successfully: " + fileName);
            try {
                metadataIn.close();
                metadataOut.close();
            } catch (IOException e) {
                System.err.println("Error closing metadata streams: " + e.getMessage());
            }
            return false;
        } catch (EOFException e) {
            // This is likely a connection test or cleanup connection, not an error
            System.out.println("Connection closed by sender (possibly a test connection)");
            return false;
        } catch (Exception e) {
            String errorMsg = "Error receiving file: " + e.getMessage();
            System.err.println(errorMsg);
            e.printStackTrace();
            statusCallback.accept(errorMsg);
            try {
                metadataSocket.close();
            } catch (IOException closeError) {
                System.err.println("Error closing socket: " + closeError.getMessage());
            }
            return false;
        } finally {
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

    private CompletableFuture<Integer> receiveChunk(ServerSocket ss, FileChannel fileChannel, int expectedChunkIndex) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket chunkSocket = ss.accept();
                 DataInputStream chunkIn = new DataInputStream(new BufferedInputStream(chunkSocket.getInputStream()))) {
                
                // Read chunk metadata
                int chunkIndex = chunkIn.readInt();
                long startPosition = chunkIn.readLong();
                int chunkSize = chunkIn.readInt();
                int totalChunks = chunkIn.readInt();
                
                // Validate metadata
                if (chunkIndex != expectedChunkIndex || chunkSize <= 0 || startPosition < 0) {
                    throw new IOException("Invalid chunk metadata: index=" + chunkIndex + 
                        ", size=" + chunkSize + ", position=" + startPosition);
                }
                
                System.out.println("Receiving chunk " + chunkIndex + " of " + totalChunks + 
                    " (size=" + chunkSize + ", position=" + startPosition + ")");
                
                // Use heap ByteBuffer instead of direct for reading from InputStream
                ByteBuffer buffer = ByteBuffer.allocate(Math.min(BUFFER_SIZE, chunkSize));
                int totalBytesRead = 0;
                
                synchronized (fileChannel) {
                    while (totalBytesRead < chunkSize) {
                        buffer.clear();
                        int bytesToRead = Math.min(buffer.capacity(), chunkSize - totalBytesRead);
                        int bytesRead = chunkIn.read(buffer.array(), 0, bytesToRead);
                        
                        if (bytesRead == -1) {
                            break;
                        }
                        
                        buffer.limit(bytesRead);
                        buffer.position(0);
                        
                        // Write buffer to file at correct position
                        while (buffer.hasRemaining()) {
                            fileChannel.write(buffer, startPosition + totalBytesRead);
                        }
                        totalBytesRead += bytesRead;
                    }
                }
                
                if (totalBytesRead != chunkSize) {
                    throw new IOException("Incomplete chunk data: expected=" + chunkSize + 
                        ", received=" + totalBytesRead);
                }
                
                return chunkIndex;
            } catch (IOException e) {
                throw new CompletionException("Error receiving chunk: " + e.getMessage(), e);
            }
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
}
