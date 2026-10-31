package com.beautica.config;

import com.beautica.notification.telegram.AllowAllTelegramChatAllowList;
import com.beautica.notification.telegram.AllowListTelegramMessenger;
import com.beautica.notification.telegram.CapturingTelegramMessenger;
import com.beautica.notification.telegram.NoOpTelegramMessenger;
import com.beautica.notification.telegram.StagingTelegramChatAllowList;
import com.beautica.notification.telegram.TelegramBotApiClient;
import com.beautica.notification.telegram.TelegramChatAllowList;
import com.beautica.notification.telegram.TelegramMessenger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;

/**
 * Selects the {@link TelegramMessenger} implementation (Phase 404, D6): exactly one bean in every cell, so the router's
 * (408) injection point always resolves.
 * <table>
 *   <caption>TelegramMessenger resolution</caption>
 *   <tr><th>{@code app.telegram.enabled}</th><th>{@code app.telegram.mode}</th><th>bean</th></tr>
 *   <tr><td>false / absent</td><td>any</td><td>{@link NoOpTelegramMessenger} (throws → SMS fallback)</td></tr>
 *   <tr><td>true</td><td>live / absent</td><td>{@link TelegramBotApiClient}</td></tr>
 *   <tr><td>true</td><td>capture</td><td>{@link CapturingTelegramMessenger}</td></tr>
 * </table>
 *
 * <h2>One bean method, selected from the bound properties (audit cycle 3)</h2>
 * Unlike {@link SmsConfig}, selection does NOT use {@code @ConditionalOnProperty}: that compares raw strings, while
 * {@link TelegramProperties} (and {@code EnvironmentFingerprintGuard}) use Spring's boolean conversion, which also
 * reads {@code 1}/{@code yes}/{@code on} as {@code true} and {@code 0}/{@code no}/{@code off} as {@code false}. With
 * string conditions, {@code APP_TELEGRAM_ENABLED=1} bound {@code enabled=true} yet matched neither bean — no
 * {@link TelegramMessenger} at all. Selecting from the one bound record means flag semantics cannot diverge; an
 * unparseable value fails the binding and therefore the boot. Mode-specific collaborators are resolved lazily
 * ({@link ObjectProvider}) so the off and capture cells need neither a {@link Clock} nor a mail sender they do not use.
 * Credentials are validated at bind time by {@link TelegramProperties}; staging is fenced by {@link TelegramEnvGuard}.
 *
 * <h2>Staging tester allow-list (Phase 432, D3)</h2>
 * Exactly one {@link TelegramChatAllowList}: {@link StagingTelegramChatAllowList} when EITHER staging signal is
 * present — the {@code staging} profile or {@code beautica.environment=staging} (432 audit-fix: the environment alone
 * used to select allow-all and skip {@link TelegramEnvGuard}) — and {@link AllowAllTelegramChatAllowList} otherwise.
 * The environment-only path applies the same {@link TelegramEnvGuard} rule. The live client is wrapped in
 * {@link AllowListTelegramMessenger} unless the allow-list is the allow-all one — i.e. on staging only, and fail
 * closed should a future profile add another restricting list. Capture mode never reaches Telegram, so it is not
 * wrapped.
 */
@Slf4j
@Configuration
// DeploymentProperties is also registered by AppConfigProperties; repeated here so the TelegramConfig slice tests
// (ApplicationContextRunner) can resolve the environment-based allow-list selection — a duplicate is a no-op.
@EnableConfigurationProperties({TelegramProperties.class, DeploymentProperties.class})
public class TelegramConfig {

    @Bean
    @Profile("staging")
    public TelegramChatAllowList stagingTelegramChatAllowList(TelegramProperties properties) {
        return new StagingTelegramChatAllowList(properties.stagingAllowedChatIdValues());
    }

    /** Fail closed: a staging environment without the staging profile still gets the tester list and its guard. */
    @Bean
    @Profile("!staging")
    public TelegramChatAllowList telegramChatAllowList(TelegramProperties properties, DeploymentProperties deployment) {
        if (deployment.environment() == DeployEnvironment.STAGING) {
            TelegramEnvGuard.requireAllowListWhenLive(properties);
            return new StagingTelegramChatAllowList(properties.stagingAllowedChatIdValues());
        }
        return new AllowAllTelegramChatAllowList();
    }

    @Bean
    public TelegramMessenger telegramMessenger(
            TelegramProperties properties,
            TelegramChatAllowList allowList,
            ObjectProvider<Clock> clock,
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.invite.from-email:noreply@beautica.app}") String fromAddress) {
        if (!properties.enabled()) {
            log.info("Telegram channel is DISABLED (app.telegram.enabled=false) — every send falls back to SMS");
            return new NoOpTelegramMessenger();
        }
        return switch (properties.mode()) {
            case LIVE -> liveMessenger(properties, clock.getObject(), allowList);
            case CAPTURE -> {
                log.warn("Telegram channel is ENABLED in CAPTURE mode — messages are mailed to the capture inbox, "
                        + "nothing reaches Telegram");
                yield new CapturingTelegramMessenger(mailSender.getObject(), fromAddress);
            }
        };
    }

    private static TelegramMessenger liveMessenger(TelegramProperties properties, Clock clock,
                                                   TelegramChatAllowList allowList) {
        TelegramBotApiClient client = new TelegramBotApiClient(properties, clock);
        if (allowList instanceof AllowAllTelegramChatAllowList) {
            log.info("Telegram channel is ENABLED in LIVE mode — registering TelegramBotApiClient");
            return client;
        }
        log.info("Telegram channel is ENABLED in LIVE mode — registering TelegramBotApiClient behind the staging "
                + "tester allow-list");
        return new AllowListTelegramMessenger(client, allowList);
    }
}
