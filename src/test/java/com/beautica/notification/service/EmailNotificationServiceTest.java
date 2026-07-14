package com.beautica.notification.service;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IContext;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailNotificationService — unit")
class EmailNotificationServiceTest {

    private static final String FROM_ADDRESS = "noreply@beautica.app";

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private SpringTemplateEngine templateEngine;

    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        // Explicit construction — fromAddress is a constructor @Value param, not @InjectMocks territory.
        service = new EmailNotificationService(mailSender, templateEngine, FROM_ADDRESS);
    }

    // -------------------------------------------------------------------------
    // sendInviteEmail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should call mailSender.send and pass correct template variables when sendInviteEmail is called")
    void should_callMailSenderSend_when_sendInviteEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>invite</html>");
        ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);

        service.sendInviteEmail("master@example.com", "https://beautica.app/invite/token123", "Salon Aurora");

        verify(templateEngine).process(templateCaptor.capture(), contextCaptor.capture());
        verify(mailSender).send(realMessage);

        assertThat(templateCaptor.getValue()).isEqualTo("email/invite-master");

        Context captured = (Context) contextCaptor.getValue();
        assertThat(captured.getVariable("inviteUrl")).isEqualTo("https://beautica.app/invite/token123");
        assertThat(captured.getVariable("salonName")).isEqualTo("Salon Aurora");
    }

    @Test
    @DisplayName("should set Ukrainian subject when sendInviteEmail is called")
    void should_setSubjectToUkrainian_when_sendInviteEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>invite</html>");

        service.sendInviteEmail("master@example.com", "https://beautica.app/invite/x", "Salon Aurora");

        assertThat(realMessage.getSubject()).isEqualTo("Запрошення до салону");
    }

    // -------------------------------------------------------------------------
    // sendNewBookingEmail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Sends new-booking notification email to master via mailSender with the email/new-booking template")
    void should_callMailSenderSend_when_sendNewBookingEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>new-booking</html>");
        Booking booking = buildBookingMock(
                "Тест", "Клієнт",
                "Майстер", "Іванов",
                "Тест послуга",
                OffsetDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC)
        );
        ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);

        service.sendNewBookingEmail("client@example.com", booking);

        verify(templateEngine).process(templateCaptor.capture(), contextCaptor.capture());
        verify(mailSender).send(realMessage);

        assertThat(templateCaptor.getValue()).isEqualTo("email/new-booking");
        assertThat(realMessage.getSubject()).isEqualTo("Нове бронювання");

        Context captured = (Context) contextCaptor.getValue();
        assertThat(captured.getVariable("clientName")).isEqualTo("Тест Клієнт");
        assertThat(captured.getVariable("serviceName")).isEqualTo("Тест послуга");
        assertThat(captured.getVariable("masterName")).isEqualTo("Майстер Іванов");
    }

    @Test
    @DisplayName("should format startsAt in Kyiv timezone when sendNewBookingEmail is called")
    void should_formatStartsAtInKyivTimezone_when_sendNewBookingEmailCalled() throws Exception {
        // UTC 10:00 = Kyiv (UTC+3) 13:00 on same date
        OffsetDateTime utcTime = OffsetDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC);
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>booking</html>");
        Booking booking = buildBookingMock("Тест", "Клієнт", "Майстер", "Іванов", "Тест послуга", utcTime);
        ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);

        service.sendNewBookingEmail("client@example.com", booking);

        verify(templateEngine).process(anyString(), contextCaptor.capture());

        Context captured = (Context) contextCaptor.getValue();
        assertThat((String) captured.getVariable("startsAt")).isEqualTo("13:00, 15 червня 2025");
    }

    @Test
    @DisplayName("should format startsAt as UTC+2 when sendNewBookingEmail is called with a winter date")
    void should_formatStartsAtAsKyivWinter_when_sendNewBookingEmailCalled() throws Exception {
        // UTC 10:00 in November = Kyiv (UTC+2, winter) 12:00
        OffsetDateTime utcTime = OffsetDateTime.of(2025, 11, 15, 10, 0, 0, 0, ZoneOffset.UTC);
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>booking</html>");
        Booking booking = buildBookingMock("Тест", "Клієнт", "Майстер", "Іванов", "Тест послуга", utcTime);
        ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);

        service.sendNewBookingEmail("client@example.com", booking);

        verify(templateEngine).process(anyString(), contextCaptor.capture());
        assertThat((String) ((Context) contextCaptor.getValue()).getVariable("startsAt"))
                .isEqualTo("12:00, 15 листопада 2025");
    }

    // -------------------------------------------------------------------------
    // sendBookingConfirmedEmail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Sends booking-confirmed notification email to client with the email/booking-confirmed template")
    void should_callMailSenderSend_when_sendBookingConfirmedEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>confirmed</html>");
        Booking booking = buildBookingMock(
                "Тест", "Клієнт", "Майстер", "Іванов", "Тест послуга",
                OffsetDateTime.of(2025, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC)
        );
        ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);

        service.sendBookingConfirmedEmail("client@example.com", booking);

        verify(templateEngine).process(templateCaptor.capture(), contextCaptor.capture());
        verify(mailSender).send(realMessage);

        assertThat(templateCaptor.getValue()).isEqualTo("email/booking-confirmed");
        assertThat(realMessage.getSubject()).isEqualTo("Бронювання підтверджено");

        Context captured = (Context) contextCaptor.getValue();
        assertThat(captured.getVariable("clientName")).isEqualTo("Тест Клієнт");
        assertThat(captured.getVariable("serviceName")).isEqualTo("Тест послуга");
        // UTC 09:00 on 2025-07-01 = Kyiv (UTC+3) 12:00 same date
        assertThat((String) captured.getVariable("startsAt")).isEqualTo("12:00, 1 липня 2025");
    }

    // -------------------------------------------------------------------------
    // sendReviewRequestEmail (Phase 18.5)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sendReviewRequestEmail renders the review-request template with all vars and a formatted startsAt")
    void should_callMailSenderSend_when_sendReviewRequestEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>review</html>");
        Booking booking = buildBookingMock(
                "Тест", "Клієнт", "Майстер", "Іванов", "Тест послуга",
                OffsetDateTime.of(2025, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC)
        );
        String reviewUrl = "https://app.beautica.ua/bookings/abc-123/review";
        ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);

        service.sendReviewRequestEmail("client@example.com", booking, reviewUrl);

        verify(templateEngine).process(templateCaptor.capture(), contextCaptor.capture());
        verify(mailSender).send(realMessage);

        assertThat(templateCaptor.getValue()).isEqualTo("email/review-request");
        assertThat(realMessage.getSubject()).isEqualTo("Оцініть візит");

        Context captured = (Context) contextCaptor.getValue();
        assertThat(captured.getVariable("clientName")).isEqualTo("Тест Клієнт");
        assertThat(captured.getVariable("masterName")).isEqualTo("Майстер Іванов");
        assertThat(captured.getVariable("serviceName")).isEqualTo("Тест послуга");
        assertThat(captured.getVariable("reviewUrl")).isEqualTo(reviewUrl);
        // UTC 09:00 on 2025-07-01 = Kyiv (UTC+3) 12:00 same date — pre-formatted, never a raw datetime.
        assertThat((String) captured.getVariable("startsAt")).isEqualTo("12:00, 1 липня 2025");
    }

    @Test
    @DisplayName("sendReviewRequestEmail rejects an unsafe reviewUrl scheme before any render or send")
    void should_throwIllegalArgument_when_sendReviewRequestEmailReviewUrlSchemeUnsafe() {
        // The scheme guard rejects before the booking graph is touched, so a bare mock suffices
        // (a full buildBookingMock would trip Mockito's strict UnnecessaryStubbingException).
        Booking booking = mock(Booking.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.sendReviewRequestEmail("client@example.com", booking, "javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");

        org.mockito.Mockito.verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("sendReviewRequestEmail strips CRLF from the To address before setTo is called")
    void should_stripCrlf_when_sendReviewRequestEmailCalledWithInjectedNewline() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>review</html>");
        Booking booking = buildBookingMock(
                "Тест", "Клієнт", "Майстер", "Іванов", "Тест послуга",
                OffsetDateTime.of(2025, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC)
        );

        service.sendReviewRequestEmail(
                "client@example.com\r\n", booking, "https://app.beautica.ua/bookings/abc/review");

        assertThat(realMessage.getRecipients(Message.RecipientType.TO))
                .isNotNull()
                .hasSize(1);
        String toHeader = realMessage.getRecipients(Message.RecipientType.TO)[0].toString();
        assertThat(toHeader).doesNotContain("\r");
        assertThat(toHeader).doesNotContain("\n");
        assertThat(toHeader).contains("client@example.com");
    }

    // -------------------------------------------------------------------------
    // sendBookingDeclinedEmail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Sends booking-declined notification email to client and forwards the provider comment to the template")
    void should_callMailSenderSend_when_sendBookingDeclinedEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>declined</html>");
        Booking booking = buildBookingMock(
                "Тест", "Клієнт", "Майстер", "Іванов", "Тест послуга",
                OffsetDateTime.of(2025, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC)
        );
        when(booking.getProviderComment()).thenReturn("На жаль, майстер недоступний");
        ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);

        service.sendBookingDeclinedEmail("client@example.com", booking);

        verify(templateEngine).process(templateCaptor.capture(), contextCaptor.capture());
        verify(mailSender).send(realMessage);

        assertThat(templateCaptor.getValue()).isEqualTo("email/booking-declined");
        assertThat(realMessage.getSubject()).isEqualTo("Бронювання скасовано");

        Context captured = (Context) contextCaptor.getValue();
        assertThat(captured.getVariable("comment")).isEqualTo("На жаль, майстер недоступний");
        assertThat(captured.getVariable("clientName")).isEqualTo("Тест Клієнт");
        assertThat(captured.getVariable("serviceName")).isEqualTo("Тест послуга");
    }

    // -------------------------------------------------------------------------
    // sendClientCancelledEmail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Sends client-cancelled notification email to master and forwards the client comment to the template")
    void should_callMailSenderSend_when_sendClientCancelledEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>cancelled</html>");
        Booking booking = buildBookingMock(
                "Тест", "Клієнт", "Майстер", "Іванов", "Тест послуга",
                OffsetDateTime.of(2025, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC)
        );
        when(booking.getClientComment()).thenReturn("Змінились плани");
        ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);

        service.sendClientCancelledEmail("master@example.com", booking);

        verify(templateEngine).process(templateCaptor.capture(), contextCaptor.capture());
        verify(mailSender).send(realMessage);

        assertThat(templateCaptor.getValue()).isEqualTo("email/booking-cancelled-provider");
        assertThat(realMessage.getSubject()).isEqualTo("Клієнт скасував бронювання");

        Context captured = (Context) contextCaptor.getValue();
        assertThat(captured.getVariable("comment")).isEqualTo("Змінились плани");
        assertThat(captured.getVariable("clientName")).isEqualTo("Тест Клієнт");
        assertThat(captured.getVariable("masterName")).isEqualTo("Майстер Іванов");
        assertThat(captured.getVariable("serviceName")).isEqualTo("Тест послуга");
    }

    // -------------------------------------------------------------------------
    // Error resilience
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should not throw when mailSender.send throws MailSendException")
    void should_notThrow_when_mailSenderThrowsMailException() {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>invite</html>");
        doThrow(new MailSendException("SMTP unavailable")).when(mailSender).send(any(MimeMessage.class));

        assertThatCode(() ->
                service.sendInviteEmail("fail@example.com", "https://beautica.app/invite/x", "Salon")
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should not throw when MimeMessageHelper setup causes MessagingException")
    void should_notThrow_when_messagingExceptionThrown() throws MessagingException {
        // Inject a real MessagingException via a mock MimeMessage whose setSubject throws.
        // MimeMessageHelper.setSubject delegates to MimeMessage.setSubject, which is declared
        // to throw MessagingException — exercising the genuine catch (MessagingException | MailException)
        // branch in EmailNotificationService.send().
        MimeMessage mockMessage = mock(MimeMessage.class);
        doThrow(new MessagingException("simulated subject failure"))
                .when(mockMessage).setSubject(anyString(), anyString());
        when(mailSender.createMimeMessage()).thenReturn(mockMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>invite</html>");

        assertThatCode(() ->
                service.sendInviteEmail("fail@example.com", "https://beautica.app/invite/x", "Salon")
        ).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // From / To header
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should set correct From address header when sendInviteEmail is called")
    void should_setFromAddress_when_sendInviteEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>invite</html>");

        service.sendInviteEmail("master@example.com", "https://beautica.app/invite/x", "Salon Aurora");

        assertThat(realMessage.getFrom())
                .isNotNull()
                .hasSize(1);
        assertThat(realMessage.getFrom()[0].toString()).contains(FROM_ADDRESS);
    }

    @Test
    @DisplayName("should set correct To recipient header when sendInviteEmail is called")
    void should_setToRecipient_when_sendInviteEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>invite</html>");

        service.sendInviteEmail("master@example.com", "https://beautica.app/invite/x", "Salon Aurora");

        assertThat(realMessage.getRecipients(Message.RecipientType.TO))
                .isNotNull()
                .hasSize(1);
        assertThat(realMessage.getRecipients(Message.RecipientType.TO)[0].toString())
                .contains("master@example.com");
    }

    // -------------------------------------------------------------------------
    // sendVerificationEmail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should call mailSender.send when sendVerificationEmail is called")
    void should_callMailSenderSend_when_sendVerificationEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>verify</html>");

        service.sendVerificationEmail("user@example.com", "123456");

        verify(templateEngine).process(eq("email/verify-email"), any(IContext.class));
        verify(mailSender).send(realMessage);
    }

    @Test
    @DisplayName("should set Ukrainian subject when sendVerificationEmail is called")
    void should_setSubjectToUkrainian_when_sendVerificationEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>verify</html>");

        service.sendVerificationEmail("user@example.com", "123456");

        assertThat(realMessage.getSubject()).isEqualTo("Код підтвердження Beautica");
    }

    @Test
    @DisplayName("should set correct From address when sendVerificationEmail is called")
    void should_setFromAddress_when_sendVerificationEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>verify</html>");

        service.sendVerificationEmail("user@example.com", "123456");

        assertThat(realMessage.getFrom())
                .isNotNull()
                .hasSize(1);
        assertThat(realMessage.getFrom()[0].toString()).contains(FROM_ADDRESS);
    }

    @Test
    @DisplayName("should set correct To recipient when sendVerificationEmail is called")
    void should_setToRecipient_when_sendVerificationEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>verify</html>");

        service.sendVerificationEmail("user@example.com", "123456");

        assertThat(realMessage.getRecipients(Message.RecipientType.TO))
                .isNotNull()
                .hasSize(1);
        assertThat(realMessage.getRecipients(Message.RecipientType.TO)[0].toString())
                .contains("user@example.com");
    }

    @Test
    @DisplayName("should render code in HTML body when sendVerificationEmail is called")
    void should_renderCodeInBody_when_sendVerificationEmailCalled() throws Exception {
        String expectedCode = "987654";
        String renderedHtml = "<html><body><span>" + expectedCode + "</span></body></html>";
        MimeMessage realMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn(renderedHtml);
        ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);

        service.sendVerificationEmail("user@example.com", expectedCode);

        verify(templateEngine).process(anyString(), contextCaptor.capture());
        Context captured = (Context) contextCaptor.getValue();
        assertThat(captured.getVariable("code")).isEqualTo(expectedCode);
    }

    @Test
    @DisplayName("should attach logo as CID inline part when sendVerificationEmail is called")
    void should_attachLogoAsCid_when_sendVerificationEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>verify</html>");

        service.sendVerificationEmail("user@example.com", "111111");

        // mailSender.send is mocked — it never calls saveChanges(), so we do it here
        // to flush MimeMessageHelper's in-memory part tree before reading getContent().
        realMessage.saveChanges();

        // MimeMessageHelper with multipart=true produces a MimeMultipart body
        Object content = realMessage.getContent();
        assertThat(content).isInstanceOf(MimeMultipart.class);
        MimeMultipart multipart = (MimeMultipart) content;

        // MimeMessageHelper(message, true) nests: multipart/mixed → multipart/related → [html, image]
        BodyPart relatedBodyPart = multipart.getBodyPart(0);
        MimeMultipart related = (MimeMultipart) relatedBodyPart.getContent();

        boolean foundLogoPart = false;
        for (int i = 0; i < related.getCount(); i++) {
            BodyPart part = related.getBodyPart(i);
            String[] contentIds = part.getHeader("Content-ID");
            String contentType = part.getContentType();
            if (contentIds != null && contentType != null
                    && contentType.startsWith("image/png")
                    && java.util.Arrays.stream(contentIds).anyMatch(id -> id.contains("beauticaLogo"))) {
                foundLogoPart = true;
                break;
            }
        }
        assertThat(foundLogoPart)
                .as("Expected inline image/png with Content-ID beauticaLogo in multipart/related")
                .isTrue();
    }

    @Test
    @DisplayName("should not throw when mailSender.send throws MailException in sendVerificationEmail")
    void should_notThrow_when_mailSenderThrowsMailException_in_sendVerificationEmail() {
        MimeMessage realMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>verify</html>");
        doThrow(new MailSendException("SMTP unavailable")).when(mailSender).send(any(MimeMessage.class));

        assertThatCode(() ->
                service.sendVerificationEmail("fail@example.com", "000000")
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should not throw when MimeMessage.setSubject throws MessagingException in sendVerificationEmail")
    void should_notThrow_when_messagingExceptionInSendVerificationEmail() throws MessagingException {
        MimeMessage mockMessage = mock(MimeMessage.class);
        doThrow(new MessagingException("simulated header failure"))
                .when(mockMessage).setSubject(anyString(), anyString());
        when(mailSender.createMimeMessage()).thenReturn(mockMessage);

        assertThatCode(() ->
                service.sendVerificationEmail("fail@example.com", "000000")
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should contain expiry warning in Ukrainian when template is rendered")
    void should_containExpiryWarning_when_templateRendered() throws Exception {
        String fixture = """
                <html>
                  <body>
                    <span>123456</span>
                    <td>Код дійсний протягом 15 хвилин.</td>
                  </body>
                </html>
                """;
        MimeMessage realMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn(fixture);

        service.sendVerificationEmail("user@example.com", "123456");

        realMessage.saveChanges();
        // Extract the text/html body part from multipart/related (inside multipart/mixed)
        MimeMultipart outer = (MimeMultipart) realMessage.getContent();
        MimeMultipart related = (MimeMultipart) outer.getBodyPart(0).getContent();
        String body = (String) related.getBodyPart(0).getContent();
        assertThat(body).contains("15 хвилин");
    }

    @Test
    @DisplayName("should contain CID logo reference in rendered template HTML")
    void should_containCidLogoReference_when_templateRendered() throws Exception {
        String fixture = """
                <html>
                  <body>
                    <img src="cid:beauticaLogo" alt="Beautica" width="64" height="64"/>
                    <span>123456</span>
                  </body>
                </html>
                """;
        MimeMessage realMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn(fixture);

        service.sendVerificationEmail("user@example.com", "123456");

        realMessage.saveChanges();
        MimeMultipart outer = (MimeMultipart) realMessage.getContent();
        MimeMultipart related = (MimeMultipart) outer.getBodyPart(0).getContent();
        String body = (String) related.getBodyPart(0).getContent();
        assertThat(body).contains("src=\"cid:beauticaLogo\"");
    }

    // -------------------------------------------------------------------------
    // sendPasswordResetOtpEmail (Phase A4 — replaces sendPasswordResetEmail)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sendPasswordResetOtpEmail — calls mailSender.send and uses email/reset-password-otp template")
    void should_callMailSenderSend_when_sendPasswordResetOtpEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>reset</html>");
        ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);

        service.sendPasswordResetOtpEmail(buildUser("user@example.com", "Anna"), "123456");

        verify(templateEngine).process(templateCaptor.capture(), any(IContext.class));
        verify(mailSender).send(realMessage);
        assertThat(templateCaptor.getValue())
                .as("template name must be email/reset-password-otp")
                .isEqualTo("email/reset-password-otp");
    }

    @Test
    @DisplayName("sendPasswordResetOtpEmail — subject is Ukrainian 'Код для скидання паролю Beautica'")
    void should_setUkrainianSubject_when_sendPasswordResetOtpEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>reset</html>");

        service.sendPasswordResetOtpEmail(buildUser("user@example.com", "Anna"), "123456");

        assertThat(realMessage.getSubject())
                .as("subject must be the Ukrainian reset-password-otp heading")
                .isEqualTo("Код для скидання паролю Beautica");
    }

    @Test
    @DisplayName("sendPasswordResetOtpEmail — From header contains configured noreply address")
    void should_setFromAddress_when_sendPasswordResetOtpEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>reset</html>");

        service.sendPasswordResetOtpEmail(buildUser("user@example.com", "Anna"), "123456");

        assertThat(realMessage.getFrom())
                .as("From: must be set")
                .isNotNull()
                .hasSize(1);
        assertThat(realMessage.getFrom()[0].toString())
                .as("From: must contain the noreply address")
                .contains(FROM_ADDRESS);
    }

    @Test
    @DisplayName("sendPasswordResetOtpEmail — To header contains the user's email address")
    void should_setToRecipient_when_sendPasswordResetOtpEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>reset</html>");

        service.sendPasswordResetOtpEmail(buildUser("reset-user@example.com", "Anna"), "123456");

        assertThat(realMessage.getRecipients(jakarta.mail.Message.RecipientType.TO))
                .as("To: must contain exactly the recipient's email")
                .isNotNull()
                .hasSize(1);
        assertThat(realMessage.getRecipients(jakarta.mail.Message.RecipientType.TO)[0].toString())
                .as("To: must contain the target address")
                .contains("reset-user@example.com");
    }

    @Test
    @DisplayName("sendPasswordResetOtpEmail — passes the raw OTP to Thymeleaf context variable 'code'")
    void should_passCodeToContext_when_sendPasswordResetOtpEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>reset</html>");
        ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);

        service.sendPasswordResetOtpEmail(buildUser("user@example.com", "Anna"), "654321");

        verify(templateEngine).process(anyString(), contextCaptor.capture());
        Context captured = (Context) contextCaptor.getValue();
        assertThat(captured.getVariable("code"))
                .as("context variable 'code' must equal the raw OTP passed in")
                .isEqualTo("654321");
    }

    @Test
    @DisplayName("sendPasswordResetOtpEmail — does not pass a 'firstName' context variable (dead/PII-avoidance, template does not use it)")
    void should_notPassFirstNameToContext_when_sendPasswordResetOtpEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>reset</html>");
        ArgumentCaptor<IContext> contextCaptor = ArgumentCaptor.forClass(IContext.class);

        service.sendPasswordResetOtpEmail(buildUser("user@example.com", "Anna"), "654321");

        verify(templateEngine).process(anyString(), contextCaptor.capture());
        Context captured = (Context) contextCaptor.getValue();
        assertThat(captured.getVariable("firstName"))
                .as("firstName must not be set — reset-password-otp.html never references it")
                .isNull();
    }

    @Test
    @DisplayName("sendPasswordResetOtpEmail — MimeMessage is multipart/related with inline image/png CID beauticaLogo")
    void should_attachLogoAsCid_when_sendPasswordResetOtpEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>reset</html>");

        service.sendPasswordResetOtpEmail(buildUser("user@example.com", "Anna"), "123456");

        realMessage.saveChanges();
        Object content = realMessage.getContent();
        assertThat(content)
                .as("MimeMessageHelper(multipart=true) must produce a MimeMultipart body")
                .isInstanceOf(MimeMultipart.class);
        MimeMultipart outer = (MimeMultipart) content;
        MimeMultipart related = (MimeMultipart) outer.getBodyPart(0).getContent();

        boolean foundLogoPart = false;
        for (int i = 0; i < related.getCount(); i++) {
            BodyPart part = related.getBodyPart(i);
            String[] contentIds = part.getHeader("Content-ID");
            String contentType = part.getContentType();
            if (contentIds != null && contentType != null
                    && contentType.startsWith("image/png")
                    && java.util.Arrays.stream(contentIds)
                            .anyMatch(id -> id.contains("beauticaLogo"))) {
                foundLogoPart = true;
                break;
            }
        }
        assertThat(foundLogoPart)
                .as("Expected inline image/png with Content-ID beauticaLogo in multipart/related")
                .isTrue();
    }

    @Test
    @DisplayName("sendPasswordResetOtpEmail — does not throw when mailSender.send throws MailSendException")
    void should_notThrow_when_mailSenderThrowsMailException_in_sendPasswordResetOtpEmail() {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>reset</html>");
        doThrow(new MailSendException("SMTP unavailable")).when(mailSender).send(any(MimeMessage.class));

        assertThatCode(() ->
                service.sendPasswordResetOtpEmail(buildUser("user@example.com", "Anna"), "123456")
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sendPasswordResetOtpEmail — does not throw when MimeMessage.setSubject throws MessagingException")
    void should_notThrow_when_messagingExceptionIn_sendPasswordResetOtpEmail() throws MessagingException {
        MimeMessage mockMessage = mock(MimeMessage.class);
        doThrow(new MessagingException("simulated header failure"))
                .when(mockMessage).setSubject(anyString(), anyString());
        when(mailSender.createMimeMessage()).thenReturn(mockMessage);

        assertThatCode(() ->
                service.sendPasswordResetOtpEmail(buildUser("user@example.com", "Anna"), "123456")
        ).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // CRLF-strip (header injection defence)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should strip CRLF from To address in sendPasswordResetOtpEmail before setTo is called")
    void should_stripCrlf_when_sendPasswordResetOtpEmailCalledWithInjectedNewline() throws Exception {
        // Use an address that is valid after CRLF stripping so MimeMessageHelper.setTo() succeeds
        // and we can assert the header was populated without the injected control characters.
        // "user@example.com\r\n" → stripped → "user@example.com" (valid RFC 5321 address).
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>reset</html>");

        service.sendPasswordResetOtpEmail(buildUser("user@example.com\r\n", "Anna"), "123456");

        assertThat(realMessage.getRecipients(Message.RecipientType.TO))
                .as("To header must be set (CRLF stripped, address remains valid)")
                .isNotNull()
                .hasSize(1);
        String toHeader = realMessage.getRecipients(Message.RecipientType.TO)[0].toString();
        assertThat(toHeader).doesNotContain("\r");
        assertThat(toHeader).doesNotContain("\n");
        assertThat(toHeader).contains("user@example.com");
    }

    @Test
    @DisplayName("should strip CRLF from To address in sendVerificationEmail before setTo is called")
    void should_stripCrlf_when_sendVerificationEmailCalledWithInjectedNewline() throws Exception {
        // Use an address that is valid after CRLF stripping so MimeMessageHelper.setTo() succeeds.
        // "user@example.com\r\n" → stripped → "user@example.com" (valid RFC 5321 address).
        MimeMessage realMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>verify</html>");

        service.sendVerificationEmail(
                "user@example.com\r\n",
                "123456");

        assertThat(realMessage.getRecipients(Message.RecipientType.TO))
                .as("To header must be set (CRLF stripped, address remains valid)")
                .isNotNull()
                .hasSize(1);
        String toHeader = realMessage.getRecipients(Message.RecipientType.TO)[0].toString();
        assertThat(toHeader).doesNotContain("\r");
        assertThat(toHeader).doesNotContain("\n");
        assertThat(toHeader).contains("user@example.com");
    }

    @Test
    @DisplayName("should strip CRLF from To address in send() helper before setTo is called (via sendInviteEmail)")
    void should_stripCrlf_when_sendHelperCalledWithInjectedNewline() throws Exception {
        // Use an address that is valid after newline stripping so MimeMessageHelper.setTo() succeeds.
        // "master@example.com\n" → stripped → "master@example.com" (valid RFC 5321 address).
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>invite</html>");

        service.sendInviteEmail(
                "master@example.com\n",
                "https://beautica.app/invite/token",
                "Salon Aurora");

        assertThat(realMessage.getRecipients(Message.RecipientType.TO))
                .as("To header must be set (newline stripped, address remains valid)")
                .isNotNull()
                .hasSize(1);
        String toHeader = realMessage.getRecipients(Message.RecipientType.TO)[0].toString();
        assertThat(toHeader).doesNotContain("\r");
        assertThat(toHeader).doesNotContain("\n");
        assertThat(toHeader).contains("master@example.com");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal mock Booking with the full association chain needed by EmailNotificationService.
     * Mock chain: booking -> client (User), master (Master -> User), masterService (MasterServiceAssignment -> ServiceDefinition).
     *
     * Master-chain stubs are {@code lenient} because {@code sendBookingConfirmedEmail} and
     * {@code sendBookingDeclinedEmail} do not access the master, so strict Mockito would raise
     * {@code UnnecessaryStubbingException} in those test methods.
     */
    private Booking buildBookingMock(
            String clientFirstName, String clientLastName,
            String masterFirstName, String masterLastName,
            String serviceName,
            OffsetDateTime startsAt
    ) {
        User clientUser = mock(User.class);
        when(clientUser.getFirstName()).thenReturn(clientFirstName);
        when(clientUser.getLastName()).thenReturn(clientLastName);

        User masterUser = mock(User.class);
        lenient().when(masterUser.getFirstName()).thenReturn(masterFirstName);
        lenient().when(masterUser.getLastName()).thenReturn(masterLastName);

        Master master = mock(Master.class);
        lenient().when(master.getUser()).thenReturn(masterUser);

        ServiceDefinition serviceDefinition = mock(ServiceDefinition.class);
        when(serviceDefinition.getName()).thenReturn(serviceName);

        MasterServiceAssignment masterService = mock(MasterServiceAssignment.class);
        when(masterService.getServiceDefinition()).thenReturn(serviceDefinition);

        Booking booking = mock(Booking.class);
        when(booking.getClient()).thenReturn(clientUser);
        lenient().when(booking.getMaster()).thenReturn(master);
        when(booking.getMasterService()).thenReturn(masterService);
        lenient().when(booking.getStartsAt()).thenReturn(startsAt);

        return booking;
    }

    /**
     * Builds a real (non-mock) {@link User} for {@code sendPasswordResetOtpEmail} tests —
     * only {@code getEmail()} / {@code getFirstName()} are read by the service, both plain
     * constructor-set fields, so a real instance is simpler than mocking.
     */
    private User buildUser(String email, String firstName) {
        return new User(email, "hash", Role.CLIENT, firstName, "Test", null);
    }
}
