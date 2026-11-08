package com.beautica.common.observability;

import io.sentry.SentryEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 441 audit-fix — fingerprint and per-(service, reason) budget of {@link ExternalCallSentryThrottle}. */
@DisplayName("ExternalCallSentryThrottle — fingerprint + per-(service, reason) Sentry budget")
class ExternalCallSentryThrottleTest {

    private static final Instant T0 = Instant.parse("2026-11-08T10:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final ExternalCallSentryThrottle throttle =
            new ExternalCallSentryThrottle(clock, SentryEventCapture.DEFAULT_LIMITS);

    private static SentryEvent externalEvent(String service, String reason) {
        SentryEvent event = new SentryEvent();
        event.setTag(ExternalCallFailure.TAG_SERVICE, service);
        event.setTag(ExternalCallFailure.TAG_REASON, reason);
        return event;
    }

    private int forwardedOf(int attempts, String service, String reason) {
        int forwarded = 0;
        for (int i = 0; i < attempts; i++) {
            if (throttle.apply(externalEvent(service, reason)) != null) {
                forwarded++;
            }
        }
        return forwarded;
    }

    @Test
    @DisplayName("an external-call event is fingerprinted [external-call, service, reason]")
    void should_setFingerprint_when_eventCarriesServiceAndReason() {
        SentryEvent event = externalEvent("fcm", "unregistered");

        SentryEvent result = throttle.apply(event);

        assertThat(result).isSameAs(event);
        assertThat(result.getFingerprints()).containsExactly("external-call", "fcm", "unregistered");
    }

    @Test
    @DisplayName("the first 5 events of a key in a window pass, the rest are dropped")
    void should_forwardOnlyFive_when_twentyIdenticalFailuresInOneWindow() {
        int forwarded = forwardedOf(20, "fcm", "unavailable");

        assertThat(forwarded).isEqualTo(5);
    }

    @Test
    @DisplayName("each distinct (service, reason) has its own budget — a flood of one never hides another")
    void should_keepSeparateBudgets_when_keysDiffer() {
        forwardedOf(50, "smsclub", "phone_invalid");

        assertThat(forwardedOf(1, "smsclub", "auth")).isEqualTo(1);
        assertThat(forwardedOf(1, "fcm", "phone_invalid")).isEqualTo(1);
    }

    @Test
    @DisplayName("a new window restores the budget")
    void should_forwardAgain_when_windowElapsed() {
        forwardedOf(10, "turnstile", "invalid_input_response");

        clock.advance(Duration.ofMinutes(10));

        assertThat(forwardedOf(10, "turnstile", "invalid_input_response")).isEqualTo(5);
    }

    @Test
    @DisplayName("a non-external event is never fingerprinted nor dropped, however many arrive")
    void should_leaveEventUntouched_when_noExternalCallTags() {
        List<SentryEvent> results = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            SentryEvent event = new SentryEvent();
            event.setTag(ExternalCallFailure.TAG_SERVICE, "fcm");
            results.add(throttle.apply(event));
        }

        assertThat(results).doesNotContainNull().allSatisfy(e -> assertThat(e.getFingerprints()).isNull());
        assertThat(throttle.trackedKeys()).isZero();
    }

    @Test
    @DisplayName("tracked keys are capped (LRU eviction), so memory stays bounded")
    void should_capTrackedKeys_when_manyDistinctReasons() {
        ExternalCallSentryThrottle small = new ExternalCallSentryThrottle(clock,
                new ExternalCallSentryLimitProperties(5, Duration.ofMinutes(10), 3, Duration.ofHours(24)));

        for (int i = 0; i < 50; i++) {
            small.apply(externalEvent("brevo-api", "reason_" + i));
        }

        assertThat(small.trackedKeys()).isEqualTo(3);
    }

