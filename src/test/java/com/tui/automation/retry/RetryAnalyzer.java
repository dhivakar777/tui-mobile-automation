package com.tui.automation.retry;

import com.tui.automation.config.ConfigManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.InvalidElementStateException;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.StaleElementReferenceException;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

import java.util.List;

/**
 * Retries a failed scenario only when the failure looks environmental: the session could not be created or was
 * lost, the device refused the input, or the UI recomposed mid-read. Assertion failures, timeouts and missing
 * elements are never retried, because those can be genuine application defects.
 */
public class RetryAnalyzer implements IRetryAnalyzer {

    private static final Logger LOG = LogManager.getLogger(RetryAnalyzer.class);
    private static final List<Class<? extends Throwable>> RETRYABLE = List.of(
            SessionNotCreatedException.class,
            NoSuchSessionException.class,
            InvalidElementStateException.class,
            StaleElementReferenceException.class);

    private int retries;

    @Override
    public boolean retry(ITestResult result) {
        int maxRetries = ConfigManager.getInt("retry.max.retries");
        Throwable failure = result.getThrowable();
        if (retries >= maxRetries || !isRetryable(failure)) {
            return false;
        }
        retries++;
        LOG.warn("Retrying scenario {} (retry {} of {}) after {}",
                scenarioName(result), retries, maxRetries, failure);
        return true;
    }

    private static boolean isRetryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AssertionError) {
                return false;
            }
            for (Class<? extends Throwable> retryable : RETRYABLE) {
                if (retryable.isInstance(cause)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String scenarioName(ITestResult result) {
        Object[] parameters = result.getParameters();
        return parameters.length > 0 ? String.valueOf(parameters[0]) : result.getName();
    }
}
