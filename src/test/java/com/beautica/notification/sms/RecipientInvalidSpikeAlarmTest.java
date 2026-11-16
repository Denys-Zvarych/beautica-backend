package com.beautica.notification.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 441 audit-fix — {@link RecipientInvalidSpikeAlarm} contract beyond what SmsClubServiceTest drives end to end. */
@DisplayName("RecipientInvalidSpikeAlarm — one aggregate signal per window")
class RecipientInvalidSpikeAlarmTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC);
    private static final Duration WINDOW = Duration.ofMinutes(10);

    @Test
    @DisplayName("a non-positive threshold or window fails fast at construction")
    void should_throw_when_thresholdOrWindowIsNotPositive() {
        assertThatThrownBy(() -> new RecipientInvalidSpikeAlarm(FIXED, 0, WINDOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("threshold");
        assertThatThrownBy(() -> new RecipientInvalidSpikeAlarm(FIXED, null, WINDOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("threshold");
        assertThatThrownBy(() -> new RecipientInvalidSpikeAlarm(FIXED, 5, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("window");
        assertThatThrownBy(() -> new RecipientInvalidSpikeAlarm(FIXED, 5, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("window");
    }

    @Test
    @Timeout(10)
    @DisplayName("concurrent rejections in one window report the threshold crossing exactly once")
    void should_reportExactlyOnce_when_threadsRecordConcurrently() throws Exception {
        int threads = 8;
        int perThread = 10_000;
        // Threshold = every record: a lost update (unsynchronized count++) leaves the count short and it never fires.
        int threshold = threads * perThread;
        RecipientInvalidSpikeAlarm alarm = new RecipientInvalidSpikeAlarm(FIXED, threshold, WINDOW);
        AtomicInteger fired = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int t = 0; t < threads; t++) {
                futures[t] = pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (alarm.recordAndCheck()) {
                            fired.incrementAndGet();
                        }
                    }
                    return null;
                });
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(fired).hasValue(1);
    }
}
