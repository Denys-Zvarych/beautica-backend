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
        classes = {ClockConfig.class, ClockOverrideTest.FixedClockConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("ClockConfig — Clock bean can be overridden via @TestConfiguration")
class ClockOverrideTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-05-10T09:00:00Z");

    @Autowired
    @Qualifier("overriddenClock")
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

        @Bean
        Clock overriddenClock() {
            return Clock.fixed(Instant.parse("2026-05-10T09:00:00Z"), ZoneOffset.UTC);
        }
    }
}
