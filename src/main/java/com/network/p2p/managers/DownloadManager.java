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
        public ConcurrentHashMap<String, DownloadWorker> workers = new ConcurrentHashMap<>(); // peerId -> worker
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
    }

    private final ConcurrentHashMap<String, ActiveDownload> downloads = new ConcurrentHashMap<>();
    private File bufferFolder;
    private FileManager fileManager;

    public interface ChunkReceivedListener {
        void onChunkReceived(String fileName, int chunkIndex, int totalChunks, String peerId);
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

    public void startDownload(String fileName, String hash, long size, Set<String> peerIds, 
                             java.util.Map<String, String> peerIdToIp, java.util.Map<String, Integer> peerIdToPort) {
        System.out.println("=== DEBUG DownloadManager.startDownload ===");
        System.out.println("DEBUG: fileName=" + fileName + ", hash=" + hash + ", size=" + size);
        System.out.println("DEBUG: peerIds=" + peerIds);
        System.out.println("DEBUG: peerIdToIp mapping=" + peerIdToIp);
        System.out.println("DEBUG: peerIdToPort mapping=" + peerIdToPort);
        
        if (downloads.containsKey(hash)) {
            System.out.println("DEBUG: Download already exists for hash: " + hash);
            return;
        }

        // Validate peers
        if (peerIds == null || peerIds.isEmpty()) {
            System.err.println("DEBUG ERROR: No peers available for download: " + fileName);
            return;
        }
        
        System.out.println("DEBUG: Number of peers: " + peerIds.size());

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
        // Note: sources is IP-based, we'll add IPs from the mapping
        for (String peerId : peerIds) {
            String ip = peerIdToIp.get(peerId);
            if (ip != null) {
                download.sources.add(ip);
            }
        }

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
        int peerCount = peerIds.size();
        String[] peerIdArray = peerIds.toArray(new String[0]);
        
        System.out.println("DEBUG: Creating workers for " + peerCount + " peers");
        for (int i = 0; i < peerCount; i++) {
            String peerId = peerIdArray[i];
            String peerIp = peerIdToIp.get(peerId);
            int port = peerIdToPort.getOrDefault(peerId, 50001); // Default fallback
            
            System.out.println("DEBUG: Processing peer " + i + ": PeerId=" + peerId + ", IP=" + peerIp + ", Port=" + port);
            if (peerIp == null || peerIp.trim().isEmpty()) {
                System.err.println("DEBUG: Skipping - IP is null or empty for peerId: " + peerId);
                continue;
            }
            System.out.println("DEBUG: Creating worker - PeerId: " + peerId + ", IP: " + peerIp + ", Port: " + port);
            DownloadWorker worker = new DownloadWorker(peerIp, port, peerId, this);
            download.workers.put(peerId, worker);
            new Thread(worker, "DownloadWorker-" + peerId).start();
            System.out.println("DEBUG: ✓ Worker created and started for peer: " + peerId);
        }
        
        System.out.println("DEBUG: Total workers created: " + download.workers.size());
        
        // Distribute chunks across peers in round-robin fashion
        // This ensures each peer downloads different chunks (like BitTorrent)
        if (download.workers.isEmpty()) {
            System.err.println("DEBUG ERROR: No valid workers created for download: " + fileName);
            downloads.remove(hash);
            return;
        }
        
        // Re-use peerIdArray from workers in case some were skipped
        peerIdArray = download.workers.keySet().toArray(new String[0]);
        int workerCount = peerIdArray.length;
        
        System.out.println("DEBUG: Distributing " + download.totalChunks + " chunks across " + workerCount + " workers");
        System.out.println("DEBUG: Workers: " + java.util.Arrays.toString(peerIdArray));
        
        for (int chunkIndex = 0; chunkIndex < download.totalChunks; chunkIndex++) {
            int peerIndex = chunkIndex % workerCount;
            String assignedPeerId = peerIdArray[peerIndex];
            DownloadWorker worker = download.workers.get(assignedPeerId);
            
            if (worker != null) {
                worker.queueTask(hash, chunkIndex);
                download.inProgressChunks.set(chunkIndex);
                System.out.println("DEBUG: ✓ Chunk " + chunkIndex + " assigned to peer " + assignedPeerId);
            } else {
                System.err.println("DEBUG ERROR: Worker is NULL for peerId: " + assignedPeerId);
            }
        }
    }

    public ActiveDownload getDownload(String hash) {
        return downloads.get(hash);
    }

    public void receiveChunk(String hash, int chunkIndex, byte[] data, String peerId) {
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
                System.out.println("Chunk " + chunkIndex + "/" + download.totalChunks + " received for " + download.fileName + " from " + peerId);

                // Notify GUI
                if (chunkListener != null) {
                    chunkListener.onChunkReceived(download.fileName, chunkIndex, download.totalChunks, peerId);
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
}
