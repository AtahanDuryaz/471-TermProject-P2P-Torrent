package com.network.p2p.managers;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DownloadManager {
    private static final int CHUNK_SIZE = 256 * 1024; // 256 KB

    public static class ActiveDownload {
        public String fileName;
        public String hash;
        public long fileSize;
        public int totalChunks;
        public BitSet completedChunks;
        public BitSet inProgressChunks; // Track chunks currently being downloaded
        public File outputFile;
        public Set<String> sources = new HashSet<>(); // IP:Port
        public ConcurrentHashMap<String, DownloadWorker> workers = new ConcurrentHashMap<>(); // peerIp -> worker
        public long startTime;

        public ActiveDownload(String fileName, String hash, long fileSize, File outputFile) {
            this.fileName = fileName;
            this.hash = hash;
            this.fileSize = fileSize;
            this.outputFile = outputFile;
            this.totalChunks = (int) Math.ceil(fileSize / (double) CHUNK_SIZE);
            this.completedChunks = new BitSet(totalChunks);
            this.inProgressChunks = new BitSet(totalChunks);
            this.startTime = System.currentTimeMillis();
        }

        public float getProgress() {
            return (float) completedChunks.cardinality() / totalChunks * 100;
        }

        public boolean isComplete() {
            return completedChunks.cardinality() == totalChunks;
        }

        // Get the last consecutive chunk index starting from 0
        // Returns -1 if chunk 0 is not available, otherwise returns the highest consecutive index
        public int getLastConsecutiveChunk() {
            if (!completedChunks.get(0)) {
                System.out.println("DEBUG SEQ: Chunk 0 not available yet");
                return -1;
            }
            
            int lastConsecutive = 0;
            for (int i = 1; i < totalChunks; i++) {
                if (completedChunks.get(i)) {
                    lastConsecutive = i;
                } else {
                    System.out.println("DEBUG SEQ: Gap found at chunk " + i + ", last consecutive = " + lastConsecutive);
                    break;
                }
            }
            
            if (lastConsecutive == totalChunks - 1) {
                System.out.println("DEBUG SEQ: All chunks consecutive! Last = " + lastConsecutive);
            }
            
            return lastConsecutive;
        }

        // Get the first missing chunk index
        public int getFirstMissingChunk() {
            return completedChunks.nextClearBit(0);
        }
    }

    private final ConcurrentHashMap<String, ActiveDownload> downloads = new ConcurrentHashMap<>();
    private File bufferFolder;
    private FileManager fileManager;

    public interface ChunkReceivedListener {
        void onChunkReceived(String fileName, int chunkIndex, int totalChunks, String peerIp);
    }

    public interface DownloadCompleteListener {
        void onDownloadComplete(String fileName, String hash);
    }

    private ChunkReceivedListener chunkListener;
    private DownloadCompleteListener completeListener;

    public void setBufferFolder(File folder) {
        this.bufferFolder = folder;
    }

    public void setFileManager(FileManager fm) {
        this.fileManager = fm;
    }

    public void setChunkReceivedListener(ChunkReceivedListener listener) {
        this.chunkListener = listener;
    }

    public void setDownloadCompleteListener(DownloadCompleteListener listener) {
        this.completeListener = listener;
    }

    public void startDownload(String fileName, String hash, long size, Set<String> initialPeerIds,
                              java.util.Map<String, String> peerIdToIp, java.util.Map<String, Integer> peerIdToPort) {
        if (downloads.containsKey(hash))
            return;

        // Validate peers
        if (initialPeerIds == null || initialPeerIds.isEmpty()) {
            System.err.println("No peers available for download: " + fileName);
            return;
        }

        // Check buffer folder from FileManager first, then fallback to local
        File targetBufferFolder = bufferFolder;
        if (targetBufferFolder == null && fileManager != null) {
            targetBufferFolder = fileManager.getBufferFolder();
        }

        if (targetBufferFolder == null) {
            System.err.println("Buffer folder not set!");
            return;
        }

        File outFile = new File(targetBufferFolder, fileName);
        ActiveDownload download = new ActiveDownload(fileName, hash, size, outFile);
        download.sources.addAll(initialPeerIds);

        downloads.put(hash, download);
        System.out.println("Started download: " + fileName);

        // Pre-allocate file with full size so VLC knows the file size
        try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
            raf.setLength(size);
            System.out.println("Pre-allocated file: " + fileName + " (" + size + " bytes)");
        } catch (IOException e) {
            System.err.println("Failed to pre-allocate file: " + e.getMessage());
        }

        // Start workers and distribute chunks in round-robin fashion (Torrent-style)
        int peerCount = initialPeerIds.size();
        String[] peerIdArray = initialPeerIds.toArray(new String[0]);
        
        for (int i = 0; i < peerCount; i++) {
            String peerId = peerIdArray[i];
            if (peerId == null || peerId.trim().isEmpty()) {
                System.err.println("Skipping null or empty peer ID at index " + i);
                continue;
            }
            
            String peerIp = peerIdToIp.get(peerId);
            Integer peerPort = peerIdToPort.get(peerId);
            
            if (peerIp == null || peerPort == null) {
                System.err.println("Skipping peer " + peerId + " - missing IP or port");
                continue;
            }
            
            DownloadWorker worker = new DownloadWorker(peerIp, peerPort, peerId, this);
            download.workers.put(peerId, worker);
            new Thread(worker, "DownloadWorker-" + peerId).start();
        }
        
        // Distribute chunks across peers in round-robin fashion
        // This ensures each peer downloads different chunks (like BitTorrent)
        if (download.workers.isEmpty()) {
            System.err.println("No valid workers created for download: " + fileName);
            downloads.remove(hash);
            return;
        }
        
        String[] workerPeerIds = download.workers.keySet().toArray(new String[0]);
        int workerCount = workerPeerIds.length;
        
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║ CHUNK DISTRIBUTION - Round Robin (Torrent-style)");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        
        for (int chunkIndex = 0; chunkIndex < download.totalChunks; chunkIndex++) {
            int peerIndex = chunkIndex % workerCount;
            String assignedPeerId = workerPeerIds[peerIndex];
            DownloadWorker worker = download.workers.get(assignedPeerId);
            
            if (worker != null) {
                worker.queueTask(hash, chunkIndex);
                download.inProgressChunks.set(chunkIndex);
                
                System.out.println("║ Chunk " + String.format("%3d", chunkIndex) + " → Peer " + assignedPeerId);
            }
        }
        
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");
    }

    public ActiveDownload getDownload(String hash) {
        return downloads.get(hash);
    }

    public void receiveChunk(String hash, int chunkIndex, byte[] data, String peerIp) {
        ActiveDownload download = downloads.get(hash);
        if (download == null)
            return;

        synchronized (download) {
            if (download.completedChunks.get(chunkIndex))
                return; // Duplicate check

            try (RandomAccessFile raf = new RandomAccessFile(download.outputFile, "rw")) {
                long offset = (long) chunkIndex * CHUNK_SIZE;
                raf.seek(offset);
                raf.write(data);

                download.completedChunks.set(chunkIndex);
                download.inProgressChunks.clear(chunkIndex);
                
                int totalReceived = download.completedChunks.cardinality();
                int lastConsecutive = download.getLastConsecutiveChunk();
                int firstMissing = download.getFirstMissingChunk();
                float progress = download.getProgress();
                
                System.out.println("\n╔════════════════════════════════════════════════════════════════╗");
                System.out.println("║ CHUNK RECEIVED - " + download.fileName);
                System.out.println("╠════════════════════════════════════════════════════════════════╣");
                System.out.println("║ Chunk Index: " + chunkIndex + " / " + download.totalChunks);
                System.out.println("║ From Peer: " + peerIp);
                System.out.println("║ Total Received: " + totalReceived + " / " + download.totalChunks);
                System.out.println("║ Last Consecutive: " + lastConsecutive + " (chunks 0-" + lastConsecutive + " ready)");
                System.out.println("║ First Missing: " + firstMissing);
                System.out.println(String.format("║ Progress: %.1f%%", progress));
                System.out.println("╚════════════════════════════════════════════════════════════════╝\n");

                // Notify GUI
                if (chunkListener != null) {
                    chunkListener.onChunkReceived(download.fileName, chunkIndex, download.totalChunks, peerIp);
                }

                if (download.isComplete()) {
                    System.out.println("Download complete: " + download.fileName);
                    if (completeListener != null) {
                        completeListener.onDownloadComplete(download.fileName, hash);
                    }
                    // Verify Hash?
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // Get all active downloads
    public ConcurrentHashMap<String, ActiveDownload> getActiveDownloads() {
        return downloads;
    }
}
