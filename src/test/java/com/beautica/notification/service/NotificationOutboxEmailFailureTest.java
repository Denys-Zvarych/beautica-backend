package com.beautica.notification.service;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.notification.crypto.OutboxPayloadCipher;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.inapp.push.InAppPushDispatcher;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.context.IContext;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Real {@link NotificationOutboxDrainWorker} + real {@link NotificationService} + real
 * {@link EmailNotificationService}, with only the {@link JavaMailSender} faked. Proves an SMTP
 * failure is no longer recorded as {@code SENT} (the original swallow bug).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Outbox drain - SMTP failure end to end (unit)")
class NotificationOutboxEmailFailureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RECIPIENT = "fail-recipient@example.com";

    @Mock private NotificationOutboxRepository outboxRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingVisitResolver visitResolver;
    @Mock private OutboxPayloadCipher cipher;
    @Mock private InAppPushDispatcher inAppPushDispatcher;
    @Mock private JavaMailSender mailSender;
    @Mock private SpringTemplateEngine templateEngine;

    private NotificationOutboxDrainWorker worker;

    @BeforeEach
    void setUp() {
        EmailNotificationService email = new EmailNotificationService(mailSender, templateEngine, "noreply@beautica.app");
        NotificationService notification = new NotificationService(email, null, null, "https://beautica.app");
        worker = new NotificationOutboxDrainWorker(
                outboxRepository, notification, bookingRepository, visitResolver, MAPPER, cipher, inAppPushDispatcher,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        ReflectionTestUtils.setField(worker, "self", worker);

        lenient().when(bookingRepository.findAllByIdsWithGraph(anyList())).thenReturn(List.of());
        lenient().when(cipher.open(anyString())).thenReturn("https://beautica.app/invite/accept?token=ABC");
        lenient().when(mailSender.createMimeMessage())
                .thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        lenient().when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>x</html>");
    }

    private NotificationOutboxEntry inviteEntry(int attempts) throws Exception {
        NotificationOutboxEntry e = new NotificationOutboxEntry();
        e.setEventType(OutboxEventType.INVITE);
        e.setAttempts(attempts);
        e.setStatus(OutboxStatus.PENDING);
        e.setAggregateId(UUID.randomUUID());
        e.setPayload(MAPPER.writeValueAsString(
                Map.of("email", RECIPIENT, "salonName", "Salon", "inviteUrlSealed", "v1:X")));
        return e;
    }

    private NotificationOutboxEntry drain(NotificationOutboxEntry entry) {
        when(outboxRepository.claimPendingBatch(50)).thenReturn(List.of(entry));
        worker.drain();
        return entry;
    }

    @Test
    @DisplayName("SMTP throws MailException — row is retried (PENDING, attempt +1), never SENT")
    void should_markRetry_when_smtpThrowsMailException() throws Exception {
        doThrow(new MailSendException("550 rejected " + RECIPIENT)).when(mailSender).send(any(MimeMessage.class));

        NotificationOutboxEntry entry = drain(inviteEntry(0));

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getLastError()).isNotNull().doesNotContain(RECIPIENT);
    }

    @Test
    @DisplayName("SMTP keeps failing up to MAX_ATTEMPTS — row is dead-lettered")
    void should_markDead_when_smtpKeepsFailingUntilMaxAttempts() throws Exception {
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(MimeMessage.class));

        NotificationOutboxEntry entry = drain(inviteEntry(NotificationOutboxDrainWorker.MAX_ATTEMPTS - 1));

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(entry.getAttempts()).isEqualTo(NotificationOutboxDrainWorker.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("SMTP accepts the message — row is SENT")
    void should_markSent_when_smtpSucceeds() throws Exception {
        doNothing().when(mailSender).send(any(MimeMessage.class));

        NotificationOutboxEntry entry = drain(inviteEntry(0));

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(entry.getLastError()).isNull();
    }
}
