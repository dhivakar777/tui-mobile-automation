package com.tui.automation.hooks;

import com.tui.automation.driver.DriverManager;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebDriverException;

/** The single owner of the driver lifecycle: one fresh session per scenario. */
public class Hooks {

    private static final Logger LOG = LogManager.getLogger(Hooks.class);

    @Before
    public void startDriver(Scenario scenario) {
        // Adds the scenario name to every log line written on this thread (see %X{scenario} in log4j2.xml).
        ThreadContext.put("scenario", scenario.getName());
        LOG.info("Starting scenario '{}' {}", scenario.getName(), scenario.getSourceTagNames());
        DriverManager.startDriver();
    }

    /** Captures failure evidence before the session ends; quitting always runs, even if the screenshot fails. */
    @After
    public void captureEvidenceAndQuit(Scenario scenario) {
        try {
            if (scenario.isFailed() && DriverManager.hasDriver()) {
                attachScreenshot(scenario);
            }
        } finally {
            DriverManager.quitDriver();
            LOG.info("Finished scenario '{}' with status {}", scenario.getName(), scenario.getStatus());
            ThreadContext.clearAll();
        }
    }

    private static void attachScreenshot(Scenario scenario) {
        try {
            byte[] png = DriverManager.getDriver().getScreenshotAs(OutputType.BYTES);
            scenario.attach(png, "image/png", "Failure screenshot");
            LOG.info("Attached failure screenshot to the report");
        } catch (WebDriverException e) {
            LOG.warn("Could not capture failure screenshot: {}", e.getMessage());
        }
    }
}
