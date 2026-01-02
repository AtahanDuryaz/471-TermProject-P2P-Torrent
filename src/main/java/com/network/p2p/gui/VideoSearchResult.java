package com.network.p2p.gui;

import java.util.ArrayList;
import java.util.List;

public class VideoSearchResult {
    public String fileName;
    public long size;
    public String hash;
    public List<String> peerIds;

    public VideoSearchResult(String fileName, long size, String hash, String peerId) {
        this.fileName = fileName;
        this.size = size;
        this.hash = hash;
        this.peerIds = new ArrayList<>();
        this.peerIds.add(peerId);
    }

    public void addPeer(String peerId) {
        if (!peerIds.contains(peerId)) {
            peerIds.add(peerId);
        }
    }

    public String getDisplayText() {
        return fileName + " (" + (size / 1024) + " KB) - " + peerIds.size() + " peer(s)";
    }
}
