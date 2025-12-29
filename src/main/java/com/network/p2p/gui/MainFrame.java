package com.network.p2p.gui;

import com.network.p2p.managers.FileManager;
import com.network.p2p.managers.PeerManager;
import com.network.p2p.network.DiscoveryService;

import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.swing.*;
import java.awt.*;

public class MainFrame extends JFrame {

    private DiscoveryService discoveryService;
    private PeerManager peerManager;
    private FileManager fileManager;
    private com.network.p2p.managers.DownloadManager downloadManager;
    private com.network.p2p.network.FileServer fileServer;
    private EmbeddedMediaPlayerComponent mediaPlayerComponent;
    private java.util.Map<String, String[]> searchResults = new java.util.HashMap<>();

    private JTextArea eventLog;
    private DefaultListModel<String> videoListModel;
    private DefaultListModel<String> streamListModel;
    private JProgressBar globalBufferStatus;
    
    // Active download tracking
    private int lastReceivedChunk = -1;
    private int totalChunksForCurrentDownload = 0;
    private static final int CHUNK_SIZE = 256 * 1024;

    public MainFrame() {
        setTitle("P2P Video Streamer - CSE471");
        setSize(1200, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // Init VLC
        try {
            mediaPlayerComponent = new EmbeddedMediaPlayerComponent();
        } catch (Exception e) {
            System.err.println("VLCj init failed: " + e.getMessage());
        }

        // Initialize Managers
        initManagers();

        // Menu Bar
        setJMenuBar(createMenuBar());

        // Layout
        setLayout(new BorderLayout(10, 10));

        // Top Panel: Search
        add(createSearchPanel(), BorderLayout.NORTH);

        // Center Panel: Split Pane (Videos | Streams)
        add(createCenterPanel(), BorderLayout.CENTER);

        // Bottom Panel: Event Log & Buffer
        add(createBottomPanel(), BorderLayout.SOUTH);

        log("Application started.");
    }

    private void initManagers() {
        peerManager = new PeerManager();
        fileManager = new FileManager();
        downloadManager = new com.network.p2p.managers.DownloadManager();
        discoveryService = new DiscoveryService(peerManager);
        fileServer = new com.network.p2p.network.FileServer(fileManager);

        peerManager.setDiscoveryService(discoveryService);
        peerManager.setFileManager(fileManager);
        downloadManager.setFileManager(fileManager);
        peerManager.setGuiCallback(() -> {
            log("Peer list updated. Total peers: " + peerManager.getPeers().size());
        });
        peerManager.setSearchListener((fname, size, hash, peerId) -> {
            SwingUtilities.invokeLater(() -> {
                String entry = fname + " (" + (size / 1024) + " KB) - from " + peerId;
                if (!videoListModel.contains(entry)) {
                    videoListModel.addElement(entry);
                    // Store metadata for later use
                    searchResults.put(entry, new String[] { fname, String.valueOf(size), hash, peerId });
                    log("Found file: " + fname + " from " + peerId);
                }
            });
        });

        downloadManager.setChunkReceivedListener((fileName, chunkIndex, totalChunks, peerIp) -> {
            SwingUtilities.invokeLater(() -> {
                lastReceivedChunk = chunkIndex;
                totalChunksForCurrentDownload = totalChunks;
                
                int progress = (int) ((chunkIndex + 1) * 100.0 / totalChunks);
                log("Chunk " + (chunkIndex + 1) + "/" + totalChunks + " received for " + fileName + " from " + peerIp + " (" + progress + "%)");
                globalBufferStatus.setValue(progress);
                globalBufferStatus.setString("Downloading: " + fileName + " - " + progress + "%");
                
                // Resume VLC if it was paused waiting for this chunk
                if (mediaPlayerComponent != null) {
                    if (!mediaPlayerComponent.mediaPlayer().status().isPlaying() && chunkIndex > 0) {
                        mediaPlayerComponent.mediaPlayer().controls().play();
                        log("Resumed playback after chunk " + (chunkIndex + 1));
                    }
                }
            });
        });

        downloadManager.setDownloadCompleteListener((fileName, hash) -> {
            SwingUtilities.invokeLater(() -> {
                log("Download complete: " + fileName);
                globalBufferStatus.setString("Download Complete: " + fileName);
                JOptionPane.showMessageDialog(this, "Download completed: " + fileName, "Success", JOptionPane.INFORMATION_MESSAGE);
            });
        });

        // UI Timer
        new javax.swing.Timer(1000, e -> updateUI()).start();
    }

    private void updateUI() {
        if (downloadManager == null)
            return;

        // Monitor VLC playback position vs downloaded chunks
        if (mediaPlayerComponent != null && mediaPlayerComponent.mediaPlayer().status().isPlaying()) {
            long currentTimeMs = mediaPlayerComponent.mediaPlayer().status().time();
            long fileLengthMs = mediaPlayerComponent.mediaPlayer().status().length();
            
            if (fileLengthMs > 0 && lastReceivedChunk >= 0) {
                // Calculate which chunk corresponds to current playback position
                // Assume average bitrate
                long totalFileSize = (long) totalChunksForCurrentDownload * CHUNK_SIZE;
                long currentBytePosition = (currentTimeMs * totalFileSize) / fileLengthMs;
                int currentChunk = (int) (currentBytePosition / CHUNK_SIZE);
                
                // If playing beyond downloaded chunks, pause
                if (currentChunk > lastReceivedChunk) {
                    mediaPlayerComponent.mediaPlayer().controls().pause();
                    log("Paused: Waiting for chunk " + (currentChunk + 1) + " (last received: " + (lastReceivedChunk + 1) + ")");
                }
            }
        }

        // Iterate active downloads
        // TODO: Implement getActiveDownloads in DownloadManager
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // Stream Menu
        JMenu streamMenu = new JMenu("Stream");

        JMenuItem connectItem = new JMenuItem("Connect");
        connectItem.addActionListener(e -> {
            discoveryService.start();
            fileServer.start();
            log("Network Connected (Discovery Started).");
            log("File Server started on port 50001.");
        });

        JMenuItem disconnectItem = new JMenuItem("Disconnect");
        disconnectItem.addActionListener(e -> {
            discoveryService.stop();
            fileServer.stop();
            log("Network Disconnected.");
        });

        JMenuItem setRootItem = new JMenuItem("Set Root Video Folder");
        setRootItem.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                fileManager.setRootDirectory(chooser.getSelectedFile());
                log("Root folder set: " + chooser.getSelectedFile().getAbsolutePath());
                refreshVideoList();
            }
        });

        JMenuItem setBufferItem = new JMenuItem("Set Buffer Folder");
        setBufferItem.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                fileManager.setBufferFolder(chooser.getSelectedFile());
                log("Buffer folder set: " + chooser.getSelectedFile().getAbsolutePath());
            }
        });

        streamMenu.add(connectItem);
        streamMenu.add(disconnectItem);
        streamMenu.addSeparator();
        streamMenu.add(setRootItem);
        streamMenu.add(setBufferItem);

        // Help Menu
        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(
                e -> JOptionPane.showMessageDialog(this, "P2P Video Streamer\nDeveloper: Atahan Düryaz \n20220702085\nCSE471 -Term Project","About", JOptionPane.INFORMATION_MESSAGE));
        helpMenu.add(aboutItem);

        menuBar.add(streamMenu);
        menuBar.add(helpMenu);
        return menuBar;
    }

    private JPanel createSearchPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JTextField searchField = new JTextField();
        JButton searchButton = new JButton("Search Network");

        searchButton.addActionListener(e -> {
            String query = searchField.getText().trim();
            if (!query.isEmpty()) {
                log("Searching for: " + query);
                String message = "ID:" + discoveryService.getPeerId() + ":" + query;
                discoveryService.broadcastPacket(com.network.p2p.network.Protocol.TYPE_QUERY_FILES, message, 3);
            }
        });

        panel.add(new JLabel("Search File: "), BorderLayout.WEST);
        panel.add(searchField, BorderLayout.CENTER);
        panel.add(searchButton, BorderLayout.EAST);
        return panel;
    }

    private JSplitPane createCenterPanel() {
        // Left: Available Videos
        videoListModel = new DefaultListModel<>();
        JList<String> videoList = new JList<>(videoListModel);
        videoList.setBorder(BorderFactory.createTitledBorder("Available Videos on Network"));

        // On Double Click -> Play/Download
        videoList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() == 2) {
                    String selected = videoList.getSelectedValue();
                    if (selected != null && searchResults.containsKey(selected)) {
                        String[] meta = searchResults.get(selected);
                        String fname = meta[0];
                        long size = Long.parseLong(meta[1]);
                        String hash = meta[2];
                        String peerId = meta[3];

                        log("Starting download of " + fname);

                        PeerManager.PeerInfo pInfo = peerManager.getPeers().get(peerId);
                        String ip = (pInfo != null) ? pInfo.ip : "127.0.0.1"; // Fallback or need real IP
                        // Assuming PeerID matches Map key or we extract from metadata if stored
                        // differently
                        // Actually I passed peerId, need IP.
                        // Simplified: PeerManager assumes single IP per PeerID

                        java.util.Set<String> peers = new java.util.HashSet<>();
                        peers.add(ip);

                        downloadManager.startDownload(fname, hash, size, peers);

                        // Start Player immediately (progressive streaming)
                        if (mediaPlayerComponent != null && fileManager.getBufferFolder() != null) {
                            String path = new java.io.File(fileManager.getBufferFolder(), fname).getAbsolutePath();
                            // Wait a bit for first chunks to arrive
                            new Thread(() -> {
                                try {
                                    Thread.sleep(3000); // Wait 3 seconds for initial buffering
                                    SwingUtilities.invokeLater(() -> {
                                        // VLC options for progressive streaming
                                        String[] options = {
                                            ":file-caching=300",              // File caching 300ms (low for progressive)
                                            ":network-caching=300",           // Network caching 300ms
                                            ":live-caching=300",              // Live caching 300ms
                                            ":clock-jitter=0",                // No jitter
                                            ":clock-synchro=0"                // No clock synchro (important for progressive)
                                        };
                                        mediaPlayerComponent.mediaPlayer().media().play(path, options);
                                        
                                        // Set to loop on the available content
                                        mediaPlayerComponent.mediaPlayer().controls().setRepeat(false);
                                        
                                        log("Started playing: " + fname + " (progressive mode)");
                                    });
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }).start();
                        }
                    }
                }
            }
        });

        // Right: Active Streams / Player
        JPanel rightPanel = new JPanel(new BorderLayout());
        streamListModel = new DefaultListModel<>();
        JList<String> streamList = new JList<>(streamListModel);

        JPanel playerContainer = new JPanel(new BorderLayout());
        if (mediaPlayerComponent != null) {
            playerContainer.add(mediaPlayerComponent, BorderLayout.CENTER);
        } else {
            playerContainer.add(new JLabel("VLC Player not initialized (Check Native Libs)"), BorderLayout.CENTER);
        }
        playerContainer.setPreferredSize(new Dimension(600, 400));

        rightPanel.add(new JScrollPane(streamList), BorderLayout.NORTH);
        rightPanel.add(playerContainer, BorderLayout.CENTER);
        rightPanel.setBorder(BorderFactory.createTitledBorder("Active Stream & Player"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(videoList), rightPanel);
        split.setDividerLocation(300);
        return split;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        // buffer status
        globalBufferStatus = new JProgressBar(0, 100);
        globalBufferStatus.setStringPainted(true);
        globalBufferStatus.setString("Global Buffer: 0%");

        // Log
        eventLog = new JTextArea(5, 50);
        eventLog.setEditable(false);

        panel.add(globalBufferStatus, BorderLayout.NORTH);
        panel.add(new JScrollPane(eventLog), BorderLayout.CENTER);
        return panel;
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            eventLog.append(message + "\n");
            eventLog.setCaretPosition(eventLog.getDocument().getLength());
        });
    }

    private void refreshVideoList() {
        videoListModel.clear();
        for (FileManager.SharedFile f : fileManager.getFileList()) {
            videoListModel.addElement(f.name + " (" + (f.size / 1024) + " KB)");
        }
    }
}
