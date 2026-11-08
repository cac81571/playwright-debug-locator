package com.playwrightdebug.locator.service;

import com.playwrightdebug.locator.service.StrictModeViolationParser.Candidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uses {@code // #elementId} hints emitted by Recorder codegen to resolve strict-mode candidates.
 */
public final class RecorderIdHint {

    private static final Pattern TRAILING_ID_COMMENT = Pattern.compile("//\\s*#([A-Za-z_][\\w-]*)\\s*$");
    private static final Pattern TRAILING_ID_COMMENT_WITH_SPACE = Pattern.compile(
            "\\s*" + TRAILING_ID_COMMENT.pattern()
    );

    private RecorderIdHint() {
    }

    /** Removes trailing {@code // #id} comments (and surrounding spaces) from a source line. */
    public static String stripTrailingIdComment(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        return TRAILING_ID_COMMENT_WITH_SPACE.matcher(line).replaceAll("").stripTrailing();
    }

    /**
     * Finds an id hint on source lines that contain the failing locator call.
     */
    public static Optional<String> findHintForFailingLocator(String source, String failingLocator) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        List<String> lines = source.lines().toList();
        for (int lineIndex : findFailingLineIndexes(source, failingLocator)) {
            if (lineIndex < 0 || lineIndex >= lines.size()) {
                continue;
            }
            Matcher comment = TRAILING_ID_COMMENT.matcher(lines.get(lineIndex));
            if (comment.find()) {
                return Optional.of(comment.group(1));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns 0-based line indexes whose source contains the failing locator call.
     */
    public static List<Integer> findFailingLineIndexes(String source, String failingLocator) {
        List<Integer> indexes = new ArrayList<>();
        if (source == null || source.isBlank() || failingLocator == null || failingLocator.isBlank()) {
            return indexes;
        }

        String needle = failingLocator.trim();
        String bare = needle.startsWith("page.") ? needle.substring("page.".length()) : needle;
        String methodKey = methodKey(bare);

        List<String> lines = source.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lineContainsFailingCall(lines.get(i), bare, methodKey)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    public static Optional<Candidate> findCandidateByElementId(List<Candidate> candidates, String elementId) {
        if (candidates == null || candidates.isEmpty() || elementId == null || elementId.isBlank()) {
            return Optional.empty();
        }

        Pattern idAttr = Pattern.compile(
                "\\bid\\s*=\\s*(['\"])" + Pattern.quote(elementId) + "\\1",
                Pattern.CASE_INSENSITIVE
        );
        Pattern hashId = Pattern.compile("#" + Pattern.quote(elementId) + "\\b");

        List<Candidate> matches = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String summary = candidate.elementSummary() == null ? "" : candidate.elementSummary();
            String aka = candidate.akaLocator() == null ? "" : candidate.akaLocator();
            if (idAttr.matcher(summary).find()
                    || hashId.matcher(summary).find()
                    || hashId.matcher(aka).find()) {
                matches.add(candidate);
            }
        }
        if (matches.size() == 1) {
            return Optional.of(matches.get(0));
        }
        return Optional.empty();
    }

    private static String methodKey(String bareFailingLocator) {
        Matcher labelLike = Pattern.compile(
                "^(getByLabel|getByText|getByPlaceholder|getByAltText|getByTitle|getByTestId)\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\""
        ).matcher(bareFailingLocator);
        if (labelLike.find()) {
            return labelLike.group(1) + ":\"" + labelLike.group(2) + "\"";
        }
        Matcher roleLike = Pattern.compile(
                "^getByRole\\(\\s*(AriaRole\\.\\w+).*setName\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\""
        ).matcher(bareFailingLocator);
        if (roleLike.find()) {
            return "getByRole:" + roleLike.group(1) + ":\"" + roleLike.group(2) + "\"";
        }
        return bareFailingLocator;
    }

    private static boolean lineContainsFailingCall(String line, String bareFailing, String methodKey) {
        if (line.contains(bareFailing) || line.contains("page." + bareFailing)) {
            return true;
        }
        if (methodKey.startsWith("getByLabel:")
                || methodKey.startsWith("getByText:")
                || methodKey.startsWith("getByPlaceholder:")
                || methodKey.startsWith("getByAltText:")
                || methodKey.startsWith("getByTitle:")
                || methodKey.startsWith("getByTestId:")) {
            int colon = methodKey.indexOf(':');
            String method = methodKey.substring(0, colon);
            String quoted = methodKey.substring(colon + 1);
            return line.contains(method + "(") && line.contains(quoted);
        }
        if (methodKey.startsWith("getByRole:")) {
            String[] parts = methodKey.split(":", 3);
            if (parts.length == 3) {
                return line.contains("getByRole(") && line.contains(parts[1]) && line.contains(parts[2]);
            }
        }
        return false;
    }
}
