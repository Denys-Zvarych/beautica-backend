package com.beautica.notification.sms;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CapturingSmsSender — SMS mailed to the staging capture inbox")
class CapturingSmsSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final CapturingSmsSender sender = new CapturingSmsSender(mailSender, "noreply@beautica.app");

    private MimeMessage stubMessage() {
        MimeMessage message = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        return message;
    }

    @Test
    void should_mailFullTextToSmsPlusDigitsAddress_when_sent() throws Exception {
        stubMessage();
        String text = "Beautica: 123456 — код підтвердження";

        sender.send("+380671234567", text);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("sms+380671234567@sms.staging.invalid");
        assertThat(sent.getValue().getContent().toString()).contains("123456").isEqualTo(text);
        assertThat(sent.getValue().getSubject()).startsWith("[SMS] Beautica: 123456");
    }

    @Test
    void should_truncateSubjectTo40CharsAndStripLineBreaks_when_textIsLongAndMultiline() {
        String subject = CapturingSmsSender.subjectFor("line1\r\nBcc: evil@x.y " + "z".repeat(100));

        assertThat(subject).doesNotContain("\r").doesNotContain("\n");
        assertThat(subject).hasSize("[SMS] ".length() + 40);
    }

    @Test
    @DisplayName("review-invite text: the subject stops at /r/ and masks the token — no token character leaks")
    void should_maskReviewToken_when_subjectBuiltForReviewInvite() {
        String token = "Ab3_xY9-Qr2zLm8KpT4wVu";

        String subject = CapturingSmsSender.subjectFor("Оцініть ваш візит: beautica.app/r/" + token);

        assertThat(subject).isEqualTo("[SMS] Оцініть ваш візит: beautica.app/r/***");
        assertThat(subject).doesNotContain(token.substring(0, 3));
    }

    @Test
    @DisplayName("review-invite link past the 40-char window: cut before /r/, still no token character")
    void should_cutBeforeLink_when_reviewLinkStartsBeyondSubjectWindow() {
        String token = "Ab3_xY9-Qr2zLm8KpT4wVu";

        String subject = CapturingSmsSender.subjectFor("x".repeat(45) + " beautica.app/r/" + token);

        assertThat(subject).isEqualTo("[SMS] " + "x".repeat(40));
    }

    @Test
    @DisplayName("an OTP-style text without /r/ keeps the original subject rule")
    void should_keepSubjectUnchanged_when_textHasNoReviewLink() {
        String subject = CapturingSmsSender.subjectFor("Код: 482913");

        assertThat(subject).isEqualTo("[SMS] Код: 482913");
    }

    @Test
    void should_throwSmsDeliveryException_when_mailTransportFails() {
        stubMessage();
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> sender.send("+380671234567", "x"))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageNotContaining("smtp down");
    }

    @Test
    void should_throwWithoutSending_when_phoneHasNoDigits() {
        assertThatThrownBy(() -> sender.send("n/a", "x")).isInstanceOf(SmsDeliveryException.class);
        assertThatThrownBy(() -> sender.send(null, "x")).isInstanceOf(SmsDeliveryException.class);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
