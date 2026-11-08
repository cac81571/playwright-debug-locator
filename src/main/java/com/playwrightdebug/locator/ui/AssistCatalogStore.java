package com.playwrightdebug.locator.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Loads / saves editable completion and snippet catalogs under
 * {@code ~/.playwright-debug-locator/}.
 */
final class AssistCatalogStore {

    record CompletionItem(String text, String condition, String summary) {
    }

    record SnippetItem(String label, String code) {
    }

    private static final Path DIR = Path.of(System.getProperty("user.home"), ".playwright-debug-locator");
    private static final Path COMPLETIONS_FILE = DIR.resolve("completions.txt");
    private static final Path SNIPPETS_FILE = DIR.resolve("snippets.txt");

    private AssistCatalogStore() {
    }

    static Path completionsPath() {
        return COMPLETIONS_FILE;
    }

    static Path snippetsPath() {
        return SNIPPETS_FILE;
    }

    static String defaultCompletionsText() {
        return """
                # 1 行 = 1 補完。
                # 形式: 挿入テキスト
                # 形式: 挿入テキスト # 説明
                # 形式: 挿入テキスト @ 表示条件
                # 形式: 挿入テキスト @ 表示条件 # 説明
                # 表示順: 前方一致 → ----- → 表示条件一致 → ----- → その他

                page.navigate("");
                page.setViewportSize(1280, 720);
                page.reload();
                page.goBack();
                page.goForward();
                page.title()
                page.url()
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("").setExact(true))
                page.getByLabel("")
                page.getByText("")
                page.getByPlaceholder("")
                page.getByTestId("")
                page.getByAltText("")
                page.getByTitle("")
                page.locator("")

                click();
                dblclick();
                fill("");
                type("");
                press("Enter");
                check();
                uncheck();
                hover();
                focus();
                clear();
                selectOption("");
                waitFor();
                isVisible()
                count()
                first()
                nth(0)
                filter(new Locator.FilterOptions().setVisible(true))

                AriaRole.BUTTON @ getByRole(
                AriaRole.LINK @ getByRole(
                AriaRole.TEXTBOX @ getByRole(
                AriaRole.COMBOBOX @ getByRole(
                AriaRole.CHECKBOX @ getByRole(
                AriaRole.RADIO @ getByRole(
                AriaRole.HEADING @ getByRole(
                AriaRole.IMG @ getByRole(
                AriaRole.LIST @ getByRole(
                AriaRole.LISTITEM @ getByRole(
                AriaRole.MENU @ getByRole(
                AriaRole.MENUITEM @ getByRole(
                AriaRole.DIALOG @ getByRole(
                AriaRole.TAB @ getByRole(
                AriaRole.SEARCHBOX @ getByRole(
                AriaRole.SWITCH @ getByRole(

                new Page.GetByRoleOptions().setName("").setExact(true) @ getByRole(
                new Locator.ClickOptions().setButton(MouseButton.RIGHT) @ click(
                assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(""))).isVisible();
                assertThat(page.getByText("")).hasText("");
                """.stripIndent();
    }

    static String defaultSnippetsText() {
        return """
                # ### ラベル の次の行から、次の ### または末尾までがコードです。

                ### navigate
                page.navigate("https://example.com/");

                ### setViewport
                page.setViewportSize(1280, 720);

                ### getByRole → click
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("").setExact(true)).click();

                ### getByRole → fill
                page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("").setExact(true)).fill("");

                ### getByLabel → fill
                page.getByLabel("").fill("");

                ### getByText → click
                page.getByText("").click();

                ### locator(CSS) → click
                page.locator("").click();

                ### locator(xpath) → click
                page.locator("xpath=").click();

                ### press Enter
                page.keyboard().press("Enter");

                ### waitFor
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("")).waitFor();

                ### assert visible
                assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(""))).isVisible();

                ### assert text
                assertThat(page.getByText("")).hasText("");

                ### filter visible
                page.locator("").filter(new Locator.FilterOptions().setVisible(true)).click();

                ### right click
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(""))
                        .click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));
                """.stripIndent();
    }

    static String loadCompletionsText() {
        return loadOrDefault(COMPLETIONS_FILE, defaultCompletionsText());
    }

    static String loadSnippetsText() {
        return loadOrDefault(SNIPPETS_FILE, defaultSnippetsText());
    }

    static void saveCompletionsText(String text) throws IOException {
        save(COMPLETIONS_FILE, text);
    }

    static void saveSnippetsText(String text) throws IOException {
        save(SNIPPETS_FILE, text);
    }

    static List<CompletionItem> parseCompletions(String text) {
        List<CompletionItem> items = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return items;
        }
        for (String rawLine : text.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String summary = "";
            String body = line;
            int hash = indexOfSummaryHash(line);
            if (hash >= 0) {
                body = line.substring(0, hash).trim();
                summary = line.substring(hash + 1).trim();
            }
            String condition = "";
            int at = body.indexOf(" @ ");
            if (at >= 0) {
                condition = body.substring(at + 3).trim();
                body = body.substring(0, at).trim();
            }
            if (!body.isEmpty()) {
                items.add(new CompletionItem(body, condition, summary));
            }
        }
        return items;
    }

    static List<SnippetItem> parseSnippets(String text) {
        List<SnippetItem> items = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return items;
        }
        String[] lines = text.split("\\R", -1);
        String label = null;
        StringBuilder code = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("###")) {
                flushSnippet(items, label, code);
                label = trimmed.substring(3).trim();
                code.setLength(0);
                continue;
            }
            if (label == null) {
                continue;
            }
            if (trimmed.startsWith("#") && code.length() == 0) {
                continue;
            }
            if (code.length() > 0) {
                code.append('\n');
            }
            code.append(line);
        }
        flushSnippet(items, label, code);
        return items;
    }

    private static void flushSnippet(List<SnippetItem> items, String label, StringBuilder code) {
        if (label == null || label.isBlank()) {
            return;
        }
        String body = trimTrailingBlankLines(code.toString());
        if (body.isBlank()) {
            return;
        }
        items.add(new SnippetItem(label, body));
    }

    private static String trimTrailingBlankLines(String value) {
        String[] lines = value.split("\\R", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].isBlank()) {
            end--;
        }
        if (end == lines.length) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /** Treat " # " as summary separator; ignore # inside quotes roughly by requiring space before #. */
    private static int indexOfSummaryHash(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '#') {
                continue;
            }
            if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    private static String loadOrDefault(Path file, String defaultText) {
        try {
            if (Files.isRegularFile(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // fall through
        }
        return Objects.requireNonNullElse(defaultText, "");
    }

    private static void save(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text == null ? "" : text, StandardCharsets.UTF_8);
    }
}
