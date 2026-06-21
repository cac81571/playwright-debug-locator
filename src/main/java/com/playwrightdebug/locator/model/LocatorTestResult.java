package com.playwrightdebug.locator.model;

import java.util.ArrayList;
import java.util.List;

public final class LocatorTestResult {

    private final List<String> lines = new ArrayList<>();
    private boolean success;

    public void addLine(String line) {
        lines.add(line);
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public boolean isSuccess() {
        return success;
    }

    public String asText() {
        return String.join(System.lineSeparator(), lines);
    }
}
