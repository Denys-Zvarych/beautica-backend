package com.beautica.config;

import com.beautica.support.TestSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import com.beautica.notification.telegram.AllowListTelegramMessenger;
import com.beautica.notification.telegram.NoOpTelegramMessenger;
import com.beautica.notification.telegram.StagingTelegramChatAllowList;
import com.beautica.notification.telegram.TelegramChatAllowList;
import com.beautica.notification.telegram.TelegramMessenger;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Phase 368 audit F1: the fingerprint guard must abort BEFORE any context, web server or scheduler exists.
 * Boots a real {@code SpringApplication} (servlet web type, the committed {@code prod,staging} YAML) with a
 * deliberately hostile {@code beautica.sms.mode} and records the lifecycle events that fired.
 */
@DisplayName("EnvironmentFingerprintGuard — fails during environment preparation, before context and web server")
class EnvironmentFingerprintGuardBootTest {

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {
    }

    @Test
    @DisplayName("staging with sms.mode=turbosms — startup aborts at environment-prepared; no context, no web server")
    void should_abortBeforeContextAndWebServer_when_fingerprintMismatched() {
        List<String> events = new ArrayList<>();
        ApplicationListener<ApplicationEvent> recorder = e -> events.add(e.getClass().getSimpleName());

        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod", "staging")
                .listeners(recorder)
                .run("--beautica.sms.mode=turbosms", "--server.port=0", "--spring.main.banner-mode=off",
                        "--JWT_SECRET=x", "--INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "--FRONTEND_BASE_URL=https://x.invalid",
                        "--PUBLIC_BASE_URL=https://api.staging.beautica.test", "--SUPPORT_EMAIL=support@test.invalid"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staging-sms-mode").hasMessageContaining("beautica.sms.mode");
        // The guard is itself a listener of the environment-prepared event, so only starting + failed are observable.
        assertThat(events).containsExactly("ApplicationStartingEvent", "ApplicationFailedEvent");
        assertThat(events).doesNotContain("ApplicationContextInitializedEvent", "ApplicationPreparedEvent",
                "ContextRefreshedEvent", "ApplicationStartedEvent", "ApplicationReadyEvent",
                WebServerInitializedEvent.class.getSimpleName(), "ServletWebServerInitializedEvent");
    }

    @Test
    @DisplayName("prod with sms.mode=capture — startup aborts before any context; message names the rule, no values")
    void should_abortBeforeContext_when_prodConfiguredWithCaptureSms() {
        List<String> events = new ArrayList<>();
        ApplicationListener<ApplicationEvent> recorder = e -> events.add(e.getClass().getSimpleName());

        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .listeners(recorder)
                .run("--beautica.sms.mode=capture", "--server.port=0", "--spring.main.banner-mode=off",
                        "--JWT_SECRET=x", "--INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "--FRONTEND_BASE_URL=https://app.beautica.ua",
                        "--PUBLIC_BASE_URL=https://api.beautica.ua", "--SUPPORT_EMAIL=help@beautica.ua"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod-sms-mode").hasMessageContaining("beautica.sms.mode");
        assertThat(events).containsExactly("ApplicationStartingEvent", "ApplicationFailedEvent");
    }

    @Test
    @DisplayName("RAILWAY_ENVIRONMENT_NAME=staging on a prod-only profile — startup aborts naming the railway rule")
    void should_abortBeforeContext_when_railwayStagingButProdOnlyProfile() {
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .run("--RAILWAY_ENVIRONMENT_NAME=staging", "--server.port=0", "--spring.main.banner-mode=off",
                        "--JWT_SECRET=x", "--INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "--FRONTEND_BASE_URL=https://app.beautica.ua",
                        "--PUBLIC_BASE_URL=https://api.beautica.ua", "--SUPPORT_EMAIL=help@beautica.ua"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("railway-environment-name").hasMessageContaining("RAILWAY_ENVIRONMENT_NAME");
    }

    @Test
    @DisplayName("prod with the dev default INTERNAL_API_KEY — startup aborts before any context; no value in message")
    void should_abortBeforeContext_when_prodUsesDevDefaultInternalApiKey() {
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .run("--INTERNAL_API_KEY=changeme-dev-only", "--server.port=0", "--spring.main.banner-mode=off",
                        "--JWT_SECRET=x", "--FRONTEND_BASE_URL=https://app.beautica.ua",
                        "--PUBLIC_BASE_URL=https://api.beautica.ua", "--SUPPORT_EMAIL=help@beautica.ua"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-api-key-default").hasMessageContaining("app.internal-api-key");
        assertThat(thrown.getMessage()).doesNotContain("changeme-dev-only");
    }

    @Test
    @DisplayName("prod,staging with a short INTERNAL_API_KEY — startup aborts naming the length rule")
    void should_abortBeforeContext_when_stagingInternalApiKeyTooShort() {
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod", "staging")
                .run("--INTERNAL_API_KEY=short", "--server.port=0", "--spring.main.banner-mode=off",
                        "--JWT_SECRET=x", "--FRONTEND_BASE_URL=https://x.invalid",
                        "--PUBLIC_BASE_URL=https://api.staging.beautica.test", "--SUPPORT_EMAIL=support@test.invalid"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-api-key-length");
    }

    @Test
    @DisplayName("prod with INTERNAL_API_KEY unset — startup aborts naming the blank rule")
    void should_abortBeforeContext_when_prodInternalApiKeyUnset() {
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--JWT_SECRET=x", "--FRONTEND_BASE_URL=https://app.beautica.ua",
                        "--PUBLIC_BASE_URL=https://api.beautica.ua", "--SUPPORT_EMAIL=help@beautica.ua"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-api-key-blank");
    }

    // ── Phase 432: staging runs its own bot; the prod bot is refused by identity, before any context ──

    private static final String STAGING_BOT_TOKEN = "7000000001:" + "z".repeat(35);
    private static final String STAGING_WEBHOOK_SECRET = "staging_webhook_secret_" + "z".repeat(41);

    /** The committed {@code prod,staging} YAML plus the staging-required vars, then the case-specific args. */
    private static SpringApplicationBuilder stagingBoot(Class<?>... sources) {
        return new SpringApplicationBuilder(sources).web(WebApplicationType.NONE).profiles("prod", "staging");
    }

    private static String[] stagingArgs(String... extra) {
        List<String> args = new ArrayList<>(List.of("--spring.main.banner-mode=off",
                "--JWT_SECRET=x", "--INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "--FRONTEND_BASE_URL=https://x.invalid",
                "--PUBLIC_BASE_URL=https://api.staging.beautica.test", "--SUPPORT_EMAIL=support@test.invalid"));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    @Test
    @DisplayName("Phase 432 — prod,staging with a prod-id TELEGRAM_BOT_TOKEN: startup aborts naming staging-telegram-not-prod-bot, token not echoed")
    void should_abortBeforeContext_when_stagingHasProdBotToken() {
        String token = EnvironmentFingerprintGuard.PROD_TELEGRAM_BOT_ID + ":" + "z".repeat(35);
        List<String> events = new ArrayList<>();
        ApplicationListener<ApplicationEvent> recorder = e -> events.add(e.getClass().getSimpleName());

        Throwable thrown = catchThrowable(() -> stagingBoot(EmptyConfig.class).listeners(recorder)
                .run(stagingArgs("--TELEGRAM_BOT_TOKEN=" + token)));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staging-telegram-not-prod-bot")
                .message().doesNotContain(token).doesNotContain("z".repeat(35))
                .doesNotContain(Long.toString(EnvironmentFingerprintGuard.PROD_TELEGRAM_BOT_ID));
        assertThat(events).containsExactly("ApplicationStartingEvent", "ApplicationFailedEvent");
    }

    @Test
    @DisplayName("Phase 432 — prod,staging with the prod bot username (flag off): startup aborts naming staging-telegram-not-prod-bot")
    void should_abortBeforeContext_when_stagingHasProdBotUsername() {
        Throwable thrown = catchThrowable(() -> stagingBoot(EmptyConfig.class)
                .run(stagingArgs("--TELEGRAM_BOT_TOKEN=" + STAGING_BOT_TOKEN, "--TELEGRAM_BOT_USERNAME=@Beautica_App_Bot",
                        "--APP_TELEGRAM_ENABLED=false")));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staging-telegram-not-prod-bot")
                .message().doesNotContainIgnoringCase("beautica_app_bot").doesNotContain(STAGING_BOT_TOKEN);
    }

    /** What the Telegram wiring needs from a real context: TelegramConfig, the staging guard, a Clock and a mail sender. */
    @Configuration(proxyBeanMethods = false)
    @Import({TelegramConfigTest.TelegramDependencies.class, TelegramConfig.class, TelegramEnvGuard.class})
    static class TelegramWiring {
    }

    @Test
    @DisplayName("Phase 432 — Railway staging today (staging bot token/username/secret, flag off, no allow-list) boots → NoOp")
    void should_boot_when_stagingHasStagingBotFlagOffNoAllowList() {
        try (ConfigurableApplicationContext context = stagingBoot(TelegramWiring.class)
                .run(stagingArgs("--TELEGRAM_BOT_TOKEN=" + STAGING_BOT_TOKEN, "--TELEGRAM_BOT_USERNAME=bt_qa_x7k2_bot",
                        "--TELEGRAM_WEBHOOK_SECRET=" + STAGING_WEBHOOK_SECRET, "--APP_TELEGRAM_ENABLED=false"))) {

            assertThat(context.getBean(TelegramMessenger.class)).isInstanceOf(NoOpTelegramMessenger.class);
            assertThat(context.getBean(TelegramChatAllowList.class)).isInstanceOf(StagingTelegramChatAllowList.class);
            assertThat(context.getBean(TelegramProperties.class).stagingAllowedChatIds()).isEmpty();
            assertThat(context.getBeansOfType(TelegramEnvGuard.class)).as("staging guard bean present").hasSize(1);
        }
    }

    @Test
    @DisplayName("Phase 432 — Railway staging shape (username, secret, flag off, no list) but the PROD bot token: aborts before any context, nothing echoed")
    void should_abortBeforeContext_when_railwayStagingShapeCarriesProdBotToken() {
        String prodToken = EnvironmentFingerprintGuard.PROD_TELEGRAM_BOT_ID + ":" + "q".repeat(35);
        List<String> events = new ArrayList<>();
        ApplicationListener<ApplicationEvent> recorder = e -> events.add(e.getClass().getSimpleName());

        Throwable thrown = catchThrowable(() -> stagingBoot(TelegramWiring.class).listeners(recorder)
                .run(stagingArgs("--TELEGRAM_BOT_TOKEN=" + prodToken, "--TELEGRAM_BOT_USERNAME=bt_qa_x7k2_bot",
                        "--TELEGRAM_WEBHOOK_SECRET=" + STAGING_WEBHOOK_SECRET, "--APP_TELEGRAM_ENABLED=false")));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staging-telegram-not-prod-bot")
                .message().doesNotContain(prodToken).doesNotContain("q".repeat(35))
                .doesNotContain(Long.toString(EnvironmentFingerprintGuard.PROD_TELEGRAM_BOT_ID))
                .doesNotContain(STAGING_WEBHOOK_SECRET);
        assertThat(events).as("no context may exist, events=%s", events)
                .containsExactly("ApplicationStartingEvent", "ApplicationFailedEvent");
    }

    @Test
    @DisplayName("Phase 432 — prod,staging with a malformed TELEGRAM_BOT_TOKEN (flag off): aborts on staging-telegram-token-format before any context")
    void should_abortBeforeContext_when_stagingTokenMalformed() {
        Throwable thrown = catchThrowable(() -> stagingBoot(TelegramWiring.class)
                .run(stagingArgs("--TELEGRAM_BOT_TOKEN=notatoken", "--APP_TELEGRAM_ENABLED=false")));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staging-telegram-token-format")
                .message().doesNotContain("notatoken");
    }

    @Test
    @DisplayName("Phase 432 — staging bot, flag on, live, allow-list set: boots with the allow-list decorator")
    void should_bootWithAllowListMessenger_when_stagingBotEnabledLiveWithAllowList() {
        try (ConfigurableApplicationContext context = stagingBoot(TelegramWiring.class)
                .run(stagingArgs("--TELEGRAM_BOT_TOKEN=" + STAGING_BOT_TOKEN, "--TELEGRAM_BOT_USERNAME=bt_qa_x7k2_bot",
                        "--TELEGRAM_WEBHOOK_SECRET=" + STAGING_WEBHOOK_SECRET, "--APP_TELEGRAM_ENABLED=true",
                        "--TELEGRAM_STAGING_ALLOWED_CHAT_IDS=111,222"))) {

            assertThat(context.getBean(TelegramMessenger.class)).isInstanceOf(AllowListTelegramMessenger.class);
            TelegramChatAllowList allowList = context.getBean(TelegramChatAllowList.class);
            assertThat(allowList.allows(111L)).isTrue();
            assertThat(allowList.allows(333L)).isFalse();
        }
    }

    @Test
    @DisplayName("Phase 432 — staging bot, flag on, live, no allow-list: startup fails in TelegramEnvGuard")
    void should_failBoot_when_stagingBotEnabledLiveWithoutAllowList() {
        Throwable thrown = catchThrowable(() -> stagingBoot(TelegramWiring.class)
                .run(stagingArgs("--TELEGRAM_BOT_TOKEN=" + STAGING_BOT_TOKEN, "--TELEGRAM_BOT_USERNAME=bt_qa_x7k2_bot",
                        "--TELEGRAM_WEBHOOK_SECRET=" + STAGING_WEBHOOK_SECRET, "--APP_TELEGRAM_ENABLED=true")));

        assertThat(thrown).isNotNull();
        assertThat(thrown).rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TELEGRAM_STAGING_ALLOWED_CHAT_IDS")
                .message().doesNotContain(STAGING_BOT_TOKEN);
    }
}
