package com.beautica.config;

import com.beautica.notification.telegram.CapturingTelegramMessenger;
import com.beautica.notification.telegram.NoOpTelegramMessenger;
import com.beautica.notification.telegram.TelegramBotApiClient;
import com.beautica.notification.telegram.TelegramMessenger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramConfig {

    @Bean
    public TelegramMessenger telegramMessenger(
            TelegramProperties properties,
            ObjectProvider<Clock> clock,
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.invite.from-email:noreply@beautica.app}") String fromAddress) {
        if (!properties.enabled()) {
            log.info("Telegram channel is DISABLED (app.telegram.enabled=false) — every send falls back to SMS");
            return new NoOpTelegramMessenger();
        }
        return switch (properties.mode()) {
            case LIVE -> {
                log.info("Telegram channel is ENABLED in LIVE mode — registering TelegramBotApiClient");
                yield new TelegramBotApiClient(properties, clock.getObject());
            }
            case CAPTURE -> {
                log.warn("Telegram channel is ENABLED in CAPTURE mode — messages are mailed to the capture inbox, "
                        + "nothing reaches Telegram");
                yield new CapturingTelegramMessenger(mailSender.getObject(), fromAddress);
            }
        };
    }
}
