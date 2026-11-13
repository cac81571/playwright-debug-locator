package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Playwright;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Launches Playwright Codegen with {@code mvn exec:java} against a generated minimal pom.xml
 * under {@code ~/.playwright-debug-locator/codegen-runner/} (no project pom.xml required).
 * <p>
 * On Windows, opens a new Command Prompt window so codegen output is visible.
 */
public final class PlaywrightCodegenService {

    private static final String TARGET = "java-junit";
    private static final String CHANNEL = "msedge";
    private static final String CLI_MAIN = "com.microsoft.playwright.CLI";
    private static final String DEFAULT_PLAYWRIGHT_VERSION = "1.60.0";
    private static final Path RUNNER_DIR = Path.of(
            System.getProperty("user.home"), ".playwright-debug-locator", "codegen-runner");
    private static final Path RUNNER_POM = RUNNER_DIR.resolve("pom.xml");

    private final AtomicReference<Process> processRef = new AtomicReference<>();

    public boolean isRunning() {
        Process process = processRef.get();
        return process != null && process.isAlive();
    }

    public void stop() {
        Process process = processRef.getAndSet(null);
        destroyProcess(process);
    }

    /**
     * Ensures the runner pom exists, launches codegen, and returns the Maven command line.
     */
    public String launch(String url) throws IOException {
        if (isRunning()) {
            stop();
        }

        ensureRunnerPom();
        List<String> mvnCommand = buildMavenCommand(url);
        String commandLine = formatCommand(mvnCommand);
        List<String> processCommand = isWindows()
                ? buildWindowsConsoleCommand(mvnCommand, commandLine)
                : mvnCommand;

        ProcessBuilder processBuilder = new ProcessBuilder(processCommand);
        processBuilder.directory(RUNNER_DIR.toFile());
        if (!isWindows()) {
            processBuilder.inheritIO();
        }

        Process process = processBuilder.start();
        processRef.set(process);

        Thread monitor = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                processRef.compareAndSet(process, null);
            }
        }, "codegen-monitor");
        monitor.setDaemon(true);
        monitor.start();
        return commandLine;
    }

    static Path runnerPomPath() {
        return RUNNER_POM;
    }

    private static void ensureRunnerPom() throws IOException {
        Files.createDirectories(RUNNER_DIR);
        String expected = runnerPomXml(resolvePlaywrightVersion());
        if (Files.isRegularFile(RUNNER_POM)) {
            String current = Files.readString(RUNNER_POM, StandardCharsets.UTF_8);
            if (expected.equals(current)) {
                return;
            }
        }
        Files.writeString(RUNNER_POM, expected, StandardCharsets.UTF_8);
    }

    private static String resolvePlaywrightVersion() {
        String version = Playwright.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            return DEFAULT_PLAYWRIGHT_VERSION;
        }
        return version.trim();
    }

    private static String runnerPomXml(String playwrightVersion) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.playwrightdebug</groupId>
                    <artifactId>playwright-codegen-runner</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                    <name>Playwright Codegen Runner</name>
                    <properties>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>com.microsoft.playwright</groupId>
                            <artifactId>playwright</artifactId>
                            <version>%s</version>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.codehaus.mojo</groupId>
                                <artifactId>exec-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(playwrightVersion);
    }

    private static List<String> buildMavenCommand(String url) {
        List<String> command = new ArrayList<>();
        command.add(mavenExecutable());
        command.add("-f");
        command.add(RUNNER_POM.toAbsolutePath().toString());
        command.add("exec:java");
        command.add("-e");
        command.add("-Dexec.mainClass=" + CLI_MAIN);
        command.add("-Dexec.args=" + buildExecArgs(url));
        return command;
    }

    private static String buildExecArgs(String url) {
        StringBuilder args = new StringBuilder();
        args.append("codegen --target=").append(TARGET)
                .append(" --channel=").append(CHANNEL);
        if (url != null && !url.isBlank()) {
            args.append(' ').append(url.trim());
        }
        return args.toString();
    }

    private static List<String> buildWindowsConsoleCommand(
            List<String> mvnCommand,
            String commandLine
    ) throws IOException {
        Path bat = Files.createTempFile("pw-codegen-", ".bat");
        bat.toFile().deleteOnExit();

        StringBuilder batBody = new StringBuilder();
        batBody.append("@echo off").append("\r\n");
        batBody.append("title Playwright Codegen").append("\r\n");
        batBody.append("cd /d ").append(quoteForBat(RUNNER_DIR.toAbsolutePath().toString())).append("\r\n");
        batBody.append("echo ").append(escapeForCmdEcho(commandLine)).append("\r\n");
        batBody.append("echo.").append("\r\n");
        for (String part : mvnCommand) {
            batBody.append(quoteForBat(part)).append(' ');
        }
        batBody.append("\r\n");
        batBody.append("set EXITCODE=%ERRORLEVEL%").append("\r\n");
        batBody.append("echo.").append("\r\n");
        batBody.append("echo Codegen finished (exit=%EXITCODE%). Press any key to close.").append("\r\n");
        batBody.append("pause >nul").append("\r\n");
        batBody.append("exit /b %EXITCODE%").append("\r\n");

        Files.writeString(bat, batBody.toString(), windowsBatCharset());

        List<String> command = new ArrayList<>();
        command.add("cmd.exe");
        command.add("/c");
        command.add("start");
        command.add("Playwright Codegen");
        command.add("/WAIT");
        command.add(bat.toAbsolutePath().toString());
        return command;
    }

    private static String mavenExecutable() {
        return isWindows() ? "mvn.cmd" : "mvn";
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    private static Charset windowsBatCharset() {
        try {
            return Charset.forName("MS932");
        } catch (Exception ex) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String formatCommand(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (String part : command) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(quoteIfNeeded(part));
        }
        return sb.toString();
    }

    private static String quoteIfNeeded(String value) {
        if (value.isEmpty() || value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0) {
            return '"' + value.replace("\"", "\\\"") + '"';
        }
        return value;
    }

    private static String quoteForBat(String value) {
        if (value.isEmpty() || needsBatQuotes(value)) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static boolean needsBatQuotes(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || c == '"' || c == '&' || c == '|' || c == '<' || c == '>' || c == '^'
                    || c == '(' || c == ')' || c == '%') {
                return true;
            }
        }
        return false;
    }

    private static String escapeForCmdEcho(String value) {
        return value
                .replace("^", "^^")
                .replace("&", "^&")
                .replace("|", "^|")
                .replace("<", "^<")
                .replace(">", "^>")
                .replace("%", "%%");
    }

    private static void destroyProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.descendants().forEach(ph -> ph.destroyForcibly());
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
