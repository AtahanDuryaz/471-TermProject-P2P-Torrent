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
    private String currentDownloadHash = null;
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
                
                // Check if we can start/resume playback based on sequential chunks
                if (currentDownloadHash != null && mediaPlayerComponent != null) {
                    com.network.p2p.managers.DownloadManager.ActiveDownload download = 
                        downloadManager.getDownload(currentDownloadHash);
                    
                    if (download != null) {
                        int lastConsecutive = download.getLastConsecutiveChunk();
                        boolean isPlaying = mediaPlayerComponent.mediaPlayer().status().isPlaying();
                        
                        System.out.println("\n>>> GUI PLAYBACK CONTROL DEBUG <<<");
                        System.out.println("DEBUG GUI: Current hash matches: " + (currentDownloadHash != null));
                        System.out.println("DEBUG GUI: Last consecutive chunk: " + lastConsecutive);
                        System.out.println("DEBUG GUI: VLC is currently playing: " + isPlaying);
                        System.out.println("DEBUG GUI: Should start playback: " + (!isPlaying && lastConsecutive >= 1));
                        
                        // If we have the first few chunks ready and player is not playing, start it
                        if (!isPlaying && lastConsecutive >= 1) {
                            mediaPlayerComponent.mediaPlayer().controls().play();
                            log("🎬 PLAYBACK STARTED - consecutive chunks 0-" + lastConsecutive + " available");
                            System.out.println("DEBUG GUI: ✓✓✓ PLAYBACK STARTED! ✓✓✓");
                        } else if (isPlaying) {
                            System.out.println("DEBUG GUI: Video already playing, no action needed");
                        } else {
                            System.out.println("DEBUG GUI: Not enough consecutive chunks yet (need >= 2, have 0-" + lastConsecutive + ")");
                        }
                        System.out.println(">>> END GUI PLAYBACK CONTROL <<<\n");
                    } else {
                        System.out.println("DEBUG GUI ERROR: Download object is NULL!");
                    }
                } else {
                    System.out.println("DEBUG GUI: Skipping playback control - hash or mediaPlayer is null");
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
        System.out.println("\n🕐 DEBUG: Starting UI Update Timer (runs every 1 second)");
        new javax.swing.Timer(1000, e -> updateUI()).start();
        System.out.println("🕐 DEBUG: UI Update Timer STARTED successfully!\n");
    }

    private void updateUI() {
        if (downloadManager == null)
            return;

        // Monitor VLC playback position vs downloaded consecutive chunks
        if (mediaPlayerComponent != null && currentDownloadHash != null) {
            com.network.p2p.managers.DownloadManager.ActiveDownload download = 
                downloadManager.getDownload(currentDownloadHash);
            
            if (download != null && !download.isComplete()) {
                int lastConsecutiveChunk = download.getLastConsecutiveChunk();
                boolean isPlaying = mediaPlayerComponent.mediaPlayer().status().isPlaying();
                
                System.out.println("\n[UPDATE UI TIMER - Every Second]");
                System.out.println("DEBUG TIMER: Download active: " + (download != null));
                System.out.println("DEBUG TIMER: Last consecutive chunk: " + lastConsecutiveChunk);
                System.out.println("DEBUG TIMER: VLC is playing: " + isPlaying);
                
                if (isPlaying) {
                    long currentTimeMs = mediaPlayerComponent.mediaPlayer().status().time();
                    long fileLengthMs = mediaPlayerComponent.mediaPlayer().status().length();
                    
                    System.out.println("DEBUG TIMER: Current playback time: " + currentTimeMs + "ms");
                    System.out.println("DEBUG TIMER: Total file length: " + fileLengthMs + "ms");
                    
                    if (fileLengthMs > 0) {
                        // Calculate which chunk corresponds to current playback position
                        long totalFileSize = download.fileSize;
                        long currentBytePosition = (currentTimeMs * totalFileSize) / fileLengthMs;
                        int currentChunk = (int) (currentBytePosition / CHUNK_SIZE);
                        
                        System.out.println("DEBUG TIMER: Calculated current chunk position: " + currentChunk);
                        System.out.println("DEBUG TIMER: Need to pause? " + (currentChunk > lastConsecutiveChunk));
                        
                        // If playing beyond consecutively downloaded chunks, pause
                        if (currentChunk > lastConsecutiveChunk) {
                            mediaPlayerComponent.mediaPlayer().controls().pause();
                            log("⏸️ PAUSED: Waiting for chunk " + (currentChunk + 1) + " (last consecutive: " + (lastConsecutiveChunk + 1) + ")");
                            System.out.println("DEBUG TIMER: ⏸️⏸️⏸️ VIDEO PAUSED - WAITING FOR CHUNK " + currentChunk + " ⏸️⏸️⏸️");
                        }
                    }
                } else {
                    System.out.println("DEBUG TIMER: Video is paused");
                    // Player is paused - check if we can resume
                    // Resume if we have at least 2-3 chunks ahead available
                    if (lastConsecutiveChunk >= 2) {
                        mediaPlayerComponent.mediaPlayer().controls().play();
                        log("▶️ RESUMED: Consecutive chunks 0-" + lastConsecutiveChunk + " now available");
                        System.out.println("DEBUG TIMER: ▶️▶️▶️ VIDEO RESUMED - CHUNKS AVAILABLE ▶️▶️▶️");
                    } else {
                        System.out.println("DEBUG TIMER: Still waiting for more chunks (have 0-" + lastConsecutiveChunk + ", need >= 2)");
                    }
                }
                System.out.println("[END UPDATE UI TIMER]\n");
            } else if (download != null && download.isComplete()) {
                System.out.println("DEBUG TIMER: Download complete, no monitoring needed");
            }
        }
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
        JButton addDockerPeersButton = new JButton("🐳 Add Docker Peers");

        searchButton.addActionListener(e -> {
            String query = searchField.getText().trim();
            log("Searching for: " + (query.isEmpty() ? "*" : query));
            
            // 1. Broadcast to network peers (UDP) if query not empty
            if (!query.isEmpty()) {
                String message = "ID:" + discoveryService.getPeerId() + ":" + query;
                discoveryService.broadcastPacket(com.network.p2p.network.Protocol.TYPE_QUERY_FILES, message, 3);
            }
            
            // 2. Filter already loaded files (from manual peers like Docker)
            videoListModel.clear();
            for (VideoSearchResult vsr : searchResults.values()) {
                if (query.isEmpty() || vsr.fileName.toLowerCase().contains(query.toLowerCase())) {
                    videoListModel.addElement(vsr.getDisplayText());
                }
            }
            
            if (videoListModel.isEmpty()) {
                log("No files found" + (query.isEmpty() ? "" : " matching: " + query));
            } else {
                log("Found " + videoListModel.size() + " matching files");
            }
        });

        addDockerPeersButton.addActionListener(e -> {
            // Add Docker container peers via localhost port mappings
            peerManager.addManualPeer("docker-peer1", "127.0.0.1", 51001);
            peerManager.addManualPeer("docker-peer2", "127.0.0.1", 52001);
            peerManager.addManualPeer("docker-peer3", "127.0.0.1", 53001);
            log("✓ Added 3 Docker peers via localhost ports 51001, 52001, 53001");
            JOptionPane.showMessageDialog(this, 
                "Docker peers added!\n" +
                "- docker-peer1 @ localhost:51001\n" +
                "- docker-peer2 @ localhost:52001\n" +
                "- docker-peer3 @ localhost:53001\n\n" +
                "Now click 'Search Network' to find their files.",
                "Docker Peers Added", 
                JOptionPane.INFORMATION_MESSAGE);
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(searchButton);
        buttonPanel.add(addDockerPeersButton);

        panel.add(new JLabel("Search File: "), BorderLayout.WEST);
        panel.add(searchField, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.EAST);
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

                    // Show peer selection dialog if multiple peers available
                    java.util.List<String> selectedPeers = new java.util.ArrayList<>();
                    if (result.peerIds.size() > 1) {
                        // Multi-selection dialog
                        JPanel panel = new JPanel(new BorderLayout(10, 10));
                        panel.add(new JLabel("Select peers to download from:"), BorderLayout.NORTH);
                        
                        JList<String> peerList = new JList<>(result.peerIds.toArray(new String[0]));
                        peerList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
                        peerList.setSelectedIndex(0); // Select first by default
                        panel.add(new JScrollPane(peerList), BorderLayout.CENTER);
                        
                        int option = JOptionPane.showConfirmDialog(MainFrame.this, panel, 
                            "Download: " + fname, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                        
                        if (option != JOptionPane.OK_OPTION) return;
                        
                        java.util.List<String> selectedPeersList = peerList.getSelectedValuesList();
                        if (selectedPeersList.isEmpty()) {
                            JOptionPane.showMessageDialog(MainFrame.this, "Please select at least one peer", "No Peer Selected", JOptionPane.WARNING_MESSAGE);
                            return;
                        }
                        selectedPeers.addAll(selectedPeersList);
                    } else {
                        // Only one peer, use it directly
                        selectedPeers.addAll(result.peerIds);
                    }

                    log("=== DEBUG: Starting download of " + fname + " from " + selectedPeers.size() + " peer(s) ===");
                    log("DEBUG: Selected PeerIds: " + selectedPeers);

                    log("=== DEBUG: Starting download of " + fname + " from " + selectedPeers.size() + " peer(s) ===");
                    log("DEBUG: Selected PeerIds: " + selectedPeers);

                    // Collect ONLY SELECTED peers for this file
                    java.util.Set<String> peerIds = new java.util.HashSet<>();
                    java.util.Map<String, String> peerIdToIp = new java.util.HashMap<>();
                    java.util.Map<String, Integer> peerIdToPort = new java.util.HashMap<>();
                    
                    log("DEBUG: Total peers in PeerManager: " + peerManager.getPeers().size());
                    for (String peerId : selectedPeers) {
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

                    // Store current download hash for tracking
                    currentDownloadHash = hash;
                    
                    System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    System.out.println("🎬 STARTING NEW DOWNLOAD");
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    System.out.println("DEBUG DOWNLOAD START: File = " + fname);
                    System.out.println("DEBUG DOWNLOAD START: Hash = " + hash);
                    System.out.println("DEBUG DOWNLOAD START: Size = " + size + " bytes");
                    System.out.println("DEBUG DOWNLOAD START: Peers = " + peerIds.size());
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

                    downloadManager.startDownload(fname, hash, size, peerIds, peerIdToIp, peerIdToPort);

                    // Start Player immediately (progressive streaming)
                    if (mediaPlayerComponent != null && fileManager.getBufferFolder() != null) {
                        String path = new java.io.File(fileManager.getBufferFolder(), fname).getAbsolutePath();
                        // Wait a bit for first chunks to arrive
                        new Thread(() -> {
                            try {
                                System.out.println("\nDEBUG VLC: Waiting 1.5s for initial buffer...");
                                Thread.sleep(200); // Wait 1.5 seconds for initial buffering (reduced from 3s)
                                SwingUtilities.invokeLater(() -> {
                                    System.out.println("DEBUG VLC: Loading video into VLC player");
                                    System.out.println("DEBUG VLC: File path: " + path);
                                    
                                    // VLC options for progressive streaming
                                    String[] options = {
                                            ":file-caching=300",
                                            ":network-caching=300",
                                            ":live-caching=300",
                                            ":demux=avformat",           // ← EKLE: Incomplete file'ları zorla
                                            ":avformat-format=mp4",      // ← EKLE: MP4 parse et
                                            ":no-mov-faststart",         // ← EKLE: Moov atom'u bekleme
                                            ":clock-jitter=0",
                                            ":clock-synchro=0",
                                            ":start-paused"
                                        };
                                    mediaPlayerComponent.mediaPlayer().media().play(path, options);
                                    
                                    System.out.println("DEBUG VLC: Video loaded, setting to paused state");
                                    // Will be started by chunk listener when first chunks are ready
                                    mediaPlayerComponent.mediaPlayer().controls().setPause(true);
                                    
                                    log("📼 Loaded video: " + fname + " (waiting for sequential chunks to start playback)");
                                    System.out.println("DEBUG VLC: ✓ Video ready in paused state, waiting for chunks...\n");
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
