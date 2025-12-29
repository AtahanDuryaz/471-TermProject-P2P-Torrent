package com.network.p2p.managers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FileManager {

    public static class SharedFile {
        public String name;
        public long size;
        public String hash;
        public File fileHandle;

        public SharedFile(String name, long size, String hash, File fileHandle) {
            this.name = name;
            this.size = size;
            this.hash = hash;
            this.fileHandle = fileHandle;
        }
    }

    private File rootDirectory;
    private File bufferDirectory;
    private final Map<String, SharedFile> sharedFiles = new ConcurrentHashMap<>(); // Hash -> File

    public void setRootDirectory(File dir) {
        if (dir.exists() && dir.isDirectory()) {
            this.rootDirectory = dir;
            scanDirectory();
        }
    }

    public void setBufferFolder(File dir) {
        if (dir != null) {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (dir.isDirectory()) {
                this.bufferDirectory = dir;
                System.out.println("Buffer folder set to: " + dir.getAbsolutePath());
            }
        }
    }

    public File getBufferFolder() {
        return bufferDirectory;
    }

    public void scanDirectory() {
        if (rootDirectory == null)
            return;

        sharedFiles.clear();
        File[] files = rootDirectory
                .listFiles((d, name) -> name.toLowerCase().endsWith(".mp4") || name.toLowerCase().endsWith(".mkv"));

        if (files != null) {
            for (File f : files) {
                try {
                    String hash = computeSha256(f);
                    SharedFile sf = new SharedFile(f.getName(), f.length(), hash, f);
                    sharedFiles.put(hash, sf);
                    System.out.println("Indexed file: " + f.getName() + " [" + hash + "]");
                } catch (Exception e) {
                    System.err.println("Error hashing file " + f.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    public List<SharedFile> getFileList() {
        return new ArrayList<>(sharedFiles.values());
    }

    public SharedFile getFileByHash(String hash) {
        return sharedFiles.get(hash);
    }

    public SharedFile searchFile(String partialName) {
        for (SharedFile sf : sharedFiles.values()) {
            if (sf.name.toLowerCase().contains(partialName.toLowerCase())) {
                return sf;
            }
        }
        return null;
    }

    private String computeSha256(File file) throws IOException, NoSuchAlgorithmException {
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(fileBytes);

        StringBuilder hexString = new StringBuilder();
        for (byte b : digest) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1)
                hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
