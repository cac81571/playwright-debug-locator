package com.playwrightdebug.locator;

import com.formdev.flatlaf.FlatLightLaf;
import com.playwrightdebug.locator.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        FlatLightLaf.setup();
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("ScrollPane.smoothScrolling", true);

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
