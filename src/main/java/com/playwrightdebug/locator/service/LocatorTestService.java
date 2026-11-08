package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.playwrightdebug.locator.model.GroovyExecutionResult;
import com.playwrightdebug.locator.model.LocatorInput;
import com.playwrightdebug.locator.model.LocatorTestResult;
import com.playwrightdebug.locator.model.PlaywrightScriptContext;
import com.playwrightdebug.locator.model.ResolvedLocator;

public final class LocatorTestService {

    private final LocatorEvaluationService evaluationService = new LocatorEvaluationService();

    public LocatorTestResult runFullTest(PlaywrightScriptContext context, LocatorInput input, double timeoutMs) {
        LocatorTestResult result = new LocatorTestResult();
        appendInputHeader(result, input);
        result.addLine("タイムアウト: " + (int) timeoutMs + " ms");
        result.addLine("---");

        try {
            ResolvedLocator resolved = evaluationService.resolve(context, input);
            appendResolved(result, resolved);
            appendLocatorDiagnostics(result, resolved.locator());
        } catch (Exception ex) {
            result.addLine("エラー: " + ex.getMessage());
            result.setSuccess(false);
        }

        return result;
    }

    public LocatorTestResult count(PlaywrightScriptContext context, LocatorInput input) {
        return simple(context, input, locator -> "count: " + locator.count());
    }

    public LocatorTestResult highlight(PlaywrightScriptContext context, LocatorInput input) {
        return simple(context, input, locator -> {
            context.page().hideHighlight();
            locator.highlight();
            int count = locator.count();
            return count + " 件の要素をハイライトしました";
        });
    }

    public LocatorTestResult hideHighlight(Page page) {
        LocatorTestResult result = new LocatorTestResult();
        try {
            page.hideHighlight();
            result.addLine("ハイライトを解除しました");
            result.setSuccess(true);
        } catch (Exception ex) {
            result.addLine("エラー: " + ex.getMessage());
            result.setSuccess(false);
        }
        return result;
    }

    public LocatorTestResult runTestScript(PlaywrightScriptContext context, LocatorInput input, double timeoutMs) {
        LocatorTestResult result = new LocatorTestResult();
        appendInputHeader(result, input);
        result.addLine("タイムアウト: " + (int) timeoutMs + " ms");
        result.addLine("---");
        try {
            GroovyExecutionResult execution = evaluationService.executeGroovy(context, input.text());
            appendExecutionResult(result, execution);
        } catch (PlaywrightException ex) {
            result.addLine("実行失敗:");
            result.addLine(ex.getMessage());
            result.setSuccess(false);
        } catch (Exception ex) {
            result.addLine("エラー: " + ex.getMessage());
            result.setSuccess(false);
        }
        return result;
    }

    public LocatorTestResult runRecorderJava(PlaywrightScriptContext context, String javaSource, double timeoutMs) {
        LocatorTestResult result = new LocatorTestResult();
        result.addLine("モード: Recorder Playwright");
        result.addLine("タイムアウト: " + (int) timeoutMs + " ms");
        result.addLine("---");
        try {
            String script = DevToolsRecorderConverter.extractExecutableScript(javaSource);
            result.addLine("実行スクリプト:");
            result.addLine(abbreviate(script));
            result.addLine("---");
            GroovyExecutionResult execution = evaluationService.executeGroovy(context, script);
            appendExecutionResult(result, execution);
        } catch (PlaywrightException ex) {
            result.addLine("実行失敗:");
            result.addLine(ex.getMessage());
            result.setSuccess(false);
        } catch (Exception ex) {
            result.addLine("エラー: " + ex.getMessage());
            result.setSuccess(false);
        }
        return result;
    }

    private static void appendExecutionResult(LocatorTestResult result, GroovyExecutionResult execution) {
        if (execution.hasLocator()) {
            result.addLine("戻り値: Locator (count=" + execution.locator().count() + ")");
        } else if (execution.isVoidResult()) {
            result.addLine("実行成功");
        } else {
            result.addLine("戻り値: " + execution.rawResult());
        }
        result.setSuccess(true);
    }

    public LocatorTestResult runClickTest(PlaywrightScriptContext context, LocatorInput input, double timeoutMs) {
        LocatorTestResult result = new LocatorTestResult();
        appendInputHeader(result, input);
        result.addLine("操作: click / " + (int) timeoutMs + " ms");
        result.addLine("---");
        try {
            if (input.mode() == LocatorInput.EvaluationMode.GROOVY) {
                evaluationService.executeGroovy(context, input.text());
                result.addLine("---");
                result.addLine("Groovy スクリプト実行成功");
                result.setSuccess(true);
                return result;
            }

            ResolvedLocator resolved = evaluationService.resolve(context, input);
            appendResolved(result, resolved);

            Locator locator = resolved.locator();
            result.addLine("count: " + locator.count());
            LocatorDiagnostics.appendActionabilityHint(result, locator.count() > 1 ? locator.first() : locator);

            locator.click(new Locator.ClickOptions().setTimeout(timeoutMs));
            result.addLine("---");
            result.addLine("click 成功");
            result.setSuccess(true);
        } catch (PlaywrightException ex) {
            result.addLine("---");
            result.addLine("click 失敗:");
            result.addLine(ex.getMessage());
            result.setSuccess(false);
        } catch (Exception ex) {
            result.addLine("エラー: " + ex.getMessage());
            result.setSuccess(false);
        }
        return result;
    }

