package com.beautica.notification.mail;

import org.springframework.mail.MailSendException;

/**
 * Message-side Brevo API failure (Phase 374, D5): other 4xx ({@code invalid_parameter}, {@code missing_parameter},
 * 404, 413, …) or a message the transport cannot convert (inline CID part, unsupported MIME part). Retrying the
 * same message cannot help, so it is NOT counted toward the outbox e-mail breaker. Still a
 * {@link org.springframework.mail.MailException}, so existing catches keep working. The message is redacted.
 */
public class BrevoPermanentMailException extends MailSendException {

    public BrevoPermanentMailException(String redactedMessage) {
        super(redactedMessage);
    }
}
