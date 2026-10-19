package com.beautica.notification.mail;

import org.springframework.mail.MailSendException;

/**
 * System-side Brevo API failure (Phase 374, D5): 5xx, 429, network/timeout, and the auth/credit outcomes
 * (401, 402, 403, {@code unauthorized}, {@code permission_denied}, {@code not_enough_credits},
 * {@code account_under_validation}). Retried and counted toward the outbox e-mail breaker, exactly like an SMTP
 * {@code MailException} today. The message is redacted: status and whitelisted code only.
 */
public class BrevoTransientMailException extends MailSendException {

    public BrevoTransientMailException(String redactedMessage) {
        super(redactedMessage);
    }
}
