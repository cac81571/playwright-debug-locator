package com.playwrightdebug.locator.ui;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Insertable Playwright Java snippets for the recorder editor.
 */
final class PlaywrightSnippetCatalog {

    record Snippet(String label, String code) {
    }

    private static volatile List<Snippet> cached = List.of();

    private PlaywrightSnippetCatalog() {
    }

    static void reload() {
        reloadFromText(AssistCatalogStore.loadSnippetsText());
    }

    static void reloadFromText(String catalogText) {
        List<Snippet> next = new ArrayList<>();
        for (AssistCatalogStore.SnippetItem item : AssistCatalogStore.parseSnippets(catalogText)) {
            next.add(new Snippet(item.label(), item.code()));
        }
        cached = List.copyOf(next);
    }

    static List<Snippet> all() {
        if (cached.isEmpty()) {
            reload();
        }
        return cached;
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

            String indent = leadingIndent(editor, start);
            String indented = applyIndent(code, indent);

            if (end > start) {
                document.remove(start, end - start);
            }
            document.insertString(start, indented, null);
            int caret = start + indented.length();
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
