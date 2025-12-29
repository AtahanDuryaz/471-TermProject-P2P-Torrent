package com.network.p2p;

import com.formdev.flatlaf.FlatDarkLaf;
import com.network.p2p.gui.MainFrame;
import javax.swing.SwingUtilities;

public class P2PVideoApp {
    public static void main(String[] args) {
        // Setup FlatLaf Theme
        FlatDarkLaf.setup();

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
