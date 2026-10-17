package com.beautica.notification.service;

/**
 * Signals that an outbox-driven transactional email was not delivered to the SMTP relay.
 *
 * <p>Thrown by {@link EmailNotificationService} so {@link NotificationOutboxDrainWorker} takes its
 * retry -> DEAD path instead of marking the row {@code SENT}. Deliberately carries no cause and
 * only the (non-PII) template name: the worker persists {@code getMessage()} into
 * {@code notification_outbox.last_error}, and a JavaMail message can echo the recipient address.
 *
 * <p><b>At-least-once delivery.</b> An SMTP timeout is ambiguous: the relay may have accepted the
 * message although the client saw a failure. The row is retried regardless, so one e-mail can reach
 * the recipient up to {@code MAX_ATTEMPTS} times. Invite e-mails are re-sent from the same stored
 * URL and token, so duplicates are identical and harmless.
 */
public class EmailDeliveryException extends RuntimeException {

    private final boolean transientSmtp;

    /**
     * @param template      non-PII template name
     * @param transientSmtp {@code true} only for a relay-side failure ({@code MailException} /
     *                      {@code MessagingException}); {@code false} for a permanent local error such
     *                      as a template render failure, which must never count toward the drain
     *                      worker's e-mail circuit breaker
     */
    public EmailDeliveryException(String template, boolean transientSmtp) {
        super("Email delivery failed [template=" + template + "]");
        this.transientSmtp = transientSmtp;
    }

    /** Whether the failure came from the SMTP relay (counts toward the breaker) rather than a local error. */
    public boolean isTransientSmtp() {
        return transientSmtp;
    }
}
