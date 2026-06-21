package com.playwrightdebug.locator.service;

import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.impl.driver.Driver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class PlaywrightCodegenService {

    private static final String TARGET = "java";
    private static final String CHANNEL = "msedge";

    private final AtomicReference<Process> processRef = new AtomicReference<>();

    public boolean isRunning() {
        Process process = processRef.get();
        return process != null && process.isAlive();
    }

    public void stop() {
        Process process = processRef.getAndSet(null);
        destroyProcess(process);
    }

    public void launch(String url) throws IOException {
        if (isRunning()) {
            stop();
        }

        List<String> args = buildArgs(url);
        Driver driver = Driver.ensureDriverInstalled(Collections.emptyMap(), false);
        ProcessBuilder processBuilder = driver.createProcessBuilder();
        processBuilder.command().addAll(args);
        processBuilder.inheritIO();

        String version = Playwright.class.getPackage().getImplementationVersion();
        if (version != null) {
            processBuilder.environment().put("PW_CLI_DISPLAY_VERSION", version);
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

    private static List<String> buildArgs(String url) {
        List<String> args = new ArrayList<>();
        args.add("codegen");
        args.add("--target=" + TARGET);
        args.add("--channel=" + CHANNEL);
        if (url != null && !url.isBlank()) {
            args.add(url.trim());
        }
        return args;
    }
}
