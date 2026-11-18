package com.playwrightdebug.locator.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Playwright Java scripts under {@code ~/.playwright-debug-locator/scripts/}.
 * Supports nested folders. Relative paths use {@code /} and omit the {@code .java} suffix for scripts.
 */
final class PlaywrightScriptLibraryStore {

    record Entry(String relativePath, boolean directory) {
        String displayName() {
            int slash = relativePath.lastIndexOf('/');
            return slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
        }
    }

    private static final Path DIR = Path.of(
            System.getProperty("user.home"), ".playwright-debug-locator", "scripts");

    private PlaywrightScriptLibraryStore() {
    }

    static Path scriptsDir() {
        return DIR;
    }

    static List<Entry> listChildren(String relativeDir) {
        Path dir = resolveDir(relativeDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (Files.isDirectory(child)) {
                    entries.add(new Entry(join(relativeDir, name), true));
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".java")) {
                    String scriptName = name.substring(0, name.length() - 5);
                    entries.add(new Entry(join(relativeDir, scriptName), false));
                }
            }
        } catch (IOException ex) {
            return List.of();
        }
        entries.sort(Comparator
                .comparing(Entry::directory).reversed()
                .thenComparing(e -> e.displayName().toLowerCase(Locale.ROOT)));
        return List.copyOf(entries);
    }

    static boolean existsScript(String relativePath) {
        String safe = sanitizeRelativePath(relativePath, false);
        return safe != null && Files.isRegularFile(scriptFile(safe));
    }

    static boolean existsDirectory(String relativePath) {
        String safe = sanitizeRelativePath(relativePath, true);
        return safe != null && Files.isDirectory(resolveDir(safe));
    }

    static String load(String relativePath) throws IOException {
        String safe = requireScriptPath(relativePath);
        Path file = scriptFile(safe);
        if (!Files.isRegularFile(file)) {
            throw new IOException("スクリプトが見つかりません: " + safe);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    static String save(String relativePath, String source) throws IOException {
        String safe = requireScriptPath(relativePath);
        Path file = scriptFile(safe);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source == null ? "" : source, StandardCharsets.UTF_8);
        return safe;
    }

    static String createDirectory(String relativePath) throws IOException {
        String safe = requireDirectoryPath(relativePath);
        Path dir = resolveDir(safe);
        Files.createDirectories(dir);
        return safe;
    }

    static void delete(String relativePath, boolean directory) throws IOException {
        if (directory) {
            String safe = requireDirectoryPath(relativePath);
            Path dir = resolveDir(safe);
            if (!Files.isDirectory(dir)) {
                throw new IOException("フォルダが見つかりません: " + safe);
            }
            deleteRecursively(dir);
            return;
        }
        String safe = requireScriptPath(relativePath);
        Path file = scriptFile(safe);
        if (!Files.isRegularFile(file)) {
            throw new IOException("スクリプトが見つかりません: " + safe);
        }
        Files.delete(file);
    }

    /**
     * Sanitizes a relative path. {@code allowDirectory} permits empty (root) when true for parent lookup.
     */
    static String sanitizeRelativePath(String relativePath, boolean allowEmpty) {
        if (relativePath == null) {
            return allowEmpty ? "" : null;
        }
        String normalized = relativePath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return allowEmpty ? "" : null;
        }
        String[] parts = normalized.split("/");
        List<String> safeParts = new ArrayList<>();
        for (String part : parts) {
            String safe = sanitizeSegment(part);
            if (safe == null) {
                return null;
            }
            safeParts.add(safe);
        }
        return String.join("/", safeParts);
    }

    static String parentPath(String relativePath) {
        String safe = sanitizeRelativePath(relativePath, true);
        if (safe == null || safe.isEmpty()) {
            return "";
        }
        int slash = safe.lastIndexOf('/');
        return slash < 0 ? "" : safe.substring(0, slash);
    }

    static String join(String parent, String child) {
        String p = parent == null ? "" : parent.trim();
        String c = child == null ? "" : child.trim();
        if (p.isEmpty()) {
            return c;
        }
        if (c.isEmpty()) {
            return p;
        }
        return p + "/" + c;
    }

    /** @deprecated flat-list API kept for compatibility; prefer path-based methods. */
    static List<String> list() {
        List<String> names = new ArrayList<>();
        for (Entry entry : listChildren("")) {
            if (!entry.directory()) {
                names.add(entry.relativePath());
            }
        }
        return List.copyOf(names);
    }

    /** @deprecated use {@link #existsScript(String)} */
    static boolean exists(String name) {
        return existsScript(name);
    }

    static String sanitizeName(String name) {
        return sanitizeSegment(name);
    }

    private static String sanitizeSegment(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(".java")) {
            trimmed = trimmed.substring(0, trimmed.length() - 5).trim();
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || "<>:\"/\\|?*".indexOf(c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String safe = sb.toString().trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) {
            return null;
        }
        return safe;
    }

    private static String requireScriptPath(String relativePath) throws IOException {
        String safe = sanitizeRelativePath(relativePath, false);
        if (safe == null) {
            throw new IOException("スクリプトパスが不正です");
        }
        return safe;
    }

    private static String requireDirectoryPath(String relativePath) throws IOException {
        String safe = sanitizeRelativePath(relativePath, false);
        if (safe == null) {
            throw new IOException("フォルダパスが不正です");
        }
        return safe;
    }

    private static Path scriptFile(String relativePath) {
        Path path = DIR;
        String[] parts = relativePath.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i == parts.length - 1) {
                path = path.resolve(parts[i] + ".java");
            } else {
                path = path.resolve(parts[i]);
            }
        }
        return path.normalize();
    }

    private static Path resolveDir(String relativeDir) {
        String safe = sanitizeRelativePath(relativeDir, true);
        if (safe == null || safe.isEmpty()) {
            return DIR;
        }
        Path path = DIR;
        for (String part : safe.split("/")) {
            path = path.resolve(part);
        }
        return path.normalize();
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
