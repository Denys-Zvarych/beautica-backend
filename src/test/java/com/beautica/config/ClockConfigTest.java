package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClockConfig — Clock bean registration")
@SpringBootTest(
        classes = ClockConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ClockConfigTest {

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("Clock bean is present in the application context")
    void should_registerClockBean_when_contextLoads() {
        assertThat(clock).isNotNull();
    }

    @Test
    @DisplayName("Default Clock bean uses UTC zone")
    void should_useUtcZone_when_noOverrideIsPresent() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
