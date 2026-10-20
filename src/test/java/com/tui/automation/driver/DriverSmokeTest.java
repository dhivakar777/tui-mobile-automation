package com.tui.automation.driver;

import com.tui.automation.config.ConfigManager;
import io.appium.java_client.AppiumBy;
import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;

import static org.testng.Assert.assertEquals;

/**
 * Temporary Phase 3 check that a session starts, the app launches on the login screen and teardown runs.
 * Replaced by a Cucumber @smoke scenario using page objects in Phase 5.
 */
public class DriverSmokeTest {

    @BeforeMethod
    public void startSession() {
        DriverManager.startDriver();
    }

    @Test
    public void appLaunchesOnLoginScreen() {
        AndroidDriver driver = (AndroidDriver) DriverManager.getDriver();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(ConfigManager.getInt("timeout.explicit.wait.seconds")));

        // Compose test tags are exposed as resource-ids without a package prefix, which AppiumBy.id cannot match.
        WebElement topBar = wait.until(ExpectedConditions.visibilityOfElementLocated(
                AppiumBy.androidUIAutomator("new UiSelector().resourceId(\"login_screen_top_app_bar\")")));

        assertEquals(driver.getCurrentPackage(), ConfigManager.get("app.package"), "Foreground app package");
        assertEquals(topBar.findElement(AppiumBy.className("android.widget.TextView")).getText(), "Log in", "Screen title");
    }

    @AfterMethod(alwaysRun = true)
    public void endSession() {
        DriverManager.quitDriver();
    }
}
