package com.playwrightdebug.locator.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Persists Codegen initial URL history under {@code ~/.playwright-debug-locator/codegen-urls.txt}.
 * First line is the most recently used URL.
 */
final class CodegenUrlHistoryStore {

    private static final int MAX_ENTRIES = 20;
    private static final Path FILE = Path.of(
            System.getProperty("user.home"), ".playwright-debug-locator", "codegen-urls.txt");

    private CodegenUrlHistoryStore() {
    }

    static List<String> load() {
        try {
            if (!Files.isRegularFile(FILE)) {
                return List.of();
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                String url = normalize(line);
                if (url != null) {
                    unique.add(url);
                }
                if (unique.size() >= MAX_ENTRIES) {
                    break;
                }
            }
            return List.copyOf(unique);
        } catch (IOException ex) {
            return List.of();
        }
    }

    static String lastUrl() {
        List<String> history = load();
        return history.isEmpty() ? "" : history.get(0);
    }

    static List<String> remember(String rawUrl) {
        String url = normalize(rawUrl);
        if (url == null) {
            return load();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        unique.add(url);
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
        return trimmed.isEmpty() ? null : trimmed;
    }
}
