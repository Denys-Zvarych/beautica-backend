package com.beautica.booking;

import com.beautica.auth.Role;
import com.beautica.booking.dto.AppointmentItemRescheduleRequest;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.service.AppointmentTransitionService;
import com.beautica.booking.service.BookingService;
import com.beautica.common.TimeZones;
import com.beautica.notification.BookingIcsExpectedContent;
import com.beautica.notification.ClientReceiptEmailExpectedCopy;
import com.beautica.notification.service.BookingVisit;
import com.beautica.notification.service.EmailDeliveryException;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.support.MimeMessageBodies;
import com.beautica.support.MutableTestClock;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 496 (NTF-49, NTF-50) — registered-client receipt e-mails and booking .ics, through the real services, the
 * real outbox drain and the real templates over a mocked SMTP transport. Every booking e-mail sent to the CLIENT is
 * rendered by a real {@link EmailNotificationService} (the context's bean is a mock — every client/master booking
 * method delegates to the real one), so subjects, recipients and the attached .ics are the production output.
 *
 * <p>Same {@code @TestPropertySource} + clock {@code @Import} as {@link RegisteredBookingReminderIT}, so the classes
 * share one cached context. The fixture master is the salon owner performing their own bookings (NTF-44: the
 * performing master is e-mailed, at {@code salon.ownerEmail()}).
 */
@TestPropertySource(properties = {"app.booking.sms.enabled=true", "app.booking.reminder-2h.enabled=true"})
@Import(GuestTwoHourReminderIT.ClockConfig.class)
@DisplayName("ClientBookingReceiptEmailIT — phase 496 client receipts + booking .ics")
class ClientBookingReceiptEmailIT extends AbstractWalkInBookingSmsIT {

