package org.app.backend;

import org.app.User;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.List;

public class Sender {
    private static final int LISTENING_PORT = 9000;
    private static final int CONNECTION_PORT = 9080;
    void sendRequest(){

    }

    public void peerListener(List<User> discoveredReceivers,boolean isListening ){
        try {
            System.out.println("sending peer " + discoveredReceivers.size());
            DatagramChannel channel = DatagramChannel.open();
            channel.bind(new InetSocketAddress(LISTENING_PORT));

            ByteBuffer buffer = ByteBuffer.allocate(1024);


            while (isListening) {
                System.out.printf("Listening on peer %d\n", LISTENING_PORT);
                SocketAddress address = channel.receive(buffer);
                if (address instanceof InetSocketAddress inetSocketAddress) {
                    String receiverIP = inetSocketAddress.getAddress().getHostAddress();
                    String receiverName = buffer.toString().trim();
                    synchronized (discoveredReceivers) {
                        if (!discoveredReceivers.contains(receiverIP)) {
                            discoveredReceivers.add(new User(receiverName, receiverIP));
                            System.out.println("Discovered receiver: " + receiverIP);
                        }
                    }
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }catch (IOException e){
            e.printStackTrace();
        }



    }

    public boolean connectionRequest(User receiver,String filesInfo){

        try(  Socket socket = new Socket(receiver.getIp(), CONNECTION_PORT);) {

            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer.println("REQUEST_TO_SEND:"+ receiver.getUsername() + filesInfo);
            System.out.println("Connection request sent to receiver.");
            String reply =  reader.readLine();
            if(reply.equals("Yes")){
                return true;
            }else if(reply.equals("No")){
                return false;
            }else
                throw new IOException("ConnectionError");


        }catch (IOException e){
           e.printStackTrace();
        }
        return false;
    }
}
