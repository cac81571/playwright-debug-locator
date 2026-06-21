package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.playwrightdebug.locator.model.PageInfo;
import com.playwrightdebug.locator.model.PlaywrightScriptContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class PlaywrightSession implements AutoCloseable {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "playwright-worker");
        thread.setDaemon(true);
        return thread;
    });

    private Playwright playwright;
    private Browser browser;
    private String endpointUrl;

    public <T> T run(Callable<T> task) throws Exception {
        Future<T> future = executor.submit(task);
        return future.get(120, TimeUnit.SECONDS);
    }

    public void runVoid(Runnable task) throws Exception {
        run(() -> {
            task.run();
            return null;
        });
    }

    public boolean isConnected() {
        return browser != null;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void connect(String endpointUrl) throws Exception {
        runVoid(() -> {
            disconnectInternal();
            this.endpointUrl = endpointUrl.trim();
            playwright = Playwright.create();
            BrowserType.ConnectOverCDPOptions options = new BrowserType.ConnectOverCDPOptions()
                    .setTimeout(30_000);
            browser = playwright.chromium().connectOverCDP(this.endpointUrl, options);
        });
    }

    public List<PageInfo> listPages() throws Exception {
        return run(() -> {
            if (browser == null) {
                throw new IllegalStateException("CDP に未接続です");
            }

            List<PageInfo> pages = new ArrayList<>();
            for (BrowserContext context : browser.contexts()) {
                for (Page page : context.pages()) {
                    String title = safeValue(() -> page.title());
                    String url = safeValue(page::url);
                    pages.add(new PageInfo(UUID.randomUUID().toString(), title, url, page));
                }
            }
            return pages;
        });
    }

    public Page requirePage(PageInfo pageInfo) {
        if (pageInfo == null || pageInfo.page() == null) {
            throw new IllegalStateException("ページが選択されていません");
        }
        return pageInfo.page();
    }

    public PlaywrightScriptContext createContext(Page page) {
        if (playwright == null || browser == null) {
            throw new IllegalStateException("CDP に未接続です");
        }
        return new PlaywrightScriptContext(playwright, browser, page.context(), page);
    }

    public void disconnect() throws Exception {
        runVoid(this::disconnectInternal);
    }

    private void disconnectInternal() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
        endpointUrl = null;
    }

    private static String safeValue(Callable<String> supplier) {
        try {
            return supplier.call();
        } catch (Exception ignored) {
            return "";
        }
    }

    @Override
    public void close() {
        try {
            disconnect();
        } catch (Exception ignored) {
            // best effort on shutdown
        }
        executor.shutdownNow();
    }
}
