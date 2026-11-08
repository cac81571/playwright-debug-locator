package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Infers ARIA roles missing from DevTools Recorder {@code aria/Name} selectors.
 * Prefer live CDP lookup when a {@link Page} is available; otherwise infer from CSS alternatives.
 */
final class RecorderRoleEnricher {

    private static final Pattern CSS_ROLE_ATTR = Pattern.compile(
            "\\[role\\s*=\\s*(['\"])([^'\"]+)\\1\\]",
            Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> SUPPORTED_ROLES = Set.of(
            "alert", "alertdialog", "application", "article", "banner", "blockquote", "button",
            "caption", "cell", "checkbox", "code", "columnheader", "combobox", "complementary",
            "contentinfo", "definition", "deletion", "dialog", "directory", "document", "emphasis",
            "feed", "figure", "form", "generic", "grid", "gridcell", "group", "heading", "img",
            "insertion", "link", "list", "listbox", "listitem", "log", "main", "marquee", "math",
            "meter", "menu", "menubar", "menuitem", "menuitemcheckbox", "menuitemradio",
            "navigation", "none", "note", "option", "paragraph", "presentation", "progressbar",
            "radio", "radiogroup", "region", "row", "rowgroup", "rowheader", "scrollbar", "search",
            "searchbox", "separator", "slider", "spinbutton", "status", "strong", "subscript",
            "superscript", "switch", "tab", "table", "tablist", "tabpanel", "term", "textbox",
            "time", "timer", "toolbar", "tooltip", "tree", "treegrid", "treeitem"
    );

    private static final String ROLE_SCRIPT = """
            el => {
              const explicit = el.getAttribute('role');
              if (explicit && explicit.trim()) {
                return explicit.trim().toLowerCase();
              }
              const tag = el.tagName.toLowerCase();
              const type = (el.getAttribute('type') || '').toLowerCase();
              if (tag === 'a' && el.hasAttribute('href')) return 'link';
              if (tag === 'button') return 'button';
              if (tag === 'summary') return 'button';
              if (tag === 'input') {
                if (type === 'button' || type === 'submit' || type === 'reset' || type === 'image') {
                  return 'button';
                }
                if (type === 'checkbox') return 'checkbox';
                if (type === 'radio') return 'radio';
                if (type === 'range') return 'slider';
                if (type === 'number') return 'spinbutton';
                if (type === 'search') return 'searchbox';
                return 'textbox';
              }
              if (tag === 'textarea') return 'textbox';
              if (tag === 'select') return 'combobox';
              if (tag === 'img') return 'img';
              if (tag === 'progress') return 'progressbar';
              if (tag === 'meter') return 'meter';
              if (/^h[1-6]$/.test(tag)) return 'heading';
              if (tag === 'nav') return 'navigation';
              if (tag === 'main') return 'main';
              if (tag === 'aside') return 'complementary';
              if (tag === 'header') return 'banner';
              if (tag === 'footer') return 'contentinfo';
              if (tag === 'form') return 'form';
              if (tag === 'table') return 'table';
              if (tag === 'ul' || tag === 'ol') return 'list';
              if (tag === 'li') return 'listitem';
              if (tag === 'option') return 'option';
              return null;
            }
            """;

    private static final String ACCESSIBLE_NAME_SCRIPT = """
            el => {
              const labelledBy = el.getAttribute('aria-labelledby');
              if (labelledBy) {
                const parts = labelledBy.split(/\\s+/).map(id => {
                  const node = document.getElementById(id);
                  return node ? (node.innerText || node.textContent || '').trim() : '';
                }).filter(Boolean);
                if (parts.length) return parts.join(' ');
              }
              const ariaLabel = el.getAttribute('aria-label');
              if (ariaLabel && ariaLabel.trim()) return ariaLabel.trim();
              if (el.labels && el.labels.length) {
                const labelText = Array.from(el.labels)
                  .map(label => (label.innerText || label.textContent || '').trim())
                  .filter(Boolean)
                  .join(' ');
                if (labelText) return labelText;
              }
              const tag = el.tagName.toLowerCase();
              if (tag === 'input' || tag === 'button' || tag === 'submit') {
                const value = el.getAttribute('value');
                if (value && value.trim()) return value.trim();
              }
              const alt = el.getAttribute('alt');
              if (alt && alt.trim()) return alt.trim();
              const title = el.getAttribute('title');
              if (title && title.trim()) return title.trim();
              const text = (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim();
              return text ? text.slice(0, 120) : '';
            }
            """;

    private final Page page;

    private RecorderRoleEnricher(Page page) {
        this.page = page;
    }

    static RecorderRoleEnricher of(Page page) {
        return new RecorderRoleEnricher(page);
    }

    boolean hasPage() {
        return page != null;
    }

    /**
     * @return role source label for logging, e.g. {@code CDP} / {@code CSS}
     */
    record ResolvedRole(String role, String source) {
    }

    /**
     * Role + accessible name that was verified to resolve back to the same DOM node as CSS/XPath.
     */
    record VerifiedAria(String role, String name, String source) {
    }

    Optional<ResolvedRole> resolve(MapStepSelectors selectors) {
        if (page != null) {
            Optional<String> fromPage = resolveFromPage(selectors);
            if (fromPage.isPresent()) {
                return Optional.of(new ResolvedRole(fromPage.get(), "CDP"));
            }
        }
        return inferFromCss(selectors.cssCandidates())
                .map(role -> new ResolvedRole(role, "CSS"));
    }

    /**
     * Builds a getByRole candidate only when it uniquely targets the same element as CSS/XPath.
     * Avoids false ARIA conversions from heuristic role inference.
     */
    Optional<VerifiedAria> resolveVerifiedAria(MapStepSelectors selectors, String recorderName) {
        if (page == null) {
            return Optional.empty();
        }
        return findAnchorLocator(selectors)
                .flatMap(anchor -> resolveVerifiedFromAnchor(anchor, recorderName));
    }

    /**
     * Verifies ARIA from an already-resolved Playwright locator argument (CSS or {@code xpath=...}).
     */
    Optional<VerifiedAria> resolveVerifiedFromStructuralSelector(String locatorArg) {
        if (page == null || locatorArg == null || locatorArg.isBlank()) {
            return Optional.empty();
        }
        return preferVisible(page.locator(locatorArg))
                .flatMap(anchor -> resolveVerifiedFromAnchor(anchor));
    }

    private Optional<VerifiedAria> resolveVerifiedFromAnchor(Locator anchor, String... preferredNames) {
        Optional<String> roleOpt = evaluateOn(anchor, ROLE_SCRIPT).flatMap(RecorderRoleEnricher::normalizeRole);
        if (roleOpt.isEmpty()) {
            return Optional.empty();
        }
        String role = roleOpt.get();
        AriaRole ariaRole;
        try {
            ariaRole = AriaRole.valueOf(role.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }

        String liveName = evaluateOn(anchor, ACCESSIBLE_NAME_SCRIPT).orElse("").trim();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (preferredNames != null) {
            for (String preferred : preferredNames) {
                if (preferred != null && !preferred.isBlank()) {
                    names.add(preferred.trim());
                }
            }
        }
        if (!liveName.isBlank()) {
            names.add(liveName);
        }
        if (names.isEmpty()) {
            return Optional.empty();
        }

        for (String name : names) {
            Locator exact = page.getByRole(ariaRole, new Page.GetByRoleOptions().setName(name).setExact(true));
            if (matchesSameElement(anchor, exact) && exact.count() == 1) {
                return Optional.of(new VerifiedAria(role, name, "CDP-verified"));
            }
        }
        return Optional.empty();
    }

    Optional<String> preferredStructuralSelector(MapStepSelectors selectors) {
        String withId = null;
        for (String css : selectors.cssCandidates()) {
            if (css.contains("#") || css.contains("[id=") || css.contains("[id =")) {
                return Optional.of(css);
            }
            if (withId == null) {
                withId = css;
            }
        }
        if (withId != null) {
            return Optional.of(withId);
        }
        if (!selectors.xpathCandidates().isEmpty()) {
            return Optional.of("xpath=" + selectors.xpathCandidates().get(0));
        }
        return Optional.empty();
    }

    private Optional<Locator> findAnchorLocator(MapStepSelectors selectors) {
        for (String css : selectors.cssCandidates()) {
            Optional<Locator> locator = preferVisible(page.locator(css));
            if (locator.isPresent()) {
                return locator;
            }
        }
        for (String xpath : selectors.xpathCandidates()) {
            Optional<Locator> locator = preferVisible(page.locator("xpath=" + xpath));
            if (locator.isPresent()) {
                return locator;
            }
        }
        return Optional.empty();
    }

    private Optional<Locator> preferVisible(Locator locator) {
        // Visible first, but Google 等では DOM にあっても Playwright 非表示のことがある。
        try {
            Locator visible = locator.filter(new Locator.FilterOptions().setVisible(true));
            if (visible.count() > 0) {
                return Optional.of(visible.first());
            }
        } catch (Exception ignored) {
            // fall through to attached lookup
        }
        try {
            if (locator.count() > 0) {
                return Optional.of(locator.first());
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private record AnchorLookup(Optional<Locator> locator, String detail) {
    }

    private AnchorLookup findEnrichmentAnchor(Locator locator) {
        try {
            int attached = locator.count();
            if (attached <= 0) {
                return new AnchorLookup(Optional.empty(), "count=0");
            }
            try {
                Locator visible = locator.filter(new Locator.FilterOptions().setVisible(true));
                int visibleCount = visible.count();
                if (visibleCount > 0) {
                    return new AnchorLookup(Optional.of(visible.first()),
                            "visible=" + visibleCount + "/" + attached);
                }
            } catch (Exception ex) {
                // keep attached
            }
            return new AnchorLookup(Optional.of(locator.first()),
                    "attached=" + attached + " (visible=0)");
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            String firstLine = message.lines().findFirst().orElse(message).trim();
            if (firstLine.length() > 80) {
                firstLine = firstLine.substring(0, 77) + "...";
            }
            return new AnchorLookup(Optional.empty(), "error: " + firstLine);
        }
    }

    private boolean matchesSameElement(Locator anchor, Locator candidate) {
        String token = null;
        try {
            // Hidden Google buttons etc. may only match when includeHidden is enabled.
            int count;
            try {
                count = candidate.count();
            } catch (Exception ex) {
                count = 0;
            }
            if (count == 0) {
                return false;
            }
            token = "pw" + Long.toString(System.nanoTime(), 36);
            String markScript = "el => el.setAttribute('data-pw-verify', '" + token + "')";
            anchor.evaluate(markScript);
            int limit = Math.min(count, 8);
            for (int i = 0; i < limit; i++) {
                String value = candidate.nth(i).getAttribute("data-pw-verify");
                if (token.equals(value)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (token != null) {
                try {
                    anchor.evaluate("el => el.removeAttribute('data-pw-verify')");
                } catch (Exception ignored) {
                    // ignore cleanup failures
                }
            }
        }
    }

    private Optional<String> evaluateOn(Locator locator, String script) {
        try {
            Object value = locator.evaluate(script);
            if (value == null) {
                return Optional.empty();
            }
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    record JavaEnrichResult(String source, int replacements, List<String> notes) {
    }

    /**
     * Uses trailing {@code // @aria ...} comments to rewrite CSS/XPath/{@code getByLabel} into {@code getByRole}.
     * Role is taken from the comment when present; otherwise resolved from the live element at runtime.
     * Replaces only when getByRole targets the same element as the structural locator.
     */
    JavaEnrichResult enrichJavaSource(String source) {
        if (page == null || source == null || source.isBlank()) {
            return new JavaEnrichResult(source, 0, List.of());
        }

        List<String> notes = new ArrayList<>();
        String[] lines = source.split("\\R", -1);
        StringBuilder out = new StringBuilder();
        int replacements = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Optional<AriaHintComment> hint = AriaHintComment.parse(line);
            if (hint.isEmpty() || hint.get().name().isBlank()) {
                out.append(line);
                if (i < lines.length - 1) {
                    out.append('\n');
                }
                continue;
            }

            Optional<String> rewritten = rewriteLineUsingAriaHint(line, hint.get(), notes);
            if (rewritten.isPresent()) {
                out.append(rewritten.get());
                replacements++;
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }

        return new JavaEnrichResult(out.toString(), replacements, List.copyOf(notes));
    }

    private Optional<String> rewriteLineUsingAriaHint(String line, AriaHintComment hint, List<String> notes) {
        Pattern call = Pattern.compile(
                "(page|\\w+)\\.(locator|getByLabel)\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\)"
        );
        Matcher matcher = call.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String receiver = matcher.group(1);
        String method = matcher.group(2);
        String arg = unescapeJavaString(matcher.group(3));

        AnchorLookup anchorLookup;
        if ("locator".equals(method)) {
            anchorLookup = findEnrichmentAnchor(page.locator(arg));
        } else {
            anchorLookup = findEnrichmentAnchor(
                    page.getByLabel(arg, new Page.GetByLabelOptions().setExact(true))
            );
            if (anchorLookup.locator().isEmpty()) {
                anchorLookup = findEnrichmentAnchor(page.getByLabel(arg));
            }
        }
        if (anchorLookup.locator().isEmpty()) {
            notes.add("ARIA 変換スキップ: 要素未検出 " + method + "(\"" + arg + "\") ["
                    + anchorLookup.detail() + "] — 実行前ページに無い/別タブの可能性");
            return Optional.empty();
        }
        Locator anchor = anchorLookup.locator().get();

        String role = hint.hasRole() ? hint.role() : null;
        if (role == null || role.isBlank()) {
            role = evaluateOn(anchor, ROLE_SCRIPT)
                    .flatMap(RecorderRoleEnricher::normalizeRole)
                    .orElse(null);
        }
        if (role == null || role.isBlank()) {
            notes.add("ARIA 変換スキップ: Role 判定不可 " + method + "(\"" + arg + "\") // "
                    + hint.describe());
            return Optional.empty();
        }

        AriaRole ariaRole;
        try {
            ariaRole = AriaRole.valueOf(role.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            notes.add("スキップ: 不正な role=" + role + " (" + arg + ")");
            return Optional.empty();
        }

        // includeHidden: Google の非表示 duplicate と CSS アンカーを突き合わせるため。
        Locator roleLocator = page.getByRole(
                ariaRole,
                new Page.GetByRoleOptions()
                        .setName(hint.name())
                        .setExact(true)
                        .setIncludeHidden(true)
        );
        boolean same = matchesSameElement(anchor, roleLocator);
        int roleCount;
        try {
            roleCount = roleLocator.count();
        } catch (Exception ex) {
            roleCount = -1;
        }
        // 生成コードは visible な getByRole を使う。検証は「同一要素を含む」ことだけでよい。
        boolean verified = same && roleCount > 0;
        if (!verified) {
            notes.add("ARIA 検証失敗のため維持: " + method + "(\"" + arg + "\") // "
                    + hint.describe() + " (判定role=" + role
                    + ", same=" + same + ", roleCount=" + roleCount
                    + ", anchor=" + anchorLookup.detail() + ")");
            return Optional.empty();
        }

        String getByRole = formatGetByRoleCall(receiver, role, hint.name());
        // Swap the call and drop @aria / #id hints (no longer needed after conversion).
        String replacedCall = matcher.replaceFirst(Matcher.quoteReplacement(getByRole));
        String replaced = RecorderIdHint.stripTrailingIdComment(AriaHintComment.stripFrom(replacedCall));

        notes.add(method + "(\"" + arg + "\") → getByRole(" + role + ", \"" + hint.name()
                + "\") (実行時 Role 判定)");
        return Optional.of(replaced);
    }

    private static String formatGetByRoleCall(String receiver, String role, String name) {
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

    private Optional<String> resolveRoleByAccessibleName(String name) {
        if (page == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        Optional<String> exactLabel = evaluateRole(
                page.getByLabel(name, new Page.GetByLabelOptions().setExact(true))
        );
        if (exactLabel.isPresent()) {
            return exactLabel;
        }
        Optional<String> looseLabel = evaluateRole(page.getByLabel(name));
        if (looseLabel.isPresent()) {
            return looseLabel;
        }
        // Buttons often expose accessible name via aria-label / value, not a <label>.
        Optional<String> ariaAttr = evaluateRole(page.locator("[aria-label=\"" + cssEscape(name) + "\"]"));
        if (ariaAttr.isPresent()) {
            return ariaAttr;
        }
        return evaluateRole(page.getByRole(
                com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(name).setExact(true)
        ));
    }

    private static String toAriaRoleEnum(String role) {
        return "AriaRole." + role.trim().toLowerCase(Locale.ROOT).replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static String cssEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJavaString(String value) {
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

    private Optional<String> resolveFromPage(MapStepSelectors selectors) {
        for (String css : selectors.cssCandidates()) {
            Optional<String> role = evaluateRole(page.locator(css));
            if (role.isPresent()) {
                return role;
            }
        }
        for (String xpath : selectors.xpathCandidates()) {
            Optional<String> role = evaluateRole(page.locator("xpath=" + xpath));
            if (role.isPresent()) {
                return role;
            }
        }
        return Optional.empty();
    }

    private Optional<String> evaluateRole(Locator locator) {
        try {
            Locator visible = locator.filter(new Locator.FilterOptions().setVisible(true));
            Locator target = visible.count() > 0 ? visible.first() : locator.first();
            if (target.count() == 0) {
                return Optional.empty();
            }
            Object value = target.evaluate(ROLE_SCRIPT);
            return normalizeRole(value == null ? null : String.valueOf(value));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    static Optional<String> inferFromCss(List<String> cssCandidates) {
        for (String css : cssCandidates) {
            Optional<String> role = inferFromSingleCss(css);
            if (role.isPresent()) {
                return role;
            }
        }
        return Optional.empty();
    }

    static Optional<String> inferFromSingleCss(String css) {
        if (css == null || css.isBlank()) {
            return Optional.empty();
        }
        String value = css.trim();
        if (value.startsWith("pierce/")) {
            value = value.substring("pierce/".length());
        }

        Matcher roleAttr = CSS_ROLE_ATTR.matcher(value);
        if (roleAttr.find()) {
            return normalizeRole(roleAttr.group(2));
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if (containsTag(lower, "button") || lower.contains("input[type=submit]")
                || lower.contains("input[type=\"submit\"]")
                || lower.contains("input[type='submit']")
                || lower.contains("input[type=button]")
                || lower.contains("input[type=\"button\"]")
                || lower.contains("input[type='button']")
                || lower.contains("input[type=reset]")
                || lower.contains("input[type=\"reset\"]")
                || lower.contains("input[type='reset']")) {
            return Optional.of("button");
        }
        if (containsTag(lower, "textarea")
                || lower.contains("input[type=text]")
                || lower.contains("input[type=\"text\"]")
                || lower.contains("input[type='text']")
                || lower.contains("input[type=email]")
                || lower.contains("input[type=password]")
                || lower.contains("input[type=search]")
                || (containsTag(lower, "input")
                && !lower.contains("type=checkbox")
                && !lower.contains("type=radio")
                && !lower.contains("type=submit")
                && !lower.contains("type=button")
                && !lower.contains("type=reset")
                && !lower.contains("type=image"))) {
            if (lower.contains("type=search") || lower.contains("type=\"search\"")
                    || lower.contains("type='search'")) {
                return Optional.of("searchbox");
            }
            return Optional.of("textbox");
        }
        if (lower.contains("type=checkbox") || lower.contains("type=\"checkbox\"")
                || lower.contains("type='checkbox'")) {
            return Optional.of("checkbox");
        }
        if (lower.contains("type=radio") || lower.contains("type=\"radio\"")
                || lower.contains("type='radio'")) {
            return Optional.of("radio");
        }
        if (containsTag(lower, "select")) {
            return Optional.of("combobox");
        }
        if (containsTag(lower, "a") || lower.contains("a[href")) {
            return Optional.of("link");
        }
        if (containsTag(lower, "img")) {
            return Optional.of("img");
        }
        if (lower.matches(".*\\bh[1-6]\\b.*")) {
            return Optional.of("heading");
        }
        return Optional.empty();
    }

    private static boolean containsTag(String cssLower, String tag) {
        // Avoid false positives like "a" inside "aside" / "alert".
        // Do not put "[:..." in a char class — Java treats it as a POSIX class.
        Pattern pattern = Pattern.compile(
                "(^|[\\s>+~])" + Pattern.quote(tag) + "($|[\\s.\\[\\]#:>+~,])"
        );
        return pattern.matcher(cssLower).find();
    }

    static Optional<String> normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!SUPPORTED_ROLES.contains(normalized)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    /**
     * Collects CSS / XPath candidates from a Recorder step's selector alternatives.
     */
    record MapStepSelectors(List<String> cssCandidates, List<String> xpathCandidates) {
        static MapStepSelectors fromStep(java.util.Map<?, ?> step) {
            java.util.ArrayList<String> css = new java.util.ArrayList<>();
            java.util.ArrayList<String> xpath = new java.util.ArrayList<>();
            Object selectorsRaw = step.get("selectors");
            if (!(selectorsRaw instanceof List<?> alternatives)) {
                return new MapStepSelectors(List.of(), List.of());
            }
            for (Object alternative : alternatives) {
                if (!(alternative instanceof List<?> parts)) {
                    continue;
                }
                for (Object partObj : parts) {
                    String part = partObj == null ? "" : String.valueOf(partObj);
                    if (part.startsWith("aria/") || part.startsWith("text/")) {
                        continue;
                    }
                    if (part.startsWith("xpath/")) {
                        xpath.add(part.substring("xpath/".length()));
                    } else if (part.startsWith("pierce/")) {
                        css.add(part.substring("pierce/".length()));
                    } else if (!part.isBlank()) {
                        css.add(part);
                    }
                }
            }
            return new MapStepSelectors(List.copyOf(css), List.copyOf(xpath));
        }
    }
}
