package com.beautica.config;

import com.beautica.notification.telegram.AllowAllTelegramChatAllowList;
import com.beautica.notification.telegram.AllowListTelegramMessenger;
import com.beautica.notification.telegram.CapturingTelegramMessenger;
import com.beautica.notification.telegram.NoOpTelegramMessenger;
import com.beautica.notification.telegram.StagingTelegramChatAllowList;
import com.beautica.notification.telegram.TelegramBotApiClient;
import com.beautica.notification.telegram.TelegramChatAllowList;
import com.beautica.notification.telegram.TelegramDeliveryException;
import com.beautica.notification.telegram.TelegramMessenger;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 404 (D6/D7) — the {@code app.telegram.*} bean-selection matrix and bind-time fail-fast. Asserts by CLASS and
 * pins the count at exactly one, as {@code SmsFeatureGateTest} does: a duplicate or missing bean at boot is the
 * expensive failure mode. {@link ApplicationContextRunner}, not {@code @SpringBootTest} (Anti-Bug §M1).
 */
@DisplayName("TelegramConfig — app.telegram.enabled/mode select the TelegramMessenger")
class TelegramConfigTest {

    /** Obviously fake, low-entropy fixtures (built, not literal) so gitleaks generic-api-key does not fire. */
    static final String TOKEN = "123456789:" + "x".repeat(35);
    static final String SECRET = "test_webhook_secret_" + "x".repeat(44);

