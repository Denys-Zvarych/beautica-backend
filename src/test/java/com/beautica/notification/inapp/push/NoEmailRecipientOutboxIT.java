package com.beautica.notification.inapp.push;

import com.beautica.notification.service.BookingVisit;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.google.firebase.messaging.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B514 QA (NTF-56, NTF-D15) — a booking between accounts that have NO email (phone-registered client and providers)
 * drains end to end on real Postgres: real outbox rows, real {@code claimPendingBatch}, real drain worker, real
 * {@code NotificationService}, and a REAL {@link EmailNotificationService} (real Thymeleaf) whose only fake is the
 * SMTP edge. The client confirmation (whose .ics attendee IS the client email) and the provider new-booking email
 * must each finish as a clean no-op — row SENT with no failed attempt, never retried toward DEAD, no SMTP call — while
 * every provider still gets the push (push and feed unchanged).
 *
 * <p>{@code EmailNotificationServiceNoRecipientTest} pins the guard per method; this class proves it composes with
 * the drain (a guard that threw, or one placed after the .ics build, would leave the rows PENDING with an error).
 */
@TestPropertySource(properties = "FIREBASE_ENABLED=true")
@DisplayName("NTF-56 — no-email client and providers: booking email rows drain as SENT no-ops, push still delivered "
        + "(B514, Testcontainers)")
class NoEmailRecipientOutboxIT extends AbstractInAppPushFlowIT {

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;
    @Autowired
    private SpringTemplateEngine templateEngine;

    private JavaMailSender smtp;

    @BeforeEach
    void wireRealEmailServiceBehindMockedSmtp() {
        smtp = mock(JavaMailSender.class);
        when(smtp.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        EmailNotificationService realEmail = new EmailNotificationService(smtp, templateEngine, "noreply@beautica.app");
        doAnswer(inv -> {
            realEmail.sendBookingConfirmedEmail(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(emailNotificationService).sendBookingConfirmedEmail(any(), any(BookingVisit.class));
        doAnswer(inv -> {
            realEmail.sendNewBookingEmail(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(emailNotificationService).sendNewBookingEmail(any(), any(BookingVisit.class));
    }

    /** Turns an email account into a phone-only one, exactly as phone registration leaves it (V244-valid). */
    private void dropEmail(UUID userId) {
        String phone = "+38067" + String.format("%07d", Math.floorMod(userId.hashCode(), 10_000_000));
        jdbcTemplate.update("UPDATE users SET email = NULL, phone_number = ?, phone_verified_at = now() WHERE id = ?",
                phone, userId);
    }

    private Map<String, Object> row(String eventType) {
        return jdbcTemplate.queryForMap(
                "SELECT status, attempts, last_error FROM notification_outbox WHERE event_type = ?", eventType);
    }

    @Test
    @DisplayName("client confirmation + provider new-booking rows end SENT with no failed attempt and no SMTP call; "
            + "all three provider pushes are delivered")
    void should_drainEmailRowsAsNoOpAndStillPush_when_clientAndProvidersHaveNoEmail() throws Exception {
        Rig rig = seedRigWithProviderDeviceTokens();
        for (UUID user : List.of(rig.clientId(), rig.ownerId(), rig.masterUserId(), rig.adminId())) {
            dropEmail(user);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email IS NULL", Long.class))
                .as("client and all three providers are phone-only").isEqualTo(4);
        clientBooks(rig, startsAt());
        assertThat(countOutbox("STATUS_CHANGED")).as("client confirmation row enqueued").isOne();
        assertThat(countOutbox("NEW_BOOKING")).as("provider new-booking row enqueued").isOne();

        drainWorker.drain();

        List<Message> pushes = awaitSends(3);
        assertThat(pushes).extracting(AbstractInAppPushFlowIT::targetTokenOf)
                .as("every provider token still got its push")
                .containsExactlyInAnyOrderElementsOf(rig.tokenByRecipient().values());
        for (String eventType : List.of("STATUS_CHANGED", "NEW_BOOKING")) {
            Map<String, Object> r = row(eventType);
            assertThat(r.get("status")).as("%s drained as a clean no-op, row=%s", eventType, r).isEqualTo("SENT");
            assertThat(r.get("attempts")).as("%s counted no failed attempt", eventType).isEqualTo(0);
            assertThat(r.get("last_error")).as("%s recorded no failure", eventType).isNull();
        }
        verify(emailNotificationService).sendBookingConfirmedEmail(any(), any(BookingVisit.class));
        verify(emailNotificationService).sendNewBookingEmail(any(), any(BookingVisit.class));
        verify(smtp, never()).createMimeMessage();
        verify(smtp, never()).send(any(MimeMessage.class));
    }
}
