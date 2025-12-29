import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

//Listens for discovery packets, parses TTL and broadcast flag, and prints the result.

public class DiscoveryReceiver {

    private static final int PORT = 50000;

    public static void main(String[] args) {
        byte[] buffer = new byte[1024];

        System.out.println("DiscoveryReceiver listening on UDP port " + PORT + "...");

        try (DatagramSocket socket = new DatagramSocket(PORT)) {
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                System.out.println("Received packet from " +
                        packet.getAddress().getHostAddress() + ":" + packet.getPort());

                parseAndPrintDiscoveryMessage(packet.getData(), packet.getLength());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    
    //Parses the message format used in DiscoverySender
    //[0]      : flags (bit 0 = broadcast flag)
    //[1]      : TTL (0–255)
    //[2..len) : peerId as UTF-8
    
    private static void parseAndPrintDiscoveryMessage(byte[] data, int length) {
        if (length < 2) {
            System.out.println("Invalid discovery message (too short).");
            return;
        }

        byte flags = data[0];
        byte ttlByte = data[1];

        boolean broadcastFlag = (flags & 0x01) != 0;
        int ttl = ttlByte & 0xFF; // convert unsigned

        String peerId = "";
        if (length > 2) {
            peerId = new String(data, 2, length - 2, StandardCharsets.UTF_8);
        }

        System.out.println("         - Discovery Message -         ");
        System.out.println("    Broadcast flag : " + broadcastFlag);
        System.out.println("    TTL            : " + ttl);
        System.out.println("    Peer ID        : " + peerId);
    }
}

