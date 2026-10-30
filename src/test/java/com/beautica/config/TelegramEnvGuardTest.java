package com.beautica.config;

import com.beautica.notification.telegram.CapturingTelegramMessenger;
import com.beautica.notification.telegram.NoOpTelegramMessenger;
import com.beautica.notification.telegram.TelegramBotApiClient;
import com.beautica.notification.telegram.TelegramMessenger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 404 (D8) — the only bot is prod's, so staging must never boot with Telegram enabled in live mode.
 * The staging profile is activated through the environment, exactly as Railway does ({@code SPRING_PROFILES_ACTIVE}).
 */
@DisplayName("TelegramEnvGuard — staging refuses enabled + live")
class TelegramEnvGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TelegramConfigTest.TelegramDependencies.class, TelegramConfig.class,
                    TelegramEnvGuard.class);

    private final ApplicationContextRunner staging =
            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("staging"));

    @Test
    @DisplayName("staging + enabled + live → context fails")
    void should_failContext_when_stagingEnabledLive() {
        staging.withPropertyValues(TelegramConfigTest.VALID_LIVE).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("staging profile");
        });
    }

    @Test
    @DisplayName("staging + enabled + mode omitted (defaults to live) → context fails")
    void should_failContext_when_stagingEnabledModeOmitted() {
        String[] withoutMode = java.util.Arrays.stream(TelegramConfigTest.VALID_LIVE)
                .filter(p -> !p.startsWith("app.telegram.mode=")).toArray(String[]::new);

        staging.withPropertyValues(withoutMode).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("staging profile");
        });
    }

    @Test
    @DisplayName("profiles prod,staging (Railway staging) + enabled + live → context fails")
    void should_failContext_when_prodAndStagingProfilesEnabledLive() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", "staging"))
                .withPropertyValues(TelegramConfigTest.VALID_LIVE).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("staging profile");
                });
    }

    @Test
    @DisplayName("staging + enabled + capture → boots with the capturing messenger")
    void should_boot_when_stagingCapture() {
        staging.withPropertyValues("app.telegram.enabled=true", "app.telegram.mode=capture").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TelegramEnvGuard.class);
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(CapturingTelegramMessenger.class);
        });
    }

    @Test
    @DisplayName("staging + flag off → boots with NoOp")
    void should_boot_when_stagingFlagOff() {
        staging.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(NoOpTelegramMessenger.class);
        });
    }

    @Test
    @DisplayName("non-staging + enabled + live → guard absent, boots live")
    void should_bootLive_when_notStaging() {
        runner.withPropertyValues(TelegramConfigTest.VALID_LIVE).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TelegramEnvGuard.class);
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(TelegramBotApiClient.class);
        });
    }
}
