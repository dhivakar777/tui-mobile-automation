package com.tui.automation.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Properties;

/**
 * Resolves configuration values in priority order:
 * JVM system property (-Dkey=value) &gt; environment variable (KEY_NAME) &gt; config/&lt;platform&gt;.properties.
 * The platform file is selected with -Dplatform (default: android).
 */
public final class ConfigManager {

    private static final String PLATFORM = System.getProperty("platform", "android").toLowerCase(Locale.ROOT);
    private static final Properties FILE_PROPERTIES = load("config/" + PLATFORM + ".properties");

    private ConfigManager() {
    }

    public static String platform() {
        return PLATFORM;
    }

    /** Returns the value for {@code key}, or an empty string when it is not set anywhere. */
    public static String get(String key) {
        String systemValue = System.getProperty(key);
        if (isSet(systemValue)) {
            return systemValue.trim();
        }
        String envValue = System.getenv(toEnvName(key));
        if (isSet(envValue)) {
            return envValue.trim();
        }
        return FILE_PROPERTIES.getProperty(key, "").trim();
    }

    public static String getRequired(String key) {
        String value = get(key);
        if (value.isEmpty()) {
            throw new IllegalStateException("Missing required configuration '" + key + "'. Set it in config/"
                    + PLATFORM + ".properties, as -D" + key + "=..., or as env var " + toEnvName(key));
        }
        return value;
    }

    public static int getInt(String key) {
        String value = getRequired(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Configuration '" + key + "' must be an integer but was '" + value + "'", e);
        }
    }

    public static boolean getBoolean(String key) {
        return Boolean.parseBoolean(getRequired(key));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static String toEnvName(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    private static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Configuration file not found on classpath: " + resource
                        + " (check the -Dplatform value)");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read configuration file " + resource, e);
        }
        return properties;
    }
}
