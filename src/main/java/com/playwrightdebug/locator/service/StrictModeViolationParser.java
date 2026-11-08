package com.playwrightdebug.locator.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Playwright strict-mode violation errors into selectable locator candidates.
 */
public final class StrictModeViolationParser {

    private static final Pattern HEADER = Pattern.compile(
            "strict mode violation:\\s*(.+?)\\s+resolved to\\s+(\\d+)\\s+elements:",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern CANDIDATE = Pattern.compile(
            "^\\s*(\\d+)\\)\\s*(.*?)\\s+aka\\s+(.+?)\\s*$",
            Pattern.MULTILINE
    );

    private StrictModeViolationParser() {
    }

    public static Optional<StrictModeViolation> parse(String errorText) {
        if (errorText == null || errorText.isBlank()) {
            return Optional.empty();
        }
        if (!errorText.toLowerCase().contains("strict mode violation")) {
            return Optional.empty();
        }

        Matcher header = HEADER.matcher(errorText);
        if (!header.find()) {
            return Optional.empty();
        }

        String failingLocator = header.group(1).trim();
        int reportedCount;
        try {
            reportedCount = Integer.parseInt(header.group(2));
        } catch (NumberFormatException ex) {
            reportedCount = -1;
        }

        // Preserve first occurrence order while dropping duplicate aka locators.
        Map<String, Candidate> uniqueByAka = new LinkedHashMap<>();
        Matcher candidateMatcher = CANDIDATE.matcher(errorText);
        while (candidateMatcher.find()) {
            int index = Integer.parseInt(candidateMatcher.group(1));
            String elementSummary = candidateMatcher.group(2).trim();
            String akaLocator = trimTrailingNoise(candidateMatcher.group(3).trim());
            String dedupeKey = normalizeAkaKey(akaLocator);
            uniqueByAka.putIfAbsent(dedupeKey, new Candidate(index, elementSummary, akaLocator));
        }

        List<Candidate> candidates = new ArrayList<>(uniqueByAka.values());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new StrictModeViolation(failingLocator, reportedCount, List.copyOf(candidates)));
    }

    private static String normalizeAkaKey(String akaLocator) {
        String key = akaLocator == null ? "" : akaLocator.trim();
        if (key.startsWith("page.")) {
            key = key.substring("page.".length());
        }
        return key.replaceAll("\\s+", " ");
    }

    private static String trimTrailingNoise(String akaLocator) {
        String value = akaLocator;
        int callLog = value.indexOf("\n");
        if (callLog >= 0) {
            value = value.substring(0, callLog).trim();
        }
        // Drop accidental trailing punctuation outside parentheses balance if needed.
        return value.strip();
    }

    public record Candidate(int index, String elementSummary, String akaLocator) {
        private static final Pattern ELEMENT_ID = Pattern.compile(
                "\\bid\\s*=\\s*(['\"])([^'\"]+)\\1",
                Pattern.CASE_INSENSITIVE
        );

        public Optional<String> elementId() {
            if (elementSummary == null || elementSummary.isBlank()) {
                return Optional.empty();
            }
            Matcher matcher = ELEMENT_ID.matcher(elementSummary);
            if (matcher.find()) {
                return Optional.of(matcher.group(2));
            }
            return Optional.empty();
        }

        public String displayLabel() {
            return index + ") " + pageExpression();
        }

        public String pageExpression() {
            String aka = akaLocator.trim();
            if (aka.startsWith("page.") || aka.startsWith("locator(") || aka.startsWith("frame.")) {
                return aka;
            }
            return "page." + aka;
        }
    }

    public record StrictModeViolation(String failingLocator, int reportedCount, List<Candidate> candidates) {
    }
}
