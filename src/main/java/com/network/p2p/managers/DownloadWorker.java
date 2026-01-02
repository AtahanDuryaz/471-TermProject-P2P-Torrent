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
    private static final int CHUNK_SIZE = 256 * 1024;

    private String peerIp;
    private int peerPort;
    private String peerId;
    private DownloadManager manager;
    // Tuple: Hash, ChunkIndex
    private BlockingQueue<String> taskQueue;
    private boolean running = true;

    public DownloadWorker(String peerIp, int peerPort, String peerId, DownloadManager manager) {
        this.peerIp = peerIp;
        this.peerPort = peerPort;
        this.peerId = peerId;
        this.manager = manager;
        this.taskQueue = new LinkedBlockingQueue<>();
    }

    public void queueTask(String hash, int chunkIndex) {
        taskQueue.offer(hash + ":" + chunkIndex);
    }

    @Override
    public void run() {
        System.out.println("DEBUG Worker[" + peerId + "]: Thread started for IP=" + peerIp);
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
                
                System.out.println("DEBUG Worker[" + peerId + "]: Processing chunk " + chunkIndex);
                
                // Check if chunk is already completed or being downloaded by another worker
                DownloadManager.ActiveDownload download = manager.getDownload(hash);
                if (download != null) {
                    synchronized (download) {
                        if (download.completedChunks.get(chunkIndex)) {
                            System.out.println("DEBUG Worker[" + peerId + "]: Chunk " + chunkIndex + " already completed, skipping");
                            continue;
                        }
                    }
                }

                System.out.println("DEBUG Worker[" + peerId + "]: Connecting to " + peerIp + ":" + peerPort + " for chunk " + chunkIndex);
                socket = new Socket(peerIp, peerPort);
                System.out.println("DEBUG Worker[" + peerId + "]: ✓ Connected to " + peerIp + ":" + peerPort);
                
                try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                        DataInputStream in = new DataInputStream(socket.getInputStream())) {

                    // Request: [RequestType(4)][HashLen(4)][HashBytes][ChunkIndex(4)]
                    out.writeInt(0); // 0 = CHUNK_REQUEST
                    byte[] hashBytes = hash.getBytes();
                    out.writeInt(hashBytes.length);
                    out.write(hashBytes);
                    out.writeInt(chunkIndex);
                    out.flush();
                    
                    System.out.println("DEBUG Worker[" + peerId + "]: Request sent for chunk " + chunkIndex);

                    // Response: [Status(1)][Len(4)][Data]
                    byte status = in.readByte();
                    System.out.println("DEBUG Worker[" + peerId + "]: Response status=" + status + " for chunk " + chunkIndex);
                    
                    if (status == 1) {
                        int len = in.readInt();
                        byte[] data = new byte[len];
                        in.readFully(data);
                        
                        System.out.println("DEBUG Worker[" + peerId + "]: ✓ Received chunk " + chunkIndex + " (" + len + " bytes)");

                        manager.receiveChunk(hash, chunkIndex, data, peerId);
                        
                        // First 15 chunks: fast (for VLC to start)
                        // Rest: slower (for visible progressive streaming)
                        if (chunkIndex < 15) {
                            Thread.sleep(50); // Fast initial buffering
                        } else {
                            Thread.sleep(200); // Slower for visible progress
                        }
                    } else {
                        System.err.println("DEBUG Worker[" + peerId + "]: ERROR - Peer returned error status for chunk " + chunkIndex);
                        // Re-queue or mark failed? For now drop.
                    }
                }
            } catch (Exception e) {
                System.err.println("DEBUG Worker[" + peerId + "]: EXCEPTION - " + e.getClass().getName() + ": " + e.getMessage());
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
