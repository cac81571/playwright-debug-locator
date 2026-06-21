package com.playwrightdebug.locator.model;

import com.microsoft.playwright.Page;

public record PageInfo(String id, String title, String url, Page page) {

    @Override
    public String toString() {
        String safeTitle = title == null || title.isBlank() ? "(no title)" : title;
        String safeUrl = url == null || url.isBlank() ? "(about:blank)" : url;
        return safeTitle + " — " + safeUrl;
    }
}
