package com.tui.automation.retry;

import org.testng.IAnnotationTransformer;
import org.testng.annotations.ITestAnnotation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Attaches {@link RetryAnalyzer} to Cucumber's {@code runScenario} test method, which executes every scenario.
 * Registered as a TestNG listener by the Surefire configuration in pom.xml; annotation transformers cannot be
 * added through {@code @Listeners}.
 */
public class RetryTransformer implements IAnnotationTransformer {

    @Override
    public void transform(ITestAnnotation annotation, Class testClass, Constructor testConstructor, Method testMethod) {
        if (testMethod != null && "runScenario".equals(testMethod.getName())) {
            annotation.setRetryAnalyzer(RetryAnalyzer.class);
        }
    }
}
