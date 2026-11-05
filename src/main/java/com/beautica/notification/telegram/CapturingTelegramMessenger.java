package com.beautica.notification.telegram;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Capture-mode {@link TelegramMessenger} (Phase 404, D6): instead of calling the Bot API, mails each message to
 * {@code tg+<chatId>@telegram.local.invalid} through the application's {@link JavaMailSender} (Mailpit locally),
 * the same approach as {@code notification/sms/CapturingSmsSender}.
 *
 * <p>The subject is fixed — the text appears only in the mail body, so no link token can surface in a Mailpit
 * list view. Logs never carry the chat id or the text (D5). Mail failure → {@link TelegramDeliveryException},
 * the same loud-failure contract as the live client.
 */
@Slf4j
public final class CapturingTelegramMessenger implements TelegramMessenger {

    static final String RECIPIENT_PREFIX = "tg+";
    static final String RECIPIENT_DOMAIN = "telegram.local.invalid";
    static final String SUBJECT = "[Telegram] message";

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public CapturingTelegramMessenger(JavaMailSender mailSender, String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(long chatId, String text) {
        TelegramMessenger.requireSendable(text);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(RECIPIENT_PREFIX + chatId + "@" + RECIPIENT_DOMAIN);
            helper.setSubject(SUBJECT);
            helper.setText(text, false);
            mailSender.send(message);
        } catch (MailException | MessagingException e) {
            log.warn("event=telegram_send outcome=transient status=capture cause={}", e.getClass().getSimpleName());
            throw new TelegramDeliveryException("Telegram capture mail failed: " + e.getClass().getSimpleName());
        }
        log.info("event=telegram_send outcome=ok status=capture");
    }
}
