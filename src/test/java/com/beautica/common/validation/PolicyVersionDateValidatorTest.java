package com.beautica.common.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 381 audit #8 — the policy-version date must be a real date, not after "today" in Europe/Kyiv. */
@DisplayName("PolicyVersionDateValidator — real date, not in the future (Kyiv)")
class PolicyVersionDateValidatorTest {

    /** 2026-10-20 22:30 UTC == 2026-10-21 01:30 in Kyiv (UTC+3 in October). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T22:30:00Z"), ZoneOffset.UTC);

    private PolicyVersionDateValidator validator() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("clock", CLOCK);
        return new PolicyVersionDateValidator(beans.getBeanProvider(Clock.class));
    }

    @ParameterizedTest(name = "[{0}] is valid")
    @ValueSource(strings = {"2026-10-20", "2026-10-21", "2026-10-21.7", "2020-02-29", "2026-10-20.123"})
    @DisplayName("should_accept_when_dateIsRealAndNotAfterKyivToday")
    void should_accept_when_dateIsRealAndNotAfterKyivToday(String value) {
        assertThat(validator().isValid(value, null)).isTrue();
    }

    @ParameterizedTest(name = "[{0}] is invalid")
    @ValueSource(strings = {"9999-99-99", "2026-13-01", "2026-02-30", "2021-02-29", "2026-00-10", "2026-10-22",
            "2026-10-22.1", "2999-01-01"})
    @DisplayName("should_reject_when_dateIsImpossibleOrInTheFuture")
    void should_reject_when_dateIsImpossibleOrInTheFuture(String value) {
        assertThat(validator().isValid(value, null)).isFalse();
    }

    @Test
    @DisplayName("should_leaveNullAndShortValuesToOtherConstraints")
    void should_leaveNullAndShortValuesToOtherConstraints() {
        assertThat(validator().isValid(null, null)).isTrue();
        assertThat(validator().isValid("v1", null)).isTrue();
    }

    @Test
    @DisplayName("should_useKyivDate_when_utcDateIsStillYesterday")
    void should_useKyivDate_when_utcDateIsStillYesterday() {
        // UTC says 2026-10-20, Kyiv already says 2026-10-21: the 21st is "today", not the future.
        assertThat(validator().isValid("2026-10-21", null)).isTrue();
        assertThat(validator().isValid("2026-10-22", null)).isFalse();
    }
}
