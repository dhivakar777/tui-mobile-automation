package com.tui.automation.hooks;

import com.tui.automation.driver.DriverManager;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.openqa.selenium.OutputType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** The single owner of the driver lifecycle: one fresh session per scenario. */
public class Hooks {

    private static final Logger LOG = LogManager.getLogger(Hooks.class);
    private static final Path SCREENSHOT_DIR = Path.of("target", "screenshots");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    @Before
    public void startDriver(Scenario scenario) {
        // Adds the scenario name to every log line written on this thread (see %X{scenario} in log4j2.xml).
        ThreadContext.put("scenario", scenario.getName());
        LOG.info("Starting scenario '{}' {}", scenario.getName(), scenario.getSourceTagNames());
        DriverManager.startDriver();
        // Adds the device to every log line, so interleaved output from concurrent scenarios stays traceable.
        ThreadContext.put("device", DriverManager.deviceId());
    }

    /**
     * Captures failure evidence before the session ends. Quitting always runs, and a screenshot problem is only
     * logged, so it can neither skip teardown nor replace the scenario's original failure.
     */
    @After
    public void captureEvidenceAndQuit(Scenario scenario) {
        try {
            if (scenario.isFailed() && DriverManager.hasDriver()) {
                captureScreenshot(scenario);
            }
        } finally {
            DriverManager.quitDriver();
            LOG.info("Finished scenario '{}' with status {}", scenario.getName(), scenario.getStatus());
            ThreadContext.clearAll();
        }
    }

    /** Writes the screenshot to target/screenshots/ and embeds the same image in the Cucumber HTML report. */
    private static void captureScreenshot(Scenario scenario) {
        try {
            byte[] png = DriverManager.getDriver().getScreenshotAs(OutputType.BYTES);
            scenario.attach(png, "image/png", "Failure screenshot");
            Files.createDirectories(SCREENSHOT_DIR);
            // The device id keeps simultaneous failures on different devices from colliding.
            Path file = SCREENSHOT_DIR.resolve(LocalDateTime.now().format(TIMESTAMP) + "_" + fileSafe(DriverManager.deviceId())
                    + "_" + fileSafe(scenario.getName()) + ".png");
            Files.write(file, png);
            LOG.info("Failure screenshot saved to {}", file);
        } catch (Exception e) {
            LOG.warn("Could not capture failure screenshot: {}", e.toString());
        }
    }

    private static String fileSafe(String scenarioName) {
        return scenarioName.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
