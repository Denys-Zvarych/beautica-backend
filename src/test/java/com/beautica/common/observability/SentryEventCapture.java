package com.beautica.common.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.sentry.NoOpTransportFactory;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.logback.SentryAppender;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test support (phase 441 audit-fix): the production Sentry path for a logback logger, end to end — the real
 * {@link SentryAppender} (MDC to context tags), the production {@link SentryOptionsConfig} options, and the production
 * {@link SentryPiiScrubber} + {@link ExternalCallSentryThrottle} as {@code beforeSend} with the default budget. Events the
 * callback lets through are collected instead of being sent (no-op transport, no network).
 *
 * <p>Initialises the global Sentry SDK: always {@link #close()} it ({@code try}-with-resources or {@code @AfterEach}).
 *
 * <p>Isolation: a Spring context with Sentry enabled (e.g. {@code SentryCaptureIT}) leaves its own {@link SentryAppender}
 * on the root logger after the context closes. Once this capture re-initialises the global SDK, that leftover appender
 * would forward every ERROR line a second time through the same {@code beforeSend}. Every foreign {@link SentryAppender}
 * in the logger context is therefore detached for the capture's lifetime and re-attached on {@link #close()}.
 */
public final class SentryEventCapture implements AutoCloseable {

    public static final ExternalCallSentryLimitProperties DEFAULT_LIMITS =
            new ExternalCallSentryLimitProperties(5, Duration.ofMinutes(10), 256, Duration.ofHours(24));
    private static final String TEST_DSN = "https://public@sentry.invalid/1";

    private final List<SentryEvent> forwarded = new CopyOnWriteArrayList<>();
    private final Logger logger;
    private final SentryAppender appender = new SentryAppender();
    private final List<Detached> detached = new ArrayList<>();

    private record Detached(Logger owner, Appender<ILoggingEvent> appender) {
    }

    private SentryEventCapture(Logger logger) {
        this.logger = logger;
        SentryPiiScrubber scrubber =
                new SentryPiiScrubber(new ExternalCallSentryThrottle(Clock.systemUTC(), DEFAULT_LIMITS));
        SentryOptions options = new SentryOptions();
        options.setDsn(TEST_DSN);
        new SentryOptionsConfig().beauticaSentryOptions().configure(options);
        options.setTransportFactory(NoOpTransportFactory.getInstance());
        options.setEnableUncaughtExceptionHandler(false);
        options.setBeforeSend((event, hint) -> {
            SentryEvent kept = scrubber.execute(event, hint);
            if (kept != null) {
                forwarded.add(kept);
            }
            return kept;
        });
        detachForeignSentryAppenders();
        appender.setOptions(options);
        appender.setMinimumEventLevel(Level.ERROR);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
    }

    /** Attaches the Sentry path to {@code logger}. */
    public static SentryEventCapture attach(Logger logger) {
        return new SentryEventCapture(logger);
    }

    /** Events that passed {@code beforeSend} — what would reach Sentry. */
    public List<SentryEvent> forwarded() {
        return List.copyOf(forwarded);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        Sentry.close();
        detached.forEach(d -> d.owner().addAppender(d.appender()));
        detached.clear();
    }

    private void detachForeignSentryAppenders() {
        for (Logger candidate : logger.getLoggerContext().getLoggerList()) {
            for (Iterator<Appender<ILoggingEvent>> it = candidate.iteratorForAppenders(); it.hasNext(); ) {
                Appender<ILoggingEvent> existing = it.next();
                if (existing instanceof SentryAppender) {
                    detached.add(new Detached(candidate, existing));
                }
            }
        }
        detached.forEach(d -> d.owner().detachAppender(d.appender()));
    }
}