    static final String[] VALID_LIVE = {
            "app.telegram.enabled=true",
            "app.telegram.mode=live",
            "app.telegram.bot-token=" + TOKEN,
            "app.telegram.bot-username=beautica_app_bot",
            "app.telegram.webhook-secret=" + SECRET};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TelegramDependencies.class, TelegramConfig.class);

    @Test
    @DisplayName("flag absent → NoOp, which throws transient")
    void should_registerNoOp_when_flagAbsent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBeans(TelegramMessenger.class).hasSize(1);
            TelegramMessenger messenger = context.getBean(TelegramMessenger.class);
            assertThat(messenger).isInstanceOf(NoOpTelegramMessenger.class);
            assertThatThrownBy(() -> messenger.send(1L, "hi")).isInstanceOf(TelegramDeliveryException.class);
        });
    }

    @Test
    @DisplayName("flag=false + live with blank credentials → NoOp, boots")
    void should_registerNoOp_when_flagFalse() {
        runner.withPropertyValues("app.telegram.enabled=false", "app.telegram.mode=live").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBeans(TelegramMessenger.class).hasSize(1);
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(NoOpTelegramMessenger.class);
        });
    }

    @Test
    @DisplayName("enabled spelled 1 / yes / on / TRUE → the enabled bean (Capturing in capture mode), exactly one")
    void should_registerEnabledBean_when_flagIsRelaxedTrue() {
        for (String value : new String[]{"1", "yes", "on", "TRUE"}) {
            runner.withPropertyValues("app.telegram.enabled=" + value, "app.telegram.mode=capture").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).getBeans(TelegramMessenger.class).hasSize(1);
                assertThat(context).getBean(TelegramMessenger.class)
                        .as("enabled=%s", value).isInstanceOf(CapturingTelegramMessenger.class);
            });
        }
    }

    @Test
    @DisplayName("enabled spelled 0 / no / off → NoOp, exactly one")
    void should_registerNoOp_when_flagIsRelaxedFalse() {
        for (String value : new String[]{"0", "no", "off"}) {
            runner.withPropertyValues("app.telegram.enabled=" + value, "app.telegram.mode=capture").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).getBeans(TelegramMessenger.class).hasSize(1);
                assertThat(context).getBean(TelegramMessenger.class)
                        .as("enabled=%s", value).isInstanceOf(NoOpTelegramMessenger.class);
            });
        }
    }

    @Test
    @DisplayName("enabled=garbage → context fails")
    void should_failContext_when_flagUnparseable() {
        runner.withPropertyValues("app.telegram.enabled=maybe").run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("relaxed true + live with valid credentials → TelegramBotApiClient")
    void should_registerBotApiClient_when_flagIsOneAndLive() {
        runner.withPropertyValues(VALID_LIVE).withPropertyValues("app.telegram.enabled=1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(TelegramBotApiClient.class);
        });
    }

    @Test
    @DisplayName("enabled + capture with blank credentials → Capturing")
    void should_registerCapturing_when_captureMode() {
        runner.withPropertyValues("app.telegram.enabled=true", "app.telegram.mode=capture").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBeans(TelegramMessenger.class).hasSize(1);
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(CapturingTelegramMessenger.class);
        });
    }

    @Test
    @DisplayName("capture → mails tg+<chatId>@telegram.local.invalid with the text in the body only")
    void should_mailToCaptureAddress_when_captureSends() throws Exception {
        runner.withPropertyValues("app.telegram.enabled=true", "app.telegram.mode=capture").run(context -> {
            JavaMailSender mailSender = context.getBean(JavaMailSender.class);
            when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

            context.getBean(TelegramMessenger.class).send(42L, "Нагадування про запис");

            ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
            verify(mailSender).send(sent.capture());
            assertThat(sent.getValue().getRecipients(Message.RecipientType.TO)[0].toString())
                    .isEqualTo("tg+42@telegram.local.invalid");
            assertThat(sent.getValue().getSubject()).doesNotContain("Нагадування");
        });
    }

    @Test
    @DisplayName("enabled + live with valid credentials → TelegramBotApiClient")
    void should_registerBotApiClient_when_liveWithCredentials() {
        runner.withPropertyValues(VALID_LIVE).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBeans(TelegramMessenger.class).hasSize(1);
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(TelegramBotApiClient.class);
        });
    }

    @Test
    @DisplayName("enabled + live + blank token → context fails")
    void should_failContext_when_liveWithBlankToken() {
        runner.withPropertyValues(VALID_LIVE).withPropertyValues("app.telegram.bot-token=").run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "app.telegram.bot-token (TELEGRAM_BOT_TOKEN) must be set when app.telegram.enabled=true "
                                + "and app.telegram.mode=live"));
    }

    @Test
    @DisplayName("enabled + live + blank username → context fails")
    void should_failContext_when_liveWithBlankUsername() {
        runner.withPropertyValues(VALID_LIVE).withPropertyValues("app.telegram.bot-username=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("enabled + live + secret containing '+' → context fails, secret not echoed")
    void should_failContext_when_secretHasPlus() {
        String bad = "abc+" + SECRET;
        runner.withPropertyValues(VALID_LIVE).withPropertyValues("app.telegram.webhook-secret=" + bad)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("app.telegram.webhook-secret has an invalid format").doesNotContain(bad);
                });
    }

    @Test
    @DisplayName("enabled + live + secret under 32 chars → context fails")
    void should_failContext_when_secretTooShort() {
        runner.withPropertyValues(VALID_LIVE).withPropertyValues("app.telegram.webhook-secret=" + "a".repeat(31))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("enabled + live + username with '@' → context fails")
    void should_failContext_when_usernameHasAt() {
        runner.withPropertyValues(VALID_LIVE).withPropertyValues("app.telegram.bot-username=@beautica_app_bot")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("enabled + live + plain-http api base url → context fails (token would travel in clear)")
    void should_failContext_when_apiBaseUrlNotHttps() {
        runner.withPropertyValues(VALID_LIVE).withPropertyValues("app.telegram.api-base-url=http://api.telegram.org")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("link-token-ttl defaults to 30 minutes (phase 405 D6)")
    void should_defaultLinkTokenTtlTo30Minutes_when_unset() {
        runner.run(context -> assertThat(context.getBean(TelegramProperties.class).linkTokenTtl())
                .isEqualTo(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("link-token-ttl of exactly 24h binds (404 audit INFO upper bound is inclusive)")
    void should_bindLinkTokenTtl_when_exactly24Hours() {
        runner.withPropertyValues("app.telegram.link-token-ttl=24h").run(context ->
                assertThat(context.getBean(TelegramProperties.class).linkTokenTtl()).isEqualTo(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("link-token-ttl over 24h → context fails")
    void should_failContext_when_linkTokenTtlOver24Hours() {
        runner.withPropertyValues("app.telegram.link-token-ttl=1441m").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure()))
                    .isEqualTo("app.telegram.link-token-ttl must not exceed PT24H");
        });
    }

    @Test
    @DisplayName("link-token-ttl zero → context fails")
    void should_failContext_when_linkTokenTtlZero() {
        runner.withPropertyValues("app.telegram.link-token-ttl=0s").run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("properties toString redacts token and secret")
    void should_redactSecrets_when_toString() {
        runner.withPropertyValues(VALID_LIVE).run(context ->
                assertThat(context.getBean(TelegramProperties.class).toString())
                        .doesNotContain(TOKEN).doesNotContain(SECRET).contains("<redacted>"));
    }

    // ── QA 404: the committed application*.yml, per profile (D6: local → capture, prod → live, staging → off) ──

    private ApplicationContextRunner committedYaml(String profiles) {
        return runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profiles);
    }

    @Test
    @DisplayName("committed YAML, test profile (no APP_TELEGRAM_ENABLED) → flag off, NoOp, real Bot API origin")
    void should_registerNoOp_when_committedYamlDefault() {
        committedYaml("test").run(context -> {
            assertThat(context).hasNotFailed();
            TelegramProperties props = context.getBean(TelegramProperties.class);
            assertThat(props.enabled()).isFalse();
            assertThat(props.mode()).isEqualTo(TelegramMode.LIVE);
            assertThat(props.apiBaseUrl()).isEqualTo("https://api.telegram.org");
            assertThat(context).getBeans(TelegramMessenger.class).hasSize(1);
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(NoOpTelegramMessenger.class);
        });
    }

    @Test
    @DisplayName("committed YAML, local profile → mode capture, flag still off → NoOp")
    void should_resolveCaptureModeButStayOff_when_localProfile() {
        committedYaml("local").run(context -> {
            assertThat(context).hasNotFailed();
            TelegramProperties props = context.getBean(TelegramProperties.class);
            assertThat(props.mode()).isEqualTo(TelegramMode.CAPTURE);
            assertThat(props.enabled()).isFalse();
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(NoOpTelegramMessenger.class);
        });
    }

    @Test
    @DisplayName("committed YAML, local profile + APP_TELEGRAM_ENABLED=true → Capturing, never the live client")
    void should_registerCapturing_when_localProfileEnabled() {
        committedYaml("local").withPropertyValues("APP_TELEGRAM_ENABLED=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBeans(TelegramMessenger.class).hasSize(1);
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(CapturingTelegramMessenger.class);
        });
    }

    @Test
    @DisplayName("committed YAML, prod and prod,staging profiles → flag off, mode live, NoOp")
    void should_registerNoOpLive_when_prodOrStagingProfile() {
        for (String profiles : new String[]{"prod", "prod,staging"}) {
            committedYaml(profiles).run(context -> {
                assertThat(context).as("profiles=%s", profiles).hasNotFailed();
                TelegramProperties props = context.getBean(TelegramProperties.class);
                assertThat(props.enabled()).as("profiles=%s", profiles).isFalse();
                assertThat(props.mode()).as("profiles=%s", profiles).isEqualTo(TelegramMode.LIVE);
                assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(NoOpTelegramMessenger.class);
            });
        }
    }

    @Test
    @DisplayName("NoOp send → transient with the disabled message, never IllegalArgument even for blank text")
    void should_throwDisabledTransient_when_noOpSendsAnyText() {
        NoOpTelegramMessenger noOp = new NoOpTelegramMessenger();

        for (String text : new String[]{"hi", "", null}) {
            assertThatThrownBy(() -> noOp.send(42L, text))
                    .isExactlyInstanceOf(TelegramDeliveryException.class)
                    .hasMessage("Telegram channel is disabled (app.telegram.enabled=false)");
        }
    }

    // ── Phase 432 D3: staging tester allow-list ──

    @Test
    @DisplayName("allow-list \"1,2\" (and \" 1 , 2 \") binds to [1, 2]")
    void should_bindAllowList_when_commaSeparatedPositiveIds() {
        for (String raw : new String[]{"1,2", " 1 , 2 "}) {
            runner.withPropertyValues("app.telegram.staging-allowed-chat-ids=" + raw).run(context ->
                    assertThat(context.getBean(TelegramProperties.class).stagingAllowedChatIdValues())
                            .as("raw=%s", raw).containsExactly(1L, 2L));
        }
    }

    @Test
    @DisplayName("allow-list unset or empty → empty list")
    void should_bindEmptyAllowList_when_unsetOrEmpty() {
        runner.run(context ->
                assertThat(context.getBean(TelegramProperties.class).stagingAllowedChatIds()).isEmpty());
        runner.withPropertyValues("app.telegram.staging-allowed-chat-ids=").run(context ->
                assertThat(context.getBean(TelegramProperties.class).stagingAllowedChatIds()).isEmpty());
    }

    @Test
    @DisplayName("allow-list with a bad entry (-5, 0, x, blank, 0x1F, #1F, +5, 1e3, overflow) → context fails; no entry echoed anywhere in the failure")
    void should_failContext_when_allowListInvalid() {
        String testerId = "111222333";
        for (String bad : new String[]{"-5", "0", "x", "", " ", "0x1F", "#1F", "+5", "1e3", "١٢", "99999999999999999999"}) {
            String raw = testerId + "," + bad + ",444555666";
            runner.withPropertyValues("app.telegram.staging-allowed-chat-ids=" + raw).run(context -> {
                assertThat(context).as("bad=%s", bad).hasFailed();
                assertThat(rootMessage(context.getStartupFailure())).as("bad=%s", bad)
                        .contains("staging-allowed-chat-ids");
                assertThat(failureReport(context.getStartupFailure())).as("bad=%s", bad)
                        .doesNotContain(testerId).doesNotContain("444555666");
                if (!bad.isBlank()) {
                    assertThat(failureReport(context.getStartupFailure())).as("bad=%s", bad)
                            .doesNotContain("=" + bad).doesNotContain("\"" + bad + "\"");
                }
            });
        }
    }

    @Test
    @DisplayName("allow-list entries are stored canonical (leading zeros dropped)")
    void should_canonicaliseAllowList_when_leadingZeros() {
        runner.withPropertyValues("app.telegram.staging-allowed-chat-ids=007,42").run(context -> {
            TelegramProperties props = context.getBean(TelegramProperties.class);
            assertThat(props.stagingAllowedChatIds()).containsExactly("7", "42");
            assertThat(props.stagingAllowedChatIdValues()).containsExactly(7L, 42L);
        });
    }

    @Test
    @DisplayName("432 audit-fix — beautica.environment=staging WITHOUT the staging profile → StagingTelegramChatAllowList + allow-list decorator")
    void should_selectStagingAllowList_when_stagingEnvironmentWithoutStagingProfile() {
        runner.withPropertyValues(VALID_LIVE)
                .withPropertyValues("beautica.environment=staging", "app.telegram.staging-allowed-chat-ids=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(TelegramChatAllowList.class)
                            .isInstanceOf(StagingTelegramChatAllowList.class);
                    assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(AllowListTelegramMessenger.class);
                    assertThat(context.getBean(TelegramChatAllowList.class).allows(2L)).isFalse();
                });
    }

    @Test
    @DisplayName("432 audit-fix — beautica.environment=staging without the profile + enabled + live + empty list → context fails (same guard rule)")
    void should_fail_when_stagingEnvironmentWithoutStagingProfile() {
        runner.withPropertyValues(VALID_LIVE).withPropertyValues("beautica.environment=staging").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("TELEGRAM_STAGING_ALLOWED_CHAT_IDS");
        });
    }

    @Test
    @DisplayName("432 audit-fix — beautica.environment=staging without the profile + flag off + no list → boots, NoOp")
    void should_boot_when_stagingEnvironmentWithoutProfileFlagOff() {
        runner.withPropertyValues("beautica.environment=staging").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(NoOpTelegramMessenger.class);
            assertThat(context).getBean(TelegramChatAllowList.class).isInstanceOf(StagingTelegramChatAllowList.class);
        });
    }

    @Test
    @DisplayName("432 audit-fix — beautica.environment=prod or local → AllowAllTelegramChatAllowList")
    void should_selectAllowAll_when_prodOrLocalEnvironment() {
        for (String environment : new String[]{"prod", "local"}) {
            runner.withPropertyValues(VALID_LIVE).withPropertyValues("beautica.environment=" + environment)
                    .run(context -> assertThat(context).getBean(TelegramChatAllowList.class)
                            .as("environment=%s", environment).isInstanceOf(AllowAllTelegramChatAllowList.class));
        }
    }

    @Test
    @DisplayName("allow-list ids are not printed by the properties toString")
    void should_notPrintAllowListIds_when_toString() {
        runner.withPropertyValues("app.telegram.staging-allowed-chat-ids=987654321").run(context ->
                assertThat(context.getBean(TelegramProperties.class).toString())
                        .doesNotContain("987654321").contains("<1 entries>"));
    }

    @Test
    @DisplayName("staging + live → AllowListTelegramMessenger over the live client, StagingTelegramChatAllowList, one of each")
    void should_wrapLiveClient_when_staging() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("staging"))
                .withPropertyValues(VALID_LIVE).withPropertyValues("app.telegram.staging-allowed-chat-ids=1,2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBeans(TelegramMessenger.class).hasSize(1);
                    assertThat(context).getBean(TelegramMessenger.class).isInstanceOf(AllowListTelegramMessenger.class);
                    assertThat(context).getBeans(TelegramChatAllowList.class).hasSize(1);
                    assertThat(context).getBean(TelegramChatAllowList.class)
                            .isInstanceOf(StagingTelegramChatAllowList.class);
                });
    }

    @Test
    @DisplayName("staging + capture → Capturing, not wrapped (nothing reaches Telegram)")
    void should_notWrapCapture_when_staging() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("staging"))
                .withPropertyValues("app.telegram.enabled=true", "app.telegram.mode=capture").run(context ->
                        assertThat(context).getBean(TelegramMessenger.class)
                                .isInstanceOf(CapturingTelegramMessenger.class));
    }

    @Test
    @DisplayName("prod + live with an allow-list set → bare TelegramBotApiClient and AllowAllTelegramChatAllowList (list ignored)")
    void should_registerBareClientAndAllowAll_when_prod() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues(VALID_LIVE).withPropertyValues("app.telegram.staging-allowed-chat-ids=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(TelegramMessenger.class).isExactlyInstanceOf(TelegramBotApiClient.class);
                    assertThat(context).getBeans(TelegramChatAllowList.class).hasSize(1);
                    TelegramChatAllowList allowList = context.getBean(TelegramChatAllowList.class);
                    assertThat(allowList).isInstanceOf(AllowAllTelegramChatAllowList.class);
                    assertThat(allowList.allows(42L)).isTrue();
                });
    }

    @Test
    @DisplayName("StagingTelegramChatAllowList allows exactly the listed ids; empty allows nothing")
    void should_allowOnlyListedChats_when_stagingAllowList() {
        TelegramChatAllowList listed = new StagingTelegramChatAllowList(List.of(111L, 222L));
        TelegramChatAllowList empty = new StagingTelegramChatAllowList(List.of());

        assertThat(listed.allows(111L)).isTrue();
        assertThat(listed.allows(222L)).isTrue();
        assertThat(listed.allows(333L)).isFalse();
        assertThat(listed.allows(-111L)).isFalse();
        assertThat(empty.allows(111L)).isFalse();
    }

    /**
     * Everything Spring Boot's failure report could print: each cause's toString plus, for a bind failure, the bound
     * property (name, value and origin) that {@code BindFailureAnalyzer} renders as "Property / Value / Origin".
     */
    private static String failureReport(Throwable t) {
        StringBuilder report = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            report.append(c).append('\n');
            if (c instanceof org.springframework.boot.context.properties.bind.BindException bind
                    && bind.getProperty() != null) {
                report.append(bind.getProperty()).append('\n');
            }
        }
        return report.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    /** What the real context supplies to TelegramConfig: the ClockConfig bean and Boot's JavaMailSender. */
    @Configuration
    static class TelegramDependencies {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        JavaMailSender javaMailSender() {
            return Mockito.mock(JavaMailSender.class);
        }
    }
}
