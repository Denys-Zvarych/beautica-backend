package com.beautica.config;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClockConfig — Clock bean registration")
class ClockConfigTest extends AbstractIntegrationTest {

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("Clock bean is present in the application context")
    void should_registerClockBean_when_contextLoads() {
        assertThat(clock).isNotNull();
        assertThat(clock).isInstanceOf(Clock.class);
    }

    @Test
    @DisplayName("Default Clock bean uses UTC zone")
    void should_useUtcZone_when_noOverrideIsPresent() {
        var zone = clock.getZone();

        assertThat(zone).isEqualTo(ZoneOffset.UTC);
    }
}
