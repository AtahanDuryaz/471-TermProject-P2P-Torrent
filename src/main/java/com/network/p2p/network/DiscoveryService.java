package com.network.p2p.network;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Handles limited-scope UDP flooding for peer discovery.
 * Implements TTL (Time-To-Live) and Packet Forwarding.
 */
public class DiscoveryService {
    private static final Logger logger = Logger.getLogger(DiscoveryService.class.getName());
    private static final int DISCOVERY_PORT = 50000;
    private static final int DEFAULT_TTL = 3;
    private static final int PACKET_SIZE = 1024;

    private final String peerId;
    private DatagramSocket socket;
    private volatile boolean running = false;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private int fileServerPort = 50001; // Default, will be updated

    // Cache to detect duplicates for flooding control: MessageHash -> Timestamp
    private final ConcurrentHashMap<String, Long> seenMessages = new ConcurrentHashMap<>();

    // Callback for when a peer is found or message received
    public interface PeerDiscoveryListener {
        void onPeerFound(String peerId, String ipAddress, int port);

        void onMessageReceived(byte type, String content, String ip, int port);
    }

    private PeerDiscoveryListener listener;
    private String broadcastAddress = "255.255.255.255"; // Default broadcast, can be overridden

    public DiscoveryService(PeerDiscoveryListener listener) {
        // Use PEER_ID environment variable if set, otherwise generate random ID
        String envPeerId = System.getenv("PEER_ID");
        if (envPeerId != null && !envPeerId.trim().isEmpty()) {
            this.peerId = envPeerId.trim();
        } else {
            this.peerId = UUID.randomUUID().toString().substring(0, 8);
        }
        
        // Use BROADCAST_ADDRESS environment variable if set
        String envBroadcast = System.getenv("BROADCAST_ADDRESS");
        if (envBroadcast != null && !envBroadcast.trim().isEmpty()) {
            this.broadcastAddress = envBroadcast.trim();
            logger.info("Using custom broadcast address: " + broadcastAddress);
        }
        
        this.listener = listener;
    }
    
    public void setFileServerPort(int port) {
        this.fileServerPort = port;
        System.out.println("DEBUG DiscoveryService: FileServer port set to " + port);
    }
    
    public int getFileServerPort() {
        return this.fileServerPort;
    }

