package com.network.p2p.managers;

import com.network.p2p.network.DiscoveryService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PeerManager implements DiscoveryService.PeerDiscoveryListener {

    public static class PeerInfo {
        public String id;
        public String ip;
        public int port;
        public long lastSeen;

        public PeerInfo(String id, String ip, int port) {
            this.id = id;
            this.ip = ip;
            this.port = port;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    private final Map<String, PeerInfo> knownPeers = new ConcurrentHashMap<>();
    private DiscoveryService discoveryService;
    private FileManager fileManager;
    private Runnable guiUpdateCallback;

    public void setDiscoveryService(DiscoveryService ds) {
        this.discoveryService = ds;
    }

    public void setFileManager(FileManager fm) {
        this.fileManager = fm;
    }

    public interface SearchResultListener {
        void onSearchResult(String fileName, long size, String hash, String peerId);
    }

    private SearchResultListener searchListener;

    public void setGuiCallback(Runnable callback) {
        this.guiUpdateCallback = callback;
    }

    public void setSearchListener(SearchResultListener listener) {
        this.searchListener = listener;
    }

    @Override
    public void onPeerFound(String peerId, String ipAddress, int port) {
        System.out.println("DEBUG PeerManager.onPeerFound: peerId=" + peerId + ", IP=" + ipAddress + ", port=" + port);
        PeerInfo info = knownPeers.get(peerId);
        if (info == null) {
            info = new PeerInfo(peerId, ipAddress, port);
            knownPeers.put(peerId, info);
            System.out.println("DEBUG: ✓ New Peer Discovered: " + peerId + "@" + ipAddress + " (Total peers: " + knownPeers.size() + ")");
            if (guiUpdateCallback != null)
                guiUpdateCallback.run();
        } else {
            info.lastSeen = System.currentTimeMillis();
            info.ip = ipAddress;
            info.port = port;
            System.out.println("DEBUG: Updated existing peer: " + peerId + "@" + ipAddress);
        }
    }

    @Override
    public void onMessageReceived(byte type, String content, String ip, int port) {
        try {
            // Content format: ID:<senderId>:<ActualPayload>
            // We need to parse senderId first
            String[] parts = content.split(":", 3);
            if (parts.length < 2)
                return;
            String senderId = parts[1];

            // Register peer if unknown (implicit discovery)
            onPeerFound(senderId, ip, port);

            if (type == com.network.p2p.network.Protocol.TYPE_QUERY_FILES) {
                if (parts.length < 3)
                    return;
                String query = parts[2];
                if (fileManager != null) {
                    FileManager.SharedFile file = fileManager.searchFile(query);
                    if (file != null) {
                        // Response should contain OUR peerId (the responder), not the sender's
                        String myPeerId = (discoveryService != null) ? discoveryService.getPeerId() : "UNKNOWN";
                        
                        // Get our FileServer port to include in response
                        int myFileServerPort = (discoveryService != null) ? discoveryService.getFileServerPort() : 50001;
                        
                        String response = "ID:" + myPeerId + ":QUERY_HIT:" + file.name + ":" + file.size + ":"
                                + file.hash + ":PORT:" + myFileServerPort;
                        System.out.println("File found for query '" + query + "', sending response with port " + myFileServerPort);
                        if (discoveryService != null) {
                            discoveryService.broadcastPacket(com.network.p2p.network.Protocol.TYPE_RESPONSE_FILES,
                                    response, 1);
                        }
                    }
                }
            } else if (type == com.network.p2p.network.Protocol.TYPE_RESPONSE_FILES) {
                // Format: ID:senderId:QUERY_HIT:fileName:size:hash:PORT:fileServerPort
                // parts[0]="ID", parts[1]=senderId, parts[2]="QUERY_HIT:fname:size:hash:PORT:port"
                System.out.println("DEBUG PeerManager: Processing RESPONSE_FILES");
                System.out.println("  - senderId: " + senderId);
                System.out.println("  - ip: " + ip);
                System.out.println("  - port: " + port);
                
                if (parts.length < 3)
                    return;
                String payload = parts[2];
                if (payload.startsWith("QUERY_HIT:")) {
                    String[] hitParts = payload.split(":"); // QUERY_HIT, fname, size, hash, PORT, port
                    System.out.println("  - hitParts: " + java.util.Arrays.toString(hitParts));
                    
                    if (hitParts.length >= 4) {
                        String fname = hitParts[1];
                        long size = Long.parseLong(hitParts[2]);
                        String hash = hitParts[3];
                        
                        // Extract FileServer port if present
                        int fileServerPort = 50001; // Default
                        if (hitParts.length >= 6 && hitParts[4].equals("PORT")) {
                            try {
                                fileServerPort = Integer.parseInt(hitParts[5]);
                                System.out.println("  - Parsed fileServerPort: " + fileServerPort);
                            } catch (NumberFormatException e) {
                                System.err.println("  - Failed to parse fileServerPort");
                            }
                        }
                        
                        // Update peer info with correct IP and FileServer port
                        System.out.println("  - Calling onPeerFound with FileServer port: " + fileServerPort);
                        onPeerFound(senderId, ip, fileServerPort);
                        
                        if (searchListener != null) {
                            searchListener.onSearchResult(fname, size, hash, senderId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Map<String, PeerInfo> getPeers() {
        return knownPeers;
    }
}
