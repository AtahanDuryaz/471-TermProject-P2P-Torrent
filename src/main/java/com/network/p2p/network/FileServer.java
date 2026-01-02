package com.network.p2p.network;

import com.network.p2p.managers.FileManager;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileServer {

    private static final int BASE_PORT = 50001; // Base TCP Port for File Transfer
    private static final int CHUNK_SIZE = 256 * 1024;

    private final FileManager fileManager;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private boolean running = false;
    private int actualPort = 0; // The port we actually bound to

    public FileServer(FileManager fileManager) {
        this.fileManager = fileManager;
    }

    public void start() {
        if (running)
            return;
        running = true;
        executor.submit(this::serverLoop);
    }
    
    public int getPort() {
        return actualPort;
    }

    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    private void serverLoop() {
        ServerSocket serverSocket = null;
        
        // Check for FILE_SERVER_PORT environment variable
        String envPort = System.getenv("FILE_SERVER_PORT");
        int startPort = BASE_PORT;
        int endPort = BASE_PORT + 99;
        
        if (envPort != null && !envPort.trim().isEmpty()) {
            try {
                int fixedPort = Integer.parseInt(envPort.trim());
                startPort = fixedPort;
                endPort = fixedPort; // Only try the specified port
                System.out.println("DEBUG FileServer: Using fixed port from environment: " + fixedPort);
            } catch (NumberFormatException e) {
                System.err.println("DEBUG FileServer: Invalid FILE_SERVER_PORT value: " + envPort + ", using default range");
            }
        }
        
        // Try to bind to a port, starting from startPort
        for (int portAttempt = startPort; portAttempt <= endPort; portAttempt++) {
            try {
                serverSocket = new ServerSocket(portAttempt);
                actualPort = portAttempt;
                System.out.println("DEBUG FileServer: Successfully bound to port " + actualPort);
                break;
            } catch (IOException e) {
                System.err.println("DEBUG FileServer: Port " + portAttempt + " is in use, trying next...");
                if (portAttempt == BASE_PORT + 99) {
                    System.err.println("ERROR: Could not find available port after 100 attempts!");
                    e.printStackTrace();
                    return;
                }
            }
        }
        
        System.out.println("FileServer started on TCP port " + actualPort);
        
        try {
            while (running) {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleClient(client));
            }
        } catch (IOException e) {
            if (running)
                e.printStackTrace();
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try (DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            // Protocol: 
            // - Request Type (4 bytes): 0 = CHUNK_REQUEST, 1 = LIST_FILES
            // - For CHUNK_REQUEST: [HashLen(4)][HashBytes][ChunkIndex(4)]
            // - For LIST_FILES: no additional data
            
            int requestType = in.readInt();
            
            System.out.println("🔍 DEBUG FileServer: Received request type = " + requestType);
            
            if (requestType == 1) {
                // LIST_FILES request
                System.out.println("📋 Client requested file list");
                java.util.List<FileManager.SharedFile> files = fileManager.getFileList();
                
                System.out.println("📋 Found " + files.size() + " files to send");
                
                // Response: [FileCount(4)] then for each file: [NameLen(4)][Name][Size(8)][HashLen(4)][Hash]
                out.writeInt(files.size());
                for (FileManager.SharedFile file : files) {
                    byte[] nameBytes = file.name.getBytes("UTF-8");
                    byte[] hashBytes = file.hash.getBytes("UTF-8");
                    
                    System.out.println("📋 Sending: " + file.name + " (" + file.size + " bytes)");
                    
                    out.writeInt(nameBytes.length);
                    out.write(nameBytes);
                    out.writeLong(file.size);
                    out.writeInt(hashBytes.length);
                    out.write(hashBytes);
                }
                out.flush();
                System.out.println("✅ Sent " + files.size() + " files to client");
                return;
            }
            
            // CHUNK_REQUEST (requestType == 0)
            int hashLen = in.readInt();
            byte[] hashBytes = new byte[hashLen];
            in.readFully(hashBytes);
            String hash = new String(hashBytes);

            int chunkIndex = in.readInt();

            System.out.println("Client requested chunk " + chunkIndex + " for " + hash);

            FileManager.SharedFile file = fileManager.getFileByHash(hash);
            if (file != null) {
                System.out.println("File found: " + file.name);
                // Send Chunk
                // 1. Seek
                try (FileInputStream fis = new FileInputStream(file.fileHandle)) {
                    long offset = (long) chunkIndex * CHUNK_SIZE;
                    if (offset < file.size) {
                        fis.skip(offset);
                        byte[] buffer = new byte[CHUNK_SIZE];
                        int bytesRead = fis.read(buffer);

                        // Response: [Status(1=OK)][DataLen(4)][Data]
                        out.writeByte(1); // OK
                        out.writeInt(bytesRead);
                        out.write(buffer, 0, bytesRead);
                        System.out.println("Sent chunk " + chunkIndex + " (" + bytesRead + " bytes)");
                    } else {
                        // Offset out of bounds
                        System.err.println("Offset out of bounds: " + offset + " >= " + file.size);
                        out.writeByte(0); // Error
                    }
                }
            } else {
                System.err.println("File NOT FOUND for hash: " + hash);
                System.err.println("Available files in FileManager:");
                for (FileManager.SharedFile sf : fileManager.getFileList()) {
                    System.err.println("  - " + sf.name + " [" + sf.hash + "]");
                }
                out.writeByte(0); // Error (File not found)
            }

        } catch (IOException e) {
            // e.printStackTrace();
        }
    }
}
