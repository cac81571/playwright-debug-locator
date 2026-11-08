package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public entry for rewriting generated Java locators using a live CDP page.
 */
public final class LiveJavaRoleEnricher {

    private static final Pattern NAVIGATE_CALL = Pattern.compile(
            "page\\.navigate\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\)"
    );

    private LiveJavaRoleEnricher() {
    }

    public record Result(String source, int replacements, List<String> notes) {
    }

    public static Result enrich(Page page, String javaSource) {
        List<String> prepNotes = new ArrayList<>();
        preparePageForEnrichment(page, javaSource, prepNotes);

        RecorderRoleEnricher.JavaEnrichResult result = RecorderRoleEnricher.of(page).enrichJavaSource(javaSource);
        List<String> notes = new ArrayList<>(prepNotes);
        notes.addAll(result.notes());
        return new Result(result.source(), result.replacements(), List.copyOf(notes));
    }

    /**
     * ARIA rewrite runs before the script, so ensure we are on the recording's target page
     * when the source contains {@code page.navigate(...)}.
     */
    private static void preparePageForEnrichment(Page page, String javaSource, List<String> notes) {
        if (page == null || javaSource == null || javaSource.isBlank()) {
            return;
        }
        Optional<String> target = lastNavigateUrl(javaSource);
        if (target.isEmpty()) {
            return;
        }
        String targetUrl = target.get();
        String current;
        try {
            current = page.url();
        } catch (Exception ex) {
            current = "";
        }
        if (sameSite(current, targetUrl)) {
            return;
        }
        try {
            page.navigate(targetUrl);
            notes.add("ARIA 変換前に navigate: " + targetUrl);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            notes.add("ARIA 変換前 navigate 失敗: " + message.lines().findFirst().orElse(message));
        }
    }

    private static Optional<String> lastNavigateUrl(String javaSource) {
        Matcher matcher = NAVIGATE_CALL.matcher(javaSource);
        String last = null;
        while (matcher.find()) {
            last = unescapeJava(matcher.group(1));
        }
        return last == null || last.isBlank() ? Optional.empty() : Optional.of(last);
    }

    private static boolean sameSite(String current, String target) {
        if (current == null || current.isBlank() || target == null || target.isBlank()) {
            return false;
        }
        try {
            java.net.URI cur = java.net.URI.create(current);
            java.net.URI tgt = java.net.URI.create(target);
            String ch = cur.getHost() == null ? "" : cur.getHost();
            String th = tgt.getHost() == null ? "" : tgt.getHost();
            return !ch.isBlank() && ch.equalsIgnoreCase(th);
        } catch (Exception ex) {
            return current.startsWith(target) || target.startsWith(current);
        }
    }

    private static String unescapeJava(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char n = value.charAt(++i);
            sb.append(switch (n) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case '"' -> '"';
                case '\\' -> '\\';
                default -> n;
            });
        }
        return sb.toString();
    }
}
