package com.beautica.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Auto-registered JUnit 5 extension that emits uniform per-test intent and outcome log lines.
 *
 * <p>Replaces hand-written {@code log.info("▶ TEST: ...")} boilerplate at the top of every test
 * method with a single central point of emission. Outcome lines ({@code ✔ PASS}, {@code ✖ FAIL},
 * {@code ⊘ ABORTED}, {@code ⇢ SKIPPED}) are produced by {@link TestWatcher} callbacks.
 *
 * <p>Lifecycle order (per test method):
 * <ol>
 *   <li>{@link #beforeTestExecution(ExtensionContext)} logs {@code ▶ TEST: <display name>} at INFO
 *       and stashes start nanos in the per-test {@link Store}.</li>
 *   <li>{@link #afterTestExecution(ExtensionContext)} reads start nanos and stashes elapsed ms.</li>
 *   <li>A {@link TestWatcher} callback ({@link #testSuccessful}, {@link #testFailed},
 *       {@link #testAborted}, {@link #testDisabled}) logs the outcome line using the stashed
 *       duration.</li>
 * </ol>
 *
 * <p>The logger is keyed to the test class so messages appear under the test's own package,
 * matching the existing {@code com.beautica} logger level configured in {@code logback-test.xml}.
 */
public final class TestIntentLoggerExtension
        implements BeforeTestExecutionCallback, AfterTestExecutionCallback, TestWatcher {

    private static final Namespace NAMESPACE =
            Namespace.create(TestIntentLoggerExtension.class);
    private static final String KEY_START_NANOS = "startNanos";
    private static final String KEY_ELAPSED_MS = "elapsedMs";

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        Store store = context.getStore(NAMESPACE);
        store.put(KEY_START_NANOS, System.nanoTime());
        loggerFor(context).info("▶ TEST: {}", resolveDisplayName(context));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        Store store = context.getStore(NAMESPACE);
        Long startNanos = store.remove(KEY_START_NANOS, Long.class);
        long elapsedMs = (startNanos == null)
                ? 0L
                : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        store.put(KEY_ELAPSED_MS, elapsedMs);
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        loggerFor(context).info("✔ PASS ({}ms)", elapsedMs(context));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        String reason = (cause == null) ? "unknown" : cause.toString();
        loggerFor(context).error("✖ FAIL ({}ms): {}", elapsedMs(context), reason);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        String reason = (cause == null) ? "unknown" : cause.toString();
        loggerFor(context).warn("⊘ ABORTED ({}ms): {}", elapsedMs(context), reason);
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        String why = reason.orElse("no reason provided");
        loggerFor(context).info("⇢ SKIPPED: {}", why);
    }

    private static Logger loggerFor(ExtensionContext context) {
        return LoggerFactory.getLogger(context.getRequiredTestClass());
    }

    private static long elapsedMs(ExtensionContext context) {
        Long stashed = context.getStore(NAMESPACE).get(KEY_ELAPSED_MS, Long.class);
        return stashed == null ? 0L : stashed;
    }

    /**
     * Resolves the label printed next to {@code ▶ TEST:} — preferring an explicit
     * {@link DisplayName} on the method, then on the class, then a humanised form of
     * the method name (underscores replaced, camelCase split on boundaries).
     */
    private static String resolveDisplayName(ExtensionContext context) {
        Optional<Method> testMethod = context.getTestMethod();
        if (testMethod.isPresent()) {
            DisplayName methodAnnotation = testMethod.get().getAnnotation(DisplayName.class);
            if (methodAnnotation != null && !methodAnnotation.value().isBlank()) {
                return methodAnnotation.value();
            }
        }
        String displayName = context.getDisplayName();
        String methodName = testMethod.map(Method::getName).orElse(null);
        if (methodName != null && !isGeneratedFromMethodName(displayName, methodName)) {
            return displayName;
        }
        if (methodName != null) {
            return humaniseMethodName(methodName);
        }
        return displayName;
    }

    /**
     * JUnit's default display name for a method is {@code methodName()} — treat that as a
     * signal that no meaningful label was declared and a humanised form will read better.
     */
    private static boolean isGeneratedFromMethodName(String displayName, String methodName) {
        return displayName != null && displayName.equals(methodName + "()");
    }

    private static String humaniseMethodName(String methodName) {
        String underscoresToSpaces = methodName.replace('_', ' ');
        String camelCaseSplit = underscoresToSpaces.replaceAll(
                "([a-z0-9])([A-Z])", "$1 $2");
        return camelCaseSplit.trim();
    }
}
