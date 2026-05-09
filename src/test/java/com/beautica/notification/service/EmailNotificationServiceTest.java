package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
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
    @DisplayName("should call mailSender.send with template email/new-booking when sendNewBookingEmail is called")
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

        service.sendNewBookingEmail("client@example.com", booking);

        verify(templateEngine).process(templateCaptor.capture(), any(IContext.class));
        verify(mailSender).send(realMessage);

        assertThat(templateCaptor.getValue()).isEqualTo("email/new-booking");
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
    @DisplayName("should call mailSender.send with template email/booking-confirmed when sendBookingConfirmedEmail is called")
    void should_callMailSenderSend_when_sendBookingConfirmedEmailCalled() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>confirmed</html>");
        Booking booking = buildBookingMock(
                "Тест", "Клієнт", "Майстер", "Іванов", "Тест послуга",
                OffsetDateTime.of(2025, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC)
        );
        ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);

        service.sendBookingConfirmedEmail("client@example.com", booking);

        verify(templateEngine).process(templateCaptor.capture(), any(IContext.class));
        verify(mailSender).send(realMessage);

        assertThat(templateCaptor.getValue()).isEqualTo("email/booking-confirmed");
    }

    // -------------------------------------------------------------------------
    // sendBookingDeclinedEmail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should call mailSender.send with template email/booking-declined and pass providerComment when sendBookingDeclinedEmail is called")
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

        Context captured = (Context) contextCaptor.getValue();
        assertThat(captured.getVariable("comment")).isEqualTo("На жаль, майстер недоступний");
    }

    // -------------------------------------------------------------------------
    // sendClientCancelledEmail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should call mailSender.send with template email/booking-cancelled-provider and pass clientComment when sendClientCancelledEmail is called")
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

        Context captured = (Context) contextCaptor.getValue();
        assertThat(captured.getVariable("comment")).isEqualTo("Змінились плани");
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
    void should_notThrow_when_messagingExceptionThrown() {
        // Return a mock MimeMessage whose setContent path throws — MimeMessageHelper
        // writes to the message during helper.setText(); an invalid message causes MessagingException.
        // Simplest approach: make createMimeMessage() return null so MimeMessageHelper NPEs on construction,
        // which is caught by the outer Exception catch block.
        when(mailSender.createMimeMessage()).thenReturn(null);
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
}
