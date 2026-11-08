package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Locator;
import com.playwrightdebug.locator.model.LocatorTestResult;

public final class LocatorDiagnostics {

    private LocatorDiagnostics() {
    }

    public static void appendElementSummary(LocatorTestResult result, String label, Locator locator) {
        result.addLine(label);
        result.addLine("  visible: " + safeBool(locator::isVisible));
        result.addLine("  enabled: " + safeBool(locator::isEnabled));
        result.addLine("  hidden:  " + safeBool(locator::isHidden));

        try {
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
        } catch (Exception ex) {
            result.addLine("  element: (取得失敗: " + brief(ex) + ")");
        }
    }

    public static void appendActionabilityHint(LocatorTestResult result, Locator locator) {
        int count;
        try {
            count = locator.count();
        } catch (Exception ex) {
            result.addLine("ヒント: count 取得失敗 (" + brief(ex) + ")");
            return;
        }
        if (count == 0) {
            return;
        }

        Boolean visible = safeBoolOrNull(locator::isVisible);
        Boolean enabled = safeBoolOrNull(locator::isEnabled);
        if (visible != null && !visible) {
            result.addLine("ヒント: 要素は DOM に存在しますが非表示です。click 等は失敗します。");
            result.addLine("      → .filter(new Locator.FilterOptions().setVisible(true))");
            result.addLine("      → より具体的なセレクター (role, aria-label, #id)");
        } else if (enabled != null && !enabled) {
            result.addLine("ヒント: 要素は表示されていますが disabled です。");
        }
    }

    private static String safeBool(BooleanSupplier supplier) {
        Boolean value = safeBoolOrNull(supplier);
        return value == null ? "(不明)" : String.valueOf(value);
    }

    private static Boolean safeBoolOrNull(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String brief(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String firstLine = message.lines().findFirst().orElse(message).trim();
        if (firstLine.length() <= 120) {
            return firstLine;
        }
        return firstLine.substring(0, 117) + "...";
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
