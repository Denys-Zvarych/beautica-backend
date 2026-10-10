package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB coverage for {@code BookingService#disposeFutureConfirmedForMasterSelfDelete}'s Phase
 * 337 behaviour — the reversal of Phase 301 Q3's decline-then-hard-delete. Every future {@code
 * CONFIRMED} booking of a self-deleting {@code SALON_MASTER}/{@code INDEPENDENT_MASTER} is now
 * DECLINED and KEPT, exactly like the owner-initiated {@code
 * declineFutureConfirmedBookingsForMasterRemoval} cascade ({@code MasterRemovalIT} is this class's
 * direct sibling — same D12 one-row-per-visit outbox contract, same fixture conventions), with one
 * {@code MASTER_REMOVED} outbox row per affected VISIT.
 *
 * <p>{@code MasterSelfDeleteBookingDisposalIT} covers the header-survivorship/review-direction
 * domain assertions this class deliberately does NOT repeat; this class is scoped to the outbox
 * D12 contract and the DB-level slot-overlap invariant Phase 337's doc calls out explicitly. The
 * future-booking CAP boundary (500/501) and the lock-acquired-before-read ordering are already
 * pinned at the unit level by {@code StaffAccountSelfDeletionServiceTest} against a mocked {@code
 * BookingService} — not re-proven here against 501 real rows, which would only re-test Postgres.
 */
@DisplayName("BookingService.disposeFutureConfirmedForMasterSelfDelete — Phase 337 decline-and-keep")
class MasterSelfDeletionFutureBookingsIT extends AbstractIntegrationTest {

