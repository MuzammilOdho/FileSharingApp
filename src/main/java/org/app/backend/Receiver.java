package org.app.backend;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;


public class Receiver {
    private static final int CONNECTION_PORT = 9080;
    // The UDP port used for broadcast messages (choose one not in use)
    private static final int BROADCAST_PORT = 9000;
    // Message that identifies the peer as available (could include an ID, name, etc.)
    private static final String BROADCAST_IP = "255.255.255.255";

    public void peerListener() throws IOException {
        try {
            ServerSocket serverSocket = new ServerSocket(CONNECTION_PORT);
            Socket socket = serverSocket.accept();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            while (true) {
                String message = reader.readLine();
                int confirm = JOptionPane.showConfirmDialog(null, "Accept file transfer request?\n" + message,
                        "File Transfer Request", JOptionPane.YES_NO_OPTION);

                if (confirm == JOptionPane.YES_OPTION) {
                    System.out.println("Connection accepted. Ready to receive files.");

                    // Proceed with file transfer
                } else {
                    System.out.println("Connection rejected.");
                    continue;
                }
            }
        }catch (IOException e){
            e.printStackTrace();
        }finally {

        }
    }

    public void peerBroadcaster(String name,boolean isReceiving) {
        try {
            System.out.println("broadcasting peer " + name);
            DatagramChannel channel = DatagramChannel.open();
            channel.setOption(StandardSocketOptions.SO_BROADCAST, true);
            channel.bind(new InetSocketAddress(BROADCAST_PORT));

            ByteBuffer buffer = ByteBuffer.wrap(name.getBytes());
            InetSocketAddress broadcastAddress = new InetSocketAddress(BROADCAST_IP, BROADCAST_PORT);
            channel.connect(broadcastAddress);

            while (isReceiving) {
                System.out.printf("Broadcasting on peer %d\n", broadcastAddress.getPort());
                try {
                    buffer.rewind();
                    channel.send(buffer, broadcastAddress);
                    Thread.sleep(1000);

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}