    private static final Instant NOW = LocalDate.now(TimeZones.KYIV)
            .atTime(12, 0).atZone(TimeZones.KYIV).toInstant();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("uk"));
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired private BookingService bookingService;
    @Autowired private AppointmentTransitionService appointmentTransitionService;
    @Autowired private NotificationOutboxDrainWorker drainWorker;
    @Autowired private NotificationOutboxService outboxService;
    @Autowired private SpringTemplateEngine templateEngine;
    @Autowired private MutableTestClock clock;
    @Autowired private PlatformTransactionManager transactionManager;

    @Value("${app.invite.from-email:noreply@beautica.app}")
    private String fromAddress;

    private final List<MimeMessage> sentMails = new CopyOnWriteArrayList<>();
    private SeededUser client;
    private EmailNotificationService realEmails;

    @BeforeEach
    void wireRealBookingEmailsAndClient() {
        clock.set(NOW);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        Session session = Session.getInstance(new Properties());
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));
        doAnswer(inv -> sentMails.add(inv.getArgument(0))).when(mailSender).send(any(MimeMessage.class));
        EmailNotificationService real = new EmailNotificationService(mailSender, templateEngine, fromAddress, null,
                clock);
        realEmails = real;
        doAnswer(delegatesTo(real)).when(emailNotificationService).sendBookingConfirmedEmail(anyString(), any());
        doAnswer(delegatesTo(real)).when(emailNotificationService).sendBookingDeclinedEmail(anyString(), any());
        doAnswer(delegatesTo(real)).when(emailNotificationService).sendVisitDeclinedEmail(anyString(), any());
        doAnswer(delegatesTo(real)).when(emailNotificationService).sendBookingRescheduledEmail(anyString(), any());
        doAnswer(delegatesTo(real)).when(emailNotificationService).sendBookingRescheduledClientEmail(anyString(), any());
        doAnswer(delegatesTo(real)).when(emailNotificationService)
                .sendBookingRescheduledClientEmail(anyString(), any(), anyBoolean());
        doAnswer(delegatesTo(real)).when(emailNotificationService)
                .sendClientCancelledEmail(anyString(), any(com.beautica.booking.entity.Booking.class));
        doAnswer(delegatesTo(real)).when(emailNotificationService)
                .sendClientCancelledEmail(anyString(), any(BookingVisit.class));
        doAnswer(delegatesTo(real)).when(emailNotificationService).sendClientCancelReceiptEmail(anyString(), any());
        client = insertUser("CLIENT", null);
    }

    @AfterEach
    void restoreClock() {
        clock.set(NOW);
    }

    @Test
    @DisplayName("client reschedules → «ви перенесли запис» receipt + REQUEST .ics to the client, the existing e-mail "
            + "to the master, no SMS")
    void should_emailClientReceiptWithIcsRequest_when_clientReschedules() throws Exception {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofDays(2))), null);
        OffsetDateTime newStart = at(NOW.plus(Duration.ofDays(3)));

        // Act
        bookingService.rescheduleBooking(client.id(), Role.CLIENT, bookingId, new RescheduleBookingRequest(newStart, false));
        drainWorker.drain();

        // Assert
        MimeMessage receipt = onlyMailTo(client.email());
        assertThat(receipt.getSubject()).isEqualTo(
                ClientReceiptEmailExpectedCopy.selfRescheduleSubject(kyivDay(newStart), kyivTime(newStart)));
        assertThat(MimeMessageBodies.htmlBodyOf(receipt)).contains(ClientReceiptEmailExpectedCopy.SELF_RESCHEDULE_LEAD);
        String ics = onlyIcs(receipt, "REQUEST");
        assertThat(BookingIcsExpectedContent.lines(ics)).contains(BookingIcsExpectedContent.METHOD_REQUEST,
                BookingIcsExpectedContent.uidLine(bookingId), "DTSTART:" + utcStamp(newStart));
        assertThat(mailsTo(salon.ownerEmail())).as("the performing master's existing reschedule e-mail").hasSize(1);
        assertThat(sentTexts()).as("NTF-D04: never an SMS to a registered client").isEmpty();
    }

    @Test
    @DisplayName("provider reschedules → the client's existing e-mail now carries a REQUEST .ics")
    void should_attachIcsRequest_when_providerReschedules() throws Exception {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofDays(2))), null);
        OffsetDateTime newStart = at(NOW.plus(Duration.ofDays(3)));

        // Act
        bookingService.rescheduleBooking(salon.ownerId(), Role.SALON_OWNER, bookingId,
                new RescheduleBookingRequest(newStart, false));
        drainWorker.drain();

        // Assert
        MimeMessage mail = onlyMailTo(client.email());
        assertThat(mail.getSubject()).isEqualTo(ClientReceiptEmailExpectedCopy.PROVIDER_RESCHEDULE_SUBJECT);
        assertThat(MimeMessageBodies.htmlBodyOf(mail)).contains(ClientReceiptEmailExpectedCopy.PROVIDER_RESCHEDULE_LEAD);
        assertThat(BookingIcsExpectedContent.lines(onlyIcs(mail, "REQUEST")))
                .contains(BookingIcsExpectedContent.uidLine(bookingId), "DTSTART:" + utcStamp(newStart));
    }

    @Test
    @DisplayName("client cancels ONE service of a two-service visit → one receipt (CANCEL .ics) + one master e-mail; "
            + "the sibling stays CONFIRMED")
    void should_emailReceiptAndMaster_when_clientCancelsOneVisitItem() throws Exception {
        // Arrange
        UUID appointmentId = insertAppAppointment();
        OffsetDateTime first = at(NOW.plus(Duration.ofDays(2)));
        UUID firstItem = insertAppBooking(first, appointmentId);
        UUID secondItem = insertAppBooking(first.plusMinutes(30), appointmentId);

        // Act
        bookingService.cancelAppointmentItem(client.id(), appointmentId, firstItem, clientCancel());
        drainWorker.drain();

        // Assert
        MimeMessage receipt = onlyMailTo(client.email());
        assertThat(receipt.getSubject())
                .isEqualTo(ClientReceiptEmailExpectedCopy.cancelReceiptSubject(kyivDay(first), kyivTime(first)));
        assertThat(MimeMessageBodies.htmlBodyOf(receipt)).contains(ClientReceiptEmailExpectedCopy.CANCEL_LEAD);
        assertThat(BookingIcsExpectedContent.lines(onlyIcs(receipt, "CANCEL"))).contains(
                BookingIcsExpectedContent.METHOD_CANCEL, BookingIcsExpectedContent.STATUS_CANCELLED,
                BookingIcsExpectedContent.uidLine(firstItem));
        assertThat(onlyMailTo(salon.ownerEmail()).getSubject())
                .isEqualTo(ClientReceiptEmailExpectedCopy.MASTER_CANCEL_SUBJECT);
        assertThat(status(secondItem)).isEqualTo("CONFIRMED");
        assertThat(sentTexts()).isEmpty();
    }

    @Test
    @DisplayName("NTF-D11: client cancels both services → one receipt and one master e-mail PER booking")
    void should_sendOneEmailPerBooking_when_clientCancelsTwoItems() throws Exception {
        // Arrange
        UUID appointmentId = insertAppAppointment();
        OffsetDateTime first = at(NOW.plus(Duration.ofDays(2)));
        UUID firstItem = insertAppBooking(first, appointmentId);
        UUID secondItem = insertAppBooking(first.plusMinutes(30), appointmentId);

        // Act
        bookingService.cancelAppointmentItem(client.id(), appointmentId, firstItem, clientCancel());
        bookingService.cancelAppointmentItem(client.id(), appointmentId, secondItem, clientCancel());
        drainWorker.drain();

        // Assert
        List<MimeMessage> receipts = mailsTo(client.email());
        assertThat(receipts).hasSize(2);
        List<String> uids = new java.util.ArrayList<>();
        for (MimeMessage receipt : receipts) {
            uids.add(BookingIcsExpectedContent.property(onlyIcs(receipt, "CANCEL"), "UID").orElseThrow());
        }
        assertThat(uids).containsExactlyInAnyOrder(firstItem + "@beautica.app", secondItem + "@beautica.app");
        assertThat(mailsTo(salon.ownerEmail())).extracting(MimeMessage::getSubject)
                .containsExactly(ClientReceiptEmailExpectedCopy.MASTER_CANCEL_SUBJECT,
                        ClientReceiptEmailExpectedCopy.MASTER_CANCEL_SUBJECT);
    }

    @Test
    @DisplayName("provider declines → the client's decline e-mail carries a CANCEL .ics")
    void should_attachIcsCancel_when_providerDeclines() throws Exception {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofDays(2))), null);

        // Act
        bookingService.declineBooking(salon.ownerId(), bookingId,
                new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null));
        drainWorker.drain();

        // Assert
        MimeMessage mail = onlyMailTo(client.email());
        assertThat(mail.getSubject()).isEqualTo(ClientReceiptEmailExpectedCopy.DECLINED_SUBJECT);
        assertThat(BookingIcsExpectedContent.lines(onlyIcs(mail, "CANCEL"))).contains(
                BookingIcsExpectedContent.METHOD_CANCEL, BookingIcsExpectedContent.uidLine(bookingId));
    }

    @Test
    @DisplayName("booking confirmed → the confirmation e-mail carries the first REQUEST .ics (organizer = From)")
    void should_attachIcsRequest_when_bookingConfirmed() throws Exception {
        // Arrange
        OffsetDateTime start = at(NOW.plus(Duration.ofDays(2)));
        UUID bookingId = insertAppBooking(start, null);

        // Act
        enqueueConfirmed(bookingId);
        drainWorker.drain();

        // Assert
        MimeMessage mail = onlyMailTo(client.email());
        assertThat(mail.getSubject()).isEqualTo(ClientReceiptEmailExpectedCopy.CONFIRMED_SUBJECT);
        Part attachment = MimeMessageBodies.attachmentsOf(mail).get(0);
        assertThat(attachment.getFileName()).isEqualTo(BookingIcsExpectedContent.FILE_NAME);
        assertThat(BookingIcsExpectedContent.lines(MimeMessageBodies.textOf(attachment))).contains(
                BookingIcsExpectedContent.METHOD_REQUEST, BookingIcsExpectedContent.STATUS_CONFIRMED,
                BookingIcsExpectedContent.uidLine(bookingId), "DTSTART:" + utcStamp(start),
                BookingIcsExpectedContent.ORGANIZER_PREFIX + fromAddress);
    }

    @Test
    @DisplayName("guest cancels by link → the master e-mail as today, never a receipt (no account), no extra SMS")
    void should_sendNoReceipt_when_guestCancelsByLink() throws Exception {
        // Arrange
        SettledGuest guest = createGuestBookingSettled(tomorrowAtNoon());
        sentMails.clear();
        String token = guest.cancelUrl().substring(guest.cancelUrl().lastIndexOf('/') + 1);

        // Act
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/book/cancel/" + token, HttpMethod.POST,
                new HttpEntity<>(ipHeaders()), String.class);
        drainWorker.drain();

        // Assert
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("guest cancel body=%s", resp.getBody()).isTrue();
        verify(emailNotificationService, never()).sendClientCancelReceiptEmail(anyString(), any());
        assertThat(sentMails).extracting(MimeMessage::getSubject)
                .containsExactly(ClientReceiptEmailExpectedCopy.MASTER_CANCEL_SUBJECT);
    }

    @Test
    @DisplayName("client deletes the account → future booking cancelled, master e-mailed as today, never a receipt")
    void should_sendNoReceipt_when_clientAccountDeleted() throws Exception {
        // Arrange
        insertAppBooking(at(NOW.plus(Duration.ofDays(2))), null);
        HttpHeaders headers = bearerHeaders(tokenFor(client.email()));

        // Act
        ResponseEntity<Void> resp = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);
        drainWorker.drain();

        // Assert
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(emailNotificationService, never()).sendClientCancelReceiptEmail(anyString(), any());
        assertThat(mailsTo(client.email())).isEmpty();
        assertThat(mailsTo(salon.ownerEmail())).extracting(MimeMessage::getSubject)
                .containsExactly(ClientReceiptEmailExpectedCopy.MASTER_CANCEL_SUBJECT);
    }

    @Test
    @DisplayName("confirmation .ics then a client reschedule → the receipt .ics keeps the UID and raises SEQUENCE")
    void should_keepSameUidAndRaiseSequence_when_confirmedThenRescheduled() throws Exception {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofDays(2))), null);
        enqueueConfirmed(bookingId);
        drainWorker.drain();
        String confirmation = onlyIcs(onlyMailTo(client.email()), "REQUEST");
        sentMails.clear();

        // Act
        bookingService.rescheduleBooking(client.id(), Role.CLIENT, bookingId,
                new RescheduleBookingRequest(at(NOW.plus(Duration.ofDays(3))), false));
        drainWorker.drain();

        // Assert
        String rescheduled = onlyIcs(onlyMailTo(client.email()), "REQUEST");
        assertThat(BookingIcsExpectedContent.property(rescheduled, "UID"))
                .isEqualTo(BookingIcsExpectedContent.property(confirmation, "UID"))
                .hasValue(bookingId + "@beautica.app");
        assertThat(BookingIcsExpectedContent.sequence(rescheduled))
                .isGreaterThan(BookingIcsExpectedContent.sequence(confirmation));
    }

    // ── QA additions (B496 audit) ─────────────────────────────────────────────

    @Test
    @DisplayName("client cancels a standalone booking (no visit) → one receipt with a CANCEL .ics + one master e-mail, "
            + "no SMS")
    void should_emailReceiptAndMaster_when_clientCancelsStandaloneBooking() throws Exception {
        // Arrange
        OffsetDateTime start = at(NOW.plus(Duration.ofDays(2)));
        UUID bookingId = insertAppBooking(start, null);

        // Act
        bookingService.cancelBooking(client.id(), bookingId, clientCancel());
        drainWorker.drain();

        // Assert
        MimeMessage receipt = onlyMailTo(client.email());
        assertThat(receipt.getSubject())
                .isEqualTo(ClientReceiptEmailExpectedCopy.cancelReceiptSubject(kyivDay(start), kyivTime(start)));
        assertThat(BookingIcsExpectedContent.lines(onlyIcs(receipt, "CANCEL"))).contains(
                BookingIcsExpectedContent.METHOD_CANCEL, BookingIcsExpectedContent.STATUS_CANCELLED,
                BookingIcsExpectedContent.uidLine(bookingId), "DTSTART:" + utcStamp(start));
        assertThat(onlyMailTo(salon.ownerEmail()).getSubject())
                .isEqualTo(ClientReceiptEmailExpectedCopy.MASTER_CANCEL_SUBJECT);
        assertThat(sentTexts()).as("NTF-D04: never an SMS to a registered client").isEmpty();
    }

    @Test
    @DisplayName("client account deactivated before the drain → master e-mailed, no receipt to the inactive account")
    void should_skipReceipt_when_clientDeactivatedBeforeDrain() throws Exception {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofDays(2))), null);
        bookingService.cancelBooking(client.id(), bookingId, clientCancel());
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", client.id());

        // Act
        drainWorker.drain();

        // Assert
        assertThat(mailsTo(client.email())).as("receipt only to the booking's own ACTIVE client").isEmpty();
        assertThat(onlyMailTo(salon.ownerEmail()).getSubject())
                .isEqualTo(ClientReceiptEmailExpectedCopy.MASTER_CANCEL_SUBJECT);
    }

    @Test
    @DisplayName("reschedule receipt then a cancel receipt → same UID, CANCEL SEQUENCE above the REQUEST one")
    void should_keepUidAndRaiseSequence_when_rescheduledThenCancelled() throws Exception {
        // Arrange — the reschedule is drained with updated_at one hour back, so the later cancel's wall-clock
        // @UpdateTimestamp is strictly later at second granularity (no same-second tie, deterministic)
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofDays(2))), null);
        bookingService.rescheduleBooking(client.id(), Role.CLIENT, bookingId,
                new RescheduleBookingRequest(at(NOW.plus(Duration.ofDays(3))), false));
        jdbcTemplate.update("UPDATE bookings SET updated_at = updated_at - interval '1 hour' WHERE id = ?", bookingId);
        drainWorker.drain();
        String request = onlyIcs(onlyMailTo(client.email()), "REQUEST");
        sentMails.clear();

        // Act
        bookingService.cancelBooking(client.id(), bookingId, clientCancel());
        drainWorker.drain();

        // Assert
        String cancel = onlyIcs(onlyMailTo(client.email()), "CANCEL");
        assertThat(BookingIcsExpectedContent.property(cancel, "UID"))
                .isEqualTo(BookingIcsExpectedContent.property(request, "UID"))
                .hasValue(bookingId + "@beautica.app");
        assertThat(BookingIcsExpectedContent.sequence(cancel))
                .as("CANCEL must outrank the REQUEST it removes")
                .isGreaterThan(BookingIcsExpectedContent.sequence(request));
    }

    @Test
    @DisplayName("client reschedules then cancels before the drain → only the cancel receipt reaches the client, "
            + "never a stale «ви перенесли» REQUEST")
    void should_sendOnlyCancelReceipt_when_clientCancelsBeforeRescheduleDrains() throws Exception {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofDays(2))), null);
        OffsetDateTime newStart = at(NOW.plus(Duration.ofDays(3)));
        bookingService.rescheduleBooking(client.id(), Role.CLIENT, bookingId,
                new RescheduleBookingRequest(newStart, false));
        bookingService.cancelBooking(client.id(), bookingId, clientCancel());

        // Act
        drainWorker.drain();

        // Assert
        MimeMessage only = onlyMailTo(client.email());
        assertThat(only.getSubject())
                .isEqualTo(ClientReceiptEmailExpectedCopy.cancelReceiptSubject(kyivDay(newStart), kyivTime(newStart)));
        assertThat(BookingIcsExpectedContent.lines(onlyIcs(only, "CANCEL")))
                .doesNotContain(BookingIcsExpectedContent.METHOD_REQUEST);
    }

    @Test
    @DisplayName("provider reschedules, client cancels before the drain → no client e-mail carries a REQUEST .ics")
    void should_attachNoRequestIcs_when_providerRescheduleDrainsAfterClientCancel() throws Exception {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofDays(2))), null);
        bookingService.rescheduleBooking(salon.ownerId(), Role.SALON_OWNER, bookingId,
                new RescheduleBookingRequest(at(NOW.plus(Duration.ofDays(3))), false));
        bookingService.cancelBooking(client.id(), bookingId, clientCancel());

        // Act
        drainWorker.drain();

        // Assert
        List<String> methods = new java.util.ArrayList<>();
        for (MimeMessage mail : mailsTo(client.email())) {
            for (Part part : MimeMessageBodies.attachmentsOf(mail)) {
                methods.add(BookingIcsExpectedContent.property(MimeMessageBodies.textOf(part), "METHOD").orElseThrow());
            }
        }
        assertThat(methods).as("a booking that left CONFIRMED before the drain gets no REQUEST")
                .containsExactly("CANCEL");
    }

    @Test
    @DisplayName("service / salon names with ; , and a line break are escaped in SUMMARY; attendee = the client")
    void should_escapeSummaryAndAddressAttendee_when_namesCarrySpecialCharacters() throws Exception {
        // Arrange
        jdbcTemplate.update("UPDATE service_definitions SET name = ? WHERE id = "
                        + "(SELECT service_def_id FROM master_services WHERE id = ?)",
                BookingIcsExpectedContent.HOSTILE_SERVICE_NAME, salon.masterServiceId());
        jdbcTemplate.update("UPDATE salons SET name = ? WHERE id = ?",
                BookingIcsExpectedContent.HOSTILE_SALON_NAME, salon.salonId());
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofDays(2))), null);

        // Act
        bookingService.cancelBooking(client.id(), bookingId, clientCancel());
        drainWorker.drain();

        // Assert
        String ics = onlyIcs(onlyMailTo(client.email()), "CANCEL");
        assertThat(ics.replace(BookingIcsExpectedContent.CRLF, "")).doesNotContain("\n").doesNotContain("\r");
        assertThat(BookingIcsExpectedContent.property(ics, "SUMMARY")).hasValue(
                BookingIcsExpectedContent.ESCAPED_HOSTILE_SERVICE_NAME + BookingIcsExpectedContent.SUMMARY_SEPARATOR
                        + MASTER_NAME + BookingIcsExpectedContent.SUMMARY_SEPARATOR
                        + BookingIcsExpectedContent.ESCAPED_HOSTILE_SALON_NAME);
        assertThat(BookingIcsExpectedContent.property(ics, "ATTENDEE")).hasValue("mailto:" + client.email());
    }

    // ── audit-fix cycle 1: one outbox row per recipient ─────────────────────────

    @Test
    @DisplayName("receipt send fails once → the master e-mail is sent exactly once; the receipt row alone retries "
            + "and is sent on the next drain")
    void should_sendMasterOnceAndRetryReceiptAlone_when_receiptSendFailsOnce() throws Exception {
        // Arrange
        OffsetDateTime start = at(NOW.plus(Duration.ofDays(2)));
        UUID bookingId = insertAppBooking(start, null);
        doThrow(new EmailDeliveryException("email/booking-cancelled-client", true))
                .doAnswer(delegatesTo(realEmails))
                .when(emailNotificationService).sendClientCancelReceiptEmail(anyString(), any());
        bookingService.cancelBooking(client.id(), bookingId, clientCancel());

        // Act
        drainWorker.drain();
        List<MimeMessage> afterFirstDrain = List.copyOf(sentMails);
        drainWorker.drain();

        // Assert
        assertThat(afterFirstDrain).as("first drain: only the master's e-mail, the receipt failed")
                .extracting(MimeMessage::getSubject).containsExactly(ClientReceiptEmailExpectedCopy.MASTER_CANCEL_SUBJECT);
        assertThat(onlyMailTo(salon.ownerEmail()).getSubject())
                .as("the master e-mail is never re-sent by the receipt's retry")
                .isEqualTo(ClientReceiptEmailExpectedCopy.MASTER_CANCEL_SUBJECT);
        assertThat(onlyMailTo(client.email()).getSubject())
                .isEqualTo(ClientReceiptEmailExpectedCopy.cancelReceiptSubject(kyivDay(start), kyivTime(start)));
        assertThat(jdbcTemplate.queryForList("SELECT status || ':' || attempts FROM notification_outbox "
                        + "WHERE aggregate_id = ? AND event_type IN ('CLIENT_CANCELLED', 'CLIENT_CANCEL_RECEIPT') "
                        + "ORDER BY event_type", String.class, bookingId))
                .as("CLIENT_CANCELLED SENT on its first attempt; CLIENT_CANCEL_RECEIPT SENT after one failed attempt")
                .containsExactly("SENT:0", "SENT:1");
    }

    @Test
    @DisplayName("self-reschedule receipt send fails once → the master's reschedule e-mail is sent exactly once; the "
            + "receipt row alone retries and is sent on the next drain")
    void should_sendMasterOnceAndRetryRescheduleReceiptAlone_when_receiptSendFailsOnce() throws Exception {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofDays(2))), null);
        OffsetDateTime newStart = at(NOW.plus(Duration.ofDays(3)));
        doThrow(new EmailDeliveryException("email/booking-rescheduled-client", true))
                .doAnswer(delegatesTo(realEmails))
                .when(emailNotificationService).sendBookingRescheduledClientEmail(anyString(), any(), anyBoolean());
        bookingService.rescheduleBooking(client.id(), Role.CLIENT, bookingId, new RescheduleBookingRequest(newStart, false));

        // Act
        drainWorker.drain();
        int clientMailsAfterFirstDrain = mailsTo(client.email()).size();
        drainWorker.drain();

        // Assert
        assertThat(clientMailsAfterFirstDrain).as("first drain: the receipt failed").isZero();
        assertThat(mailsTo(salon.ownerEmail())).as("the master e-mail is never re-sent by the receipt's retry")
                .hasSize(1);
        assertThat(onlyMailTo(client.email()).getSubject()).isEqualTo(
                ClientReceiptEmailExpectedCopy.selfRescheduleSubject(kyivDay(newStart), kyivTime(newStart)));
    }

    // ── QA addition: per-item CLIENT reschedule (BOOK-05, BOOK-14, NTF-49, NTF-D11) ──────────────

    @Test
    @DisplayName("client moves ONE service of a two-service visit → one «ви перенесли запис» receipt for that booking "
            + "(REQUEST .ics, same UID, raised SEQUENCE), nothing for the sibling, master e-mailed once")
    void should_emailOneReceiptForMovedItemOnly_when_clientReschedulesOneVisitItem() throws Exception {
        // Arrange
        UUID appointmentId = insertAppAppointment();
        OffsetDateTime first = at(NOW.plus(Duration.ofDays(2)));
        UUID firstItem = insertAppBooking(first, appointmentId);
        UUID secondItem = insertAppBooking(first.plusMinutes(30), appointmentId);
        enqueueConfirmed(firstItem);
        drainWorker.drain();
        // the visit confirmation carries one .ics per item — keep the moved item's
        String confirmation = null;
        for (Part part : MimeMessageBodies.attachmentsOf(onlyMailTo(client.email()))) {
            String candidate = MimeMessageBodies.textOf(part);
            if (BookingIcsExpectedContent.property(candidate, "UID").orElseThrow().equals(firstItem + "@beautica.app")) {
                confirmation = candidate;
            }
        }
        assertThat(confirmation).as("the confirmation carries the moved item's .ics").isNotNull();
        sentMails.clear();
        OffsetDateTime newStart = at(NOW.plus(Duration.ofDays(3)));

        // Act
        appointmentTransitionService.rescheduleAppointmentItem(client.id(), Role.CLIENT, appointmentId, firstItem,
                new AppointmentItemRescheduleRequest(newStart, false));
        drainWorker.drain();

        // Assert
        MimeMessage receipt = onlyMailTo(client.email());
        assertThat(receipt.getSubject()).isEqualTo(
                ClientReceiptEmailExpectedCopy.selfRescheduleSubject(kyivDay(newStart), kyivTime(newStart)));
        assertThat(MimeMessageBodies.htmlBodyOf(receipt)).contains(ClientReceiptEmailExpectedCopy.SELF_RESCHEDULE_LEAD);
        String ics = onlyIcs(receipt, "REQUEST");
        assertThat(BookingIcsExpectedContent.lines(ics)).contains(BookingIcsExpectedContent.METHOD_REQUEST,
                BookingIcsExpectedContent.uidLine(firstItem), "DTSTART:" + utcStamp(newStart));
        assertThat(BookingIcsExpectedContent.property(ics, "UID"))
                .isEqualTo(BookingIcsExpectedContent.property(confirmation, "UID"));
        assertThat(BookingIcsExpectedContent.sequence(ics))
                .as("the receipt REQUEST must outrank the confirmation it replaces")
                .isGreaterThan(BookingIcsExpectedContent.sequence(confirmation));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox WHERE aggregate_id = ?",
                Integer.class, secondItem)).as("the unmoved sibling gets no outbox row").isZero();
        assertThat(status(secondItem)).isEqualTo("CONFIRMED");
        assertThat(mailsTo(salon.ownerEmail())).as("the performing master's reschedule e-mail, once").hasSize(1);
        assertThat(sentTexts()).as("NTF-D04: never an SMS to a registered client").isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static CancelBookingRequest clientCancel() {
        return new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null);
    }

    /** The CONFIRMED status-change row a create writes (BookingService), enqueued through the real outbox service. */
    private void enqueueConfirmed(UUID bookingId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> outboxService.enqueueStatusChanged(bookingId));
    }

    /**
     * A CONFIRMED 30-min APP booking of {@link #client} with the fixture salon master; created/updated two days before
     * {@link #NOW}, so every later write raises {@code updated_at} (the .ics SEQUENCE source).
     */
    private UUID insertAppBooking(OffsetDateTime startsAt, UUID appointmentId) {
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(2)), ZoneOffset.UTC);
        jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, salon_id, client_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, appointment_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 30, 0, 'APP', ?, ?, ?)",
                bookingId, salon.masterId(), salon.masterServiceId(), salon.salonId(), client.id(), startsAt,
                startsAt.plusMinutes(30), appointmentId, created, created);
        return bookingId;
    }

    /** A CONFIRMED APP visit header for {@link #client}; items attach through {@code bookings.appointment_id}. */
    private UUID insertAppAppointment() {
        UUID appointmentId = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(2)), ZoneOffset.UTC);
        jdbcTemplate.update("INSERT INTO appointments (id, client_id, salon_id, status, booking_source, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'CONFIRMED', 'APP', ?, ?)",
                appointmentId, client.id(), salon.salonId(), created, created);
        return appointmentId;
    }

    private String status(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private List<MimeMessage> mailsTo(String email) {
        return sentMails.stream().filter(m -> recipients(m).contains(email)).toList();
    }

    private MimeMessage onlyMailTo(String email) {
        List<MimeMessage> mails = mailsTo(email);
        assertThat(mails).as("exactly one e-mail to %s", email).hasSize(1);
        return mails.get(0);
    }

    private static List<String> recipients(MimeMessage mail) {
        try {
            return java.util.Arrays.stream(mail.getAllRecipients()).map(Object::toString).toList();
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The mail's single .ics, after asserting its Content-Type carries {@code method}. */
    private static String onlyIcs(MimeMessage mail, String method) throws Exception {
        List<Part> attachments = MimeMessageBodies.attachmentsOf(mail);
        assertThat(attachments).as("exactly one .ics").hasSize(1);
        assertThat(attachments.get(0).getContentType())
                .startsWith(BookingIcsExpectedContent.CONTENT_TYPE_PREFIX)
                .containsIgnoringCase("method=" + method);
        return MimeMessageBodies.textOf(attachments.get(0));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atZone(TimeZones.KYIV).toOffsetDateTime();
    }

    private static String kyivDay(OffsetDateTime t) {
        return t.atZoneSameInstant(TimeZones.KYIV).format(DAY);
    }

    private static String kyivTime(OffsetDateTime t) {
        return t.atZoneSameInstant(TimeZones.KYIV).format(HH_MM);
    }

    private static String utcStamp(OffsetDateTime t) {
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(t.withOffsetSameInstant(ZoneOffset.UTC));
    }

    private static HttpHeaders ipHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", PublicBookingPageFixtures.randomIp());
        return headers;
    }
}
