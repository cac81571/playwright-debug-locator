package com.playwrightdebug.locator.model;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

public record PlaywrightScriptContext(
        Playwright playwright,
        Browser browser,
        BrowserContext context,
        Page page
) {
}
