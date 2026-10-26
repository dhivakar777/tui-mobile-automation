package com.tui.automation.driver;

import com.tui.automation.config.ConfigManager;
import io.appium.java_client.android.options.UiAutomator2Options;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Provider-specific configuration for running sessions on a device cloud. Only BrowserStack is supported.
 *
 * <p>Credentials are read from the {@value #USERNAME_VARIABLE} and {@value #ACCESS_KEY_VARIABLE} environment
 * variables and nowhere else: never from config files or {@code -D} properties (Surefire records every system
 * property in its reports). They are sent only inside the provider's {@code bstack:options} capability, which
 * is not on the logging allowlist, and are removed from any session-creation error before it is reported.
 */
final class CloudOptions {

    static final String USERNAME_VARIABLE = "BROWSERSTACK_USERNAME";
    static final String ACCESS_KEY_VARIABLE = "BROWSERSTACK_ACCESS_KEY";
    private static final String PROVIDER = "browserstack";
    private static final String MASK = "***";
    private static final Pattern CREDENTIAL_FIELD = Pattern.compile(
            "(?i)(\"?(?:accessKey|userName|username|password)\"?\\s*[:=]\\s*\"?)[^,}\\s\"]+");

    private final Function<String, Optional<String>> config;
    private final Function<String, String> environment;

    /** Where the no-argument constructor reads credentials. Only tests replace it, to supply fake values. */
    static Function<String, String> environmentSource = System::getenv;

    CloudOptions() {
        this(ConfigManager::getOptional, environmentSource);
    }

    /** Lets tests supply configuration and fake environment variables without touching the real ones. */
    CloudOptions(Function<String, Optional<String>> config, Function<String, String> environment) {
        this.config = config;
        this.environment = environment;
    }

    /**
     * True for {@code execution.target=cloud}, false for {@code local}. Anything else is rejected rather than
     * treated as local, so a typo can never silently run on a local device.
     */
    static boolean isSelected(Function<String, Optional<String>> config) {
        String target = config.apply("execution.target").orElse("local").toLowerCase(Locale.ROOT);
        return switch (target) {
            case "local" -> false;
            case "cloud" -> true;
            default -> throw new IllegalStateException(
                    "Unknown execution.target '" + target + "'; use 'local' or 'cloud'");
        };
    }

    URL hubUrl() {
        String url = required("cloud.hub.url");
        if (url.matches("(?i)^[a-z][a-z0-9+.-]*://[^/]*@.*")) {
            throw new IllegalStateException("cloud.hub.url must not contain credentials; set "
                    + USERNAME_VARIABLE + " and " + ACCESS_KEY_VARIABLE + " instead");
        }
        try {
            return URI.create(url).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new IllegalStateException("Invalid cloud.hub.url (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Adds the cloud app, device and provider options to the shared session options. */
    UiAutomator2Options apply(UiAutomator2Options options) {
        String provider = required("cloud.provider").toLowerCase(Locale.ROOT);
        if (!PROVIDER.equals(provider)) {
            throw new IllegalStateException("Unsupported cloud.provider '" + provider + "'; only 'browserstack' is supported");
        }
        for (String localOnly : List.of("device.udid", "device.udids", "app.path")) {
            if (config.apply(localOnly).isPresent()) {
                throw new IllegalStateException(localOnly + " applies to local execution only; clear it when execution.target=cloud");
            }
        }
        Map<String, Object> browserstack = new LinkedHashMap<>();
        browserstack.put("userName", credential(USERNAME_VARIABLE));
        browserstack.put("accessKey", credential(ACCESS_KEY_VARIABLE));
        browserstack.put("projectName", required("cloud.project.name"));
        browserstack.put("buildName", required("cloud.build.name"));
        options.setApp(required("cloud.app"))
                .setDeviceName(required("cloud.device.name"))
                .setPlatformVersion(required("cloud.platform.version"));
        options.setCapability("bstack:options", browserstack);
        return options;
    }

    /** The credential values currently set, so they can be removed from error text. */
    List<String> secrets() {
        List<String> secrets = new ArrayList<>();
        for (String variable : List.of(USERNAME_VARIABLE, ACCESS_KEY_VARIABLE)) {
            String value = environment.apply(variable);
            if (value != null && !value.isBlank()) {
                secrets.add(value.trim());
            }
        }
        return secrets;
    }

    /**
     * Rebuilds a failure and its whole cause chain with every secret value masked. Selenium's session-creation
     * errors quote the requested capabilities, which include the access key; each throwable is copied with a masked
     * message and its original stack frames, so the diagnosis survives but the secret does not.
     */
    static IllegalStateException sanitized(String message, Throwable failure, List<String> secrets) {
        IllegalStateException copy = new IllegalStateException(mask(message, secrets), sanitizedCause(failure, secrets));
        copy.setStackTrace(new Throwable().getStackTrace());
        return copy;
    }

    private static Throwable sanitizedCause(Throwable failure, List<String> secrets) {
        if (failure == null) {
            return null;
        }
        Throwable copy = new SanitizedCause(failure.getClass().getName() + ": " + mask(failure.getMessage(), secrets),
                sanitizedCause(failure.getCause(), secrets));
        copy.setStackTrace(failure.getStackTrace());
        return copy;
    }

    /**
     * Masks secrets in two ways. Exact values are replaced wherever they occur. The values of credential fields are
     * also replaced by name, because Selenium shortens long capability values ({@code abc123...}) and a shortened
     * key would no longer match the exact value.
     */
    static String mask(String text, List<String> secrets) {
        if (text == null) {
            return null;
        }
        String masked = text;
        for (String secret : secrets) {
            masked = masked.replace(secret, MASK);
        }
        return CREDENTIAL_FIELD.matcher(masked).replaceAll("$1" + MASK);
    }

    private String required(String key) {
        return config.apply(key).orElseThrow(() -> new IllegalStateException(
                "Missing required cloud configuration '" + key + "' (execution.target=cloud)"));
    }

    private String credential(String variable) {
        String value = environment.apply(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + variable + " is not set (execution.target=cloud)");
        }
        return value.trim();
    }

    /** A cause whose message has been masked; it carries the original class name in its message. */
    private static final class SanitizedCause extends RuntimeException {
        SanitizedCause(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }
}
