package com.playwrightdebug.locator.ui;

import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Installs Playwright Java completions (Ctrl+Space).
 * Order: prefix filter → ----- → display-condition matches → ----- → others.
 */
final class PlaywrightCompletionSupport {

    private static final PrefixCompletionProvider PROVIDER = new PrefixCompletionProvider();

    private PlaywrightCompletionSupport() {
    }

    static void install(RSyntaxTextArea textArea) {
        reload();
        AutoCompletion ac = new AutoCompletion(PROVIDER) {
            @Override
            protected void insertCompletion(Completion c, boolean typedPopup) {
                if (c instanceof SeparatorCompletion) {
                    return;
                }
                super.insertCompletion(c, typedPopup);
            }
        };
        ac.setAutoCompleteSingleChoices(false);
        ac.setShowDescWindow(true);
        ac.setParameterAssistanceEnabled(true);
        ac.setChoicesWindowSize(440, 300);
        ac.setDescriptionWindowSize(380, 240);
        ac.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
        ac.install(textArea);
    }

    static void reload() {
        reloadFromText(AssistCatalogStore.loadCompletionsText());
    }

    static void reloadFromText(String catalogText) {
        PROVIDER.clear();
        for (AssistCatalogStore.CompletionItem item : AssistCatalogStore.parseCompletions(catalogText)) {
            PROVIDER.addCompletion(new CatalogCompletion(
                    PROVIDER,
                    item.text(),
                    item.condition(),
                    item.summary()
            ));
        }
    }

    private static String html(String summary, String insert, String condition) {
        String title = summary == null || summary.isBlank() ? insert : summary;
        StringBuilder sb = new StringBuilder("<html><b>")
                .append(escape(title))
                .append("</b><br><code>")
                .append(escape(insert))
                .append("</code>");
        if (condition != null && !condition.isBlank()) {
            sb.append("<br>when: <code>").append(escape(condition)).append("</code>");
        }
        sb.append("</html>");
        return sb.toString();
    }

    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String lineContextBeforeCaret(JTextComponent comp) {
        try {
            Document doc = comp.getDocument();
            int caret = comp.getCaretPosition();
            Element root = doc.getDefaultRootElement();
            int lineIndex = root.getElementIndex(caret);
            Element line = root.getElement(lineIndex);
            int start = line.getStartOffset();
            if (caret <= start) {
                return "";
            }
            return doc.getText(start, caret - start);
        } catch (BadLocationException ex) {
            return "";
        }
    }

    private static final class CatalogCompletion extends BasicCompletion {
        private final String condition;

        CatalogCompletion(PrefixCompletionProvider provider, String text, String condition, String summary) {
            super(provider, text, summary == null ? "" : summary, html(summary, text, condition));
            this.condition = condition == null ? "" : condition;
        }

        String condition() {
            return condition;
        }

        boolean matchesCondition(String context) {
            if (condition.isBlank() || context == null || context.isEmpty()) {
                return false;
            }
            return context.contains(condition);
        }
    }

    private static final class SeparatorCompletion extends BasicCompletion {
        SeparatorCompletion(PrefixCompletionProvider provider, int relevance) {
            super(provider, "-----");
            setRelevance(relevance);
        }
    }

    /**
     * Treats {@code .} as part of the entered token so {@code AriaRole.} prefixes match.
     */
    private static final class PrefixCompletionProvider extends DefaultCompletionProvider {
        @Override
        protected boolean isValidChar(char ch) {
            return Character.isJavaIdentifierPart(ch) || ch == '.';
        }

        @Override
        @SuppressWarnings("unchecked")
        protected List<Completion> getCompletionsImpl(JTextComponent comp) {
            String entered = getAlreadyEnteredText(comp);
            if (entered == null) {
                entered = "";
            }
            String context = lineContextBeforeCaret(comp);

            List<CatalogCompletion> prefixMatches = new ArrayList<>();
            List<CatalogCompletion> conditionMatches = new ArrayList<>();
            List<CatalogCompletion> others = new ArrayList<>();

            for (Completion completion : completions) {
                if (!(completion instanceof CatalogCompletion item)) {
                    continue;
                }
                boolean prefixOk = !entered.isEmpty()
                        && startsWithIgnoreCase(item.getInputText(), entered);
                boolean conditionOk = item.matchesCondition(context);

                if (prefixOk) {
                    prefixMatches.add(item);
                } else if (conditionOk) {
                    conditionMatches.add(item);
                } else {
                    others.add(item);
                }
            }

            List<Completion> result = new ArrayList<>();
            int relevance = 100_000;

            for (CatalogCompletion item : prefixMatches) {
                item.setRelevance(relevance--);
                result.add(item);
            }
            if (!prefixMatches.isEmpty() && (!conditionMatches.isEmpty() || !others.isEmpty())) {
                result.add(new SeparatorCompletion(this, relevance--));
            }
            for (CatalogCompletion item : conditionMatches) {
                item.setRelevance(relevance--);
                result.add(item);
            }
            if (!conditionMatches.isEmpty() && !others.isEmpty()) {
                result.add(new SeparatorCompletion(this, relevance--));
            }
            for (CatalogCompletion item : others) {
                item.setRelevance(relevance--);
                result.add(item);
            }
            return result;
        }
    }
}