    private static final OffsetDateTime FUTURE = OffsetDateTime.now().plusDays(5);
    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(3);
    private static final String GUEST_PHONE = "+380501112233";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER: a future CONFIRMED booking is DECLINED/PROVIDER_UNAVAILABLE "
            + "and KEPT, with exactly one MASTER_REMOVED outbox row keyed to it")
    void should_declineAndKeepFutureBookings_when_independentMasterDeletesAccount() throws Exception {
        String email = "im-337-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        UUID clientId = csd.createClient();
        UUID bookingId = csd.insertBooking(clientId, masterId, masterServiceId, null, "CONFIRMED", FUTURE);
        String token = fixtures.tokenFor(email);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(bookingId)).isTrue();
        assertThat(bookingStatus(bookingId)).isEqualTo("DECLINED");
        assertThat(bookingCancellationReason(bookingId)).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(masterRemovedAggregateIds()).containsExactly(bookingId);
    }

    @Test
    @DisplayName("SALON_MASTER: a future CONFIRMED booking is DECLINED/PROVIDER_UNAVAILABLE and "
            + "KEPT, with exactly one MASTER_REMOVED outbox row keyed to it")
    void should_declineAndKeepFutureBookings_when_salonMasterDeletesAccount() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID bookingId = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(bookingId)).isTrue();
        assertThat(bookingStatus(bookingId)).isEqualTo("DECLINED");
        assertThat(bookingCancellationReason(bookingId)).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(masterRemovedAggregateIds()).containsExactly(bookingId);
    }

    @Test
    @DisplayName("a 2-service future visit: both legs DECLINED and KEPT, the header itself DECLINED "
            + "and KEPT (never collapsed), but exactly ONE MASTER_REMOVED outbox row — keyed to the "
            + "lowest-startsAt leg (D12), mirroring MasterRemovalIT's owner-driven case 2")
    void should_enqueueOneMasterRemovedPerVisit_when_multiServiceVisit() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID appointmentId = csd.insertAppointmentHeader(clientId, salon.salonId(), "CONFIRMED");
        UUID first = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE, appointmentId);
        UUID second = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE.plusHours(1), appointmentId);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bookingStatus(first)).isEqualTo("DECLINED");
        assertThat(bookingStatus(second)).isEqualTo("DECLINED");
        assertThat(csd.appointmentExists(appointmentId))
                .as("Phase 337 — every leg still exists, so the header is never collapsed/deleted")
                .isTrue();
        assertThat(appointmentStatus(appointmentId)).isEqualTo("DECLINED");
        assertThat(masterRemovedAggregateIds())
                .as("one entry per VISIT (D12): a 2-service visit collapses to ONE entry, keyed to "
                        + "the lowest-startsAt leg")
                .containsExactly(first);
    }

    @Test
    @DisplayName("two masters each with their own visit (V190: one master per visit): only the "
            + "deleting master's visit is DECLINED, the other master's leg and header stay CONFIRMED, "
            + "and exactly ONE MASTER_REMOVED outbox row is enqueued — complements "
            + "MasterSelfDeleteBookingDisposalIT's identical booking/header assertions with the D12 "
            + "outbox-row-count angle that class deliberately does not cover")
    void should_declineOnlyOwnVisit_andEnqueueExactlyOneOutboxRow_when_otherMasterHasSeparateVisit() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        ClientSelfDeleteTestFixtures.SecondMaster masterB = csd.addSecondMaster(salon);
        UUID clientId = csd.createClient();
        // V190: a visit has a single master, so masterB's leg lives in its own appointment header.
        UUID appointmentAId = csd.insertAppointmentHeader(clientId, salon.salonId(), "CONFIRMED");
        UUID appointmentBId = csd.insertAppointmentHeader(clientId, salon.salonId(), "CONFIRMED");
        UUID masterALegId = csd.insertBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                "CONFIRMED", FUTURE, appointmentAId);
        UUID masterBLegId = csd.insertBooking(
                clientId, masterB.masterId(), masterB.masterServiceId(), salon.salonId(),
                "CONFIRMED", FUTURE.plusHours(1), appointmentBId);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bookingStatus(masterALegId)).isEqualTo("DECLINED");
        assertThat(bookingStatus(masterBLegId))
                .as("masterB never self-deleted — their leg is untouched")
                .isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(appointmentBId))
                .as("masterB's own visit is untouched — header stays CONFIRMED")
                .isEqualTo("CONFIRMED");
        assertThat(appointmentStatus(appointmentAId)).isEqualTo("DECLINED");
        assertThat(masterRemovedAggregateIds())
                .as("exactly one MASTER_REMOVED row (D12) — masterA's visit only; masterB's visit "
                        + "is not part of this master's cascade at all")
                .containsExactly(masterALegId);
    }

    @Test
    @DisplayName("a past booking of the departing master is left completely untouched, and enqueues "
            + "no MASTER_REMOVED entry at all")
    void should_leavePastBookingsUntouched() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID pastBookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bookingStatus(pastBookingId)).isEqualTo("COMPLETED");
        assertThat(masterRemovedAggregateIds()).isEmpty();
    }

    @Test
    @DisplayName("the DECLINED-and-kept row never blocks a new booking at the same master/time slot "
            + "— no_overlapping_bookings (V113) is scoped WHERE status = 'CONFIRMED', so a kept "
            + "DECLINED row falls outside it")
    void should_notBlockSlot_when_declinedRowKept() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID bookingId = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bookingStatus(bookingId)).isEqualTo("DECLINED");

        // A brand-new CONFIRMED row for a DIFFERENT client, same master, same time range — a raw
        // INSERT throws (DataIntegrityViolationException, EXCLUDE violation) if the kept DECLINED
        // row were still inside no_overlapping_bookings' predicate. The master row is DETACHED at
        // this point (bookings.master_id, master_services.id both still live, is_active=false), but
        // the EXCLUDE constraint itself has no is_active/attachment condition at all.
        UUID otherClientId = csd.createClient();
        UUID newBookingId = csd.insertBooking(otherClientId, salon, "CONFIRMED", FUTURE);

        assertThat(csd.bookingExists(newBookingId)).isTrue();
        assertThat(bookingStatus(newBookingId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("a guest (LINK) future booking is ALSO declined and KEPT, and still gets one "
            + "MASTER_REMOVED outbox row like every other visit (D12 does not special-case guests) "
            + "— the phase doc's 'none for guest bookings is fine' is about DELIVERY, which the "
            + "drain already no-ops for a null client, not about whether the row is written")
    void should_declineAndKeepGuestBooking_andEnqueueOneRowRegardless() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID guestBookingId = insertConfirmedGuestBooking(salon);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(guestBookingId)).isTrue();
        assertThat(bookingStatus(guestBookingId)).isEqualTo("DECLINED");
        assertThat(bookingCancellationReason(guestBookingId)).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(masterRemovedAggregateIds()).containsExactly(guestBookingId);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private String bookingStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String bookingCancellationReason(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    /** Every {@code MASTER_REMOVED} outbox aggregate id enqueued so far — mirrors MasterRemovalIT. */
    private List<UUID> masterRemovedAggregateIds() {
        return jdbcTemplate.queryForList(
                "SELECT aggregate_id FROM notification_outbox WHERE event_type = 'MASTER_REMOVED'",
                UUID.class);
    }

    /**
     * Seeds a CONFIRMED guest (LINK) booking directly against the salon's own master: {@code
     * client_id} NULL, {@code booking_source = 'LINK'}, guest fields populated, and a non-null
     * {@code cancel_token} (V91 CHECK requires it for any ACTIVE — i.e. CONFIRMED — LINK row) —
     * mirrors {@code GuestBookingDeclineNotificationIT#insertConfirmedGuestSalonBooking}.
     */
    private UUID insertConfirmedGuestBooking(ClientSelfDeleteTestFixtures.Salon salon) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, guest_name, guest_surname, "
                        + "guest_phone, cancel_token, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'LINK', 'Гість', "
                        + "'Тестовий', ?, ?, NOW(), NOW())",
                bookingId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                FUTURE, FUTURE.plusMinutes(60), GUEST_PHONE, UUID.randomUUID());
        return bookingId;
    }
}
