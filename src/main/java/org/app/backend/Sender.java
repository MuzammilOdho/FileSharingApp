package org.app.backend;

import org.app.User;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Sender {
    private volatile boolean isListening = true;

    public void setListening(boolean listening) {
        isListening = listening;
    }

    private static final int LISTENING_PORT = 9000;
    private static final int CONNECTION_PORT = 9080;
    private static final int RECEIVER_PORT = 9090;
    private static final int BUFFER_SIZE = 8192;
    void sendRequest() {

    }

    public void peerListener(List<User> discoveredReceivers) {
        try {
            System.out.println("Starting peer listener...");

            // Open Datagram Channel and Bind to Listening Port
            DatagramChannel channel = DatagramChannel.open();
            channel.bind(new InetSocketAddress(LISTENING_PORT));
            channel.configureBlocking(false);


            ByteBuffer buffer = ByteBuffer.allocate(1024);

            while (isListening) {
                System.out.printf("Listening on port %d...\n", LISTENING_PORT);
                buffer.clear();

                // Receive Data from a Sender
                SocketAddress address = channel.receive(buffer);
                buffer.flip();

                if (address instanceof InetSocketAddress inetSocketAddress) {
                    String receiverIP = inetSocketAddress.getAddress().getHostAddress();
                    String receiverName = StandardCharsets.UTF_8.decode(buffer).toString().trim();

                    synchronized (discoveredReceivers) {
                        boolean exists = discoveredReceivers.stream()
                                .anyMatch(user -> user.getIp().equals(receiverIP));

                        if (!exists) {
                            discoveredReceivers.add(new User(receiverName, receiverIP));
                            System.out.println("Discovered receiver: " + receiverName + " (" + receiverIP + ")");
                        }
                    }
                }
                try {

                Thread.sleep(1000); // Reduce CPU usage
                }catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Peer listener interrupted");
                }
            }

            System.out.println("Stopping peer listener...");
            channel.close(); // Close channel when done
        } catch (IOException e) {
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

    public void sendFile(String receiverIP, File file) {
        try (Socket socket = new Socket(receiverIP, RECEIVER_PORT);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             FileInputStream fis = new FileInputStream(file)) {

            // Send file name and size
            dos.writeUTF(file.getName());
            dos.writeLong(file.length());

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long totalBytesSent = 0;
            long fileSize = file.length();

            System.out.println("Sending " + file.getName());

            while ((bytesRead = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
                totalBytesSent += bytesRead;

                // Print progress
                int progress = (int) ((totalBytesSent * 100) / fileSize);
                System.out.print("\rProgress: " + progress + "%");
            }

            System.out.println("\nFile sent: " + file.getName());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

