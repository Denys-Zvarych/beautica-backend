package com.beautica.notification.mail;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MimeToBrevoPayloadMapper — MIME to Brevo JSON (Phase 374 D7)")
class MimeToBrevoPayloadMapperTest {

    private static final String FROM = "noreply@beautica.app";
    private static final String TO = "client@example.com";

    private static MimeMessage newMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    /** Same shape as EmailNotificationService.send without the logo: multipart, UTF-8, HTML body. */
    private static MimeMessage htmlMessage(String subject, String html) throws Exception {
        MimeMessage message = newMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(FROM);
        helper.setTo(TO);
        helper.setSubject(subject);
        helper.setText(html, true);
        return message;
    }

    @Test
    @DisplayName("HTML-only multipart → htmlContent, sender name defaulted, single To, no text/reply-to/attachments")
    void should_mapHtmlBody_when_htmlOnlyMultipart() throws Exception {
        MimeMessage message = htmlMessage("Нове бронювання", "<p>Привіт</p>");

        BrevoEmailRequest request = MimeToBrevoPayloadMapper.map(message, "Beautica");

        assertThat(request.sender()).isEqualTo(new BrevoEmailRequest.Address(FROM, "Beautica"));
        assertThat(request.to()).containsExactly(new BrevoEmailRequest.Address(TO, null));
        assertThat(request.htmlContent()).isEqualTo("<p>Привіт</p>");
        assertThat(request.textContent()).isNull();
        assertThat(request.replyTo()).isNull();
        assertThat(request.attachment()).isEmpty();
        assertThat(request.headers()).isEmpty();
    }

    @Test
    @DisplayName("UTF-8 Ukrainian subject is decoded, never sent as an encoded-word")
    void should_decodeUkrainianSubject_when_utf8Subject() throws Exception {
        MimeMessage message = htmlMessage("Код для скидання паролю Beautica", "<p>x</p>");

        BrevoEmailRequest request = MimeToBrevoPayloadMapper.map(message, "Beautica");

        assertThat(request.subject()).isEqualTo("Код для скидання паролю Beautica");
    }

    @Test
    @DisplayName("text-only (non-multipart) → textContent plus an HTML-escaped htmlContent with <br> per line")
    void should_generateEscapedHtml_when_textOnly() throws Exception {
        MimeMessage message = newMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(FROM);
        helper.setTo(TO);
        helper.setSubject("admin");
        helper.setText("line <1> & \"q\"\nline 2", false);

        BrevoEmailRequest request = MimeToBrevoPayloadMapper.map(message, "Beautica");

        assertThat(request.textContent()).isEqualTo("line <1> & \"q\"\nline 2");
        assertThat(request.htmlContent()).isEqualTo("line &lt;1&gt; &amp; &quot;q&quot;<br>line 2");
    }

    @Test
    @DisplayName("Reply-To header present → replyTo; absent → null (getReplyTo() would fall back to From)")
    void should_mapReplyToOnlyWhenHeaderPresent() throws Exception {
        MimeMessage withReplyTo = htmlMessage("s", "<p>x</p>");
        withReplyTo.setReplyTo(new jakarta.mail.Address[]{new InternetAddress("user@example.org")});
        MimeMessage without = htmlMessage("s", "<p>x</p>");

        assertThat(MimeToBrevoPayloadMapper.map(withReplyTo, "Beautica").replyTo())
                .isEqualTo(new BrevoEmailRequest.Address("user@example.org", null));
        assertThat(MimeToBrevoPayloadMapper.map(without, "Beautica").replyTo()).isNull();
    }

    @Test
    @DisplayName("attachment part → {name, base64 content}; HTML body still mapped")
    void should_mapAttachmentAsBase64_when_attachmentPresent() throws Exception {
        MimeMessage message = newMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(FROM);
        helper.setTo("support@beautica.app");
        helper.setSubject("[Beautica Support] message");
        helper.setText("<p>body</p>", true);
        byte[] bytes = "%PDF-1.4 fake".getBytes(StandardCharsets.US_ASCII);
        helper.addAttachment("attachment-1.pdf", new ByteArrayResource(bytes), "application/pdf");

        BrevoEmailRequest request = MimeToBrevoPayloadMapper.map(message, "Beautica");

        assertThat(request.attachment()).singleElement().satisfies(a -> {
            assertThat(a.name()).isEqualTo("attachment-1.pdf");
            assertThat(a.content()).isEqualTo(bytes);
        });
        assertThat(request.htmlContent()).isEqualTo("<p>body</p>");
    }

    @Test
    @DisplayName("wire JSON — byte[] content serialises to the same standard base64 string as before (no line breaks)")
    void should_serialiseAttachmentAsStandardBase64_when_writtenAsJson() throws Exception {
        byte[] bytes = new byte[4000];
        new java.util.Random(374).nextBytes(bytes);
        BrevoEmailRequest.Attachment attachment = new BrevoEmailRequest.Attachment("attachment-1.pdf", bytes);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(attachment);

        assertThat(json).isEqualTo("{\"name\":\"attachment-1.pdf\",\"content\":\""
                + Base64.getEncoder().encodeToString(bytes) + "\"}");
    }

