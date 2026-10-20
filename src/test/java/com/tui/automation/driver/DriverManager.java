package com.tui.automation.driver;

import io.appium.java_client.AppiumDriver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Owns the Appium session for the current thread. Each test thread gets its own driver,
 * so scenarios can later run in parallel without sharing sessions.
 */
public final class DriverManager {

    private static final Logger LOG = LogManager.getLogger(DriverManager.class);
    private static final ThreadLocal<AppiumDriver> DRIVER = new ThreadLocal<>();

    private DriverManager() {
    }

    public static void startDriver() {
        if (DRIVER.get() != null) {
            throw new IllegalStateException("A driver is already running on thread " + Thread.currentThread().getName());
        }
        DRIVER.set(DriverFactory.createDriver());
    }

    public static AppiumDriver getDriver() {
        AppiumDriver driver = DRIVER.get();
        if (driver == null) {
            throw new IllegalStateException("No driver on thread " + Thread.currentThread().getName()
                    + ". Call DriverManager.startDriver() first.");
        }
        return driver;
    }

    /** Ends the session if one exists. Always clears the thread slot, even if quit() fails. */
    public static void quitDriver() {
        AppiumDriver driver = DRIVER.get();
        if (driver == null) {
            return;
        }
        try {
            LOG.info("Ending session {}", driver.getSessionId());
            driver.quit();
        } catch (RuntimeException e) {
            LOG.warn("Driver quit failed; the session may already be gone: {}", e.getMessage());
        } finally {
            DRIVER.remove();
        }
    }
}
