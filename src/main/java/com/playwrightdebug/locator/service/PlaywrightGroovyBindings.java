package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public final class PlaywrightGroovyBindings {

    private PlaywrightGroovyBindings() {
    }

    public static Page.GetByRoleOptions roleOptions(String name) {
        return new Page.GetByRoleOptions().setName(name);
    }

    public static Page.GetByRoleOptions roleOptions() {
        return new Page.GetByRoleOptions();
    }

    public static Locator.FilterOptions visibleFilter() {
        return new Locator.FilterOptions().setVisible(true);
    }
}
