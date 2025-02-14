package org.app.backend;

import javax.swing.*;

import org.app.gui.TransferProgressDialog;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;


public class Receiver {
    private volatile boolean isReceiving = true;

    public void setReceiving(boolean receiving) {
        isReceiving = receiving;
    }

    private static final int RECEIVING_PORT = 9090;
    private static final int BUFFER_SIZE = 8 * 1024 * 1024; // 8MB buffer size
    private static final int CONNECTION_PORT = 9080;
    // The UDP port used for broadcast messages (choose one not in use)
    private static final int BROADCAST_PORT = 9000;
    // Message that identifies the peer as available (could include an ID, name, etc.)
    private static final String BROADCAST_IP = "255.255.255.255";


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
                    Thread.sleep(1000); // Prevent spamming network
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
        try {
            ServerSocket serverSocket = new ServerSocket(CONNECTION_PORT);
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
            serverSocket.close();
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

                System.out.println("Stopped broadcasting and receiving new connections");
                isReceiving = false;
                System.out.println("File receiver server started on port " + RECEIVING_PORT);

                JFrame parentFrame = new JFrame();
                TransferProgressDialog progressDialog = new TransferProgressDialog(
                        parentFrame, "Receiving Files");
                progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

                try (ServerSocket fileSocket = new ServerSocket(RECEIVING_PORT)) {
                    fileSocket.setSoTimeout(15000);
                    while (true) {
                        try (Socket transferSocket = fileSocket.accept()) {
                            transferSocket.setSoTimeout(15000);
                            SwingUtilities.invokeLater(() -> {
                                progressDialog.setVisible(true);
                                progressDialog.updateProgress(0);
                            });

                            boolean completed = receiveFile(transferSocket, saveDirectory,
                                    progress -> SwingUtilities.invokeLater(() -> {
                                        progressDialog.updateProgress(progress);
                                        System.out.println("Progress: " + progress + "%");
                                    }),
                                    status -> SwingUtilities.invokeLater(() -> {
                                        progressDialog.setStatus(status);
                                        System.out.println("Status: " + status);
                                    })
                            );

                            if (completed) {
                                System.out.println("Received termination signal");
                                break;
                            }

                        } catch (Exception e) {
                            System.err.println("Error in file transfer: " + e.getMessage());
                            break;
                        }
                    }
                    // Update UI after completion
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

    public boolean receiveFile(Socket metadataSocket, String saveDirectory,
                               Consumer<Integer> progressCallback,
                               Consumer<String> statusCallback) {
        try {
            metadataSocket.setSoTimeout(15000);

            // Create save directory if it doesn't exist
            File saveDir = new File(saveDirectory);
            if (!saveDir.exists()) {
                if (!saveDir.mkdirs()) {
                    throw new IOException("Failed to create save directory: " + saveDirectory);
                }
            }

            DataInputStream metadataIn = new DataInputStream(
                    new BufferedInputStream(metadataSocket.getInputStream()));
            PrintWriter metadataOut = new PrintWriter(
                    new BufferedOutputStream(metadataSocket.getOutputStream()), true);

            System.out.println("Reading metadata");
            long fileSize = metadataIn.readLong();

            if (fileSize == -1) {
                System.out.println("Received completion signal");
                isReceiving = false;
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

            ServerSocket[] chunkServers = new ServerSocket[totalChunks];
            try {
                for (int i = 0; i < totalChunks; i++) {
                    chunkServers[i] = new ServerSocket(RECEIVING_PORT + 1 + i);
                    System.out.println("Created chunk server " + i + " on port " + (RECEIVING_PORT + 1 + i));
                }

                metadataOut.println("READY");
                metadataOut.flush();

                ExecutorService chunkExecutor = Executors.newFixedThreadPool(
                        Math.min(Runtime.getRuntime().availableProcessors(), totalChunks));
                CompletionService<Integer> completionService = new ExecutorCompletionService<>(chunkExecutor);

                System.out.println("Starting to receive chunks...");
                for (int i = 0; i < totalChunks; i++) {
                    final int chunkIndex = i;
                    completionService.submit(() -> {
                        try (ServerSocket chunkServer = chunkServers[chunkIndex]) {
                            System.out.println("Waiting for chunk " + chunkIndex + " on port " + (RECEIVING_PORT + 1 + chunkIndex));
                            Socket chunkSocket = chunkServer.accept();
                            chunkSocket.setTcpNoDelay(true);
                            chunkSocket.setReceiveBufferSize(BUFFER_SIZE);
                            chunkSocket.setSendBufferSize(BUFFER_SIZE);
                            chunkSocket.setPerformancePreferences(0, 1, 2);
                            DataInputStream chunkIn = new DataInputStream(chunkSocket.getInputStream());

                            int receivedChunkIndex = chunkIn.readInt();
                            long startPosition = chunkIn.readLong();
                            int chunkSize = chunkIn.readInt();

                            System.out.println("Receiving chunk " + chunkIndex + " (Size: " + chunkSize + " bytes)");

                            byte[] buffer = new byte[BUFFER_SIZE];
                            int totalBytesRead = 0;

                            synchronized (receivedFile) {
                                RandomAccessFile raf = new RandomAccessFile(receivedFile, "rw");
                                raf.seek(startPosition);
                                while (totalBytesRead < chunkSize) {
                                    int bytesRead = chunkIn.read(buffer, 0,
                                            Math.min(buffer.length, chunkSize - totalBytesRead));
                                    if (bytesRead == -1) break;
                                    raf.write(buffer, 0, bytesRead);
                                    totalBytesRead += bytesRead;
                                }
                            }

                            System.out.println("Completed receiving chunk " + chunkIndex);
                            return receivedChunkIndex;
                        } catch (Exception e) {
                            System.err.println("Error receiving chunk " + chunkIndex + ": " + e.getMessage());
                            throw e;
                        }
                    });
                }

                int completedChunks = 0;
                while (completedChunks < totalChunks) {
                    try {
                        Future<Integer> completedChunk = completionService.take();
                        completedChunk.get();
                        completedChunks++;

                        int progress = (int) ((completedChunks * 100.0) / totalChunks);
                        System.out.println("Progress: " + progress + "% (" + completedChunks + "/" + totalChunks + " chunks)");
                        final int finalProgress = progress;
                        SwingUtilities.invokeLater(() -> {
                            progressCallback.accept(finalProgress);
                            statusCallback.accept("Receiving file: " + fileName + " (" + finalProgress + "%)");
                        });
                    } catch (Exception e) {
                        System.err.println("Error tracking progress: " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                chunkExecutor.shutdown();
                System.out.println("File received successfully: " + fileName);
                statusCallback.accept("File received successfully: " + fileName);

                try {
                    metadataIn.close();
                    metadataOut.close();
                } catch (IOException e) {
                    System.err.println("Error closing metadata streams: " + e.getMessage());
                }

                return false;
            } finally {
                for (ServerSocket server : chunkServers) {
                    if (server != null) {
                        try {
                            server.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
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