package com.tui.automation.runners;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;
import org.testng.annotations.DataProvider;

/**
 * Runs all feature files through TestNG. Select scenarios with a tag expression, e.g.
 * mvn test -Dcucumber.filter.tags="@smoke"
 *
 * <p>Scenarios may run concurrently; how many at once is set by {@code parallel.threads} in pom.xml (default 1, so
 * sequential). Use more than one thread only together with {@code device.udids}, one device per thread.
 */
@CucumberOptions(
        features = "classpath:features",
        glue = {"com.tui.automation.stepdefinitions", "com.tui.automation.hooks"},
        plugin = {"pretty", "html:target/cucumber-reports/cucumber.html"})
public class TestRunner extends AbstractTestNGCucumberTests {

    @Override
    @DataProvider(parallel = true)
    public Object[][] scenarios() {
        return super.scenarios();
    }
}
