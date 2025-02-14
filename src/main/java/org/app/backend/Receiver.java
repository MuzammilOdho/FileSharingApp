package org.app.backend;

import javax.swing.*;
import org.app.gui.TransferProgressDialog;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;

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
    // Use CHUNK_PORT for the persistent chunk connection.
    private static final int CHUNK_PORT = RECEIVING_PORT + 1;

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
            socket.setSoTimeout(15000);
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

                // Loop to receive multiple files until a termination signal is received.
                try (ServerSocket fileSocket = new ServerSocket(RECEIVING_PORT)) {
                    fileSocket.setSoTimeout(15000);
                    while (true) {
                        try (Socket transferSocket = fileSocket.accept()) {
                            transferSocket.setSoTimeout(15000);
                            SwingUtilities.invokeLater(() -> {
                                progressDialog.setVisible(true);
                                progressDialog.updateProgress(0);
                            });
                            // Process one file transfer.
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
        try {
            metadataSocket.setSoTimeout(15000);

            // Ensure save directory exists.
            File saveDir = new File(saveDirectory);
            if (!saveDir.exists() && !saveDir.mkdirs()) {
                throw new IOException("Failed to create save directory: " + saveDirectory);
            }

            DataInputStream metadataIn = new DataInputStream(new BufferedInputStream(metadataSocket.getInputStream()));
            PrintWriter metadataOut = new PrintWriter(new BufferedOutputStream(metadataSocket.getOutputStream()), true);

            System.out.println("Reading metadata");
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
            try (RandomAccessFile raf = new RandomAccessFile(receivedFile, "rw")) {
                raf.setLength(fileSize);
            }

            // Use a persistent chunk server on CHUNK_PORT.
            try (ServerSocket persistentChunkServer = new ServerSocket(CHUNK_PORT)) {
                persistentChunkServer.setSoTimeout(15000);
                // Send READY to indicate that the receiver is set up for chunks.
                metadataOut.println("READY");
                metadataOut.flush();

                // Accept one persistent connection for all chunk data.
                try (Socket chunkSocket = persistentChunkServer.accept();
                     DataInputStream chunkIn = new DataInputStream(new BufferedInputStream(chunkSocket.getInputStream()))) {
                    // First, read the total number of chunks.
                    int receivedTotalChunks = chunkIn.readInt();
                    if (receivedTotalChunks != totalChunks) {
                        throw new IOException("Chunk count mismatch. Expected " + totalChunks + " but got " + receivedTotalChunks);
                    }
                    // For each chunk, read metadata and then the data.
                    for (int i = 0; i < totalChunks; i++) {
                        int chunkIndex = chunkIn.readInt();
                        long startPosition = chunkIn.readLong();
                        int chunkSize = chunkIn.readInt();
                        System.out.println("Receiving chunk " + chunkIndex + " (Size: " + chunkSize + " bytes)");
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int totalBytesRead = 0;
                        try (RandomAccessFile raf = new RandomAccessFile(receivedFile, "rw")) {
                            raf.seek(startPosition);
                            while (totalBytesRead < chunkSize) {
                                int bytesRead = chunkIn.read(buffer, 0, Math.min(buffer.length, chunkSize - totalBytesRead));
                                if (bytesRead == -1) break;
                                raf.write(buffer, 0, bytesRead);
                                totalBytesRead += bytesRead;
                            }
                        }
                        int progress = (int) (((i + 1) * 100.0) / totalChunks);
                        SwingUtilities.invokeLater(() -> {
                            progressCallback.accept(progress);
                            statusCallback.accept("Receiving file: " + fileName + " (" + progress + "%)");
                        });
                        System.out.println("Completed receiving chunk " + chunkIndex);
                    }
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
        }
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
