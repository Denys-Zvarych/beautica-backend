package com.beautica.notification.mail;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Bean conditions on the resolved {@link MailTransportKind} (Phase 374, D2). Used instead of
 * {@code @ConditionalOnProperty} so the condition resolves the value through the same enum binding as
 * {@link MailTransportProperties} and the fingerprint guard ({@code brevo-api}, {@code BREVO_API} and
 * {@code brevo_api} all mean the same transport; an unknown value fails the context).
 */
public abstract sealed class MailTransportCondition implements Condition
        permits MailTransportCondition.OnBrevoApi, MailTransportCondition.OnSmtp {

    private final MailTransportKind expected;

    MailTransportCondition(MailTransportKind expected) {
        this.expected = expected;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return MailTransportKind.of(context.getEnvironment()) == expected;
    }

    /** Matches when {@code app.mail.transport=brevo-api}. */
    public static final class OnBrevoApi extends MailTransportCondition {
        public OnBrevoApi() {
            super(MailTransportKind.BREVO_API);
        }
    }

    /** Matches when {@code app.mail.transport} is {@code smtp} or unset. */
    public static final class OnSmtp extends MailTransportCondition {
        public OnSmtp() {
            super(MailTransportKind.SMTP);
        }
    }
}
