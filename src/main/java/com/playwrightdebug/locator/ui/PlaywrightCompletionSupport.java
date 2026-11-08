package com.playwrightdebug.locator.ui;

import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.CompletionProvider;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.fife.ui.autocomplete.TemplateCompletion;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Installs Playwright Java API completions (Ctrl+Space) on an RSyntaxTextArea.
 */
final class PlaywrightCompletionSupport {

    private PlaywrightCompletionSupport() {
    }

    static void install(RSyntaxTextArea textArea) {
        AutoCompletion ac = new AutoCompletion(createProvider());
        ac.setAutoCompleteSingleChoices(false);
        ac.setShowDescWindow(true);
        ac.setParameterAssistanceEnabled(true);
        ac.setChoicesWindowSize(440, 300);
        ac.setDescriptionWindowSize(380, 240);
        ac.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
        ac.install(textArea);
    }

    private static CompletionProvider createProvider() {
        DefaultCompletionProvider provider = new DefaultCompletionProvider();
        provider.setAutoActivationRules(false, ".");

        add(provider, "page.navigate", "page.navigate(\"\");", "指定 URL へ遷移");
        add(provider, "page.setViewportSize", "page.setViewportSize(1280, 720);", "ビューポートサイズ");
        add(provider, "page.reload", "page.reload();", "再読み込み");
        add(provider, "page.goBack", "page.goBack();", "戻る");
        add(provider, "page.goForward", "page.goForward();", "進む");
        add(provider, "page.title", "page.title()", "ページタイトル");
        add(provider, "page.url", "page.url()", "現在の URL");

        add(provider, "page.getByRole",
                "page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(\"\").setExact(true))",
                "role + name で取得");
        add(provider, "page.getByLabel", "page.getByLabel(\"\")", "ラベルで取得");
        add(provider, "page.getByText", "page.getByText(\"\")", "テキストで取得");
        add(provider, "page.getByPlaceholder", "page.getByPlaceholder(\"\")", "placeholder で取得");
        add(provider, "page.getByTestId", "page.getByTestId(\"\")", "test id で取得");
        add(provider, "page.getByAltText", "page.getByAltText(\"\")", "alt で取得");
        add(provider, "page.getByTitle", "page.getByTitle(\"\")", "title で取得");
        add(provider, "page.locator", "page.locator(\"\")", "CSS / xpath= で取得");

        add(provider, "click", "click();", "クリック");
        add(provider, "dblclick", "dblclick();", "ダブルクリック");
        add(provider, "fill", "fill(\"\");", "入力");
        add(provider, "type", "type(\"\");", "タイプ入力");
        add(provider, "press", "press(\"Enter\");", "キー送信");
        add(provider, "check", "check();", "チェック");
        add(provider, "uncheck", "uncheck();", "チェック解除");
        add(provider, "hover", "hover();", "ホバー");
        add(provider, "focus", "focus();", "フォーカス");
        add(provider, "clear", "clear();", "クリア");
        add(provider, "selectOption", "selectOption(\"\");", "select 選択");
        add(provider, "waitFor", "waitFor();", "要素待ち");
        add(provider, "isVisible", "isVisible()", "表示判定");
        add(provider, "count", "count()", "件数");
        add(provider, "first", "first()", "先頭");
        add(provider, "nth", "nth(0)", "N 番目");
        add(provider, "filterVisible",
                "filter(new Locator.FilterOptions().setVisible(true))",
                "visible のみ");

        add(provider, "AriaRole.BUTTON", "AriaRole.BUTTON", "button");
        add(provider, "AriaRole.LINK", "AriaRole.LINK", "link");
        add(provider, "AriaRole.TEXTBOX", "AriaRole.TEXTBOX", "textbox");
        add(provider, "AriaRole.COMBOBOX", "AriaRole.COMBOBOX", "combobox");
        add(provider, "AriaRole.CHECKBOX", "AriaRole.CHECKBOX", "checkbox");
        add(provider, "AriaRole.RADIO", "AriaRole.RADIO", "radio");
        add(provider, "AriaRole.HEADING", "AriaRole.HEADING", "heading");
        add(provider, "AriaRole.IMG", "AriaRole.IMG", "img");
        add(provider, "AriaRole.LIST", "AriaRole.LIST", "list");
        add(provider, "AriaRole.LISTITEM", "AriaRole.LISTITEM", "listitem");
        add(provider, "AriaRole.MENU", "AriaRole.MENU", "menu");
        add(provider, "AriaRole.MENUITEM", "AriaRole.MENUITEM", "menuitem");
        add(provider, "AriaRole.DIALOG", "AriaRole.DIALOG", "dialog");
        add(provider, "AriaRole.TAB", "AriaRole.TAB", "tab");
        add(provider, "AriaRole.SEARCHBOX", "AriaRole.SEARCHBOX", "searchbox");
        add(provider, "AriaRole.SWITCH", "AriaRole.SWITCH", "switch");

        add(provider, "GetByRoleOptions",
                "new Page.GetByRoleOptions().setName(\"\").setExact(true)",
                "getByRole オプション");
        add(provider, "ClickOptions.RIGHT",
                "new Locator.ClickOptions().setButton(MouseButton.RIGHT)",
                "右クリック");
        add(provider, "assertThat.isVisible",
                "assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(\"\"))).isVisible();",
                "表示アサーション");
        add(provider, "assertThat.hasText",
                "assertThat(page.getByText(\"\")).hasText(\"\");",
                "テキストアサーション");

        provider.addCompletion(new TemplateCompletion(
                provider,
                "tpl-click-role",
                "template: getByRole.click",
                "page.getByRole(AriaRole.${role}, new Page.GetByRoleOptions().setName(\"${name}\").setExact(true)).click();${cursor}",
                "role クリック（タブで編集）",
                "Template: getByRole → click"
        ));
        provider.addCompletion(new TemplateCompletion(
                provider,
                "tpl-fill-role",
                "template: getByRole.fill",
                "page.getByRole(AriaRole.${role}, new Page.GetByRoleOptions().setName(\"${name}\").setExact(true)).fill(\"${value}\");${cursor}",
                "role 入力（タブで編集）",
                "Template: getByRole → fill"
        ));

        return provider;
    }

    private static void add(DefaultCompletionProvider provider, String trigger, String insert, String summary) {
        if (trigger.equals(insert)) {
            provider.addCompletion(new BasicCompletion(
                    provider,
                    insert,
                    summary,
                    html(summary, insert)
            ));
        } else {
            provider.addCompletion(new ShorthandCompletion(
                    provider,
                    trigger,
                    insert,
                    summary,
                    html(summary, insert)
            ));
        }
    }

    private static String html(String summary, String insert) {
        return "<html><b>" + escape(summary) + "</b><br><code>" + escape(insert) + "</code></html>";
    }

    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
