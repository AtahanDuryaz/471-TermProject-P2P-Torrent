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
    private java.util.Map<String, VideoSearchResult> searchResults = new java.util.HashMap<>();

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
                VideoSearchResult result = searchResults.get(hash);
                if (result == null) {
                    // First time seeing this file (by hash)
                    result = new VideoSearchResult(fname, size, hash, peerId);
                    searchResults.put(hash, result);
                    videoListModel.addElement(result.getDisplayText());
                    log("Found file: " + fname + " from " + peerId);
                } else {
                    // Same file, different peer - add peer and update display
                    result.addPeer(peerId);
                    // Update the display text in the list
                    int index = -1;
                    for (int i = 0; i < videoListModel.size(); i++) {
                        if (videoListModel.get(i).startsWith(fname)) {
                            index = i;
                            break;
                        }
                    }
                    if (index != -1) {
                        videoListModel.set(index, result.getDisplayText());
                    }
                    log("Added peer " + peerId + " for file: " + fname + " (total peers: " + result.peerIds.size() + ")");
                }
            });
        });

        downloadManager.setChunkReceivedListener((fileName, chunkIndex, totalChunks, peerId) -> {
            SwingUtilities.invokeLater(() -> {
                lastReceivedChunk = chunkIndex;
                totalChunksForCurrentDownload = totalChunks;
                
                int progress = (int) ((chunkIndex + 1) * 100.0 / totalChunks);
                log("Chunk " + (chunkIndex + 1) + "/" + totalChunks + " received for " + fileName + " from peer " + peerId + " (" + progress + "%)");
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
            fileServer.start();
            // Wait a moment for FileServer to bind to a port
            new Thread(() -> {
                try {
                    Thread.sleep(500); // Wait 500ms for port binding
                    int port = fileServer.getPort();
                    if (port > 0) {
                        discoveryService.setFileServerPort(port);
                        SwingUtilities.invokeLater(() -> {
                            log("File Server started on port " + port);
                        });
                    }
                    discoveryService.start();
                    SwingUtilities.invokeLater(() -> {
                        log("Network Connected (Discovery Started).");
                    });
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }).start();
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
                    if (selected == null) return;
                    
                    // Find the VideoSearchResult by matching display text
                    VideoSearchResult result = null;
                    for (VideoSearchResult vsr : searchResults.values()) {
                        if (selected.startsWith(vsr.fileName)) {
                            result = vsr;
                            break;
                        }
                    }
                    
                    if (result == null) return;
                    
                    String fname = result.fileName;
                    long size = result.size;
                    String hash = result.hash;

                    log("=== DEBUG: Starting download of " + fname + " from " + result.peerIds.size() + " peer(s) ===");
                    log("DEBUG: PeerIds in result: " + result.peerIds);

                    // Collect ALL peers for this file - use peerIds instead of IPs (same IP can have multiple peers)
                    java.util.Set<String> peerIds = new java.util.HashSet<>();
                    java.util.Map<String, String> peerIdToIp = new java.util.HashMap<>();
                    java.util.Map<String, Integer> peerIdToPort = new java.util.HashMap<>();
                    
                    log("DEBUG: Total peers in PeerManager: " + peerManager.getPeers().size());
                    for (String peerId : result.peerIds) {
                        log("DEBUG: Looking for peerId: " + peerId);
                        PeerManager.PeerInfo pInfo = peerManager.getPeers().get(peerId);
                        if (pInfo == null) {
                            log("DEBUG: ERROR - PeerInfo is NULL for peerId: " + peerId);
                        } else {
                            log("DEBUG: Found PeerInfo - IP: " + pInfo.ip + ", Port: " + pInfo.port);
                            if (pInfo.ip != null && !pInfo.ip.trim().isEmpty()) {
                                peerIds.add(peerId);
                                peerIdToIp.put(peerId, pInfo.ip);
                                peerIdToPort.put(peerId, pInfo.port);
                                log("DEBUG: ✓ Added peerId: " + peerId + ", IP: " + pInfo.ip + ", port=" + pInfo.port);
                            } else {
                                log("DEBUG: ERROR - IP is null or empty for peerId: " + peerId);
                            }
                        }
                    }
                    
                    log("DEBUG: Total peers collected: " + peerIds.size());
                    log("DEBUG: PeerId->IP mapping: " + peerIdToIp);
                    log("DEBUG: PeerId->Port mapping: " + peerIdToPort);
                    
                    if (peerIds.isEmpty()) {
                        log("ERROR: No valid peers available for download");
                        return;
                    }

                    downloadManager.startDownload(fname, hash, size, peerIds, peerIdToIp, peerIdToPort);

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
