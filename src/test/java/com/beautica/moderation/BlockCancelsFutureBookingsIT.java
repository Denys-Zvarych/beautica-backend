package com.beautica.moderation;

import com.beautica.booking.service.BookingSmsDispatcher;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.support.BookableMasterSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 398 user decision (2026-10-28) — a NEW block declines every future {@code CONFIRMED} booking / visit of the
 * blocked client in the block scope, against real Postgres and the real HTTP stack: independent-master scope,
 * salon scope across two salon masters (single booking + whole visit), guest by OTP-verified phone, registered by
 * account. Started / completed bookings, other clients, other providers and the other identity type are untouched;
 * the client gets the neutral provider-decline notice (outbox {@code STATUS_CHANGED} + in-app
 * {@code BOOKING_DECLINED}; guest SMS through the real drain → {@link BookingSmsDispatcher}); the performing master
 * gets the same in-app {@code BOOKING_DECLINED} an ordinary provider decline sends them, unless they are the actor;
 * an idempotent repeat cancels nothing; {@code cancelledBookings} counts VISITS (orchestrator decision 2026-10-28).
 *
 * <p>{@link BookingSmsDispatcher} is a {@code @MockBean} — the assertion is "the provider-cancel SMS was handed to
 * the dispatcher for this phone", not the Turbosms wire (covered by {@code GuestProviderCancellationSmsIT}).
 */
@DisplayName("A new block cancels the client's future bookings in scope (phase 398 IT)")
class BlockCancelsFutureBookingsIT extends AbstractBookingBlockIT {

    private static final String GUEST_PHONE = "+380671234511";
    private static final String OTHER_PHONE = "+380671234512";

    @MockBean
    private BookingSmsDispatcher bookingSmsDispatcher;

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Test
    @DisplayName("independent master: a client block declines that client's future bookings with this master "
            + "only — neutral notice, count returned; the past source booking and another master's booking stay")
    void should_declineFutureBookings_when_independentMasterBlocksClient() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID service = makeIndependentBookable(master.masterId());
        IndependentMaster other = createIndependentMaster(unique("indep-other"));
        UUID otherService = makeIndependentBookable(other.masterId());
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        String clientToken = tokenFor(clientEmail);
        UUID future1 = createdId(appBook(clientToken, master.masterId(), service, slot(3, 10)));
        UUID future2 = createdId(appBook(clientToken, master.masterId(), service, slot(4, 10)));
        UUID otherProvider = createdId(appBook(clientToken, other.masterId(), otherService, slot(5, 10)));
        UUID source = insertIndependentBooking(clientId, master.masterId());
        int statusChangedBefore1 = outboxRows("STATUS_CHANGED", future1);
        int statusChangedBefore2 = outboxRows("STATUS_CHANGED", future2);

