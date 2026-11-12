package com.playwrightdebug.locator.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists Playwright tab editor drafts under {@code ~/.playwright-debug-locator/}.
 */
final class PlaywrightWorkspaceStore {

    private static final Path DIR = Path.of(System.getProperty("user.home"), ".playwright-debug-locator");
    private static final Path JSON_FILE = DIR.resolve("recorder-json.json");
    private static final Path JAVA_FILE = DIR.resolve("playwright-java.java");

    private PlaywrightWorkspaceStore() {
    }

    static String loadJson() {
        return loadOrEmpty(JSON_FILE);
    }

    static String loadJava() {
        String saved = loadOrEmpty(JAVA_FILE);
        return saved.isBlank() ? defaultJavaText() : saved;
    }

    static String defaultJavaText() {
        return """
                import com.microsoft.playwright.junit.UsePlaywright;
                import com.microsoft.playwright.Page;
                import com.microsoft.playwright.options.*;

                import org.junit.jupiter.api.*;
                import static com.microsoft.playwright.assertions.PlaywrightAssertions.*;

                /**
                 * Sample Playwright Java code.
                 * CDP で選択中のタブに対して「Playwright 実行」できます。
                 */
                @UsePlaywright
                public class Sample {

                    @Test
                    void sample(Page page) {
                        page.navigate("https://example.com/");
                        page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions()
                                .setName("Example Domain")
                                .setExact(true)).waitFor();
                    }
                }
                """;
    }

    static void save(String json, String javaSource) {
        saveQuietly(JSON_FILE, json);
        saveQuietly(JAVA_FILE, javaSource);
    }

    private static String loadOrEmpty(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private static void saveQuietly(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text == null ? "" : text, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // best-effort persist on exit
        }
    }
}
