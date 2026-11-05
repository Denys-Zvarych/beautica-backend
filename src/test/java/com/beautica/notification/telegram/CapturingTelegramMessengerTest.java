package com.beautica.notification.telegram;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 404 (D6) — capture mode mails each Telegram message to {@code tg+<chatId>@telegram.local.invalid}
 * (Mailpit locally), mirroring {@code CapturingSmsSenderTest}: fixed subject, text in the body only, a mail
 * failure is the same transient outcome as the live client, and no chat id / text reaches a log line (D5).
 */
@DisplayName("CapturingTelegramMessenger — Telegram messages mailed to the capture inbox")
class CapturingTelegramMessengerTest {

    private static final String FROM = "noreply@beautica.app";
    private static final long CHAT_ID = 555000111222L;
    private static final String TEXT = "Нагадування: манікюр завтра о 10:00. Підключено: beautica.app/tg/Ab3_xY9-Qr2zLm8K";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final CapturingTelegramMessenger messenger = new CapturingTelegramMessenger(mailSender, FROM);

    private ListAppender<ILoggingEvent> logAppender;
    private Logger classLogger;

    @BeforeEach
    void attachLogCapture() {
        classLogger = (Logger) LoggerFactory.getLogger(CapturingTelegramMessenger.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        classLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogCapture() {
        classLogger.detachAppender(logAppender);
    }

    private void stubMessage() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(1)).send(sent.capture());
        return sent.getValue();
    }

    @Test
    @DisplayName("one mail to tg+<chatId>@telegram.local.invalid, sent from the app sender address")
    void should_mailOnceToTgPlusChatIdAddress_when_sent() throws Exception {
        stubMessage();

        messenger.send(CHAT_ID, TEXT);

        MimeMessage mail = sentMessage();
        assertThat(mail.getRecipients(Message.RecipientType.TO))
                .extracting(Object::toString)
                .containsExactly("tg+555000111222@telegram.local.invalid");
        assertThat(mail.getFrom()).extracting(a -> ((InternetAddress) a).getAddress()).containsExactly(FROM);
    }

    @Test
    @DisplayName("subject is the fixed '[Telegram] message' — no text (and so no link token) in a Mailpit list view")
    void should_useFixedSubjectWithoutText_when_sent() throws Exception {
        stubMessage();

        messenger.send(CHAT_ID, TEXT);

        String subject = sentMessage().getSubject();
        assertThat(subject).as("subject, actual=%s", subject).isEqualTo("[Telegram] message");
        assertThat(subject).doesNotContain("Ab3_xY9").doesNotContain(Long.toString(CHAT_ID));
    }

    @Test
    @DisplayName("body is exactly the text, line breaks preserved")
    void should_carryTextVerbatimInBody_when_sent() throws Exception {
        stubMessage();
        String multiline = "Рядок 1\nРядок 2: " + TEXT;

        messenger.send(CHAT_ID, multiline);

        assertThat(sentMessage().getContent().toString()).isEqualTo(multiline);
    }

    @Test
    @DisplayName("negative (group-style) chat id still yields a parseable capture address")
    void should_mailToSignedAddress_when_chatIdNegative() throws Exception {
        stubMessage();

        messenger.send(-1001234567890L, TEXT);

        assertThat(sentMessage().getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("tg+-1001234567890@telegram.local.invalid");
    }

    @Test
    @DisplayName("mail transport failure → TelegramDeliveryException carrying no chat id, text or transport detail")
    void should_throwTransientWithoutDetail_when_mailTransportFails() {
        stubMessage();
        doThrow(new MailSendException("smtp down for tg+555000111222")).when(mailSender).send(any(MimeMessage.class));

        Throwable thrown = catchThrowable(() -> messenger.send(CHAT_ID, TEXT));

        assertThat(thrown).isInstanceOf(TelegramDeliveryException.class).hasNoCause()
                .hasMessage("Telegram capture mail failed: MailSendException");
    }

    @Test
    @DisplayName("null, blank or over-4096-char text → IllegalArgumentException, no mail built or sent")
    void should_rejectWithoutMail_when_textNotSendable() {
        String tooLong = "a".repeat(TelegramMessenger.MAX_TEXT_LENGTH + 1);

        for (String bad : new String[]{null, "", "   ", tooLong}) {
            assertThatThrownBy(() -> messenger.send(CHAT_ID, bad))
                    .as("text of length %s", bad == null ? "null" : bad.length())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        verify(mailSender, never()).createMimeMessage();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("text of exactly 4096 chars is still captured")
    void should_mail_when_textAtLimit() throws Exception {
        stubMessage();
        String atLimit = "a".repeat(TelegramMessenger.MAX_TEXT_LENGTH);

        messenger.send(CHAT_ID, atLimit);

        assertThat(sentMessage().getContent().toString()).hasSize(TelegramMessenger.MAX_TEXT_LENGTH);
    }

    @Test
    @DisplayName("log lines on success and on failure carry neither chat id nor text (D5)")
    void should_neverLogChatIdOrText_when_successOrFailure() {
        stubMessage();
        messenger.send(CHAT_ID, TEXT);
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        catchThrowable(() -> messenger.send(CHAT_ID, TEXT));

        assertThat(logAppender.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("event=telegram_send outcome=ok status=capture",
                        "event=telegram_send outcome=transient status=capture cause=MailSendException")
                .allSatisfy(line -> assertThat(line).doesNotContain(Long.toString(CHAT_ID), TEXT, "Ab3_xY9"));
    }
}
