package com.tui.automation.driver;

import com.tui.automation.config.ConfigManager;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.WebDriverException;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates, exposes and ends the Appium session. The driver is held per thread; when {@code device.udids} is set,
 * each session also claims its own device and port from {@link DevicePool}.
 */
public final class DriverManager {

    private static final Logger LOG = LogManager.getLogger(DriverManager.class);
    private static final ThreadLocal<AppiumDriver> DRIVER = new ThreadLocal<>();
    private static final Duration NEW_COMMAND_TIMEOUT = Duration.ofSeconds(120);
    private static final List<String> LOGGABLE_CAPABILITIES = List.of(
            "platformName", "appium:automationName", "appium:udid", "appium:app", "appium:appPackage",
            "appium:appActivity", "appium:language", "appium:locale", "appium:noReset", "appium:systemPort");

    private DriverManager() {
    }

    public static void startDriver() {
        if (DRIVER.get() != null) {
            throw new IllegalStateException("A driver is already running on thread " + Thread.currentThread().getName());
        }
        URL serverUrl = serverUrl();
        UiAutomator2Options options = androidOptions();
        if (DevicePool.isEnabled()) {
            DevicePool.Device device = DevicePool.claim();
            options.setUdid(device.udid()).setSystemPort(device.systemPort());
        }
        LOG.info("Creating Android session on {} with capabilities {}", serverUrl, loggableCapabilities(options));
        try {
            AndroidDriver driver = new AndroidDriver(serverUrl, options);
            LOG.info("Session {} started on device {}", driver.getSessionId(), driver.getCapabilities().getCapability("deviceUDID"));
            DRIVER.set(driver);
        } catch (RuntimeException e) {
            // No session exists, so quitDriver() will not run for it; return the device here instead.
            DevicePool.release();
            if (e instanceof WebDriverException) {
                throw new IllegalStateException("Could not start an Appium session on " + serverUrl
                        + ". Is the Appium server running and the device listed by 'adb devices'?", e);
            }
            throw e;
        }
    }

    public static AppiumDriver getDriver() {
        AppiumDriver driver = DRIVER.get();
        if (driver == null) {
            throw new IllegalStateException("No driver on thread " + Thread.currentThread().getName()
                    + ". Call DriverManager.startDriver() first.");
        }
        return driver;
    }

    /** The device the current thread's session runs on, or an empty string when no session is running. */
    public static String deviceId() {
        AppiumDriver driver = DRIVER.get();
        return driver == null ? "" : String.valueOf(driver.getCapabilities().getCapability("deviceUDID"));
    }

    /** False when session creation failed, so teardown code can skip driver calls such as screenshots. */
    public static boolean hasDriver() {
        return DRIVER.get() != null;
    }

    /**
     * Ends the session if one exists. Always clears the thread slot and returns any claimed device to the pool,
     * even if quit() fails.
     */
    public static void quitDriver() {
        AppiumDriver driver = DRIVER.get();
        try {
            if (driver != null) {
                LOG.info("Ending session {}", driver.getSessionId());
                driver.quit();
            }
        } catch (RuntimeException e) {
            LOG.warn("Driver quit failed; the session may already be gone: {}", e.getMessage());
        } finally {
            DRIVER.remove();
            DevicePool.release();
        }
    }

    private static UiAutomator2Options androidOptions() {
        UiAutomator2Options options = new UiAutomator2Options()
                .setAppPackage(ConfigManager.get("app.package"))
                .setAppActivity(ConfigManager.get("app.activity"))
                // Clear app data at session start so every scenario begins on a fresh login screen.
                .setNoReset(false)
                .setNewCommandTimeout(NEW_COMMAND_TIMEOUT)
                .setDisableWindowAnimation(true);

        ConfigManager.getOptional("device.udid").ifPresent(options::setUdid);
        ConfigManager.getOptional("device.language").ifPresent(options::setLanguage);
        ConfigManager.getOptional("device.locale").ifPresent(options::setLocale);
        ConfigManager.getOptional("app.path").ifPresent(path -> {
            Path apk = Path.of(path).toAbsolutePath();
            if (!Files.isRegularFile(apk)) {
                throw new IllegalStateException("Configured app.path does not exist: " + apk);
            }
            options.setApp(apk.toString());
        });
        return options;
    }

    /**
     * Only allowlisted capabilities are logged. A full capability map can carry credentials (for example a cloud
     * provider's access key), so anything not listed here is never written to logs or reports.
     */
    private static Map<String, Object> loggableCapabilities(Capabilities capabilities) {
        Map<String, Object> loggable = new LinkedHashMap<>();
        for (String name : LOGGABLE_CAPABILITIES) {
            Object value = capabilities.getCapability(name);
            if (value != null) {
                loggable.put(name, value);
            }
        }
        return loggable;
    }

    private static URL serverUrl() {
        String url = ConfigManager.get("appium.server.url");
        try {
            return URI.create(url).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new IllegalStateException("Invalid appium.server.url: " + url, e);
        }
    }
}
