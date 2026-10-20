package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot-time length guard on {@code app.booking-info.worker-key} (website-Worker shared secret): blank stays
 * a valid "feature off", a non-blank key needs at least 32 characters, and the failure never echoes the key.
 * Config-only, no DB.
 */
@DisplayName("app.booking-info.worker-key — boot-time length guard")
class BookingInfoWorkerPropertiesTest {

    private static final String KEY_31 = "k".repeat(31);
    private static final String KEY_32 = "k".repeat(32);

    @Configuration
    @EnableConfigurationProperties(BookingInfoWorkerProperties.class)
    static class Props {
    }

    private static ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
                .withUserConfiguration(Props.class)
                .withPropertyValues(properties);
    }

    @Test
    @DisplayName("non-blank key shorter than 32 chars → boot fails with a clear message that does not print the key")
    void should_failBoot_when_workerKeyShorterThan32() {
        runner("app.booking-info.worker-key=" + KEY_31).run(ctx -> {
            assertThat(ctx).hasFailed();
            String report = rootCauseChainText(ctx.getStartupFailure());
            assertThat(report).contains("at least 32 characters");
            assertThat(report).doesNotContain(KEY_31);
        });
    }

    @Test
    @DisplayName("31 chars padded with whitespace to 33 → still too short (surrounding whitespace ignored)")
    void should_failBoot_when_workerKeyOnlyReaches32WithWhitespace() {
        runner("app.booking-info.worker-key= " + KEY_31 + " ")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("blank key → boots, feature off")
    void should_boot_when_workerKeyBlank() {
        runner("app.booking-info.worker-key=").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(BookingInfoWorkerProperties.class).isEnabled()).isFalse();
        });
    }

    @Test
    @DisplayName("unset key → boots, feature off")
    void should_boot_when_workerKeyUnset() {
        runner().run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("exactly 32 chars → boots, feature on")
    void should_boot_when_workerKeyIs32Chars() {
        runner("app.booking-info.worker-key=" + KEY_32).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(BookingInfoWorkerProperties.class).isEnabled()).isTrue();
        });
    }

    @Test
    @DisplayName("a 44-char openssl rand -base64 32 value → boots")
    void should_boot_when_workerKeyIsBase64Of32Bytes() {
        runner("app.booking-info.worker-key=q7Lm1yq0b2Q9cJ4p8e3uVt6WkRZsXo5nHdA1fGiB0mE=")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    private static String rootCauseChainText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            text.append(t).append('\n');
        }
        return text.toString();
    }
}
