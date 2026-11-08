package com.playwrightdebug.locator.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Persists CDP port input history under {@code ~/.playwright-debug-locator/ports.txt}.
 * First line is the most recently used port.
 */
final class PortHistoryStore {

    static final int DEFAULT_PORT = 9222;
    private static final int MAX_ENTRIES = 20;
    private static final Path FILE = Path.of(
            System.getProperty("user.home"), ".playwright-debug-locator", "ports.txt");

    private PortHistoryStore() {
    }

    static List<String> load() {
        try {
            if (!Files.isRegularFile(FILE)) {
                return List.of(String.valueOf(DEFAULT_PORT));
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                String port = normalize(line);
                if (port != null) {
                    unique.add(port);
                }
                if (unique.size() >= MAX_ENTRIES) {
                    break;
                }
            }
            if (unique.isEmpty()) {
                return List.of(String.valueOf(DEFAULT_PORT));
            }
            return List.copyOf(unique);
        } catch (IOException ex) {
            return List.of(String.valueOf(DEFAULT_PORT));
        }
    }

    static String lastPort() {
        List<String> history = load();
        return history.isEmpty() ? String.valueOf(DEFAULT_PORT) : history.get(0);
    }

    static List<String> remember(String rawPort) {
        String port = normalize(rawPort);
        if (port == null) {
            return load();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        unique.add(port);
        for (String existing : load()) {
            unique.add(existing);
            if (unique.size() >= MAX_ENTRIES) {
                break;
            }
        }
        List<String> history = new ArrayList<>(unique);
        try {
            Files.createDirectories(FILE.getParent());
            Files.write(FILE, history, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // keep in-memory result even if disk write fails
        }
        return history;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            int port = Integer.parseInt(trimmed);
            if (port < 1 || port > 65535) {
                return null;
            }
            return String.valueOf(port);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
