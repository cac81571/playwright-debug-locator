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

    private static final Pattern ARIA_WITH_ROLE = Pattern.compile(
            "^aria/(.*)\\[role=\"([^\"]+)\"\\]$"
    );

    private static final Pattern CSS_ID = Pattern.compile("#([A-Za-z_][\\w-]*)");
    private static final Pattern ATTR_ID = Pattern.compile("\\[id=(['\"])([^'\"]+)\\1\\]");

    public ConversionResult convert(String json) {
        return convert(json, null);
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
     * @param page unused (kept for call-site compatibility); generation does not require CDP
     */
    public ConversionResult convert(String json, Page page) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Recorder JSON が空です");
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
        warnings.add("生成方針: CSS/XPath 本体 + JSON 記載の // @aria のみ（Role 推測なし。判定は実行時）");

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
                List<String> lines = convertStep(type, step, warnings, i);
                if (lines.isEmpty()) {
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

    private static List<String> convertStep(
            String type,
            Map<?, ?> step,
            List<String> warnings,
            int stepIndex
    ) {
        return switch (type) {
            case "setViewport" -> List.of(convertSetViewport(step));
            case "navigate" -> List.of(convertNavigate(step));
            case "click" -> List.of(withLocatorComments(step, warnings, stepIndex,
                    convertClick(step, false)));
            case "doubleClick" -> List.of(withLocatorComments(step, warnings, stepIndex,
                    convertClick(step, true)));
            case "change" -> List.of(withLocatorComments(step, warnings, stepIndex,
                    convertChange(step)));
            case "keyDown" -> List.of(convertKey(step, true));
            case "keyUp" -> List.of(convertKey(step, false));
            case "hover" -> List.of(withLocatorComments(step, warnings, stepIndex,
                    locatorExpression(step) + ".hover();"));
            case "scroll" -> List.of(withLocatorComments(step, warnings, stepIndex,
                    convertScroll(step)));
            case "waitForElement" -> List.of(withLocatorComments(step, warnings, stepIndex,
                    locatorExpression(step) + ".waitFor();"));
            case "waitForExpression" -> List.of(convertWaitForExpression(step));
            default -> List.of();
        };
    }

    private static String withLocatorComments(
            Map<?, ?> step,
            List<String> warnings,
            int stepIndex,
            String codeLine
    ) {
        StringBuilder line = new StringBuilder(codeLine);
        Optional<AriaHintComment> aria = AriaHintComment.fromStep(step);
        if (aria.isPresent()) {
            line.append(' ').append(aria.get().toComment());
            warnings.add("steps[" + stepIndex + "]: " + aria.get().describe()
                    + (aria.get().hasRole() ? "" : "（role は JSON 未記載 → 実行時判定）"));
        }
        extractCssIdHint(step).ifPresent(id -> line.append(" // #").append(id));
        return line.toString();
    }

    /**
     * Pulls a DOM id hint from CSS / pierce alternatives so strict-mode recovery can match it later.
     */
    static Optional<String> extractCssIdHint(Map<?, ?> step) {
        Object selectorsRaw = step.get("selectors");
        if (!(selectorsRaw instanceof List<?> alternatives)) {
            return Optional.empty();
        }
        String found = null;
        for (Object alternative : alternatives) {
            if (!(alternative instanceof List<?> parts)) {
                continue;
            }
            for (Object partObj : parts) {
                String part = stringValue(partObj);
                if (part.startsWith("aria/") || part.startsWith("text/") || part.startsWith("xpath/")) {
                    continue;
                }
                String css = part.startsWith("pierce/") ? part.substring("pierce/".length()) : part;
                Matcher attr = ATTR_ID.matcher(css);
                if (attr.find()) {
                    found = attr.group(2);
                    continue;
                }
                Matcher id = CSS_ID.matcher(css);
                if (id.find()) {
                    found = id.group(1);
                }
            }
        }
        return found == null || found.isBlank() ? Optional.empty() : Optional.of(found);
    }

    private static String convertSetViewport(Map<?, ?> step) {
        int width = intValue(step.get("width"), 1280);
        int height = intValue(step.get("height"), 720);
        return "page.setViewportSize(%d, %d);".formatted(width, height);
    }

    private static String convertNavigate(Map<?, ?> step) {
        String url = stringValue(step.get("url"));
        if (url.isBlank()) {
            throw new IllegalArgumentException("navigate.url が空です");
        }
        return "page.navigate(\"%s\");".formatted(escapeJava(url));
    }

    private static String convertClick(Map<?, ?> step, boolean doubleClick) {
        String locator = locatorExpression(step);
        String button = stringValue(step.get("button")).toLowerCase(Locale.ROOT);
        if (doubleClick) {
            if ("secondary".equals(button) || "right".equals(button)) {
                return locator + ".dblclick(new Locator.DblclickOptions().setButton(MouseButton.RIGHT));";
            }
            return locator + ".dblclick();";
        }
        if ("secondary".equals(button) || "right".equals(button)) {
            return locator + ".click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));";
        }
        if ("auxiliary".equals(button) || "middle".equals(button)) {
            return locator + ".click(new Locator.ClickOptions().setButton(MouseButton.MIDDLE));";
        }
        return locator + ".click();";
    }

    private static String convertChange(Map<?, ?> step) {
        String value = stringValue(step.get("value"));
        return locatorExpression(step) + ".fill(\"%s\");".formatted(escapeJava(value));
    }

    private static String convertKey(Map<?, ?> step, boolean down) {
        String key = stringValue(step.get("key"));
        if (key.isBlank()) {
            throw new IllegalArgumentException("key が空です");
        }
        String method = down ? "down" : "up";
        return "page.keyboard().%s(\"%s\");".formatted(method, escapeJava(key));
    }

    private static String convertScroll(Map<?, ?> step) {
        int x = intValue(step.get("x"), 0);
        int y = intValue(step.get("y"), 0);
        Object selectors = step.get("selectors");
        if (selectors instanceof Collection<?> && !((Collection<?>) selectors).isEmpty()) {
            return locatorExpression(step)
                    + ".evaluate(\"el => { el.scrollTop = %d; el.scrollLeft = %d; }\");"
                    .formatted(y, x);
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

    static String locatorExpression(Map<?, ?> step) {
        Object selectorsRaw = step.get("selectors");
        if (!(selectorsRaw instanceof List<?> alternatives) || alternatives.isEmpty()) {
            throw new IllegalArgumentException("selectors がありません");
        }

        List<?> chosen = chooseStructuralSelector(alternatives);
        if (chosen.isEmpty()) {
            throw new IllegalArgumentException("有効な selector がありません");
        }

        String first = stringValue(chosen.get(0));
        if (chosen.size() == 1) {
            return toStructuralLocator("page", first);
        }

        String expr = "page";
        for (Object part : chosen) {
            expr = toStructuralLocator(expr, stringValue(part));
        }
        return expr;
    }

    /**
     * Prefers CSS (especially with id), then XPath/pierce/text. ARIA is last-resort only.
     */
    private static List<?> chooseStructuralSelector(List<?> alternatives) {
        List<?> fallback = List.of();
        List<?> cssWithId = null;
        List<?> cssFallback = null;
        List<?> textFallback = null;
        List<?> xpath = null;
        List<?> pierce = null;
        List<?> ariaFallback = null;

        for (Object alternative : alternatives) {
            if (!(alternative instanceof List<?> parts) || parts.isEmpty()) {
                continue;
            }
            if (fallback.isEmpty()) {
                fallback = parts;
            }
            String head = stringValue(parts.get(0));
            if (head.startsWith("aria/")) {
                if (ariaFallback == null) {
                    ariaFallback = parts;
                }
            } else if (textFallback == null && head.startsWith("text/")) {
                textFallback = parts;
            } else if (xpath == null && head.startsWith("xpath/")) {
                xpath = parts;
            } else if (pierce == null && head.startsWith("pierce/")) {
                pierce = parts;
            } else if (isCssSelector(head)) {
                boolean hasId = head.contains("#") || head.contains("[id=") || head.contains("[id =");
                if (hasId && cssWithId == null) {
                    cssWithId = parts;
                } else if (cssFallback == null) {
                    cssFallback = parts;
                }
            }
        }

        if (cssWithId != null) {
            return cssWithId;
        }
        if (cssFallback != null) {
            return cssFallback;
        }
        if (xpath != null) {
            return xpath;
        }
        if (pierce != null) {
            return pierce;
        }
        if (textFallback != null) {
            return textFallback;
        }
        if (ariaFallback != null) {
            return ariaFallback;
        }
        return fallback;
    }

    private static String toStructuralLocator(String receiver, String selector) {
        if (selector.startsWith("text/")) {
            String text = selector.substring("text/".length());
            return receiver + ".getByText(\"%s\")".formatted(escapeJava(text));
        }
        if (selector.startsWith("aria/")) {
            // Last resort when Recorder has no CSS/XPath alternative.
            Matcher withRole = ARIA_WITH_ROLE.matcher(selector);
            if (withRole.matches()) {
                return formatGetByRole(receiver, withRole.group(2), withRole.group(1));
            }
            String name = selector.substring("aria/".length());
            return "%s.getByLabel(\"%s\")".formatted(receiver, escapeJava(name));
        }
        if (selector.startsWith("xpath/")) {
            String xpath = selector.substring("xpath/".length());
            return receiver + ".locator(\"xpath=%s\")".formatted(escapeJava(xpath));
        }
        if (selector.startsWith("pierce/")) {
            String css = selector.substring("pierce/".length());
            return receiver + ".locator(\"%s\")".formatted(escapeJava(css));
        }
        return receiver + ".locator(\"%s\")".formatted(escapeJava(selector));
    }

    private static String formatGetByRole(String receiver, String role, String name) {
        String optionsType = "page".equals(receiver)
                ? "Page.GetByRoleOptions"
                : "Locator.GetByRoleOptions";
        return "%s.getByRole(%s, new %s().setName(\"%s\").setExact(true))".formatted(
                receiver,
                toAriaRoleEnum(role),
                optionsType,
                escapeJava(name)
        );
    }

    static String toAriaRoleEnum(String role) {
        String normalized = role.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return "AriaRole." + normalized.toUpperCase(Locale.ROOT);
    }

    private static boolean isCssSelector(String selector) {
        if (selector == null || selector.isBlank()) {
            return false;
        }
        if (selector.startsWith("aria/")
                || selector.startsWith("text/")
                || selector.startsWith("xpath/")
                || selector.startsWith("pierce/")) {
            return false;
        }
        return true;
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
