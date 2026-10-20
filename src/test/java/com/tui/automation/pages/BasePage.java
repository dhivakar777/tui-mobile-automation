package com.tui.automation.pages;

import com.tui.automation.config.ConfigManager;
import com.tui.automation.driver.DriverManager;
import io.appium.java_client.AppiumBy;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

/** Shared driver, explicit wait and Compose test-tag locator for all pages. */
public abstract class BasePage {

    protected final AppiumDriver driver = DriverManager.getDriver();
    protected final WebDriverWait wait = new WebDriverWait(driver,
            Duration.ofSeconds(ConfigManager.getInt("timeout.explicit.wait.seconds")));

    /**
     * Compose exposes test tags as resource-ids without a package prefix, which AppiumBy.id cannot match,
     * so tags are located with a UiSelector instead.
     */
    protected static By byTag(String testTag) {
        return AppiumBy.androidUIAutomator("new UiSelector().resourceId(\"" + testTag + "\")");
    }

    protected WebElement waitForVisible(By locator) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    /** Waits up to the explicit timeout; returns false instead of throwing when the element does not appear. */
    protected boolean isVisible(By locator) {
        try {
            waitForVisible(locator);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }
}
