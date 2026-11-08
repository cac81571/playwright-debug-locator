package com.playwrightdebug.locator.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces a failing Playwright locator expression inside generated/edited source.
 */
public final class StrictModeLocatorReplacer {

    private static final Pattern LABEL_LIKE = Pattern.compile(
            "^(getByLabel|getByText|getByPlaceholder|getByAltText|getByTitle|getByTestId)\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\".*$"
    );

    private static final Pattern ROLE_LIKE = Pattern.compile(
            "^getByRole\\(\\s*(AriaRole\\.\\w+)\\s*,\\s*new\\s+(?:Page|Locator)\\.GetByRoleOptions\\(\\)\\.setName\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\).*$"
    );

    private StrictModeLocatorReplacer() {
    }

    public static ReplaceResult replace(String source, String failingLocator, String replacementExpression) {
        if (source == null || source.isBlank()) {
            return new ReplaceResult(source, 0);
        }
        if (failingLocator == null || failingLocator.isBlank()
                || replacementExpression == null || replacementExpression.isBlank()) {
            return new ReplaceResult(source, 0);
        }

        String failing = failingLocator.trim();
        String replacement = replacementExpression.trim();
        String withoutPage = failing.startsWith("page.") ? failing.substring("page.".length()) : failing;
        String withPage = failing.startsWith("page.") ? failing : "page." + failing;

        Matcher labelLike = LABEL_LIKE.matcher(withoutPage);
        if (labelLike.matches()) {
            return replaceMethodCalls(
                    source,
                    labelLike.group(1),
                    labelLike.group(2),
                    replacement
            );
        }

        Matcher roleLike = ROLE_LIKE.matcher(withoutPage);
        if (roleLike.matches()) {
            ReplaceResult roleResult = replaceGetByRoleCalls(
                    source,
                    roleLike.group(1),
                    roleLike.group(2),
                    replacement
            );
            if (roleResult.replacements() > 0) {
                return roleResult;
            }
        }

        ReplacePass exactWithPage = replaceExact(source, withPage, ensurePagePrefix(replacement));
        if (exactWithPage.count() > 0) {
            return new ReplaceResult(exactWithPage.text(), exactWithPage.count());
        }

        ReplacePass exactBare = replaceExact(source, withoutPage, stripPagePrefix(replacement));
        if (exactBare.count() > 0) {
            return new ReplaceResult(exactBare.text(), exactBare.count());
        }

        return new ReplaceResult(source, 0);
    }

    private static ReplaceResult replaceMethodCalls(
            String source,
            String method,
            String stringArg,
            String replacement
    ) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        int i = 0;
        while (i < source.length()) {
            int callStart = indexOfMethodCall(source, method, i);
            if (callStart < 0) {
                out.append(source, i, source.length());
                break;
            }

            boolean hasPage = callStart >= 5 && source.startsWith("page.", callStart - 5);
            int exprStart = hasPage ? callStart - 5 : callStart;
            out.append(source, i, exprStart);

            int openParen = source.indexOf('(', callStart);
            if (openParen < 0) {
                out.append(source, exprStart, callStart + method.length());
                i = callStart + method.length();
                continue;
            }

            ParsedStringArg parsedArg = readFirstStringArg(source, openParen + 1);
            if (parsedArg == null || !stringArg.equals(parsedArg.value())) {
                out.append(source, exprStart, openParen + 1);
                i = openParen + 1;
                continue;
            }

            int closeParen = findMatchingParen(source, openParen);
            if (closeParen < 0) {
                out.append(source, exprStart, openParen + 1);
                i = openParen + 1;
                continue;
            }

            String repl = hasPage ? ensurePagePrefix(replacement) : stripPagePrefix(replacement);
            out.append(repl);
            count++;
            i = closeParen + 1;
        }
        return new ReplaceResult(out.toString(), count);
    }

    private static ReplaceResult replaceGetByRoleCalls(
            String source,
            String roleEnum,
            String name,
            String replacement
    ) {
        // Reuse method-call scanning by looking for getByRole( and validating internals.
        StringBuilder out = new StringBuilder();
        int count = 0;
        int i = 0;
        while (i < source.length()) {
            int callStart = indexOfMethodCall(source, "getByRole", i);
            if (callStart < 0) {
                out.append(source, i, source.length());
                break;
            }

            boolean hasPage = callStart >= 5 && source.startsWith("page.", callStart - 5);
            int exprStart = hasPage ? callStart - 5 : callStart;
            out.append(source, i, exprStart);

            int openParen = source.indexOf('(', callStart);
            if (openParen < 0) {
                out.append(source, exprStart, callStart + "getByRole".length());
                i = callStart + "getByRole".length();
                continue;
            }
            int closeParen = findMatchingParen(source, openParen);
            if (closeParen < 0) {
                out.append(source, exprStart, openParen + 1);
                i = openParen + 1;
                continue;
            }

            String inside = source.substring(openParen + 1, closeParen);
            if (!inside.contains(roleEnum) || !inside.contains("\"" + name + "\"")) {
                out.append(source, exprStart, closeParen + 1);
                i = closeParen + 1;
                continue;
            }

            String repl = hasPage ? ensurePagePrefix(replacement) : stripPagePrefix(replacement);
            out.append(repl);
            count++;
            i = closeParen + 1;
        }
        return new ReplaceResult(out.toString(), count);
    }

    private static int indexOfMethodCall(String source, String method, int from) {
        int idx = from;
        while (idx < source.length()) {
            int found = source.indexOf(method + "(", idx);
            if (found < 0) {
                found = source.indexOf(method + " (", idx);
            }
            if (found < 0) {
                return -1;
            }
            if (found > 0) {
                char prev = source.charAt(found - 1);
                if (Character.isLetterOrDigit(prev) || prev == '_' || prev == '$') {
                    idx = found + method.length();
                    continue;
                }
            }
            return found;
        }
        return -1;
    }

    private static ParsedStringArg readFirstStringArg(String source, int from) {
        int i = from;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        if (i >= source.length() || source.charAt(i) != '"') {
            return null;
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < source.length()) {
            char c = source.charAt(i++);
            if (c == '\\' && i < source.length()) {
                sb.append(source.charAt(i++));
                continue;
            }
            if (c == '"') {
                return new ParsedStringArg(sb.toString(), i);
            }
            sb.append(c);
        }
        return null;
    }

    private static int findMatchingParen(String source, int openIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = openIndex; i < source.length(); i++) {
            char c = source.charAt(i);
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
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static ReplacePass replaceExact(String source, String target, String replacement) {
        if (target == null || target.isBlank() || !source.contains(target)) {
            return new ReplacePass(source, 0);
        }
        int count = 0;
        String updated = source;
        int idx = 0;
        while ((idx = updated.indexOf(target, idx)) >= 0) {
            updated = updated.substring(0, idx) + replacement + updated.substring(idx + target.length());
            count++;
            idx += replacement.length();
        }
        return new ReplacePass(updated, count);
    }

    private static String ensurePagePrefix(String expression) {
        return expression.startsWith("page.") ? expression : "page." + expression;
    }

    private static String stripPagePrefix(String expression) {
        return expression.startsWith("page.") ? expression.substring("page.".length()) : expression;
    }

    public record ReplaceResult(String text, int replacements) {
    }

    private record ReplacePass(String text, int count) {
    }

    private record ParsedStringArg(String value, int endIndex) {
    }
}
