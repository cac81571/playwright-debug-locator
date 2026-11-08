package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.playwrightdebug.locator.model.PageInfo;
import com.playwrightdebug.locator.model.PlaywrightScriptContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlaywrightSession implements AutoCloseable {

    private static final AtomicInteger WORKER_SEQ = new AtomicInteger();

    private volatile ExecutorService executor = newWorker();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private volatile Playwright playwright;
    private volatile Browser browser;
    private volatile String endpointUrl;

    public <T> T run(Callable<T> task) throws Exception {
        return run(task, 120_000);
    }

    public <T> T run(Callable<T> task, long timeoutMs) throws Exception {
        ExecutorService current = executor;
        Future<T> future = current.submit(task);
        try {
            return future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            forceReset("操作が " + timeoutMs + " ms 以内に完了しませんでした。"
                    + " ページの読み込み待ちで Playwright が固まった可能性が高いです。再接続してください。");
            throw new TimeoutException("操作が " + timeoutMs + " ms 以内に完了しませんでした。"
                    + " ページの読み込み待ちで Playwright が固まった可能性が高いです。再接続してください。");
        } catch (Exception ex) {
            if (current.isShutdown()) {
                throw new IllegalStateException("Playwright ワーカーが停止しています。再接続してください。", ex);
            }
            throw ex;
        }
    }

    public void runVoid(Runnable task) throws Exception {
        runVoid(task, 120_000);
    }

    public void runVoid(Runnable task, long timeoutMs) throws Exception {
        run(() -> {
            task.run();
            return null;
        }, timeoutMs);
    }

    public boolean isConnected() {
        return browser != null;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void connect(String endpointUrl, double timeoutMs) throws Exception {
        String trimmed = endpointUrl == null ? "" : endpointUrl.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Endpoint URL が空です");
        }

        CdpPreflight preflight = probeCdpEndpoint(trimmed);
        if (!preflight.reachable()) {
            throw new IllegalStateException(
                    "CDP エンドポイントに到達できません (" + trimmed + ")。"
                            + " ブラウザを --remote-debugging-port 付きで起動しているか確認してください。"
                            + (preflight.detail().isBlank() ? "" : " 詳細: " + preflight.detail())
            );
        }

        long hardTimeout = Math.max(1_000L, (long) timeoutMs + 5_000L);
        try {
            runVoid(() -> {
                disconnectInternal();
                this.endpointUrl = trimmed;
                playwright = Playwright.create();
                BrowserType.ConnectOverCDPOptions options = new BrowserType.ConnectOverCDPOptions()
                        .setTimeout(timeoutMs);
                browser = playwright.chromium().connectOverCDP(this.endpointUrl, options);
            }, hardTimeout);
        } catch (Exception ex) {
            throw enrichConnectFailure(ex, preflight);
        }
    }

    public CdpPreflight probeCdpEndpoint(String endpointUrl) {
        String base = endpointUrl.endsWith("/")
                ? endpointUrl.substring(0, endpointUrl.length() - 1)
                : endpointUrl.trim();
        try {
            HttpRequest versionRequest = HttpRequest.newBuilder(URI.create(base + "/json/version"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> versionResponse = httpClient.send(versionRequest, HttpResponse.BodyHandlers.ofString());
            if (versionResponse.statusCode() < 200 || versionResponse.statusCode() >= 300) {
                return new CdpPreflight(false, 0, "HTTP " + versionResponse.statusCode());
            }

            int pageCount = 0;
            try {
                HttpRequest listRequest = HttpRequest.newBuilder(URI.create(base + "/json/list"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> listResponse = httpClient.send(listRequest, HttpResponse.BodyHandlers.ofString());
                if (listResponse.statusCode() >= 200 && listResponse.statusCode() < 300) {
                    pageCount = parseCdpPageTargets(listResponse.body()).size();
                }
            } catch (Exception ignored) {
                // version worked; page count is best-effort
            }
            return new CdpPreflight(true, pageCount, "");
        } catch (Exception ex) {
            return new CdpPreflight(false, 0, ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private static Exception enrichConnectFailure(Exception ex, CdpPreflight preflight) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        boolean afterWsConnected = message.contains("<ws connected>") || message.contains("ws connected");
        boolean timeout = message.contains("Timeout") || ex.getClass().getSimpleName().contains("Timeout");
        if (timeout && afterWsConnected) {
            return new IllegalStateException("""
                    CDP の WebSocket までは接続できましたが、Playwright の初期化でタイムアウトしました。
                    よくある原因: 読み込み中・スリープ・応答しないタブが残っている。
                    対処:
                      1) スピナー中のタブをリロードまたは閉じる
                      2) 新しいタブを1つ開き、問題のタブを閉じる
                      3) 接続 Timeout を一時的に長くする（根本対策にはならない場合あり）
                    検出ページ数: %d
                    ---
                    %s""".formatted(preflight.pageCount(), message).strip(), ex);
        }
        return ex;
    }

    public record CdpPreflight(boolean reachable, int pageCount, String detail) {
    }

    public List<PageInfo> listPages() throws Exception {
        // Prefer CDP /json/list for title/url — page.title()/stale url() are unreliable.
        List<CdpTarget> cdpTargets = fetchCdpPageTargets();

        return run(() -> {
            if (browser == null) {
                throw new IllegalStateException("CDP に未接続です");
            }

            List<Page> playwrightPages = new ArrayList<>();
            for (BrowserContext context : browser.contexts()) {
                playwrightPages.addAll(context.pages());
            }

            return matchPages(cdpTargets, playwrightPages);
        }, 10_000);
    }

    static List<PageInfo> matchPages(List<CdpTarget> cdpTargets, List<Page> playwrightPages) {
        List<CdpTarget> usableTargets = cdpTargets.stream()
                .filter(target -> !isBrowserUiTarget(target.title(), target.url()))
                .toList();

        List<PageInfo> pages = new ArrayList<>();
        Set<Page> usedPages = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<CdpTarget> unmatchedTargets = new ArrayList<>();

        Map<Page, String> urlsByPage = new IdentityHashMap<>();
        for (Page page : playwrightPages) {
            urlsByPage.put(page, normalizeUrl(safeValue(page::url)));
        }

        for (CdpTarget target : usableTargets) {
            String targetUrl = normalizeUrl(target.url());
            Page match = null;
            if (!targetUrl.isBlank()) {
                for (Page page : playwrightPages) {
                    if (usedPages.contains(page)) {
                        continue;
                    }
                    if (targetUrl.equals(urlsByPage.get(page))) {
                        match = page;
                        break;
                    }
                }
            }
            if (match != null) {
                usedPages.add(match);
                pages.add(new PageInfo(target.id(), target.title(), target.url(), match));
            } else {
                unmatchedTargets.add(target);
            }
        }

        // URL mismatch (navigation race / trailing slash / etc.): pair leftovers by order,
        // preferring CDP title/url so the dropdown reflects /json/list.
        List<Page> leftoverPages = new ArrayList<>();
        for (Page page : playwrightPages) {
            if (!usedPages.contains(page)) {
                leftoverPages.add(page);
            }
        }
        int leftoverIndex = 0;
        for (CdpTarget target : unmatchedTargets) {
            if (leftoverIndex < leftoverPages.size()) {
                Page page = leftoverPages.get(leftoverIndex++);
                usedPages.add(page);
                pages.add(new PageInfo(target.id(), target.title(), target.url(), page));
            } else {
                // New tab may appear in CDP before Playwright exposes a Page.
                pages.add(new PageInfo(target.id(), target.title(), target.url(), null));
            }
        }

        for (; leftoverIndex < leftoverPages.size(); leftoverIndex++) {
            Page page = leftoverPages.get(leftoverIndex);
            String url = urlsByPage.getOrDefault(page, "");
            if (isBrowserUiTarget("", url)) {
                continue;
            }
            pages.add(new PageInfo(UUID.randomUUID().toString(), "", url, page));
        }

        return pages;
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
        runVoid(this::disconnectInternal, 10_000);
    }

    private List<CdpTarget> fetchCdpPageTargets() {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return List.of();
        }

        try {
            String base = endpointUrl.endsWith("/")
                    ? endpointUrl.substring(0, endpointUrl.length() - 1)
                    : endpointUrl;
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/json/list"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            return parseCdpPageTargets(response.body());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    static List<CdpTarget> parseCdpPageTargets(String json) {
        // Preserve CDP order and allow duplicate URLs (multiple tabs on same site).
        Map<String, CdpTarget> uniqueById = new LinkedHashMap<>();
        int depth = 0;
        int objectStart = -1;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    String object = json.substring(objectStart, i + 1);
                    objectStart = -1;
                    if (!extractJsonString(object, "type").equals("page")) {
                        continue;
                    }
                    String id = unescapeJson(extractJsonString(object, "id"));
                    String url = unescapeJson(extractJsonString(object, "url"));
                    String title = unescapeJson(extractJsonString(object, "title"));
                    if (id.isBlank()) {
                        id = UUID.randomUUID().toString();
                    }
                    uniqueById.putIfAbsent(id, new CdpTarget(id, title, url));
                }
            }
        }
        return new ArrayList<>(uniqueById.values());
    }

    private static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.endsWith("/") && trimmed.length() > 8) {
            int scheme = trimmed.indexOf("://");
            if (scheme > 0 && trimmed.indexOf('/', scheme + 3) != trimmed.length() - 1) {
                // keep path "/" only as-is; strip trailing slash on longer paths
            }
        }
        return trimmed;
    }

    static boolean isBrowserUiTarget(String title, String url) {
        String safeTitle = title == null ? "" : title.trim();
        if (safeTitle.startsWith("DevTools")) {
            return true;
        }
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.startsWith("devtools://")
                || lower.startsWith("chrome://")
                || lower.startsWith("edge://")
                || lower.startsWith("chrome-extension://")
                || lower.startsWith("edge-extension://");
    }

    record CdpTarget(String id, String title, String url) {
    }

    private static String extractJsonString(String object, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = pattern.matcher(object);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static String unescapeJson(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case '"', '\\', '/' -> out.append(next);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 < value.length()) {
                            String hex = value.substring(i + 1, i + 5);
                            out.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                    }
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private synchronized void forceReset(String ignoredReason) {
        Playwright pw = playwright;
        Browser br = browser;
        playwright = null;
        browser = null;
        endpointUrl = null;

        // Close from this thread to unblock a hung playwright-worker call.
        try {
            if (br != null) {
                br.close();
            }
        } catch (Exception ignored) {
            // best effort
        }
        try {
            if (pw != null) {
                pw.close();
            }
        } catch (Exception ignored) {
            // best effort
        }

        ExecutorService old = executor;
        executor = newWorker();
        old.shutdownNow();
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

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "playwright-worker-" + WORKER_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void close() {
        forceReset("shutdown");
    }
}
