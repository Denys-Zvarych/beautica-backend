package com.beautica.notification.mail;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Outbound mail transport (Phase 374, D2). Bound from {@code app.mail.transport}: {@code smtp} (default; local,
 * test, staging) or {@code brevo-api} (prod, Railway Hobby blocks SMTP egress). Any other value fails the
 * binding and therefore the boot.
 */
public enum MailTransportKind {

    /** Spring Boot's auto-configured {@code JavaMailSenderImpl} over {@code spring.mail.*}. */
    SMTP,

    /** {@link BrevoApiMailSender}: HTTPS POST to the Brevo transactional API. */
    BREVO_API;

    public static final String PROPERTY = "app.mail.transport";

    /**
     * The single resolution rule shared by the bean condition, the fingerprint guard and the banner, so the three
     * can never disagree on which transport is active.
     *
     * @throws org.springframework.boot.context.properties.bind.BindException when the value is not a transport
     */
    public static MailTransportKind of(Environment env) {
        return Binder.get(env).bind(PROPERTY, MailTransportKind.class).orElse(SMTP);
    }
}
