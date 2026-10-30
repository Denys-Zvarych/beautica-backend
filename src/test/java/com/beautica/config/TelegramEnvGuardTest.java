package com.beautica.config;

import com.beautica.notification.telegram.AllowListTelegramMessenger;
import com.beautica.notification.telegram.CapturingTelegramMessenger;
import com.beautica.notification.telegram.NoOpTelegramMessenger;
import com.beautica.notification.telegram.TelegramBotApiClient;
import com.beautica.notification.telegram.TelegramMessenger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 432 (D2/D3, supersedes 404 D8) — staging runs its own bot, so enabled + live boots only with a non-empty tester
 * allow-list; capture and the flag off boot without one. The staging profile is activated through the environment,
 * exactly as Railway does ({@code SPRING_PROFILES_ACTIVE}).
 */
@DisplayName("TelegramEnvGuard — staging enabled + live requires the tester allow-list")
class TelegramEnvGuardTest {

    private static final String ALLOW_LIST = "app.telegram.staging-allowed-chat-ids=111,222";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TelegramConfigTest.TelegramDependencies.class, TelegramConfig.class,
                    TelegramEnvGuard.class);

    private final ApplicationContextRunner staging =
            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("staging"));

    @Test
    @DisplayName("staging + enabled + live + empty allow-list → context fails naming the variable")
    void should_failContext_when_stagingEnabledLiveWithoutAllowList() {
        staging.withPropertyValues(TelegramConfigTest.VALID_LIVE).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TELEGRAM_STAGING_ALLOWED_CHAT_IDS")
                    .message().doesNotContain(TelegramConfigTest.TOKEN);
        });
    }

    @Test
    @DisplayName("staging + enabled + mode omitted (defaults to live) + empty allow-list → context fails")
    void should_failContext_when_stagingEnabledModeOmittedWithoutAllowList() {
        String[] withoutMode = Arrays.stream(TelegramConfigTest.VALID_LIVE)
                .filter(p -> !p.startsWith("app.telegram.mode=")).toArray(String[]::new);

        staging.withPropertyValues(withoutMode).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("staging-allowed-chat-ids");
        });
    }

    @Test
    @DisplayName("profiles prod,staging (Railway staging) + enabled + live + empty allow-list → context fails")
    void should_failContext_when_prodAndStagingProfilesEnabledLiveWithoutAllowList() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", "staging"))
                .withPropertyValues(TelegramConfigTest.VALID_LIVE).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("staging-allowed-chat-ids");
                });
    }

    @Test
    @DisplayName("staging + enabled + live + allow-list → boots with the allow-list decorator")
    void should_boot_when_stagingEnabledLiveWithAllowList() {
        staging.withPropertyValues(TelegramConfigTest.VALID_LIVE).withPropertyValues(ALLOW_LIST).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TelegramEnvGuard.class);
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(AllowListTelegramMessenger.class);
        });
    }

    @Test
    @DisplayName("staging + enabled + capture with no allow-list → boots with the capturing messenger")
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
    @DisplayName("staging + flag off + live credentials + no allow-list (Railway staging today) → boots with NoOp")
    void should_boot_when_stagingFlagOffWithCredentialsWithoutAllowList() {
        staging.withPropertyValues(TelegramConfigTest.VALID_LIVE).withPropertyValues("app.telegram.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(NoOpTelegramMessenger.class);
                });
    }

    @Test
    @DisplayName("prod + enabled + live with no allow-list → guard absent, boots the bare live client")
    void should_bootLive_when_prodWithoutAllowList() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues(TelegramConfigTest.VALID_LIVE).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TelegramEnvGuard.class);
                    assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(TelegramBotApiClient.class);
                });
    }

    @Test
    @DisplayName("non-staging (no profile) + enabled + live → guard absent, boots live")
    void should_bootLive_when_notStaging() {
        runner.withPropertyValues(TelegramConfigTest.VALID_LIVE).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TelegramEnvGuard.class);
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(TelegramBotApiClient.class);
        });
    }
}
