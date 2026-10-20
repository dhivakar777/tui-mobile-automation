package com.tui.automation.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Properties;

/** Reads settings from config/config.properties; any key can be overridden with -Dkey=value. */
public final class ConfigManager {

    private static final String CONFIG_FILE = "config/config.properties";
    private static final Properties DEFAULTS = load();

    private ConfigManager() {
    }

    /** Returns a required value, failing fast when it is missing or blank. */
    public static String get(String key) {
        return getOptional(key).orElseThrow(() -> new IllegalStateException(
                "Missing required configuration '" + key + "'. Set it in " + CONFIG_FILE + " or pass -D" + key + "=..."));
    }

    public static Optional<String> getOptional(String key) {
        String value = System.getProperty(key, DEFAULTS.getProperty(key, ""));
        return value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    public static int getInt(String key) {
        String value = get(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Configuration '" + key + "' must be an integer but was '" + value + "'", e);
        }
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in == null) {
                throw new IllegalStateException("Configuration file not found on classpath: " + CONFIG_FILE);
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + CONFIG_FILE, e);
        }
        return properties;
    }
}
