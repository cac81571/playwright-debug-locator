package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks a Playwright locator for a Recorder step using CDP/HTML when available.
 * Priority: role ARIA → getByLabel → getByText → placeholder → testId → id/CSS/XPath.
 */
final class RecorderLocatorPicker {

    private static final Pattern ARIA_WITH_ROLE = Pattern.compile(
            "^aria/(.*)\\[role=\"([^\"]+)\"\\]$"
    );
    private static final Pattern PLACEHOLDER_ATTR = Pattern.compile(
            "\\[placeholder=(['\"])([^'\"]*)\\1\\]", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TEST_ID_ATTR = Pattern.compile(
            "\\[(?:data-testid|data-test-id|data-test)=(['\"])([^'\"]*)\\1\\]",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CSS_ID = Pattern.compile("#([A-Za-z_][\\w-]*)");
    private static final Pattern ATTR_ID = Pattern.compile("\\[id=(['\"])([^'\"]+)\\1\\]");

    private final Page page;

    RecorderLocatorPicker(Page page) {
        this.page = page;
    }

    record Pick(String expression, List<String> unusedComments) {
        String withComments() {
            if (unusedComments == null || unusedComments.isEmpty()) {
                return expression;
            }
            StringBuilder sb = new StringBuilder(expression);
            for (String comment : unusedComments) {
                String singleLine = toSingleLineComment(comment);
                if (singleLine.isEmpty()) {
                    continue;
                }
                sb.append(" // ").append(singleLine);
            }
            return sb.toString();
        }
    }

    /** Flattens text so it is safe inside a {@code //} line comment. */
    static String toSingleLineComment(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").trim();
    }

    enum Kind {
        ROLE(0),
        LABEL(1),
        TEXT(2),
        PLACEHOLDER(3),
        TEST_ID(4),
        ID(5),
        CSS(6),
        XPATH(7);

        final int rank;

        Kind(int rank) {
            this.rank = rank;
        }
    }

    record Candidate(Kind kind, String expression, String comment, String raw) {
    }

    Pick pick(Map<?, ?> step) {
        List<Candidate> candidates = collectCandidates(step);
        enrichFromLiveDom(step, candidates);
        candidates = dedupe(candidates);
        candidates.sort((a, b) -> Integer.compare(a.kind().rank, b.kind().rank));

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("有効な selector がありません");
        }

        int chosenIndex = 0;
        if (page != null) {
            for (int i = 0; i < candidates.size(); i++) {
                if (works(candidates.get(i))) {
                    chosenIndex = i;
                    break;
                }
            }
        }

        Candidate chosen = candidates.get(chosenIndex);
        List<String> unused = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (i == chosenIndex) {
                continue;
            }
            unused.add(candidates.get(i).comment());
        }
        return new Pick(chosen.expression(), List.copyOf(unused));
    }

    private List<Candidate> collectCandidates(Map<?, ?> step) {
        List<Candidate> out = new ArrayList<>();
        Object selectorsRaw = step.get("selectors");
        if (!(selectorsRaw instanceof List<?> alternatives)) {
            return out;
        }
        for (Object alternative : alternatives) {
            if (!(alternative instanceof List<?> parts) || parts.isEmpty()) {
                continue;
            }
            if (parts.size() == 1) {
                addFromSelector(out, "page", stringValue(parts.get(0)));
            } else {
                // chained selectors: keep structural chain as CSS/XPath fallback only
                String expr = "page";
                StringBuilder comment = new StringBuilder();
                boolean ok = true;
                for (Object part : parts) {
                    String selector = stringValue(part);
                    if (selector.startsWith("aria/") || selector.startsWith("text/")) {
                        ok = false;
                        break;
                    }
                    expr = toStructuralLocator(expr, selector);
                    if (comment.length() > 0) {
                        comment.append('>');
                    }
                    comment.append(shortRaw(selector));
                }
                if (ok) {
                    Kind kind = expr.contains("xpath=") ? Kind.XPATH : Kind.CSS;
                    out.add(new Candidate(kind, expr, comment.toString(), comment.toString()));
                }
                // also expose first part as a simple candidate when useful
                addFromSelector(out, "page", stringValue(parts.get(0)));
            }
        }
        return out;
    }

