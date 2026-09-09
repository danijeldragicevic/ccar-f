package dev.ccarf.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Loads and provides access to the properties in ccarf.properties.
 */
public final class Config {

    private static final String RESOURCE = "/ccarf.properties";
    private static final Properties PROPERTIES = load();

    private Config() {}

    // Load the properties from the resource file.
    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = Config.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE
                        + " is not on the classpath. It lives in the 'common' module - check that"
                        + " your module declares a dependency on dev.ccarf:common.");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
        return properties;
    }

    
    // The model for anything doing real reasoning: agent loops, orchestrators.
    public static String modelMain() {
        return require("model.main");
    }

    // The model for workers under an orchestrator: bounded subtasks, isolated context.
    public static String modelWorker() {
        return require("model.worker");
    }

    // Per-response output cap, and the spend throttle while drilling exercises.
    public static long maxTokens() {
        return requireLong("max.tokens");
    }

    // Helper methods

    // Returns the value for key, or fallback when it is absent or blank.
    private static String get(String key, String fallback) {
        String value = PROPERTIES.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    // Returns the value for key, or throw error when it is missing.
    private static String require(String key) {
        String value = get(key, null);
        if (value == null) {
            throw new IllegalStateException("Missing '" + key + "' in " + RESOURCE);
        }
        return value;
    }

    // Returns the value for key as a long, or throw error when it is missing or not a number.
    private static long requireLong(String key) {
        String raw = require(key);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Property '" + key + "' in " + RESOURCE + " is not a number: " + raw, e);
        }
    }
}