    @Test
    @DisplayName("concurrent callers on one key never exceed the budget")
    void should_forwardExactlyFive_when_manyThreadsRaceOnOneKey() throws Exception {
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger forwarded = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    if (throttle.apply(externalEvent("fcm", "quota_exceeded")) != null) {
                        forwarded.incrementAndGet();
                    }
                }
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        tasks.forEach(task -> futures.add(pool.submit(task)));
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(forwarded.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("a saturated window doubles the next window's length, capped at max-window")
    void should_doubleWindowUpToCap_when_eachWindowSaturates() {
        ExternalCallSentryThrottle capped = new ExternalCallSentryThrottle(clock,
                new ExternalCallSentryLimitProperties(5, Duration.ofMinutes(10), 256, Duration.ofMinutes(40)));
        List<Long> lengths = new ArrayList<>();

        for (int round = 0; round < 4; round++) {
            long length = saturateAndElapse(capped);
            lengths.add(length);
        }

        assertThat(lengths).containsExactly(
                Duration.ofMinutes(10).toMillis(), Duration.ofMinutes(20).toMillis(),
                Duration.ofMinutes(40).toMillis(), Duration.ofMinutes(40).toMillis());
    }

    @Test
    @DisplayName("a window without drops resets the next window to the base length")
    void should_resetToBaseWindow_when_windowEndsWithoutDrops() {
        saturateAndElapse(throttle);
        throttle.apply(externalEvent("fcm", "unavailable"));
        long backedOff = throttle.windowMillisOf("fcm", "unavailable");

        clock.advance(Duration.ofMillis(backedOff));
        throttle.apply(externalEvent("fcm", "unavailable"));

        assertThat(backedOff).isEqualTo(Duration.ofMinutes(20).toMillis());
        assertThat(throttle.windowMillisOf("fcm", "unavailable")).isEqualTo(Duration.ofMinutes(10).toMillis());
    }

    @Test
    @DisplayName("a gap long enough to hold the doubled window empty also resets to the base length")
    void should_resetToBaseWindow_when_idleGapCoversDoubledWindow() {
        saturateAndElapse(throttle);

        clock.advance(Duration.ofMinutes(20));
        throttle.apply(externalEvent("fcm", "unavailable"));

        assertThat(throttle.windowMillisOf("fcm", "unavailable")).isEqualTo(Duration.ofMinutes(10).toMillis());
    }

    @Test
    @DisplayName("30 days of non-stop failures on one key forward under 400 events, the first 5 at once")
    void should_forwardUnder400Events_when_oneKeyFailsNonStopFor30Days() {
        Duration step = Duration.ofSeconds(30);
        long steps = Duration.ofDays(30).dividedBy(step);
        long stepsPerDay = Duration.ofDays(1).dividedBy(step);
        int forwarded = 0;
        int forwardedOnLastDay = 0;
        int forwardedInFirstFive = 0;

        for (long i = 0; i < steps; i++) {
            boolean passed = throttle.apply(externalEvent("smsclub", "auth")) != null;
            if (passed) {
                forwarded++;
                forwardedOnLastDay += i >= steps - stepsPerDay ? 1 : 0;
                forwardedInFirstFive += i < 5 ? 1 : 0;
            }
            clock.advance(step);
        }

        assertThat(forwardedInFirstFive).isEqualTo(5);
        assertThat(forwarded).isLessThan(400);
        assertThat(forwardedOnLastDay).isBetween(1, 10);
    }

    @Test
    @DisplayName("the next forwarded event carries ext_call_suppressed=<dropped count>, then the count restarts")
    void should_tagSuppressedCount_when_eventPassesAfterDrops() {
        SentryEvent first = throttle.apply(externalEvent("brevo-api", "unauthorized"));
        forwardedOf(11, "brevo-api", "unauthorized");
        clock.advance(Duration.ofMinutes(10));

        SentryEvent afterDrops = throttle.apply(externalEvent("brevo-api", "unauthorized"));
        SentryEvent following = throttle.apply(externalEvent("brevo-api", "unauthorized"));

        assertThat(first.getTag(ExternalCallSentryThrottle.TAG_SUPPRESSED)).isNull();
        assertThat(afterDrops.getTag(ExternalCallSentryThrottle.TAG_SUPPRESSED)).isEqualTo("7");
        assertThat(following.getTag(ExternalCallSentryThrottle.TAG_SUPPRESSED)).isNull();
    }

    /** Saturates the fcm/unavailable key's current window, moves the clock to its end and returns its length. */
    private long saturateAndElapse(ExternalCallSentryThrottle target) {
        for (int i = 0; i < 6; i++) {
            target.apply(externalEvent("fcm", "unavailable"));
        }
        long length = target.windowMillisOf("fcm", "unavailable");
        clock.advance(Duration.ofMillis(length));
        return length;
    }

    /** A test clock whose instant moves only when told to. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