    private void addFromSelector(List<Candidate> out, String receiver, String selector) {
        if (selector == null || selector.isBlank()) {
            return;
        }
        if (selector.startsWith("aria/")) {
            Matcher withRole = ARIA_WITH_ROLE.matcher(selector);
            if (withRole.matches()) {
                String name = withRole.group(1);
                String role = withRole.group(2);
                String expr = formatGetByRole(receiver, role, name);
                out.add(new Candidate(Kind.ROLE, expr, "getByRole(" + role + ",\"" + name + "\")", selector));
            } else {
                String name = selector.substring("aria/".length());
                String expr = "%s.getByLabel(\"%s\")".formatted(receiver, escapeJava(name));
                out.add(new Candidate(Kind.LABEL, expr, "getByLabel(\"" + name + "\")", selector));
            }
            return;
        }
        if (selector.startsWith("text/")) {
            String text = selector.substring("text/".length());
            String expr = receiver + ".getByText(\"%s\")".formatted(escapeJava(text));
            out.add(new Candidate(Kind.TEXT, expr, "getByText(\"" + text + "\")", selector));
            return;
        }
        if (selector.startsWith("xpath/")) {
            String xpath = selector.substring("xpath/".length());
            String expr = receiver + ".locator(\"xpath=%s\")".formatted(escapeJava(xpath));
            out.add(new Candidate(Kind.XPATH, expr, "xpath=" + xpath, selector));
            return;
        }

        String css = selector.startsWith("pierce/") ? selector.substring("pierce/".length()) : selector;
        Matcher ph = PLACEHOLDER_ATTR.matcher(css);
        if (ph.find()) {
            String value = ph.group(2);
            String expr = receiver + ".getByPlaceholder(\"%s\")".formatted(escapeJava(value));
            out.add(new Candidate(Kind.PLACEHOLDER, expr, "getByPlaceholder(\"" + value + "\")", css));
        }
        Matcher tid = TEST_ID_ATTR.matcher(css);
        if (tid.find()) {
            String value = tid.group(2);
            String expr = receiver + ".getByTestId(\"%s\")".formatted(escapeJava(value));
            out.add(new Candidate(Kind.TEST_ID, expr, "getByTestId(\"" + value + "\")", css));
        }
        Matcher attrId = ATTR_ID.matcher(css);
        Matcher hashId = CSS_ID.matcher(css);
        if (attrId.find()) {
            String id = attrId.group(2);
            String expr = receiver + ".locator(\"#%s\")".formatted(escapeJava(id));
            out.add(new Candidate(Kind.ID, expr, "#" + id, css));
        } else if (hashId.find()) {
            String id = hashId.group(1);
            // prefer pure #id when the css is just an id selector
            if (css.trim().equals("#" + id)) {
                String expr = receiver + ".locator(\"#%s\")".formatted(escapeJava(id));
                out.add(new Candidate(Kind.ID, expr, "#" + id, css));
            } else {
                String expr = receiver + ".locator(\"%s\")".formatted(escapeJava(css));
                out.add(new Candidate(Kind.CSS, expr, css, css));
                out.add(new Candidate(Kind.ID, receiver + ".locator(\"#" + escapeJava(id) + "\")", "#" + id, css));
            }
        } else {
            String expr = receiver + ".locator(\"%s\")".formatted(escapeJava(css));
            out.add(new Candidate(Kind.CSS, expr, css, css));
        }
    }

