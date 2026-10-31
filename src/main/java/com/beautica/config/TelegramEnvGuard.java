package com.beautica.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Staging startup guard (Phase 432, D2/D3 — supersedes phase 404 D8). Staging runs its own public test bot, so with
 * {@code app.telegram.enabled=true && mode=live} the tester allow-list
 * ({@code app.telegram.staging-allowed-chat-ids}) must be non-empty: an empty list would make the channel look on
 * while every send silently falls back to SMS. {@code mode=capture} (Mailpit) and the flag off both boot with no
 * list — Railway staging today carries the staging bot's credentials with the flag off and no list.
 *
 * <p>The prod bot itself is refused on staging by {@code EnvironmentFingerprintGuard}
 * ({@code staging-telegram-not-prod-bot}), whatever the flag. Prod has no such guard and ignores the list.
 */
@Component
@Profile("staging")
public class TelegramEnvGuard {

    public TelegramEnvGuard(TelegramProperties properties) {
        requireAllowListWhenLive(properties);
    }

    /**
     * The staging rule itself. Also applied by {@code TelegramConfig} when {@code beautica.environment=staging} is
     * set without the {@code staging} profile (432 audit-fix: this bean is profile-gated, the environment is not), so
     * both signals fail closed through one rule.
     *
     * @throws IllegalStateException when the channel is enabled in live mode with an empty tester allow-list
     */
    static void requireAllowListWhenLive(TelegramProperties properties) {
        if (properties.enabled() && properties.mode() == TelegramMode.LIVE
                && properties.stagingAllowedChatIds().isEmpty()) {
            throw new IllegalStateException("Refusing to start: app.telegram.enabled=true with mode=live on staging "
                    + "(profile or beautica.environment) requires a non-empty app.telegram.staging-allowed-chat-ids "
                    + "(TELEGRAM_STAGING_ALLOWED_CHAT_IDS). Set the tester chat ids, or use "
                    + "APP_TELEGRAM_ENABLED=false or app.telegram.mode=capture on staging.");
        }
    }
}
