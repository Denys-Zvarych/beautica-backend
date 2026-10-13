package com.beautica.notification.sms;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.util.regex.Pattern;

/**
 * Staging SMS transport (Phase 368, D3): instead of calling Turbosms, mails the SMS text to
 * {@code sms+<E.164 digits>@sms.staging.invalid} through the application's own {@link JavaMailSender}
 * (which in staging points at Mailpit), so testers read OTPs exactly as they read email OTPs.
 *
 * <p>Deliberately <b>not</b> an {@link SmsService} or {@link OtpSmsSender} itself, mirroring
 * {@link TurbosmsOtpSmsSender}: a class in both hierarchies would make the OTP bean also match
 * {@code SmsService} and break the exactly-one-{@code SmsService} invariant
 * ({@code SmsFeatureGateTest}). {@link CapturingSmsService} and {@link CapturingOtpSmsSender} are the
 * two thin adapters over this one implementation.
 *
 * <p>There is <b>no fixed OTP and no bypass</b>: the real generated code is delivered, only the
 * transport differs. The SMS body is never logged (Anti-Bug §I); the phone is masked.
 */
@Slf4j
public final class CapturingSmsSender {

    static final String RECIPIENT_DOMAIN = "sms.staging.invalid";
    static final String SUBJECT_PREFIX = "[SMS] ";
    static final int SUBJECT_TEXT_CHARS = 40;

    private static final Pattern NON_DIGITS = Pattern.compile("\\D");
    private static final Pattern LINE_BREAKS_AND_CONTROL = Pattern.compile("[\\r\\n\\p{Cntrl}]+");

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public CapturingSmsSender(JavaMailSender mailSender, String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    /**
     * @throws SmsDeliveryException when the phone has no digits or the mail transport fails —
     *                              the same loud-failure contract as the Turbosms sender
     */
    public void send(String phoneE164, String text) {
        String digits = phoneE164 == null ? "" : NON_DIGITS.matcher(phoneE164).replaceAll("");
        if (digits.isEmpty()) {
            throw new SmsDeliveryException("SMS capture rejected: recipient has no digits");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo("sms+" + digits + "@" + RECIPIENT_DOMAIN);
            helper.setSubject(subjectFor(text));
            helper.setText(text == null ? "" : text, false);
            mailSender.send(message);
        } catch (MailException | jakarta.mail.MessagingException e) {
            throw new SmsDeliveryException("SMS capture mail failed: " + e.getClass().getSimpleName(), e);
        }
        log.info("SMS captured to mail to={}", PhoneMask.mask(phoneE164));
    }

    /** First {@value #SUBJECT_TEXT_CHARS} chars on one line — CR/LF stripped so the subject cannot inject headers. */
    static String subjectFor(String text) {
        String flat = text == null ? "" : LINE_BREAKS_AND_CONTROL.matcher(text).replaceAll(" ").strip();
        return SUBJECT_PREFIX + (flat.length() <= SUBJECT_TEXT_CHARS ? flat : flat.substring(0, SUBJECT_TEXT_CHARS));
    }
}
