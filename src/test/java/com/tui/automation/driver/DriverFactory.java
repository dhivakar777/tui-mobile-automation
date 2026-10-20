package com.tui.automation.driver;

import com.tui.automation.config.ConfigManager;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.WebDriverException;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Builds platform-specific capabilities and creates Appium sessions. */
final class DriverFactory {

    private static final Logger LOG = LogManager.getLogger(DriverFactory.class);

    private DriverFactory() {
    }

    static AppiumDriver createDriver() {
        String platform = ConfigManager.platform();
        if (!"android".equals(platform)) {
            throw new IllegalStateException("Unsupported platform '" + platform + "'. Only 'android' is implemented.");
        }
        URL serverUrl = serverUrl();
        UiAutomator2Options options = androidOptions();
        LOG.info("Creating Android session on {} with capabilities {}", serverUrl, options.asMap());
        try {
            AndroidDriver driver = new AndroidDriver(serverUrl, options);
            LOG.info("Session {} started on device {}", driver.getSessionId(), driver.getCapabilities().getCapability("deviceUDID"));
            return driver;
        } catch (WebDriverException e) {
            throw new IllegalStateException(diagnoseSessionFailure(serverUrl), e);
        }
    }

    private static UiAutomator2Options androidOptions() {
        UiAutomator2Options options = new UiAutomator2Options()
                .setDeviceName(ConfigManager.getRequired("device.name"))
                .setAppPackage(ConfigManager.getRequired("app.package"))
                .setAppActivity(ConfigManager.getRequired("app.activity"))
                .setNoReset(ConfigManager.getBoolean("app.no.reset"))
                .setNewCommandTimeout(Duration.ofSeconds(ConfigManager.getInt("timeout.new.command.seconds")))
                .setDisableWindowAnimation(true);

        String udid = ConfigManager.get("device.udid");
        if (!udid.isEmpty()) {
            options.setUdid(udid);
        }
        String appPath = ConfigManager.get("app.path");
        if (!appPath.isEmpty()) {
            Path apk = Path.of(appPath).toAbsolutePath();
            if (!Files.isRegularFile(apk)) {
                throw new IllegalStateException("Configured app.path does not exist: " + apk);
            }
            options.setApp(apk.toString());
        }
        return options;
    }

    private static URL serverUrl() {
        String url = ConfigManager.getRequired("appium.server.url");
        try {
            return URI.create(url).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new IllegalStateException("Invalid appium.server.url: " + url, e);
        }
    }

    /** Distinguishes "server not running" from "server rejected the session" to speed up troubleshooting. */
    private static String diagnoseSessionFailure(URL serverUrl) {
        String statusUrl = serverUrl.toString().replaceAll("/$", "") + "/status";
        try {
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                    .send(HttpRequest.newBuilder(URI.create(statusUrl)).timeout(Duration.ofSeconds(5)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            return "Appium server at " + serverUrl + " is reachable (status " + response.statusCode()
                    + ") but could not create a session. Check that the device is connected and authorised"
                    + " ('adb devices'), the app package/activity are correct, and the Appium server log.";
        } catch (Exception statusError) {
            if (statusError instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "Appium server is not reachable at " + serverUrl + ". Start it with"
                    + " 'appium --address 127.0.0.1 --port 4723' or set appium.server.url / APPIUM_SERVER_URL.";
        }
    }
}
