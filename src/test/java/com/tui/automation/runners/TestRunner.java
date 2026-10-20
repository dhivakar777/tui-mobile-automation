package com.tui.automation.runners;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;

/**
 * Runs all feature files through TestNG. Select scenarios with a tag expression, e.g.
 * mvn test -Dcucumber.filter.tags="@smoke"
 */
@CucumberOptions(
        features = "classpath:features",
        glue = {"com.tui.automation.stepdefinitions", "com.tui.automation.hooks"},
        plugin = {"pretty", "html:target/cucumber-reports/cucumber.html"})
public class TestRunner extends AbstractTestNGCucumberTests {
}
