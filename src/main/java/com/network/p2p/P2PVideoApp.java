package com.network.p2p;

import com.formdev.flatlaf.FlatDarkLaf;
import com.network.p2p.gui.MainFrame;
import com.network.p2p.managers.FileManager;
import com.network.p2p.managers.PeerManager;
import com.network.p2p.network.DiscoveryService;
import com.network.p2p.network.FileServer;

import javax.swing.SwingUtilities;
import java.io.File;

public class P2PVideoApp {
    public static void main(String[] args) {
        // Check for headless mode
        boolean headlessMode = false;
        for (String arg : args) {
            if ("--headless".equals(arg)) {
                headlessMode = true;
                break;
            }
        }

        if (headlessMode) {
            System.out.println("╔════════════════════════════════════════════════════════════════╗");
            System.out.println("║          P2P VIDEO STREAMING - HEADLESS PEER MODE              ║");
            System.out.println("╚════════════════════════════════════════════════════════════════╝");
            System.out.println();
            runHeadlessPeer();
        } else {
            // GUI Mode
            FlatDarkLaf.setup();
            SwingUtilities.invokeLater(() -> {
                MainFrame frame = new MainFrame();
                frame.setVisible(true);
            });
        }
    }

    private static void runHeadlessPeer() {
        try {
            // Initialize managers
            FileManager fileManager = new FileManager();
            PeerManager peerManager = new PeerManager();
            DiscoveryService discoveryService = new DiscoveryService(peerManager);
            FileServer fileServer = new FileServer(fileManager);

            // Wire dependencies
            peerManager.setDiscoveryService(discoveryService);
            peerManager.setFileManager(fileManager);

            // Set directories from environment or defaults
            String videoDir = System.getenv("VIDEO_DIR");
            if (videoDir == null || videoDir.isEmpty()) {
                videoDir = "./videos";
            }
            
            String bufferDir = System.getenv("BUFFER_DIR");
            if (bufferDir == null || bufferDir.isEmpty()) {
                bufferDir = "./buffer";
            }

            File videoFolder = new File(videoDir);
            File bufferFolder = new File(bufferDir);

            // Create directories if they don't exist
            if (!videoFolder.exists()) {
                videoFolder.mkdirs();
                System.out.println("Created video directory: " + videoFolder.getAbsolutePath());
            }
            if (!bufferFolder.exists()) {
                bufferFolder.mkdirs();
                System.out.println("Created buffer directory: " + bufferFolder.getAbsolutePath());
            }

            // Set directories
            fileManager.setRootDirectory(videoFolder);
            fileManager.setBufferFolder(bufferFolder);

            System.out.println("Video directory: " + videoFolder.getAbsolutePath());
            System.out.println("Buffer directory: " + bufferFolder.getAbsolutePath());
            System.out.println();

            // Start services
            System.out.println("Starting File Server...");
            fileServer.start();
            
            // Wait for FileServer to bind
            Thread.sleep(500);
            int fileServerPort = fileServer.getPort();
            
            if (fileServerPort > 0) {
                discoveryService.setFileServerPort(fileServerPort);
                System.out.println("✓ File Server started on port: " + fileServerPort);
            } else {
                System.err.println("✗ Failed to start File Server");
                System.exit(1);
            }

            System.out.println("Starting Discovery Service...");
            discoveryService.start();
            System.out.println("✓ Discovery Service started");
            System.out.println("✓ Peer ID: " + discoveryService.getPeerId());
            System.out.println();

            System.out.println("Headless peer is running. Shared files:");
            for (FileManager.SharedFile file : fileManager.getFileList()) {
                System.out.println("  - " + file.name + " (" + (file.size / 1024) + " KB) [" + file.hash + "]");
            }
            
            if (fileManager.getFileList().isEmpty()) {
                System.out.println("  (No video files found in " + videoFolder.getAbsolutePath() + ")");
            }
            
            System.out.println();
            System.out.println("Press Ctrl+C to stop the peer.");

            // Add shutdown hook for graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down headless peer...");
                discoveryService.stop();
                fileServer.stop();
                System.out.println("Goodbye!");
            }));

            // Keep alive
            while (true) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            System.err.println("Error running headless peer: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
