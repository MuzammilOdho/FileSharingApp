package org.app.backend;

import javax.swing.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;


public class Receiver {
    private volatile boolean isReceiving = true;

    public void setReceiving(boolean receiving) {
        isReceiving = receiving;
    }

    private static final int RECEIVING_PORT = 9090;
    private static final int BUFFER_SIZE = 8192;
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

            channel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public void listenForConnectionRequests() {
        try (ServerSocket serverSocket = new ServerSocket(CONNECTION_PORT)) {
            serverSocket.setSoTimeout(2000); // 2-second timeout to check the isReceiving flag
            System.out.println("Listening for connection requests on port " + CONNECTION_PORT);

            while (isReceiving) {
                try {
                    Socket socket = serverSocket.accept(); //wait for any incoming connection for 2 second then check for isReceiving
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);

                    String requestMessage = reader.readLine();
                    int confirm = JOptionPane.showConfirmDialog(null, requestMessage,
                            "Incoming Connection Request", JOptionPane.YES_NO_OPTION);

                    if (confirm == JOptionPane.YES_OPTION) {
                        writer.println("YES");

                        isReceiving = false; // Stop broadcasting and accepting connections
                        System.out.println("Connection accepted. Stopping broadcast...");

                        break; // Exit loop after accepting connection

                    } else {
                        writer.println("NO");   // Reject connection and keep listening
                        System.out.println("Connection rejected.");
                    }
                    socket.close();
                } catch (SocketTimeoutException e) {
                    // Timeout occurred, check isReceiving flag again
                }
            }
            System.out.println("Stopping connection listener...");
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private static void receiveFile(Socket socket,String saveDirectory){
        try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            String fileName = dis.readUTF();
            long fileSize = dis.readLong();

            File receivedFile = new File(saveDirectory + fileName);

            // Prevent overwriting
            int count = 1;
            while (receivedFile.exists()) {
                String newFileName = fileName.replaceFirst("(\\.[^.]+)$", "_" + count + "$1");
                receivedFile = new File(saveDirectory + newFileName);
                count++;
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            try (FileOutputStream fos = new FileOutputStream(receivedFile)) {
                int bytesRead;
                long totalBytesRead = 0;

                while ((bytesRead = dis.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    if (totalBytesRead >= fileSize) break;
                }
                System.out.println("\nFile received: " + receivedFile.getAbsolutePath());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
