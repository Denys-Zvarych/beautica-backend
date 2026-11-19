package com.beautica.auth.appcheck;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Phone-first hardening B9 — accepted {@code jti}s are remembered until the token's {@code exp}, then forgotten. */
@DisplayName("AppCheckReplayStore — jti memory bounded by the token lifetime (AUTH-D27 d)")
class AppCheckReplayStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    /** A clock the test moves forward; drives the Caffeine ticker too. */
    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

        void advanceSeconds(long seconds) {
            now.updateAndGet(i -> i.plusSeconds(seconds));
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
            return now.get();
        }
    }

    @Test
    @DisplayName("should_reportReplay_when_jtiSeenBeforeExp")
    void should_reportReplay_when_jtiSeenBeforeExp() {
        AppCheckReplayStore store = new AppCheckReplayStore(new MovableClock());

        boolean first = store.markFirstUse("jti-1", NOW.plusSeconds(300));
        boolean second = store.markFirstUse("jti-1", NOW.plusSeconds(300));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    @DisplayName("should_forgetJti_when_tokenExpired")
    void should_forgetJti_when_tokenExpired() {
        MovableClock clock = new MovableClock();
        AppCheckReplayStore store = new AppCheckReplayStore(clock);
        store.markFirstUse("jti-1", NOW.plusSeconds(300));

        clock.advanceSeconds(301);
        boolean afterExp = store.markFirstUse("jti-1", NOW.plusSeconds(600));

        assertThat(afterExp).isTrue();
    }
}
