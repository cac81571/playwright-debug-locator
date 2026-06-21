package com.playwrightdebug.locator.model;

public record LocatorInput(EvaluationMode mode, String text) {

    public enum EvaluationMode {
        SIMPLE,
        GROOVY,
        TEST_SCRIPT
    }
}
