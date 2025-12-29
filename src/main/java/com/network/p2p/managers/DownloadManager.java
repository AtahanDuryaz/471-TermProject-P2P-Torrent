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
        public File outputFile;
        public Set<String> sources = new HashSet<>(); // IP:Port
        public long startTime;

        public ActiveDownload(String fileName, String hash, long fileSize, File outputFile) {
            this.fileName = fileName;
            this.hash = hash;
            this.fileSize = fileSize;
            this.outputFile = outputFile;
            this.totalChunks = (int) Math.ceil(fileSize / (double) CHUNK_SIZE);
            this.completedChunks = new BitSet(totalChunks);
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

    public void startDownload(String fileName, String hash, long size, Set<String> initialPeers) {
        if (downloads.containsKey(hash))
            return;

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
        download.sources.addAll(initialPeers);

        downloads.put(hash, download);
        System.out.println("Started download: " + fileName);

        // Pre-allocate file with full size so VLC knows the file size
        try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
            raf.setLength(size);
            System.out.println("Pre-allocated file: " + fileName + " (" + size + " bytes)");
        } catch (IOException e) {
            System.err.println("Failed to pre-allocate file: " + e.getMessage());
        }

        // Start workers
        for (String peerIp : initialPeers) {
            DownloadWorker worker = new DownloadWorker(peerIp, this);
            new Thread(worker).start();

            // Queue all chunks (Simple approach)
            for (int i = 0; i < download.totalChunks; i++) {
                worker.queueTask(hash, i);
            }
        }
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
                System.out.println("Chunk " + chunkIndex + "/" + download.totalChunks + " received for " + download.fileName + " from " + peerIp);

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

    public ActiveDownload getDownload(String hash) {
        return downloads.get(hash);
    }
}
