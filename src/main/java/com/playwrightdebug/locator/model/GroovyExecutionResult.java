package com.playwrightdebug.locator.model;

import com.microsoft.playwright.Locator;

public record GroovyExecutionResult(Locator locator, Object rawResult) {

    public boolean hasLocator() {
        return locator != null;
    }

    public boolean isVoidResult() {
        return locator == null && rawResult == null;
    }
}
