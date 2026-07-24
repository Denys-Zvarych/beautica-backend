package com.beautica.support;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** JUnit Platform listener emitting suite + top-level class banners via SLF4J. */
public final class TestSuiteLoggerListener implements TestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(TestSuiteLoggerListener.class);

    private static final String BANNER =
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";
    private static final String SUITE_NAME = "Beautica backend test plan";

    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicLong startNanos = new AtomicLong();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        startNanos.set(System.nanoTime());
        passed.set(0);
        failed.set(0);
        skipped.set(0);
        log.info("\n\n{}", BANNER);
        log.info("Suite starting: {}", SUITE_NAME);
        log.info("{}", BANNER);
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        if (isTopLevelTestClass(id)) {
            id.getSource()
                    .filter(ClassSource.class::isInstance)
                    .map(ClassSource.class::cast)
                    .ifPresent(source -> log.info("\n\n▶ CLASS {}", simpleNameOf(source)));
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (id.isContainer()) {
            return;
        }
        switch (result.getStatus()) {
            case SUCCESSFUL -> passed.incrementAndGet();
            case FAILED, ABORTED -> failed.incrementAndGet();
        }
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        if (!id.isContainer()) {
            skipped.incrementAndGet();
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos.get());
        log.info("\n\n{}", BANNER);
        log.info(
                "Suite finished: {} | Passed: {} | Failed: {} | Skipped: {} | Elapsed: {}ms",
                SUITE_NAME,
                passed.get(),
                failed.get(),
                skipped.get(),
                elapsedMs);
        log.info("{}", BANNER);
    }

    /**
     * A top-level test class is a container whose source is a {@link ClassSource} — filters
     * out the root engine container and nested test classes (whose source resolves differently).
     */
    private static boolean isTopLevelTestClass(TestIdentifier id) {
        return id.isContainer()
                && id.getSource().filter(ClassSource.class::isInstance).isPresent();
    }

    private static String simpleNameOf(ClassSource source) {
        String className = source.getClassName();
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? className : className.substring(lastDot + 1);
    }
}
