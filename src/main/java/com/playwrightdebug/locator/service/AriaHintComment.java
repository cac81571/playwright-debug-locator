package com.playwrightdebug.locator.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Machine-readable ARIA hint stored as a trailing comment on generated locator lines.
 * Generation writes only what Recorder JSON contains (no role inference).
 * <pre>
 * page.locator("#foo").click(); // @aria name="Google 検索"
 * page.locator("#bar").click(); // @aria role=button name="送信"
 * </pre>
 */
public final class AriaHintComment {

    public static final Pattern PATTERN = Pattern.compile(
            "//\\s*@aria(?:\\s+role=([A-Za-z0-9_-]+))?\\s+name=\"((?:\\\\.|[^\"\\\\])*)\""
    );

    /** Matches optional whitespace plus an {@code // @aria ...} comment. */
    private static final Pattern COMMENT_WITH_LEADING_SPACE = Pattern.compile(
            "\\s*" + PATTERN.pattern()
    );

    private static final Pattern ARIA_WITH_ROLE = Pattern.compile(
            "^aria/(.*)\\[role=\"([^\"]+)\"\\]$"
    );

    private final String role;
    private final String name;

    public AriaHintComment(String role, String name) {
        this.role = blankToNull(role);
        this.name = name == null ? "" : name;
    }

    public String role() {
        return role;
    }

    public String name() {
        return name;
    }

    public boolean hasRole() {
        return role != null && !role.isBlank();
    }

    public String toComment() {
        if (hasRole()) {
            return "// @aria role=" + role + " name=\"" + escapeJava(name) + "\"";
        }
        return "// @aria name=\"" + escapeJava(name) + "\"";
    }

    public String describe() {
        if (hasRole()) {
            return "@aria role=" + role + " name=\"" + name + "\"";
        }
        return "@aria name=\"" + name + "\"";
    }

    public AriaHintComment withRole(String newRole) {
        return new AriaHintComment(newRole, name);
    }

    public static Optional<AriaHintComment> parse(String lineOrComment) {
        if (lineOrComment == null || lineOrComment.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(lineOrComment);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new AriaHintComment(matcher.group(1), unescapeJava(matcher.group(2))));
    }

    /** Removes {@code // @aria ...} (and surrounding spaces) from a source line. */
    public static String stripFrom(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        return COMMENT_WITH_LEADING_SPACE.matcher(line).replaceAll("").stripTrailing();
    }

    /**
     * Builds an ARIA hint from Recorder JSON only. Does not infer missing roles.
     */
    public static Optional<AriaHintComment> fromStep(Map<?, ?> step) {
        return extractRawAria(step).map(raw -> new AriaHintComment(raw.role(), raw.name()));
    }

    private static Optional<RawAria> extractRawAria(Map<?, ?> step) {
        Object selectorsRaw = step.get("selectors");
        if (!(selectorsRaw instanceof java.util.List<?> alternatives)) {
            return Optional.empty();
        }
        for (Object alternative : alternatives) {
            if (!(alternative instanceof java.util.List<?> parts)) {
                continue;
            }
            for (Object partObj : parts) {
                String part = partObj == null ? "" : String.valueOf(partObj);
                if (!part.startsWith("aria/")) {
                    continue;
                }
                Matcher withRole = ARIA_WITH_ROLE.matcher(part);
                if (withRole.matches()) {
                    return Optional.of(new RawAria(withRole.group(2), withRole.group(1)));
                }
                return Optional.of(new RawAria(null, part.substring("aria/".length())));
            }
        }
        return Optional.empty();
    }

    private record RawAria(String role, String name) {
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String escapeJava(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
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
