package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {ClockOverrideTest.FixedClockConfig.class, ClockConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("ClockConfig — @ConditionalOnMissingBean is suppressed when test Clock bean is registered as 'systemClock'")
class ClockOverrideTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-05-10T09:00:00Z");

    @Autowired
    @Qualifier("systemClock")
    private Clock clock;

    @Test
    @DisplayName("Injected Clock returns the fixed instant when overridden")
    void should_returnFixedInstant_when_clockIsOverriddenWithFixedClock() {
        var now = clock.instant();

        assertThat(now).isEqualTo(FIXED_INSTANT);
    }

    @Test
    @DisplayName("Fixed Clock operates in UTC zone")
    void should_useUtcZone_when_clockIsFixed() {
        var zone = clock.getZone();

        assertThat(zone).isEqualTo(ZoneOffset.UTC);
    }

    @TestConfiguration
    static class FixedClockConfig {

        // Named "systemClock" to trigger @ConditionalOnMissingBean in ClockConfig.systemClock(),
        // ensuring the production bean is suppressed when a test Clock bean is present.
        @Bean
        Clock systemClock() {
            return Clock.fixed(Instant.parse("2026-05-10T09:00:00Z"), ZoneOffset.UTC);
        }
    }
}
