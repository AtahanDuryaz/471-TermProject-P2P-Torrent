import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

//onstructs a discovery packet and sends it using UDP
//constructs the packet with an application-level TTL and broadcast flag

public class DiscoverySender {

    //broadcast to this port
    private static final int PORT = 50000;

    public static void main(String[] args) {
        int ttl = 5;                     // application-level TTL -> hop limit
        boolean broadcastFlag = true;    // this message is a broadcast discovery
        String peerId = "peer-123";      // some peer ID

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true); //allow sending to 255.255.255.255

            byte[] data = buildDiscoveryMessage(ttl, broadcastFlag, peerId);

            //send to local LAN broadcast address
            InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");

            DatagramPacket packet = new DatagramPacket(
                    data,
                    data.length,
                    broadcastAddress,
                    PORT
            );

            System.out.println("Sending discovery message...");
            System.out.println("TTL: " + ttl +
                    ", broadcastFlag: " + broadcastFlag +
                    ", peerId: " + peerId);

            socket.send(packet);

            System.out.println("Discovery message sent to " +
                    broadcastAddress.getHostAddress() + ":" + PORT);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //Message format
    //[0]      : flags (bit 0 = broadcast flag)
    //[1]      : TTL (0–255)
    //[2..]    : peerId as UTF-8 bytes

    private static byte[] buildDiscoveryMessage(int ttl, boolean broadcastFlag, String peerId) {
        byte flags = 0x00;
        if (broadcastFlag) {
            flags |= 0x01; // set bit 0
        }

        byte ttlByte = (byte) ttl;
        byte[] peerBytes = peerId.getBytes(StandardCharsets.UTF_8);

        byte[] msg = new byte[2 + peerBytes.length];
        msg[0] = flags;
        msg[1] = ttlByte;
        System.arraycopy(peerBytes, 0, msg, 2, peerBytes.length);

        return msg;
    }
}

