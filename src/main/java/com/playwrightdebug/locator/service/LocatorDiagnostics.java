package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Locator;
import com.playwrightdebug.locator.model.LocatorTestResult;

public final class LocatorDiagnostics {

    private LocatorDiagnostics() {
    }

    public static void appendElementSummary(LocatorTestResult result, String label, Locator locator) {
        result.addLine(label);
        result.addLine("  visible: " + locator.isVisible());
        result.addLine("  enabled: " + locator.isEnabled());
        result.addLine("  hidden:  " + locator.isHidden());

        Object info = locator.evaluate(
                "el => JSON.stringify({" +
                        "tag: el.tagName.toLowerCase()," +
                        "id: el.id || ''," +
                        "class: el.className || ''," +
                        "ariaLabel: el.getAttribute('aria-label') || ''," +
                        "text: (el.innerText || '').trim().slice(0, 80)" +
                        "})"
        );
        result.addLine("  element: " + info);
    }

    public static void appendActionabilityHint(LocatorTestResult result, Locator locator) {
        if (locator.count() == 0) {
            return;
        }

        if (!locator.isVisible()) {
            result.addLine("ヒント: 要素は DOM に存在しますが非表示です。click 等は失敗します。");
            result.addLine("      → .filter(new Locator.FilterOptions().setVisible(true))");
            result.addLine("      → より具体的なセレクター (role, aria-label, #id)");
        } else if (!locator.isEnabled()) {
            result.addLine("ヒント: 要素は表示されていますが disabled です。");
        }
    }
}
