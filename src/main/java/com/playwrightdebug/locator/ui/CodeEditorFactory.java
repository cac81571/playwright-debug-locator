package com.playwrightdebug.locator.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;

import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;

final class CodeEditorFactory {

    private CodeEditorFactory() {
    }

    static RSyntaxTextArea createEditor(String syntaxStyle, String initialText, int rows, int cols) {
        RSyntaxTextArea area = new RSyntaxTextArea(rows, cols);
        area.setSyntaxEditingStyle(syntaxStyle);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setAnimateBracketMatching(true);
        area.setAutoIndentEnabled(true);
        area.setTabSize(2);
        area.setTabsEmulated(true);
        area.setMarkOccurrences(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        if (initialText != null) {
            area.setText(initialText);
            area.setCaretPosition(0);
        }
        applyLightTheme(area);
        area.setHighlightCurrentLine(false);
        return area;
    }

    static RSyntaxTextArea createJsonEditor(String initialText) {
        return createEditor(SyntaxConstants.SYNTAX_STYLE_JSON, initialText, 18, 40);
    }

    static RSyntaxTextArea createJavaEditor(String initialText) {
        return createEditor(SyntaxConstants.SYNTAX_STYLE_JAVA, initialText, 18, 40);
    }

    static RTextScrollPane wrapWithLineNumbers(RSyntaxTextArea area) {
        RTextScrollPane scroll = new RTextScrollPane(area);
        scroll.setLineNumbersEnabled(true);
        scroll.setFoldIndicatorEnabled(true);
        return scroll;
    }

    private static void applyLightTheme(RSyntaxTextArea area) {
        try (InputStream in = CodeEditorFactory.class.getResourceAsStream(
                "/org/fife/ui/rsyntaxtextarea/themes/idea.xml")) {
            if (in != null) {
                Theme.load(in).apply(area);
            }
        } catch (IOException ignored) {
            // keep default colors
        }
    }
}