    public void start() {
        if (running)
            return;
        running = true;

        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
            logger.info("DiscoveryService started on port " + DISCOVERY_PORT + " with PeerID: " + peerId);

            // Start Listener Thread
            executor.submit(this::listenLoop);

            // Start Broadcaster Thread (Announce presence every 5 seconds)
            executor.submit(this::broadcastLoop);

            // Start Cleanup Thread for seenMessages cache
            executor.submit(this::cleanupLoop);

        } catch (SocketException e) {
            logger.severe("Failed to start DiscoveryService: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        executor.shutdownNow();
    }

    private void broadcastLoop() {
        while (running) {
            try {
                sendDiscoveryPacket(DEFAULT_TTL);
                Thread.sleep(5000); // Announce every 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[PACKET_SIZE];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                parseAndProcessPacket(packet);
            } catch (IOException e) {
                if (running)
                    logger.warning("Error receiving packet: " + e.getMessage());
            }
        }
    }

    private void cleanupLoop() {
        while (running) {
            try {
                Thread.sleep(60000); // Run every minute
                long now = System.currentTimeMillis();
                seenMessages.entrySet().removeIf(entry -> (now - entry.getValue()) > 10000); // Remove entries older
                                                                                             // than 10s
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void parseAndProcessPacket(DatagramPacket packet) {
        byte[] data = packet.getData();
        int length = packet.getLength();
        if (length < 2)
            return;

        int ttl = data[0] & 0xFF;
        byte type = data[1];

        // Extract content (skipping TTL and Type)
        String content = new String(data, 2, length - 2, StandardCharsets.UTF_8);

        System.out.println("DEBUG parseAndProcessPacket:");
        System.out.println("  - From IP: " + packet.getAddress().getHostAddress());
        System.out.println("  - From Port: " + packet.getPort());
        System.out.println("  - Type: " + type);
        System.out.println("  - Content: " + content);
        System.out.println("  - TTL: " + ttl);

        // 1. Message Identity Check (Flooding Loop Prevention)
        // We use a hash of the content + type to identify unique messages
        String msgHash = type + ":" + content;
        if (seenMessages.containsKey(msgHash)) {
            System.out.println("  - Already seen this message, skipping");
            return; // Already processed
        }
        seenMessages.put(msgHash, System.currentTimeMillis());

        // 2. Self Check
        if (content.contains("ID:" + peerId)) { // Assuming ID is part of content
            System.out.println("  - This is my own message, skipping");
            return;
        }

        System.out.println("  - Processing message...");

        // 3. Process Logic
        switch (type) {
            case Protocol.TYPE_HELLO:
                handleHello(content, packet.getAddress().getHostAddress(), packet.getPort());
                break;
            case Protocol.TYPE_QUERY_FILES:
                logger.info("Received QUERY: " + content);
                handleQuery(content, packet.getAddress(), packet.getPort());
                break;
            case Protocol.TYPE_RESPONSE_FILES:
                logger.info("Received RESPONSE: " + content);
                handleResponse(content, packet.getAddress().getHostAddress(), packet.getPort());
                break;
        }

        // 4. Forwarding (Limited-Scope Flooding)
        // We forward HELLO and QUERY, but typically RESPONSE is unicast (direct).
        // For simplicity in this project, we might flood queries but unicast responses.
        if (ttl > 1 && type != Protocol.TYPE_RESPONSE_FILES) {
            data[0] = (byte) (ttl - 1);
            forwardPacket(data, length);
        }
    }

    private void handleHello(String content, String ip, int port) {
        System.out.println("DEBUG DiscoveryService.handleHello called:");
        System.out.println("  - content: " + content);
        System.out.println("  - ip: " + ip);
        System.out.println("  - port: " + port);
        
        // CONTENT Format: ID:<peerId>:PORT:<fileServerPort>
        String[] parts = content.split(":");
        System.out.println("  - parts: " + java.util.Arrays.toString(parts));
        
        if (parts.length >= 2 && parts[0].equals("ID")) {
            String remoteId = parts[1];
            int fileServerPort = 50001; // Default
            if (parts.length >= 4 && parts[2].equals("PORT")) {
                try {
                    fileServerPort = Integer.parseInt(parts[3]);
                    System.out.println("  - Parsed fileServerPort: " + fileServerPort);
                } catch (NumberFormatException e) {
                    System.err.println("DEBUG: Failed to parse port from HELLO: " + content);
                }
            }
            System.out.println("DEBUG DiscoveryService.handleHello: Calling onPeerFound with:");
            System.out.println("  - remoteId: " + remoteId);
            System.out.println("  - ip: " + ip);
            System.out.println("  - fileServerPort: " + fileServerPort);
            
            if (listener != null) {
                listener.onPeerFound(remoteId, ip, fileServerPort);
            } else {
                System.err.println("DEBUG ERROR: listener is NULL!");
            }
        } else {
            System.err.println("DEBUG ERROR: Invalid HELLO format: " + content);
        }
    }

    private void handleQuery(String content, InetAddress requestorIp, int requestorPort) {
        if (listener != null) {
            listener.onMessageReceived(Protocol.TYPE_QUERY_FILES, content, requestorIp.getHostAddress(), requestorPort);
        }
    }

    private void handleResponse(String content, String ip, int port) {
        System.out.println("DEBUG DiscoveryService.handleResponse called:");
        System.out.println("  - content: " + content);
        System.out.println("  - ip: " + ip);
        System.out.println("  - port: " + port);
        
        // Content format: ID:<peerId>:QUERY_HIT:...
        // We need to extract peerId and notify PeerManager
        String[] parts = content.split(":", 3);
        if (parts.length >= 2 && parts[0].equals("ID")) {
            String remoteId = parts[1];
            System.out.println("  - remoteId from response: " + remoteId);
            
            // For RESPONSE, we don't have FileServer port in the message
            // But we should have already received HELLO from this peer
            // So just notify the listener without calling onPeerFound
            // The listener (PeerManager) will call onPeerFound with the correct info
        }
        
        if (listener != null) {
            listener.onMessageReceived(Protocol.TYPE_RESPONSE_FILES, content, ip, port);
        }
    }

    private void forwardPacket(byte[] data, int length) {
        try {
            InetAddress broadcastAddr = InetAddress.getByName(broadcastAddress);
            DatagramPacket packet = new DatagramPacket(data, length, broadcastAddr, DISCOVERY_PORT);
            socket.send(packet);
            logger.info("Forwarded packet to " + broadcastAddress);
        } catch (Exception e) {
            logger.warning("Failed to forward: " + e.getMessage());
        }
    }

    private void sendDiscoveryPacket(int ttl) {
        String msg = "ID:" + peerId + ":PORT:" + fileServerPort;
        broadcastPacket(Protocol.TYPE_HELLO, msg, ttl);
    }

    public void broadcastPacket(byte type, String content, int ttl) {
        try {
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            byte[] data = new byte[2 + contentBytes.length];
            data[0] = (byte) ttl;
            data[1] = type;
            System.arraycopy(contentBytes, 0, data, 2, contentBytes.length);

            InetAddress broadcastAddr = InetAddress.getByName(broadcastAddress);
            DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddr, DISCOVERY_PORT);
            socket.send(packet);
            logger.info("Broadcasted packet to " + broadcastAddress + " - Type: " + type + ", Content: " + content);
        } catch (Exception e) {
            logger.warning("Failed to broadcast: " + e.getMessage());
        }
    }

    public String search(String fileName) {
        // Implement search packet sending if needed
        return null;
    }

    public String getPeerId() {
        return peerId;
    }
}
