package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 435 audit-fix cycle 2 — {@code app.booking.terminal-view-days} is bound to 1..365; boot fails outside it.
 * Config-only, no DB.
 */
@DisplayName("app.booking.terminal-view-days — @Validated bounds 1..365")
class BookingSmsPropertiesTest {

    private static final String KEY = "app.booking.terminal-view-days=";

    @Configuration
    @EnableConfigurationProperties(BookingSmsProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(Props.class);

    @ParameterizedTest
    @ValueSource(ints = {1, 30, 365})
    @DisplayName("a value inside 1..365 binds")
    void should_bind_when_terminalViewDaysInRange(int days) {
        runner.withPropertyValues(KEY + days)
                .run(ctx -> assertThat(ctx.getBean(BookingSmsProperties.class).getTerminalViewDays()).isEqualTo(days));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 366, 100_000})
    @DisplayName("a value outside 1..365 fails boot")
    void should_failBoot_when_terminalViewDaysOutOfRange(int days) {
        runner.withPropertyValues(KEY + days)
                .run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("terminalViewDays"));
    }

    @Test
    @DisplayName("the default (30) binds when the key is unset")
    void should_useDefault_when_terminalViewDaysUnset() {
        runner.run(ctx -> assertThat(ctx.getBean(BookingSmsProperties.class).getTerminalViewDays()).isEqualTo(30));
    }
}