        ResponseEntity<String> resp = postBlock(tokenFor(emailOf(master.userId())), source);

        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(resp).path("cancelledBookings").asInt()).isEqualTo(2);
        assertDeclinedByProvider(future1);
        assertDeclinedByProvider(future2);
        assertThat(statusOf(otherProvider)).isEqualTo("CONFIRMED");
        assertThat(statusOf(source)).isEqualTo("COMPLETED");
        assertThat(outboxRows("STATUS_CHANGED", future1))
                .as("one neutral decline notice per booking (create already enqueued its confirmation)")
                .isEqualTo(statusChangedBefore1 + 1);
        assertThat(outboxRows("STATUS_CHANGED", future2)).isEqualTo(statusChangedBefore2 + 1);
        assertThat(inAppRows(clientId, "BOOKING_DECLINED")).isEqualTo(2);
        assertThat(inAppRows(master.userId(), "BOOKING_DECLINED"))
                .as("the performing master IS the actor — excluded, exactly like PATCH /decline")
                .isZero();
        assertThat(resp.getBody()).doesNotContainIgnoringCase("заблок");
    }

    @Test
    @DisplayName("salon: an owner's client block declines the client's future booking with master A and the "
            + "WHOLE visit with master B; an in-progress booking, another client's booking stay CONFIRMED")
    void should_declineAcrossSalonMasters_when_salonBlocksClient() throws Exception {
        SalonFixture salon = createSalon();
        UUID serviceA = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        UUID masterB = BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, salon.salonId());
        UUID firstB = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ?", UUID.class, masterB);
        UUID secondB = assignmentOf(BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon.salonId(), masterB));
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        String clientToken = tokenFor(clientEmail);
        String otherEmail = unique("client-other");
        createUser(otherEmail, "CLIENT", null);
        UUID singleA = createdId(appBook(clientToken, salon.masterId(), serviceA, slot(3, 10)));
        UUID visitB = createdId(appVisit(clientToken, masterB, List.of(firstB, secondB), slot(4, 10)));
        UUID otherClient = createdId(appBook(tokenFor(otherEmail), salon.masterId(), serviceA, slot(3, 14)));
        UUID inProgress = insertBooking(clientId, salon, "CONFIRMED"); // started 2 h ago — not "future"
        UUID source = insertCompletedBooking(clientId, salon);

        ResponseEntity<String> resp = postBlock(tokenFor(salon.ownerEmail()), source);

        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(resp).path("cancelledBookings").asInt())
                .as("counts visits: the single booking + the 2-item visit")
                .isEqualTo(2);
        assertDeclinedByProvider(singleA);
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM bookings WHERE appointment_id = ?", String.class, visitB))
                .containsExactly("DECLINED", "DECLINED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, visitB)).isEqualTo("DECLINED");
        assertThat(statusOf(inProgress)).isEqualTo("CONFIRMED");
        assertThat(statusOf(otherClient)).isEqualTo("CONFIRMED");
        assertThat(statusOf(source)).isEqualTo("COMPLETED");
        assertThat(inAppRows(clientId, "BOOKING_DECLINED"))
                .as("one in-app notice per visit: the single booking + the visit")
                .isEqualTo(2);
        assertThat(inAppRows(userOfMaster(salon.masterId()), "BOOKING_DECLINED"))
                .as("performing master A gets the ordinary provider-decline notice for their booking")
                .isEqualTo(1);
        assertThat(inAppRows(userOfMaster(masterB), "BOOKING_DECLINED"))
                .as("performing master B gets ONE notice for the whole visit")
                .isEqualTo(1);
        assertThat(inAppRows(userIdOf(salon.ownerEmail()), "BOOKING_DECLINED"))
                .as("the acting owner (not a performer here) gets nothing")
                .isZero();
    }

    @Test
    @DisplayName("salon guest block: declines that phone's future LINK booking and SMSes the guest the neutral "
            + "provider-cancel text; another phone and an APP client with the same profile phone stay CONFIRMED")
    void should_declineGuestBookingAndSendSms_when_salonBlocksGuestPhone() throws Exception {
        SalonFixture salon = createSalon();
        UUID service = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        String salonSlug = setSalonSlug(salon.salonId());
        UUID guestFuture = guestBookingId(guestBookSalon(guestTokenProvider.generate(GUEST_PHONE), salonSlug,
                salon.masterId(), List.of(service), slot(3, 10)));
        UUID otherPhone = guestBookingId(guestBookSalon(guestTokenProvider.generate(OTHER_PHONE), salonSlug,
                salon.masterId(), List.of(service), slot(3, 12)));
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        setProfilePhone(clientId, GUEST_PHONE);
        UUID appSamePhone = createdId(appBook(tokenFor(clientEmail), salon.masterId(), service, slot(3, 14)));
        UUID source = insertGuestBooking(salon, "LINK", GUEST_PHONE);

        ResponseEntity<String> resp = postBlock(tokenFor(salon.ownerEmail()), source);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(resp).path("cancelledBookings").asInt()).isEqualTo(1);
        assertDeclinedByProvider(guestFuture);
        assertThat(statusOf(otherPhone)).isEqualTo("CONFIRMED");
        assertThat(statusOf(appSamePhone)).isEqualTo("CONFIRMED");
        assertThat(inAppRows(userOfMaster(salon.masterId()), "BOOKING_DECLINED"))
                .as("the performing master is notified of the declined guest booking, like PATCH /decline")
                .isEqualTo(1);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(bookingSmsDispatcher).dispatchOrThrow(
                eq(BookingSmsDispatcher.Kind.GUEST_PROVIDER_CANCELLATION), eq(GUEST_PHONE), text.capture());
        assertThat(text.getValue()).startsWith("Beautica: Ваш запис ").contains("скасовано")
                .doesNotContainIgnoringCase("блок");
        verify(bookingSmsDispatcher, never()).dispatchOrThrow(
                eq(BookingSmsDispatcher.Kind.GUEST_PROVIDER_CANCELLATION), eq(OTHER_PHONE), anyString());
    }

    @Test
    @DisplayName("idempotent repeat (200): cancels nothing — a CONFIRMED future booking that slipped in after the "
            + "block stays CONFIRMED and the count is 0")
    void should_cancelNothing_when_blockRepeated() throws Exception {
        SalonFixture salon = createSalon();
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        UUID source = insertCompletedBooking(clientId, salon);
        String ownerToken = tokenFor(salon.ownerEmail());
        ResponseEntity<String> first = postBlock(ownerToken, source);
        UUID lateBooking = insertBooking(clientId, salon, "CONFIRMED");
        jdbcTemplate.update("UPDATE bookings SET starts_at = NOW() + interval '3 days', "
                + "ends_at = NOW() + interval '3 days 1 hour' WHERE id = ?", lateBooking);
        int statusChangedBefore = outboxRows("STATUS_CHANGED", lateBooking);

        ResponseEntity<String> repeat = postBlock(ownerToken, source);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(first).path("cancelledBookings").asInt()).isZero();
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(repeat).path("cancelledBookings").asInt()).isZero();
        assertThat(statusOf(lateBooking)).isEqualTo("CONFIRMED");
        assertThat(outboxRows("STATUS_CHANGED", lateBooking)).isEqualTo(statusChangedBefore);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** {@code data.id} of an APP create: the booking id ({@code POST /bookings}) or the visit id ({@code /appointments}). */
    private UUID createdId(ResponseEntity<String> resp) {
        assertCreated(resp);
        return UUID.fromString(data(resp).path("id").asText());
    }

    /** A LINK create answers an unwrapped {@code GuestBookingResponse}; its {@code bookingId} is the booking row. */
    private UUID guestBookingId(ResponseEntity<String> resp) {
        assertCreated(resp);
        return UUID.fromString(readTree(resp).path("bookingId").asText());
    }

    private void assertDeclinedByProvider(UUID bookingId) {
        var row = jdbcTemplate.queryForMap(
                "SELECT status, cancellation_reason, provider_comment FROM bookings WHERE id = ?", bookingId);
        assertThat(row.get("status")).isEqualTo("DECLINED");
        assertThat(row.get("cancellation_reason")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(row.get("provider_comment")).isNull();
    }

    private String statusOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private int outboxRows(String eventType, UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, bookingId);
    }

    private int inAppRows(UUID recipientId, String type) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE recipient_user_id = ? AND type = ?",
                Integer.class, recipientId, type);
    }

    private UUID userOfMaster(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private UUID userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
