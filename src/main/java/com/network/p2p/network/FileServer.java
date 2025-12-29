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

    private static final int PORT = 50001; // TCP Port for File Transfer
    private static final int CHUNK_SIZE = 256 * 1024;

    private final FileManager fileManager;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private boolean running = false;

    public FileServer(FileManager fileManager) {
        this.fileManager = fileManager;
    }

    public void start() {
        if (running)
            return;
        running = true;
        executor.submit(this::serverLoop);
        System.out.println("FileServer started on TCP port " + PORT);
    }

    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    private void serverLoop() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (running) {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleClient(client));
            }
        } catch (IOException e) {
            if (running)
                e.printStackTrace();
        }
    }

    private void handleClient(Socket socket) {
        try (DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            // Protocol: REQUEST_CHUNK:<fileHash>:<chunkIndex>
            // For now, keeping it simple text-based or binary
            // Let's use simple text line for request

            // Wait for request (simple implementation)
            // Ideally we should use the same Protocol class or similar
            // But TCP is stream based.

            // Let's assume client sends: [HashLen(1)][HashBytes][ChunkIndex(4)]
            // Or easier: UTF string "REQUEST:Hash:Index" newline

            // Reading simple line (not robust for binary but okay for project text
            // protocol)
            // Better:
            int hashLen = in.readInt();
            byte[] hashBytes = new byte[hashLen];
            in.readFully(hashBytes);
            String hash = new String(hashBytes);

            int chunkIndex = in.readInt();

            System.out.println("Client requested chunk " + chunkIndex + " for " + hash);

            FileManager.SharedFile file = fileManager.getFileByHash(hash);
            if (file != null) {
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
                    } else {
                        // Offset out of bounds
                        out.writeByte(0); // Error
                    }
                }
            } else {
                out.writeByte(0); // Error (File not found)
            }

        } catch (IOException e) {
            // e.printStackTrace();
        }
    }
}