    /**
     * Uses a live element (when CDP page is available) to add missing role/label/placeholder/testid candidates.
     */
    private void enrichFromLiveDom(Map<?, ?> step, List<Candidate> candidates) {
        if (page == null) {
            return;
        }
        Optional<Locator> anchor = findAnchor(candidates);
        if (anchor.isEmpty()) {
            return;
        }
        Locator el = anchor.get();
        try {
            Object info = el.first().evaluate("""
                    el => {
                      const role = el.getAttribute('role')
                        || (el.tagName === 'BUTTON' ? 'button'
                          : el.tagName === 'A' ? 'link'
                          : el.tagName === 'INPUT' && (el.type === 'text' || el.type === 'search' || el.type === 'email' || !el.type) ? 'textbox'
                          : el.tagName === 'TEXTAREA' ? 'textbox'
                          : el.tagName === 'SELECT' ? 'combobox'
                          : el.tagName === 'INPUT' && el.type === 'checkbox' ? 'checkbox'
                          : el.tagName === 'INPUT' && el.type === 'radio' ? 'radio'
                          : el.tagName === 'H1' || el.tagName === 'H2' || el.tagName === 'H3' || el.tagName === 'H4' || el.tagName === 'H5' || el.tagName === 'H6' ? 'heading'
                          : el.tagName === 'IMG' ? 'img'
                          : null);
                      const name = el.getAttribute('aria-label')
                        || el.getAttribute('aria-labelledby')
                        || el.getAttribute('name')
                        || el.getAttribute('alt')
                        || el.getAttribute('title')
                        || (el.innerText || '').trim().slice(0, 80)
                        || '';
                      const labelledBy = (() => {
                        const id = el.getAttribute('aria-labelledby');
                        if (!id) return '';
                        return id.split(/\\s+/).map(i => document.getElementById(i)?.innerText?.trim() || '').filter(Boolean).join(' ');
                      })();
                      const labelText = (() => {
                        if (el.id) {
                          const lab = document.querySelector('label[for=\"' + CSS.escape(el.id) + '\"]');
                          if (lab) return (lab.innerText || '').trim();
                        }
                        const parent = el.closest('label');
                        return parent ? (parent.innerText || '').trim() : '';
                      })();
                      return {
                        role: role,
                        name: (labelledBy || labelText || name || '').trim(),
                        placeholder: el.getAttribute('placeholder') || '',
                        testId: el.getAttribute('data-testid') || el.getAttribute('data-test-id') || el.getAttribute('data-test') || '',
                        id: el.id || ''
                      };
                    }
                    """);
            if (!(info instanceof Map<?, ?> map)) {
                return;
            }
            String role = stringValue(map.get("role"));
            String name = stringValue(map.get("name"));
            String placeholder = stringValue(map.get("placeholder"));
            String testId = stringValue(map.get("testId"));
            String id = stringValue(map.get("id"));

            if (!role.isBlank() && !name.isBlank()) {
                String expr = formatGetByRole("page", role, name);
                candidates.add(new Candidate(Kind.ROLE, expr, "getByRole(" + role + ",\"" + name + "\")", "html:role"));
            } else if (!name.isBlank()) {
                String expr = "page.getByLabel(\"%s\")".formatted(escapeJava(name));
                candidates.add(new Candidate(Kind.LABEL, expr, "getByLabel(\"" + name + "\")", "html:label"));
                String textExpr = "page.getByText(\"%s\")".formatted(escapeJava(name));
                candidates.add(new Candidate(Kind.TEXT, textExpr, "getByText(\"" + name + "\")", "html:text"));
            }
            if (!placeholder.isBlank()) {
                String expr = "page.getByPlaceholder(\"%s\")".formatted(escapeJava(placeholder));
                candidates.add(new Candidate(Kind.PLACEHOLDER, expr, "getByPlaceholder(\"" + placeholder + "\")", "html:ph"));
            }
            if (!testId.isBlank()) {
                String expr = "page.getByTestId(\"%s\")".formatted(escapeJava(testId));
                candidates.add(new Candidate(Kind.TEST_ID, expr, "getByTestId(\"" + testId + "\")", "html:testid"));
            }
            if (!id.isBlank()) {
                String expr = "page.locator(\"#%s\")".formatted(escapeJava(id));
                candidates.add(new Candidate(Kind.ID, expr, "#" + id, "html:id"));
            }
        } catch (Exception ignored) {
            // keep JSON-only candidates
        }
    }

    private Optional<Locator> findAnchor(List<Candidate> candidates) {
        // Prefer structural locators to resolve the element first.
        for (Kind kind : List.of(Kind.ID, Kind.CSS, Kind.XPATH, Kind.TEST_ID, Kind.PLACEHOLDER, Kind.LABEL, Kind.TEXT, Kind.ROLE)) {
            for (Candidate candidate : candidates) {
                if (candidate.kind() != kind) {
                    continue;
                }
                Locator locator = toLocator(candidate);
                if (locator == null) {
                    continue;
                }
                try {
                    if (locator.count() > 0) {
                        return Optional.of(locator.first());
                    }
                } catch (Exception ignored) {
                    // try next
                }
            }
        }
        return Optional.empty();
    }

