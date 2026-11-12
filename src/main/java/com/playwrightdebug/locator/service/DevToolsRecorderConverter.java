package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Chrome/Edge DevTools Recorder JSON exports into Playwright Java JUnit 5 tests.
 */
public final class DevToolsRecorderConverter {

    public ConversionResult convert(String json) {
        throw new IllegalArgumentException("CDP ページ付きの convert(json, page) を使用してください");
    }

    /**
     * Collects navigate URLs from a Recorder export (step url + assertedEvents), in order.
     */
    public static List<String> extractNavigateUrls(String json) {
        List<String> urls = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return urls;
        }
        Object parsed;
        try {
            parsed = SimpleJson.parse(json);
        } catch (Exception ex) {
            return urls;
        }
        if (!(parsed instanceof Map<?, ?> recording)) {
            return urls;
        }
        Object stepsRaw = recording.get("steps");
        if (!(stepsRaw instanceof List<?> steps)) {
            return urls;
        }
        for (Object stepObj : steps) {
            if (!(stepObj instanceof Map<?, ?> step)) {
                continue;
            }
            if (!"navigate".equals(stringValue(step.get("type")))) {
                continue;
            }
            String url = stringValue(step.get("url"));
            if (!url.isBlank()) {
                urls.add(url);
            }
            Object events = step.get("assertedEvents");
            if (events instanceof List<?> list) {
                for (Object eventObj : list) {
                    if (!(eventObj instanceof Map<?, ?> event)) {
                        continue;
                    }
                    String eventUrl = stringValue(event.get("url"));
                    if (!eventUrl.isBlank()) {
                        urls.add(eventUrl);
                    }
                }
            }
        }
        return urls;
    }

    public static Optional<String> extractLastNavigateUrl(String json) {
        List<String> urls = extractNavigateUrls(json);
        if (urls.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(urls.get(urls.size() - 1));
    }

    /**
     * @param page live CDP page used to read HTML attributes and verify locator candidates
     */
    public ConversionResult convert(String json, Page page) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Recorder JSON が空です");
        }
        if (page == null) {
            throw new IllegalArgumentException("CDP ページが必要です（接続してページを選択してください）");
        }

        Object parsed;
        try {
            parsed = SimpleJson.parse(json);
        } catch (Exception ex) {
            throw new IllegalArgumentException("JSON の解析に失敗しました: " + ex.getMessage(), ex);
        }
        if (!(parsed instanceof Map<?, ?> recording)) {
            throw new IllegalArgumentException("Recorder JSON のルートはオブジェクトである必要があります");
        }

        String title = stringValue(recording.get("title"));
        Object stepsRaw = recording.get("steps");
        if (!(stepsRaw instanceof List<?> steps) || steps.isEmpty()) {
            throw new IllegalArgumentException("steps 配列が見つからないか空です");
        }

        String className = toJavaClassName(title);
        String methodName = "recordedFlow";
        List<String> warnings = new ArrayList<>();
        List<String> bodyLines = new ArrayList<>();
        warnings.add("生成方針: role ARIA → getByLabel → getByText → placeholder → testId → id/CSS/XPath（未使用候補は行末コメント）");

        RecorderLocatorPicker picker = new RecorderLocatorPicker(page);
        ConversionContext ctx = new ConversionContext(page, picker, warnings);

        for (int i = 0; i < steps.size(); i++) {
            Object stepObj = steps.get(i);
            if (!(stepObj instanceof Map<?, ?> step)) {
                warnings.add("steps[" + i + "]: オブジェクトではないためスキップ");
                continue;
            }
            String type = stringValue(step.get("type"));
            if (type.isBlank()) {
                warnings.add("steps[" + i + "]: type がないためスキップ");
                continue;
            }
            try {
                List<String> lines = convertStep(type, step, ctx, i);
                if (lines.isEmpty()) {
                    if ("setViewport".equals(type)) {
                        continue;
                    }
                    warnings.add("steps[" + i + "] (" + type + "): 未対応のためコメント出力");
                    bodyLines.add("// TODO: unsupported step type \"" + type + "\"");
                } else {
                    bodyLines.addAll(lines);
                }
            } catch (Exception ex) {
                warnings.add("steps[" + i + "] (" + type + "): " + ex.getMessage());
                bodyLines.add("// TODO: failed to convert step \"" + type + "\": "
                        + escapeJava(ex.getMessage()));
            }
        }

        String code = buildJavaSource(className, methodName, title, bodyLines);
        return new ConversionResult(code, className, warnings);
    }

    private record ConversionContext(Page page, RecorderLocatorPicker picker, List<String> warnings) {
    }

    private static List<String> convertStep(
            String type,
            Map<?, ?> step,
            ConversionContext ctx,
            int stepIndex
    ) {
        return switch (type) {
            case "setViewport" -> List.of();
            case "navigate" -> List.of(convertNavigate(step, ctx));
            case "click" -> List.of(actionWithLocator(step, ctx, false, ".click();",
                    ".click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));",
                    ".click(new Locator.ClickOptions().setButton(MouseButton.MIDDLE));"));
            case "doubleClick" -> List.of(actionWithLocator(step, ctx, true, ".dblclick();",
                    ".dblclick(new Locator.DblclickOptions().setButton(MouseButton.RIGHT));",
                    ".dblclick();"));
            case "change" -> {
                String value = stringValue(step.get("value"));
                RecorderLocatorPicker.Pick pick = ctx.picker().pick(step);
                yield List.of(pick.expression() + ".fill(\"%s\");".formatted(escapeJava(value))
                        + unusedSuffix(pick));
            }
            case "keyDown" -> List.of(convertKey(step, true));
            case "keyUp" -> List.of(convertKey(step, false));
            case "hover" -> {
                RecorderLocatorPicker.Pick pick = ctx.picker().pick(step);
                yield List.of(pick.expression() + ".hover();" + unusedSuffix(pick));
            }
            case "scroll" -> List.of(convertScroll(step, ctx));
            case "waitForElement" -> {
                RecorderLocatorPicker.Pick pick = ctx.picker().pick(step);
                yield List.of(pick.expression() + ".waitFor();" + unusedSuffix(pick));
            }
            case "waitForExpression" -> List.of(convertWaitForExpression(step));
            default -> List.of();
        };
    }

    private static String unusedSuffix(RecorderLocatorPicker.Pick pick) {
        if (pick.unusedComments() == null || pick.unusedComments().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String comment : pick.unusedComments()) {
            String singleLine = RecorderLocatorPicker.toSingleLineComment(comment);
            if (!singleLine.isEmpty()) {
                sb.append(" // ").append(singleLine);
            }
        }
        return sb.toString();
    }

    private static String actionWithLocator(
            Map<?, ?> step,
            ConversionContext ctx,
            boolean doubleClick,
            String primarySuffix,
            String rightSuffix,
            String middleSuffix
    ) {
        RecorderLocatorPicker.Pick pick = ctx.picker().pick(step);
        String button = stringValue(step.get("button")).toLowerCase(Locale.ROOT);
        String suffix = primarySuffix;
        if ("secondary".equals(button) || "right".equals(button)) {
            suffix = rightSuffix;
        } else if (!doubleClick && ("auxiliary".equals(button) || "middle".equals(button))) {
            suffix = middleSuffix;
        }
        return pick.expression() + suffix + unusedSuffix(pick);
    }

    private static String convertNavigate(Map<?, ?> step, ConversionContext ctx) {
        String url = stringValue(step.get("url"));
        if (url.isBlank()) {
            throw new IllegalArgumentException("navigate.url が空です");
        }
        if (ctx.page() != null) {
            try {
                ctx.page().navigate(url);
            } catch (Exception ex) {
                ctx.warnings().add("navigate 実行失敗（生成は継続）: " + ex.getMessage());
            }
        }
        return "page.navigate(\"%s\");".formatted(escapeJava(url));
    }

    private static String convertKey(Map<?, ?> step, boolean down) {
        String key = stringValue(step.get("key"));
        if (key.isBlank()) {
            throw new IllegalArgumentException("key が空です");
        }
        String method = down ? "down" : "up";
        return "page.keyboard().%s(\"%s\");".formatted(method, escapeJava(key));
    }

    private static String convertScroll(Map<?, ?> step, ConversionContext ctx) {
        int x = intValue(step.get("x"), 0);
        int y = intValue(step.get("y"), 0);
        Object selectors = step.get("selectors");
        if (selectors instanceof Collection<?> && !((Collection<?>) selectors).isEmpty()) {
            RecorderLocatorPicker.Pick pick = ctx.picker().pick(step);
            return pick.expression()
                    + ".evaluate(\"el => { el.scrollTop = %d; el.scrollLeft = %d; }\");"
                    .formatted(y, x)
                    + unusedSuffix(pick);
        }
        return "page.mouse().wheel(%d, %d);".formatted(x, y);
    }

    private static String convertWaitForExpression(Map<?, ?> step) {
        String expression = stringValue(step.get("expression"));
        if (expression.isBlank()) {
            throw new IllegalArgumentException("waitForExpression.expression が空です");
        }
        return "page.waitForFunction(\"%s\");".formatted(escapeJava(expression));
    }

    static String toAriaRoleEnum(String role) {
        String normalized = role.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return "AriaRole." + normalized.toUpperCase(Locale.ROOT);
    }

    private static String buildJavaSource(
            String className,
            String methodName,
            String title,
            List<String> bodyLines
    ) {
        StringBuilder body = new StringBuilder();
        if (bodyLines.isEmpty()) {
            body.append("        // (no convertible steps)\n");
        } else {
            for (String line : bodyLines) {
                body.append("        ").append(line).append('\n');
            }
        }

        String titleComment = title.isBlank() ? "DevTools Recorder export" : title;
        return """
                import com.microsoft.playwright.junit.UsePlaywright;
                import com.microsoft.playwright.Page;
                import com.microsoft.playwright.options.*;

                import org.junit.jupiter.api.*;
                import static com.microsoft.playwright.assertions.PlaywrightAssertions.*;

                /**
                 * Generated from DevTools Recorder: %s
                 */
                @UsePlaywright
                public class %s {

                    @Test
                    void %s(Page page) {
                %s    }
                }
                """.formatted(
                escapeJava(titleComment),
                className,
                methodName,
                body.toString()
        );
    }

    /**
     * Extracts a Groovy-executable Playwright script from generated (or edited) JUnit Java source.
     * Uses the selected page from CDP — class / @Test / @UsePlaywright wrappers are stripped.
     */
    public static String extractExecutableScript(String javaSource) {
        if (javaSource == null || javaSource.isBlank()) {
            throw new IllegalArgumentException("実行する Playwright コードが空です");
        }

        String source = javaSource.strip();
        String methodBody = extractTestMethodBody(source);
        if (methodBody != null) {
            String cleaned = stripLeadingIndent(methodBody).strip();
            if (cleaned.isBlank()) {
                throw new IllegalArgumentException("テストメソッド本体が空です");
            }
            return cleaned;
        }

        // Already a bare script (user edited down to statements only).
        String bare = stripPackageAndImports(source).strip();
        if (bare.isBlank()) {
            throw new IllegalArgumentException("実行可能な文が見つかりません");
        }
        return bare;
    }

    private static String extractTestMethodBody(String source) {
        // Prefer @Test annotated method, otherwise first method taking Page.
        int searchFrom = 0;
        int testAnnotation = indexOfIgnoreStrings(source, "@Test", 0);
        if (testAnnotation >= 0) {
            searchFrom = testAnnotation;
        }

        Pattern methodPattern = Pattern.compile(
                "\\b(?:public\\s+|protected\\s+|private\\s+)?void\\s+\\w+\\s*\\(\\s*Page\\s+\\w+\\s*\\)\\s*\\{"
        );
        Matcher matcher = methodPattern.matcher(source);
        boolean found = matcher.find(searchFrom);
        if (!found && searchFrom > 0) {
            found = matcher.find(0);
        }
        if (!found) {
            return null;
        }

        int bodyStart = matcher.end();
        int bodyEnd = findMatchingBrace(source, bodyStart - 1);
        if (bodyEnd < 0) {
            throw new IllegalArgumentException("メソッド本体の { } が対応していません");
        }
        return source.substring(bodyStart, bodyEnd);
    }

    private static int findMatchingBrace(String source, int openIndex) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escape = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = openIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
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
            if (inChar) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int indexOfIgnoreStrings(String source, String needle, int from) {
        return source.indexOf(needle, from);
    }

    private static String stripLeadingIndent(String body) {
        List<String> lines = body.lines().toList();
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            minIndent = Math.min(minIndent, indent);
        }
        if (minIndent == Integer.MAX_VALUE || minIndent == 0) {
            return body;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.isBlank()) {
                sb.append('\n');
            } else {
                sb.append(line.substring(Math.min(minIndent, line.length()))).append('\n');
            }
        }
        return sb.toString();
    }

    private static String stripPackageAndImports(String source) {
        StringBuilder sb = new StringBuilder();
        for (String line : source.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")
                    || trimmed.startsWith("import ")
                    || trimmed.startsWith("@")
                    || trimmed.startsWith("public class ")
                    || trimmed.startsWith("class ")
                    || trimmed.equals("{")
                    || trimmed.equals("}")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    static String toJavaClassName(String title) {
        String source = title == null || title.isBlank() ? "RecordedFlow" : title;
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        if (sb.isEmpty()) {
            return "RecordedFlowTest";
        }
        if (Character.isDigit(sb.charAt(0))) {
            sb.insert(0, "Recording");
        }
        if (!sb.toString().endsWith("Test")) {
            sb.append("Test");
        }
        return sb.toString();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
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
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public record ConversionResult(String javaSource, String className, List<String> warnings) {
    }
}
