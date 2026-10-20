package com.tui.automation.hooks;

import com.tui.automation.driver.DriverManager;
import io.cucumber.java.After;
import io.cucumber.java.Before;

/** The single owner of the driver lifecycle: one fresh session per scenario. */
public class Hooks {

    @Before
    public void startDriver() {
        DriverManager.startDriver();
    }

    @After
    public void quitDriver() {
        DriverManager.quitDriver();
    }
}
