package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.playwrightdebug.locator.model.ResolvedLocator;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocatorResolver {

    private static final Pattern LOCATOR_CALL = Pattern.compile(
            "^(?:page\\.)?locator\\(\\s*(['\"])(.*)\\1\\s*\\)(\\s*(?:\\..*)?)?$",
            Pattern.DOTALL
    );

    private static final Pattern GET_BY_TEXT = Pattern.compile(
            "^(?:page\\.)?getByText\\(\\s*(['\"])(.*)\\1\\s*\\)(\\s*(?:\\..*)?)?$",
            Pattern.DOTALL
    );

    private static final Pattern GET_BY_LABEL = Pattern.compile(
            "^(?:page\\.)?getByLabel\\(\\s*(['\"])(.*)\\1\\s*\\)(\\s*(?:\\..*)?)?$",
            Pattern.DOTALL
    );

    private static final Pattern GET_BY_PLACEHOLDER = Pattern.compile(
            "^(?:page\\.)?getByPlaceholder\\(\\s*(['\"])(.*)\\1\\s*\\)(\\s*(?:\\..*)?)?$",
            Pattern.DOTALL
    );

    private static final Pattern GET_BY_ALT_TEXT = Pattern.compile(
            "^(?:page\\.)?getByAltText\\(\\s*(['\"])(.*)\\1\\s*\\)(\\s*(?:\\..*)?)?$",
            Pattern.DOTALL
    );

    private static final Pattern GET_BY_TITLE = Pattern.compile(
            "^(?:page\\.)?getByTitle\\(\\s*(['\"])(.*)\\1\\s*\\)(\\s*(?:\\..*)?)?$",
            Pattern.DOTALL
    );

    private static final Pattern GET_BY_TEST_ID = Pattern.compile(
            "^(?:page\\.)?getByTestId\\(\\s*(['\"])(.*)\\1\\s*\\)(\\s*(?:\\..*)?)?$",
            Pattern.DOTALL
    );

    private static final Pattern GET_BY_ROLE = Pattern.compile(
            "^(?:page\\.)?getByRole\\(\\s*AriaRole\\.([A-Z_]+)"
                    + "(?:\\s*,\\s*new\\s+Page\\.GetByRoleOptions\\(\\)\\.setName\\(\\s*(['\"])(.*)\\2\\s*\\))?"
                    + "\\s*\\)(\\s*(?:\\..*)?)?$",
            Pattern.DOTALL
    );

    private LocatorResolver() {
    }

    public static ResolvedLocator resolve(Page page, String input) {
        String raw = input == null ? "" : input.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("Locator を入力してください");
        }

        Matcher locatorCall = LOCATOR_CALL.matcher(raw);
        if (locatorCall.matches()) {
            String selector = locatorCall.group(2);
            Locator locator = page.locator(selector);
            return finish(raw, "locator(\"" + selector + "\")", applyChain(locator, locatorCall.group(3)));
        }

        ResolvedLocator fromGetBy = tryGetBy(page, raw);
        if (fromGetBy != null) {
            return fromGetBy;
        }

        if (isQuoted(raw)) {
            String selector = unquote(raw);
            return finish(raw, selector, page.locator(selector));
        }

        return finish(raw, raw, page.locator(raw));
    }

    private static ResolvedLocator tryGetBy(Page page, String raw) {
        Matcher text = GET_BY_TEXT.matcher(raw);
        if (text.matches()) {
            return finishGetBy(raw, "getByText", text.group(2), page.getByText(text.group(2)), text.group(3));
        }

        Matcher label = GET_BY_LABEL.matcher(raw);
        if (label.matches()) {
            return finishGetBy(raw, "getByLabel", label.group(2), page.getByLabel(label.group(2)), label.group(3));
        }

        Matcher placeholder = GET_BY_PLACEHOLDER.matcher(raw);
        if (placeholder.matches()) {
            return finishGetBy(raw, "getByPlaceholder", placeholder.group(2),
                    page.getByPlaceholder(placeholder.group(2)), placeholder.group(3));
        }

        Matcher altText = GET_BY_ALT_TEXT.matcher(raw);
        if (altText.matches()) {
            return finishGetBy(raw, "getByAltText", altText.group(2), page.getByAltText(altText.group(2)), altText.group(3));
        }

        Matcher title = GET_BY_TITLE.matcher(raw);
        if (title.matches()) {
            return finishGetBy(raw, "getByTitle", title.group(2), page.getByTitle(title.group(2)), title.group(3));
        }

        Matcher testId = GET_BY_TEST_ID.matcher(raw);
        if (testId.matches()) {
            return finishGetBy(raw, "getByTestId", testId.group(2), page.getByTestId(testId.group(2)), testId.group(3));
        }

        Matcher role = GET_BY_ROLE.matcher(raw);
        if (role.matches()) {
            AriaRole ariaRole = AriaRole.valueOf(role.group(1));
            Locator locator;
            String labelText;
            if (role.group(3) != null) {
                locator = page.getByRole(ariaRole, new Page.GetByRoleOptions().setName(role.group(3)));
                labelText = "getByRole(" + ariaRole + ", name=\"" + role.group(3) + "\")";
            } else {
                locator = page.getByRole(ariaRole);
                labelText = "getByRole(" + ariaRole + ")";
            }
            return finish(raw, labelText, applyChain(locator, role.group(4)));
        }

        return null;
    }

    private static ResolvedLocator finishGetBy(
            String raw,
            String method,
            String value,
            Locator locator,
            String chainSuffix
    ) {
        return finish(raw, method + "(\"" + value + "\")", applyChain(locator, chainSuffix));
    }

    private static ResolvedLocator finish(String raw, String resolvedLabel, Locator locator) {
        return new ResolvedLocator(raw, resolvedLabel, locator);
    }

    private static Locator applyChain(Locator locator, String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return locator;
        }

        String rest = suffix.trim();
        if (rest.startsWith(".first()")) {
            return applyChain(locator.first(), rest.substring(".first()".length()));
        }
        if (rest.startsWith(".last()")) {
            return applyChain(locator.last(), rest.substring(".last()".length()));
        }

        Matcher nth = Pattern.compile("^\\.nth\\((\\d+)\\)(.*)", Pattern.DOTALL).matcher(rest);
        if (nth.matches()) {
            Locator nthLocator = locator.nth(Integer.parseInt(nth.group(1)));
            return applyChain(nthLocator, nth.group(2));
        }

        throw new IllegalArgumentException("未対応のチェーンです: " + rest.trim());
    }

    private static boolean isQuoted(String value) {
        return (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
                || (value.startsWith("'") && value.endsWith("'") && value.length() >= 2);
    }

    private static String unquote(String value) {
        return value.substring(1, value.length() - 1);
    }
}
