package com.beautica.config;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code app.telegram.enabled} binds {@code true} with Spring's boolean conversion — the same rule
 * {@link TelegramProperties} and {@link TelegramConfig} use (404 audit cycle 3: a raw-string
 * {@code @ConditionalOnProperty} would read {@code APP_TELEGRAM_ENABLED=1} as off while the channel is on).
 *
 * <p>Gates every phase 406 webhook bean: {@code TelegramWebhookController}, {@code TelegramWebhookSecretVerifier}
 * and {@link RateLimitConfig#telegramWebhookRejectBuckets()}, so a dark deploy builds none of them.
 */
public final class TelegramEnabledCondition extends SpringBootCondition {

    private static final String PROPERTY = "app.telegram.enabled";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean enabled = Binder.get(context.getEnvironment()).bind(PROPERTY, Boolean.class).orElse(false);
        return enabled
                ? ConditionOutcome.match(PROPERTY + " is true")
                : ConditionOutcome.noMatch(PROPERTY + " is not true");
    }
}
