package com.beautica.notification.mail;

import com.beautica.common.observability.ExternalCallFailure;
import org.slf4j.Logger;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;

/**
 * Phase 441 — the one place a caller of {@code JavaMailSender#send} logs a {@link MailException}, so a failed send is
 * exactly one ERROR (one Sentry event) whichever transport is active:
 * <ul>
 *   <li>{@code brevo-api}: {@link BrevoApiMailSender} already logged the ERROR at the call (with Brevo's redacted
 *       message), and only ever throws {@link BrevoPermanentMailException} / {@link BrevoTransientMailException} —
 *       the caller logs WARN.</li>
 *   <li>{@code smtp}: Boot's {@code JavaMailSenderImpl} logs nothing, so the caller's catch IS the point closest to
 *       the call — it logs the {@link ExternalCallFailure} ERROR ({@code service=brevo-smtp}).</li>
 * </ul>
 * The SMTP exception is not attached: its message can list the rejected recipient addresses. The root cause class is
 * the {@code detail} instead.
 */
public final class MailSendFailures {

    static final String SMTP_SERVICE = "brevo-smtp";
    static final String OP_SEND = "send";
    static final String REASON_AUTH = "auth";
    static final String REASON_REJECTED = "rejected";

    private MailSendFailures() {
    }

    /**
     * @param context a log-safe label of what was sent (a template name) — never an address
     */
    public static void log(Logger log, String context, MailException failure) {
        if (failure instanceof BrevoPermanentMailException || failure instanceof BrevoTransientMailException) {
            log.warn("Mail send failed [{}]: {} (reported by the brevo-api transport)", context,
                    failure.getClass().getSimpleName());
            return;
        }
        ExternalCallFailure.of(SMTP_SERVICE, OP_SEND).reason(smtpReason(failure))
                .detail(context + " " + ExternalCallFailure.rootCauseName(failure)).log(log);
    }

    private static String smtpReason(MailException failure) {
        if (failure instanceof MailAuthenticationException) {
            return REASON_AUTH;
        }
        String transport = ExternalCallFailure.transportReason(failure);
        return ExternalCallFailure.UNEXPECTED.equals(transport) ? REASON_REJECTED : transport;
    }
}