    public LocatorTestResult waitForVisible(PlaywrightScriptContext context, LocatorInput input, double timeoutMs) {
        LocatorTestResult result = new LocatorTestResult();
        appendInputHeader(result, input);
        result.addLine("待機: visible / " + (int) timeoutMs + " ms");
        try {
            ResolvedLocator resolved = evaluationService.resolve(context, input);
            appendResolved(result, resolved);
            resolved.locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(timeoutMs));
            result.addLine("visible になりました");
            result.setSuccess(true);
        } catch (Exception ex) {
            result.addLine("エラー: " + ex.getMessage());
            result.setSuccess(false);
        }
        return result;
    }

    public LocatorTestResult listMatches(PlaywrightScriptContext context, LocatorInput input, int maxItems) {
        LocatorTestResult result = new LocatorTestResult();
        appendInputHeader(result, input);
        try {
            ResolvedLocator resolved = evaluationService.resolve(context, input);
            appendResolved(result, resolved);

            Locator locator = resolved.locator();
            int count = locator.count();
            result.addLine("count: " + count);

            int limit = Math.min(count, maxItems);
            for (int i = 0; i < limit; i++) {
                Locator item = locator.nth(i);
                Object info = item.evaluate(
                        "el => JSON.stringify({" +
                                "id: el.id || ''," +
                                "ariaLabel: el.getAttribute('aria-label') || ''," +
                                "class: el.className || ''" +
                                "})"
                );
                String summary = String.format(
                        "[%d] visible=%s enabled=%s tag=%s attrs=%s text=%s",
                        i,
                        item.isVisible(),
                        item.isEnabled(),
                        item.evaluate("el => el.tagName.toLowerCase()"),
                        info,
                        quote(truncate(item.innerText()))
                );
                result.addLine(summary);
            }

            if (count > maxItems) {
                result.addLine("... 他 " + (count - maxItems) + " 件");
            }

            result.setSuccess(true);
        } catch (Exception ex) {
            result.addLine("エラー: " + ex.getMessage());
            result.setSuccess(false);
        }
        return result;
    }

    private LocatorTestResult simple(PlaywrightScriptContext context, LocatorInput input, LocatorAction action) {
        LocatorTestResult result = new LocatorTestResult();
        appendInputHeader(result, input);
        try {
            ResolvedLocator resolved = evaluationService.resolve(context, input);
            appendResolved(result, resolved);
            result.addLine(action.run(resolved.locator()));
            result.setSuccess(true);
        } catch (Exception ex) {
            result.addLine("エラー: " + ex.getMessage());
            result.setSuccess(false);
        }
        return result;
    }

    private static void appendLocatorDiagnostics(LocatorTestResult result, Locator locator) {
        int count;
        try {
            count = locator.count();
        } catch (Exception ex) {
            result.addLine("count: (取得失敗) " + ex.getMessage());
            result.setSuccess(false);
            return;
        }
        result.addLine("count: " + count);

        if (count == 0) {
            result.addLine("一致する要素がありません");
            result.setSuccess(false);
            return;
        }

        Locator target = count > 1 ? locator.first() : locator;
        if (count > 1) {
            result.addLine("警告: 複数一致 — click は strict mode エラーまたは意図しない要素の可能性");
            LocatorDiagnostics.appendElementSummary(result, "先頭 (DOM 順 .first()):", target);

            try {
                Locator visibleLocator = locator.filter(new Locator.FilterOptions().setVisible(true));
                int visibleCount = visibleLocator.count();
                result.addLine("visible count: " + visibleCount);
                if (visibleCount > 0) {
                    LocatorDiagnostics.appendElementSummary(result, "先頭の visible 要素:", visibleLocator.first());
                } else {
                    result.addLine("表示中の一致要素はありません");
                }
            } catch (Exception ex) {
                result.addLine("visible count: (取得失敗) " + ex.getMessage());
            }
        } else {
            LocatorDiagnostics.appendElementSummary(result, "一致要素:", locator);
        }

        LocatorDiagnostics.appendActionabilityHint(result, target);

        result.addLine("---");
        try {
            boolean actionable = target.isVisible() && target.isEnabled();
            result.addLine(actionable
                    ? "結果: OK（click 可能な状態です）"
                    : "結果: 警告（click すると Call log のように失敗する可能性があります）");
            result.setSuccess(actionable);
        } catch (Exception ex) {
            result.addLine("結果: 判定失敗（" + ex.getMessage() + "）");
            result.setSuccess(false);
        }
    }

    private static void appendInputHeader(LocatorTestResult result, LocatorInput input) {
        String modeLabel = switch (input.mode()) {
            case SIMPLE -> "簡易";
            case GROOVY -> "Groovy";
            case TEST_SCRIPT -> "テスト";
        };
        result.addLine("モード: " + modeLabel);
        result.addLine("入力: " + abbreviate(input.text()));
    }

    private static void appendResolved(LocatorTestResult result, ResolvedLocator resolved) {
        if (!resolved.raw().equals(resolved.resolvedLabel())) {
            result.addLine("解決: " + resolved.resolvedLabel());
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").trim();
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 157) + "...";
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }

    private static String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "(empty)";
        }
        return "\"" + value + "\"";
    }

    @FunctionalInterface
    private interface LocatorAction {
        String run(Locator locator);
    }
}