    private boolean works(Candidate candidate) {
        Locator locator = toLocator(candidate);
        if (locator == null) {
            return false;
        }
        try {
            int count = locator.count();
            if (count <= 0) {
                return false;
            }
            if (count == 1) {
                return true;
            }
            // Prefer a visible match among duplicates.
            Locator visible = locator.filter(new Locator.FilterOptions().setVisible(true));
            return visible.count() >= 1;
        } catch (Exception ex) {
            return false;
        }
    }

    private Locator toLocator(Candidate candidate) {
        if (page == null || candidate == null) {
            return null;
        }
        try {
            return switch (candidate.kind()) {
                case ROLE -> {
                    Matcher m = Pattern.compile(
                            "getByRole\\(AriaRole\\.([A-Z0-9_]+),\\s*new Page\\.GetByRoleOptions\\(\\)\\.setName\\(\"((?:\\\\.|[^\"\\\\])*)\"\\)"
                    ).matcher(candidate.expression());
                    if (!m.find()) {
                        yield null;
                    }
                    AriaRole role = AriaRole.valueOf(m.group(1));
                    String name = unescapeJava(m.group(2));
                    yield page.getByRole(role, new Page.GetByRoleOptions().setName(name).setExact(true));
                }
                case LABEL -> {
                    Matcher m = Pattern.compile("getByLabel\\(\"((?:\\\\.|[^\"\\\\])*)\"\\)").matcher(candidate.expression());
                    yield m.find() ? page.getByLabel(unescapeJava(m.group(1)), new Page.GetByLabelOptions().setExact(true)) : null;
                }
                case TEXT -> {
                    Matcher m = Pattern.compile("getByText\\(\"((?:\\\\.|[^\"\\\\])*)\"\\)").matcher(candidate.expression());
                    yield m.find() ? page.getByText(unescapeJava(m.group(1))) : null;
                }
                case PLACEHOLDER -> {
                    Matcher m = Pattern.compile("getByPlaceholder\\(\"((?:\\\\.|[^\"\\\\])*)\"\\)").matcher(candidate.expression());
                    yield m.find() ? page.getByPlaceholder(unescapeJava(m.group(1))) : null;
                }
                case TEST_ID -> {
                    Matcher m = Pattern.compile("getByTestId\\(\"((?:\\\\.|[^\"\\\\])*)\"\\)").matcher(candidate.expression());
                    yield m.find() ? page.getByTestId(unescapeJava(m.group(1))) : null;
                }
                case ID, CSS, XPATH -> {
                    Matcher m = Pattern.compile("locator\\(\"((?:\\\\.|[^\"\\\\])*)\"\\)").matcher(candidate.expression());
                    yield m.find() ? page.locator(unescapeJava(m.group(1))) : null;
                }
            };
        } catch (Exception ex) {
            return null;
        }
    }

    private static List<Candidate> dedupe(List<Candidate> candidates) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Candidate> out = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String key = candidate.kind() + "|" + candidate.expression();
            if (seen.add(key)) {
                out.add(candidate);
            }
        }
        return out;
    }

    private static String toStructuralLocator(String receiver, String selector) {
        if (selector.startsWith("xpath/")) {
            return receiver + ".locator(\"xpath=%s\")".formatted(escapeJava(selector.substring("xpath/".length())));
        }
        if (selector.startsWith("pierce/")) {
            return receiver + ".locator(\"%s\")".formatted(escapeJava(selector.substring("pierce/".length())));
        }
        return receiver + ".locator(\"%s\")".formatted(escapeJava(selector));
    }

    private static String formatGetByRole(String receiver, String role, String name) {
        return "%s.getByRole(%s, new Page.GetByRoleOptions().setName(\"%s\").setExact(true))".formatted(
                receiver,
                toAriaRoleEnum(role),
                escapeJava(name)
        );
    }

    private static String toAriaRoleEnum(String role) {
        String normalized = role.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return "AriaRole." + normalized.toUpperCase(Locale.ROOT);
    }

    private static String shortRaw(String selector) {
        if (selector.startsWith("xpath/")) {
            return "xpath=" + selector.substring("xpath/".length());
        }
        if (selector.startsWith("pierce/")) {
            return selector.substring("pierce/".length());
        }
        return selector;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
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
