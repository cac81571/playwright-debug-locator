package com.playwrightdebug.locator.ui;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.util.List;

/**
 * Insertable Playwright Java snippets for the recorder editor.
 */
final class PlaywrightSnippetCatalog {

    record Snippet(String label, String code) {
    }

    private PlaywrightSnippetCatalog() {
    }

    static List<Snippet> all() {
        return List.of(
                new Snippet("navigate",
                        "page.navigate(\"https://example.com/\");"),
                new Snippet("setViewport",
                        "page.setViewportSize(1280, 720);"),
                new Snippet("getByRole → click",
                        "page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(\"\").setExact(true)).click();"),
                new Snippet("getByRole → fill",
                        "page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName(\"\").setExact(true)).fill(\"\");"),
                new Snippet("getByLabel → fill",
                        "page.getByLabel(\"\").fill(\"\");"),
                new Snippet("getByText → click",
                        "page.getByText(\"\").click();"),
                new Snippet("locator(CSS) → click",
                        "page.locator(\"\").click();"),
                new Snippet("locator(xpath) → click",
                        "page.locator(\"xpath=\").click();"),
                new Snippet("press Enter",
                        "page.keyboard().press(\"Enter\");"),
                new Snippet("waitFor",
                        "page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(\"\")).waitFor();"),
                new Snippet("assert visible",
                        "assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(\"\"))).isVisible();"),
                new Snippet("assert text",
                        "assertThat(page.getByText(\"\")).hasText(\"\");"),
                new Snippet("filter visible",
                        "page.locator(\"\").filter(new Locator.FilterOptions().setVisible(true)).click();"),
                new Snippet("right click",
                        "page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(\"\"))"
                                + ".click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));")
        );
    }

    static void insert(JTextComponent editor, Snippet snippet) {
        if (editor == null || snippet == null) {
            return;
        }
        try {
            Document document = editor.getDocument();
            int start = Math.min(editor.getSelectionStart(), editor.getSelectionEnd());
            int end = Math.max(editor.getSelectionStart(), editor.getSelectionEnd());
            String code = snippet.code();

            // Keep surrounding indent when inserting on an empty/whitespace line.
            String indent = leadingIndent(editor, start);
            String indented = applyIndent(code, indent);

            if (end > start) {
                document.remove(start, end - start);
            }
            document.insertString(start, indented, null);
            int caret = start + indented.length();
            // Place caret inside the first empty string literal if present.
            int emptyString = indented.indexOf("\"\"");
            if (emptyString >= 0) {
                caret = start + emptyString + 1;
            }
            editor.setCaretPosition(Math.min(caret, document.getLength()));
            editor.requestFocusInWindow();
        } catch (BadLocationException ignored) {
            // ignore
        }
    }

    private static String leadingIndent(JTextComponent editor, int offset) {
        try {
            Document document = editor.getDocument();
            int lineStart = offset;
            while (lineStart > 0) {
                String ch = document.getText(lineStart - 1, 1);
                if ("\n".equals(ch) || "\r".equals(ch)) {
                    break;
                }
                lineStart--;
            }
            StringBuilder indent = new StringBuilder();
            int i = lineStart;
            while (i < offset && i < document.getLength()) {
                String ch = document.getText(i, 1);
                if (" ".equals(ch) || "\t".equals(ch)) {
                    indent.append(ch);
                    i++;
                } else {
                    break;
                }
            }
            // If inserting mid-line with no indent captured, fall back to 8 spaces (method body).
            if (indent.isEmpty() && lineStart == offset) {
                return "        ";
            }
            return indent.toString();
        } catch (BadLocationException ex) {
            return "        ";
        }
    }

    private static String applyIndent(String code, String indent) {
        String[] lines = code.split("\\R", -1);
        if (lines.length == 1) {
            return code;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n').append(indent);
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
