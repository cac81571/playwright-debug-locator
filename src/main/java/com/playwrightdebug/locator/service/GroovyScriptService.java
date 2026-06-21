package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Locator;
import com.playwrightdebug.locator.model.GroovyExecutionResult;
import com.playwrightdebug.locator.model.PlaywrightScriptContext;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class GroovyScriptService {

    private static final CompilerConfiguration CONFIG = createConfiguration();

    public GroovyExecutionResult execute(PlaywrightScriptContext context, String script) {
        Binding binding = createBinding(context);
        GroovyShell shell = new GroovyShell(binding, CONFIG);
        Object result;
        try {
            result = shell.evaluate(prepareScript(script));
        } catch (Exception ex) {
            throw new IllegalArgumentException(formatGroovyError(ex), ex);
        }

        if (result instanceof Locator locator) {
            return new GroovyExecutionResult(locator, null);
        }
        return new GroovyExecutionResult(null, result);
    }

    public Locator evaluateLocator(PlaywrightScriptContext context, String script) {
        GroovyExecutionResult execution = execute(context, script);
        if (execution.hasLocator()) {
            return execution.locator();
        }
        throw new IllegalArgumentException(describeUnexpectedResult(execution.rawResult()));
    }

    private static Binding createBinding(PlaywrightScriptContext context) {
        Binding binding = new Binding();
        binding.setVariable("playwright", context.playwright());
        binding.setVariable("browser", context.browser());
        binding.setVariable("context", context.context());
        binding.setVariable("page", context.page());
        return binding;
    }

    private static String prepareScript(String script) {
        ParsedScript parsed = parseScript(script.strip());
        if (parsed.body.isEmpty()) {
            throw new IllegalArgumentException("Groovy スクリプトが空です");
        }

        String body = parsed.body;
        if (hasExplicitReturn(body)) {
            return joinSections(parsed.imports, body);
        }

        body = removeTrailingSemicolonFromLastStatement(body);

        String preparedBody;
        if (!body.contains("\n")) {
            preparedBody = "return " + body;
        } else {
            String indentedBody = body.lines()
                    .map(line -> "    " + line)
                    .collect(Collectors.joining("\n"));
            preparedBody = """
                    def __evalScript() {
                    %s
                    }
                    return __evalScript()
                    """.formatted(indentedBody);
        }

        return joinSections(parsed.imports, preparedBody);
    }

    private static ParsedScript parseScript(String script) {
        List<String> imports = new ArrayList<>();
        List<String> bodyLines = new ArrayList<>();
        boolean importSection = true;

        for (String line : script.lines().toList()) {
            String trimmed = line.trim();
            if (importSection && isImportLine(trimmed)) {
                imports.add(line);
                continue;
            }
            if (importSection && trimmed.isEmpty()) {
                continue;
            }
            if (importSection && trimmed.startsWith("//")) {
                continue;
            }
            importSection = false;
            bodyLines.add(line);
        }

        return new ParsedScript(imports, String.join("\n", bodyLines).strip());
    }

    private static boolean isImportLine(String trimmed) {
        return trimmed.startsWith("import ") || trimmed.startsWith("import static ");
    }

    private static String joinSections(List<String> imports, String body) {
        if (imports.isEmpty()) {
            return body;
        }
        return imports.stream().collect(Collectors.joining("\n")) + "\n\n" + body;
    }

    private static boolean hasExplicitReturn(String script) {
        return script.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("//"))
                .anyMatch(line -> line.startsWith("return ") || line.startsWith("return("));
    }

    private static String removeTrailingSemicolonFromLastStatement(String script) {
        String[] lines = script.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            if (trimmed.endsWith(";")) {
                lines[i] = lines[i].replaceFirst(";\\s*$", "");
            }
            break;
        }
        return String.join("\n", lines);
    }

    private static String describeUnexpectedResult(Object result) {
        if (result == null) {
            return """
                    Groovy スクリプトは Locator を返す必要があります (実際: null)。
                    click() などのアクションだけを実行する場合は「Click 試行」を使うか、
                    Locator 式を return してください。""";
        }
        return "Groovy スクリプトは Locator を返す必要があります (実際: " + result.getClass().getName() + ")";
    }

    private static String formatGroovyError(Exception ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? ex.toString() : cause.getMessage();
    }

    private static CompilerConfiguration createConfiguration() {
        ImportCustomizer imports = new ImportCustomizer();
        imports.addImports(
                "com.microsoft.playwright.Page",
                "com.microsoft.playwright.Locator",
                "com.microsoft.playwright.Browser",
                "com.microsoft.playwright.BrowserContext",
                "com.microsoft.playwright.Playwright",
                "com.microsoft.playwright.options.AriaRole",
                "com.microsoft.playwright.Page.GetByRoleOptions",
                "com.microsoft.playwright.Locator.FilterOptions"
        );
        imports.addStarImports("com.microsoft.playwright.options");
        imports.addStaticStars("com.playwrightdebug.locator.service.PlaywrightGroovyBindings");

        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.addCompilationCustomizers(imports);
        return configuration;
    }

    private record ParsedScript(List<String> imports, String body) {
    }
}
