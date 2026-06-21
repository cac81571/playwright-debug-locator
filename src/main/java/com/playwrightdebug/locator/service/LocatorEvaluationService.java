package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Locator;
import com.playwrightdebug.locator.model.GroovyExecutionResult;
import com.playwrightdebug.locator.model.LocatorInput;
import com.playwrightdebug.locator.model.PlaywrightScriptContext;
import com.playwrightdebug.locator.model.ResolvedLocator;

public final class LocatorEvaluationService {

    private final GroovyScriptService groovyScriptService = new GroovyScriptService();

    public ResolvedLocator resolve(PlaywrightScriptContext context, LocatorInput input) {
        if (input.text() == null || input.text().isBlank()) {
            throw new IllegalArgumentException("Locator を入力してください");
        }

        if (input.mode() == LocatorInput.EvaluationMode.GROOVY) {
            GroovyExecutionResult execution = executeGroovy(context, input.text());
            if (!execution.hasLocator()) {
                throw new IllegalArgumentException(
                        "Locator を返す Groovy 式が必要です。"
                                + " click() 等のアクションだけの場合は「Click 試行」を使ってください。"
                );
            }
            return new ResolvedLocator(input.text().trim(), "Groovy script", execution.locator());
        }

        return LocatorResolver.resolve(context.page(), input.text());
    }

    public GroovyExecutionResult executeGroovy(PlaywrightScriptContext context, String script) {
        return groovyScriptService.execute(context, script);
    }
}
