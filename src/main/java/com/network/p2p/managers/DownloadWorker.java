package com.network.p2p.managers;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class DownloadWorker implements Runnable {
    private static final Logger logger = Logger.getLogger(DownloadWorker.class.getName());
    private static final int PORT = 50001;
    private static final int CHUNK_SIZE = 256 * 1024;

    private String peerIp;
    private DownloadManager manager;
    // Tuple: Hash, ChunkIndex
    private BlockingQueue<String> taskQueue;
    private boolean running = true;

    public DownloadWorker(String peerIp, DownloadManager manager) {
        this.peerIp = peerIp;
        this.manager = manager;
        this.taskQueue = new LinkedBlockingQueue<>();
    }

    public void queueTask(String hash, int chunkIndex) {
        taskQueue.offer(hash + ":" + chunkIndex);
    }

    @Override
    public void run() {
        while (running) {
            Socket socket = null;
            try {
                // Peek task first
                String task = taskQueue.poll(2, TimeUnit.SECONDS);
                if (task == null)
                    continue;

                String[] parts = task.split(":");
                String hash = parts[0];
                int chunkIndex = Integer.parseInt(parts[1]);

                socket = new Socket(peerIp, PORT);
                try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                        DataInputStream in = new DataInputStream(socket.getInputStream())) {

                    // Request: [HashLen(4)][HashBytes][ChunkIndex(4)]
                    byte[] hashBytes = hash.getBytes();
                    out.writeInt(hashBytes.length);
                    out.write(hashBytes);
                    out.writeInt(chunkIndex);
                    out.flush();

                    // Response: [Status(1)][Len(4)][Data]
                    byte status = in.readByte();
                    if (status == 1) {
                        int len = in.readInt();
                        byte[] data = new byte[len];
                        in.readFully(data);

                        manager.receiveChunk(hash, chunkIndex, data, peerIp);
                    } else {
                        System.err.println("Peer " + peerIp + " returned error for chunk " + chunkIndex);
                        // Re-queue or mark failed? For now drop.
                    }
                }
            } catch (Exception e) {
                // logger.warning("Worker error (" + peerIp + "): " + e.getMessage());
                // Basic retry or backoff could go here
                try {
                    Thread.sleep(1000);
                } catch (Exception ex) {
                }
            } finally {
                if (socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                    } catch (Exception e) {
                    }
                }
            }
        }
    }

    public void stop() {
        running = false;
    }
}
