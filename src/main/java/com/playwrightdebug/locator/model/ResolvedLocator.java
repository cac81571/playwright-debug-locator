package com.playwrightdebug.locator.model;

import com.microsoft.playwright.Locator;

public record ResolvedLocator(String raw, String resolvedLabel, Locator locator) {
}
