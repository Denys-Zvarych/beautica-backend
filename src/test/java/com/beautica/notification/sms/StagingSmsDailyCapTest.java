package com.beautica.notification.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StagingSmsDailyCap — staging-only SMSClub send ceiling per Kyiv day (phase 439 audit-fix)")
class StagingSmsDailyCapTest {

    /** 2026-11-07 is EET (UTC+2): Kyiv midnight is 22:00Z the day before. */
    private static final Instant BEFORE_KYIV_MIDNIGHT = Instant.parse("2026-11-07T21:59:59Z");
    private static final Instant AFTER_KYIV_MIDNIGHT = Instant.parse("2026-11-07T22:00:00Z");

    @Test
    @DisplayName("the cap admits exactly N sends per Kyiv day and refuses the next one")
    void should_refuseTheNextSend_when_dailyCapIsUsedUp() {
        StagingSmsDailyCap cap = new StagingSmsDailyCap(new MutableClock(BEFORE_KYIV_MIDNIGHT), 2);

        boolean first = cap.tryAcquire();
        boolean second = cap.tryAcquire();
        boolean third = cap.tryAcquire();

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(third).isFalse();
    }

    @Test
    @DisplayName("the counter resets at Kyiv midnight, not UTC midnight")
    void should_resetCounter_when_kyivMidnightPasses() {
        MutableClock clock = new MutableClock(BEFORE_KYIV_MIDNIGHT);
        StagingSmsDailyCap cap = new StagingSmsDailyCap(clock, 1);
        cap.tryAcquire();
        boolean refusedBeforeMidnight = cap.tryAcquire();

        clock.now = AFTER_KYIV_MIDNIGHT;
        boolean afterMidnight = cap.tryAcquire();

        assertThat(refusedBeforeMidnight).isFalse();
        assertThat(afterMidnight).as("22:00Z is 00:00 in Kyiv, still the same UTC date").isTrue();
    }

    @ParameterizedTest(name = "cap = {0}")
    @NullSource
    @ValueSource(ints = {0, -1})
    @DisplayName("a null, zero or negative cap fails construction, naming the property")
    void should_failFast_when_capIsNotPositive(Integer bad) {
        Clock clock = Clock.fixed(BEFORE_KYIV_MIDNIGHT, ZoneOffset.UTC);

        assertThatThrownBy(() -> new StagingSmsDailyCap(clock, bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sms.smsclub.staging-daily-cap");
    }

    /** Test clock whose instant can be advanced; the zone is ignored by {@link StagingSmsDailyCap} (it re-zones). */
    private static final class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone;

        MutableClock(Instant now) {
            this(now, ZoneOffset.UTC);
        }

        private MutableClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new DelegatingZoneClock(this, newZone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Re-zoned view that still reads the parent's (mutable) instant. */
    private static final class DelegatingZoneClock extends Clock {
        private final MutableClock parent;
        private final ZoneId zone;

        DelegatingZoneClock(MutableClock parent, ZoneId zone) {
            this.parent = parent;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new DelegatingZoneClock(parent, newZone);
        }

        @Override
        public Instant instant() {
            return parent.instant();
        }
    }
}