    @Test
    @DisplayName("inline CID part → BrevoPermanentMailException(inline-parts-unsupported), never silently dropped")
    void should_rejectInlineCidPart_when_present() throws Exception {
        MimeMessage message = htmlMessage("s", "<img src=\"cid:beauticaLogo\"/>");
        new MimeMessageHelper(message, true, "UTF-8")
                .addInline("beauticaLogo", new ByteArrayResource(new byte[]{1, 2, 3}), "image/png");

        assertThatThrownBy(() -> MimeToBrevoPayloadMapper.map(message, "Beautica"))
                .isInstanceOf(BrevoPermanentMailException.class)
                .hasMessage("Brevo API conversion failed reason=inline-parts-unsupported");
    }

    @Test
    @DisplayName("CR/LF in a recipient display name is stripped (header-injection guard)")
    void should_stripCrLf_when_displayNameContainsLineBreaks() throws Exception {
        MimeMessage message = htmlMessage("s", "<p>x</p>");
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(TO, "Name\r\nX-Evil: 1", "UTF-8"));

        BrevoEmailRequest request = MimeToBrevoPayloadMapper.map(message, "Beautica");

        assertThat(request.to()).singleElement().satisfies(a -> {
            assertThat(a.email()).isEqualTo(TO);
            assertThat(a.name()).startsWith("Name").doesNotContain("\r").doesNotContain("\n");
        });
    }

    @Test
    @DisplayName("CR/LF smuggled into the address itself → permanent failure, never forwarded to Brevo")
    void should_failPermanently_when_addressContainsLineBreaks() throws Exception {
        MimeMessage message = htmlMessage("s", "<p>x</p>");
        InternetAddress hostile = new InternetAddress();
        hostile.setAddress("victim@example.com\r\nBcc: evil@example.com");
        message.setRecipient(Message.RecipientType.TO, hostile);

        assertThatThrownBy(() -> MimeToBrevoPayloadMapper.map(message, "Beautica"))
                .isInstanceOf(BrevoPermanentMailException.class)
                .hasMessage("Brevo API conversion failed reason=unconvertible-mime");
    }

    @Test
    @DisplayName("sender display name from From wins; the property default applies only when From has none; capped at 70")
    void should_applySenderName_when_fromHasNoPersonal() throws Exception {
        MimeMessage named = htmlMessage("s", "<p>x</p>");
        named.setFrom(new InternetAddress(FROM, "Beautica Support", "UTF-8"));
        MimeMessage unnamed = htmlMessage("s", "<p>x</p>");

        assertThat(MimeToBrevoPayloadMapper.map(named, "Default").sender().name()).isEqualTo("Beautica Support");
        assertThat(MimeToBrevoPayloadMapper.map(unnamed, "Default").sender().name()).isEqualTo("Default");
        assertThat(MimeToBrevoPayloadMapper.map(unnamed, "N".repeat(90)).sender().name()).hasSize(70);
    }

    @Test
    @DisplayName("cc/bcc recipients and custom X- headers are carried; standard headers are not")
    void should_mapCcBccAndCustomHeaders() throws Exception {
        MimeMessage message = htmlMessage("s", "<p>x</p>");
        message.setRecipient(Message.RecipientType.CC, new InternetAddress("cc@example.com"));
        message.setRecipient(Message.RecipientType.BCC, new InternetAddress("bcc@example.com"));
        message.setHeader("X-Beautica-Trace", "abc");

        BrevoEmailRequest request = MimeToBrevoPayloadMapper.map(message, "Beautica");

        assertThat(request.cc()).extracting(BrevoEmailRequest.Address::email).containsExactly("cc@example.com");
        assertThat(request.bcc()).extracting(BrevoEmailRequest.Address::email).containsExactly("bcc@example.com");
        assertThat(request.headers()).containsExactly(java.util.Map.entry("X-Beautica-Trace", "abc"));
    }

    @Test
    @DisplayName("no From → permanent(missing-sender); no body → permanent(missing-body)")
    void should_failPermanently_when_senderOrBodyMissing() throws Exception {
        MimeMessage noFrom = newMessage();
        noFrom.setRecipient(Message.RecipientType.TO, new InternetAddress(TO));
        noFrom.setText("x", "UTF-8");
        MimeMessage noBody = newMessage();
        new MimeMessageHelper(noBody, true, "UTF-8").setFrom(FROM);

        assertThatThrownBy(() -> MimeToBrevoPayloadMapper.map(noFrom, "Beautica"))
                .isInstanceOf(BrevoPermanentMailException.class).hasMessageContaining("missing-sender");
        assertThatThrownBy(() -> MimeToBrevoPayloadMapper.map(noBody, "Beautica"))
                .isInstanceOf(BrevoPermanentMailException.class).hasMessageContaining("reason=");
    }
}
