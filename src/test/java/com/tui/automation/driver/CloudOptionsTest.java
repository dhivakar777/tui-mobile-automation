package com.tui.automation.driver;

import com.sun.net.httpserver.HttpServer;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

/**
 * Device-free checks of cloud configuration and credential handling. Every credential here is fake. A local HTTP
 * server stands in for a cloud hub that rejects every session, so no device, Appium server or cloud account is used.
 */
public class CloudOptionsTest {

    private static final String FAKE_USER = "fake-user-7c41";
    private static final String FAKE_KEY = "FAKEKEY0000TESTONLY9999";
    private static final String FAKE_LONG_KEY = "FAKELONGKEY0123456789abcdefghijklmnopqrstuvwxyz";
    private static final List<String> CLOUD_PROPERTIES = List.of("execution.target", "cloud.provider", "cloud.hub.url",
            "cloud.app", "cloud.device.name", "cloud.platform.version", "cloud.project.name", "cloud.build.name");

    private static final List<String> LOCAL_ONLY_PROPERTIES = List.of("device.udid", "device.udids", "app.path");

    private HttpServer rejectingHub;
    private final Map<String, String> savedLocalProperties = new HashMap<>();

    @BeforeMethod
    public void startRejectingHub() throws IOException {
        rejectingHub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rejectingHub.createContext("/", exchange -> {
            byte[] body = ("{\"value\":{\"error\":\"session not created\",\"message\":\"Fake hub rejected the session\","
                    + "\"stacktrace\":\"\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        rejectingHub.start();
    }

    @AfterMethod(alwaysRun = true)
    public void stopRejectingHubAndRestore() {
        rejectingHub.stop(0);
        CLOUD_PROPERTIES.forEach(System::clearProperty);
        savedLocalProperties.forEach(System::setProperty);
        savedLocalProperties.clear();
        CloudOptions.environmentSource = System::getenv;
    }

    @Test
    public void unknownExecutionTargetIsRejectedInsteadOfRunningLocally() {
        IllegalStateException e = expectThrows(IllegalStateException.class,
                () -> CloudOptions.isSelected(key -> key.equals("execution.target") ? Optional.of("clod") : Optional.empty()));
        assertTrue(e.getMessage().contains("Unknown execution.target 'clod'"), e.getMessage());
        assertFalse(CloudOptions.isSelected(key -> Optional.of("local")));
        assertTrue(CloudOptions.isSelected(key -> Optional.of("cloud")));
    }

    @Test
    public void missingCredentialNamesTheVariableWithoutRevealingTheOther() {
        CloudOptions cloud = new CloudOptions(config(), env(FAKE_USER, null));
        IllegalStateException e = expectThrows(IllegalStateException.class, () -> cloud.apply(new UiAutomator2Options()));
        assertTrue(e.getMessage().contains(CloudOptions.ACCESS_KEY_VARIABLE), e.getMessage());
        assertNoSecret(e.getMessage(), FAKE_USER);
    }

    @Test
    public void missingCloudSettingFailsClearly() {
        Map<String, String> settings = settings();
        settings.remove("cloud.app");
        CloudOptions cloud = new CloudOptions(config(settings), env(FAKE_USER, FAKE_KEY));
        IllegalStateException e = expectThrows(IllegalStateException.class, () -> cloud.apply(new UiAutomator2Options()));
        assertTrue(e.getMessage().contains("cloud.app"), e.getMessage());
    }

    @Test
    public void localOnlySettingsAreRejectedInCloudMode() {
        Map<String, String> settings = settings();
        settings.put("device.udid", "emulator-5554");
        CloudOptions cloud = new CloudOptions(config(settings), env(FAKE_USER, FAKE_KEY));
        IllegalStateException e = expectThrows(IllegalStateException.class, () -> cloud.apply(new UiAutomator2Options()));
        assertTrue(e.getMessage().contains("device.udid applies to local execution only"), e.getMessage());
    }

    @Test
    public void hubUrlContainingCredentialsIsRejected() {
        Map<String, String> settings = settings();
        settings.put("cloud.hub.url", "https://" + FAKE_USER + ":" + FAKE_KEY + "@hub.example.test/wd/hub");
        IllegalStateException e = expectThrows(IllegalStateException.class,
                () -> new CloudOptions(config(settings), env(FAKE_USER, FAKE_KEY)).hubUrl());
        assertTrue(e.getMessage().contains("must not contain credentials"), e.getMessage());
        assertNoSecret(e.getMessage(), FAKE_USER, FAKE_KEY);
    }

    @Test
    public void credentialsAreSentToTheProviderButNeverLogged() {
        UiAutomator2Options options = new CloudOptions(config(), env(FAKE_USER, FAKE_KEY)).apply(new UiAutomator2Options());
        Object providerOptions = options.getCapability("bstack:options");
        assertNotNull(providerOptions, "bstack:options must be sent");
        assertTrue(providerOptions.toString().contains(FAKE_KEY), "the access key must reach the provider");
        assertEquals(options.getCapability("appium:app"), "bs://fake-app-id");
        assertNoSecret(DriverManager.loggableCapabilities(options).toString(), FAKE_USER, FAKE_KEY);
    }

    @Test
    public void selenium_quotesCredentials_soTheRawFailureIsUnsafe() throws Exception {
        // Guards the premise of the sanitiser: if Selenium stops quoting capabilities, this test says so.
        String raw = stackTrace(rawSessionFailure(FAKE_KEY));
        assertTrue(raw.contains(FAKE_KEY), "expected Selenium's raw error to quote the access key");
    }

    @Test
    public void sessionFailureIsSanitisedInMessageCausesAndStackTrace() throws Exception {
        for (String key : List.of(FAKE_KEY, FAKE_LONG_KEY)) {
            RuntimeException raw = rawSessionFailure(key);
            IllegalStateException safe = CloudOptions.sanitized("Could not start a cloud session", raw, List.of(FAKE_USER, key));
            String everything = safe.getMessage() + "\n" + safe + "\n" + stackTrace(safe);
            assertNoSecret(everything, FAKE_USER, key, key.substring(0, 12));
            assertTrue(everything.contains("Fake hub rejected the session"), "the provider's reason must be kept");
            assertTrue(everything.contains("org.openqa.selenium.SessionNotCreatedException"), "the original type must be kept");
            assertTrue(everything.contains("bstack:options"), "the rest of the diagnosis must be kept");
        }
    }

    @Test
    public void endToEndCloudSessionFailureLeaksNoSecretToConsoleLogOrException() throws Exception {
        // A local run may pass -Ddevice.udid; cloud mode rejects local-only settings, so set them aside here.
        for (String key : LOCAL_ONLY_PROPERTIES) {
            String value = System.clearProperty(key);
            if (value != null) {
                savedLocalProperties.put(key, value);
            }
        }
        settings().forEach(System::setProperty);
        System.setProperty("cloud.hub.url", hubUrl().toString());
        CloudOptions.environmentSource = env(FAKE_USER, FAKE_KEY);
        Path logFile = Path.of("target", "logs", "test-execution.log");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        IllegalStateException failure;
        try {
            failure = expectThrows(IllegalStateException.class, DriverManager::startDriver);
        } finally {
            System.setOut(originalOut);
        }
        assertFalse(DriverManager.hasDriver(), "no session may be left behind");
        assertTrue(console.toString(StandardCharsets.UTF_8).contains("Creating cloud Android session"),
                "console output should have been captured");
        assertTrue(failure.getMessage().startsWith("Could not start a cloud session"), failure.getMessage());
        assertNoSecret(console.toString(StandardCharsets.UTF_8), FAKE_USER, FAKE_KEY);
        assertNoSecret(stackTrace(failure), FAKE_USER, FAKE_KEY);
        assertNoSecret(Files.readString(logFile), FAKE_USER, FAKE_KEY);
    }

    private RuntimeException rawSessionFailure(String accessKey) throws Exception {
        UiAutomator2Options options = new CloudOptions(config(), env(FAKE_USER, accessKey)).apply(new UiAutomator2Options());
        try {
            new AndroidDriver(hubUrl(), options).quit();
            fail("the fake hub must reject the session");
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private URL hubUrl() throws Exception {
        return URI.create("http://127.0.0.1:" + rejectingHub.getAddress().getPort() + "/wd/hub").toURL();
    }

    private static Map<String, String> settings() {
        Map<String, String> settings = new HashMap<>();
        settings.put("execution.target", "cloud");
        settings.put("cloud.provider", "browserstack");
        settings.put("cloud.hub.url", "https://hub.example.test/wd/hub");
        settings.put("cloud.app", "bs://fake-app-id");
        settings.put("cloud.device.name", "Fake Device");
        settings.put("cloud.platform.version", "13.0");
        settings.put("cloud.project.name", "TUI mobile automation");
        settings.put("cloud.build.name", "unit-test");
        return settings;
    }

    private static java.util.function.Function<String, Optional<String>> config() {
        return config(settings());
    }

    private static java.util.function.Function<String, Optional<String>> config(Map<String, String> settings) {
        return key -> Optional.ofNullable(settings.get(key));
    }

    private static java.util.function.Function<String, String> env(String user, String key) {
        Map<String, String> variables = new HashMap<>();
        variables.put(CloudOptions.USERNAME_VARIABLE, user);
        variables.put(CloudOptions.ACCESS_KEY_VARIABLE, key);
        return variables::get;
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter out = new StringWriter();
        throwable.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static void assertNoSecret(String text, String... secrets) {
        for (String secret : secrets) {
            assertFalse(text.contains(secret), "secret leaked: text contains a credential value");
        }
    }
}
