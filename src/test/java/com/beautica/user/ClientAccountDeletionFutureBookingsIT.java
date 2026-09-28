package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.common.TimeZones;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB coverage for Phase 338: a self-deleting CLIENT's future {@code CONFIRMED} bookings are
 * now cancelled and KEPT (detached with the sentinel), never physically deleted — one visit, one
 * {@code CLIENT_CANCELLED} outbox row. See {@code
 * docs/backend-phases/phase-338-client-self-delete-keeps-cancelled-future-bookings.md}'s test plan.
 *
 * <p>Sibling of {@link ClientAccountHardDeleteIT} / {@link ClientDetachCoherenceIT} /
 * {@link ClientAccountSelfDeleteOutboxCoherenceIT}, which each already cover one slice of this
 * behaviour (the single-booking cancel-and-keep path, the CHECK-constraint coherence, and the
 * outbox supersession respectively) — this class is the END-TO-END proof across every visit shape
 * the doc's test plan names: an independent-master booking, a salon booking (with the provider-side
 * PII-hidden read), a multi-service visit (one outbox row for the WHOLE visit), a past booking left
 * untouched, and the freed slot becoming bookable again by a different client.
 */
@DisplayName("DELETE /api/v1/users/me — Phase 338 future-booking keep-and-cancel cascade, end to end")
class ClientAccountDeletionFutureBookingsIT extends AbstractIntegrationTest {

    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

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
    @DisplayName("independent-master booking: cancelled and KEPT, detached with the sentinel, "
            + "exactly one CLIENT_CANCELLED outbox row (no surviving STATUS_CHANGED); a past booking "
            + "of the SAME client is left completely untouched by status")
    void should_cancelAndKeepFutureBooking_withOneClientCancelledRow_andLeavePastBookingUnchanged()
            throws Exception {
        String masterEmail = "iafb-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);

        String clientEmail = "iafb-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(5).withHour(11).withMinute(0).withSecond(0).withNano(0);
        UUID futureBookingId = postBooking(clientToken, masterId, masterServiceId, startsAt);
        // Creating the booking itself enqueues NEW_BOOKING + STATUS_CHANGED (BookingService
        // #doCreateBooking) — noise for THIS test, which measures only what self-delete's own
        // cancel cascade enqueues. Dropped here rather than counted, mirroring the established
        // dropCreateTimeNotifications convention (BookingTestFixtures) for the appointment-scoped
        // sibling below — this is the equivalent for a single standalone booking, which has no
        // appointment id to key that helper off of. Since Phase 338's fix (item 1/3) means
        // self-delete no longer deletes ANYTHING from the outbox, these create-time rows would
        // otherwise survive self-delete untouched and inflate this test's count — which is CORRECT
        // production behaviour (proven directly by ClientAccountSelfDeleteOutboxCoherenceIT's own
        // pre-existing-PENDING-row assertion), just not what THIS test is measuring.
        dropOutboxRowsFor(futureBookingId);

        UUID pastBookingId = jdbcTemplate.queryForObject(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (gen_random_uuid(), ?, ?, ?, 'COMPLETED', ?, ?, 500.00, 60, 0, 'APP', "
                        + "NOW(), NOW()) RETURNING id",
                UUID.class, clientId, masterId, masterServiceId, PAST, PAST.plusMinutes(60));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The future booking is KEPT — cancelled, detached with the sentinel.
        assertThat(bookingExists(futureBookingId)).isTrue();
        assertThat(bookingStatusOf(futureBookingId)).isEqualTo("CANCELLED");
        assertThat(clientIdOf(futureBookingId)).isNull();
        assertThat(guestNameOf(futureBookingId)).isEqualTo("Видалений клієнт");
        assertThat(cancellationReasonOf(futureBookingId)).isEqualTo("CLIENT_CANCELLED");

        // Exactly one outbox row for the future booking, and it is CLIENT_CANCELLED — the batched
        // self-delete cancel path never writes a per-booking STATUS_CHANGED row at all (perf audit
        // item 3), so there is nothing to supersede here.
        assertThat(countByAggregateId(futureBookingId)).isEqualTo(1);
        assertThat(eventTypeOf(futureBookingId)).isEqualTo("CLIENT_CANCELLED");

        // The past booking is untouched in status, only detached (Phase 300, unchanged by 338).
        assertThat(bookingStatusOf(pastBookingId)).isEqualTo("COMPLETED");
        assertThat(clientIdOf(pastBookingId)).isNull();
        assertThat(guestNameOf(pastBookingId)).isEqualTo("Видалений клієнт");
    }

    @Test
    @DisplayName("salon booking: the kept, cancelled future booking exposes NO client PII on the "
            + "provider's own booking read, and carries exactly one CLIENT_CANCELLED outbox row")
    void should_hideClientPiiOnProviderRead_andEnqueueOneClientCancelled_when_salonBookingClientSelfDeletes()
            throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        UUID futureBookingId = csd.insertBooking(clientId, salon, "CONFIRMED",
                OffsetDateTime.now().plusDays(5));

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class);

        assertThat(bookingExists(futureBookingId)).isTrue();
        assertThat(bookingStatusOf(futureBookingId)).isEqualTo("CANCELLED");
        assertThat(countByAggregateId(futureBookingId)).isEqualTo(1);
        assertThat(eventTypeOf(futureBookingId)).isEqualTo("CLIENT_CANCELLED");

        String ownerToken = fixtures.tokenFor(emailOf(salon.ownerId()));
        ResponseEntity<String> detail = restTemplate.exchange(
                "/api/v1/bookings/" + futureBookingId, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), String.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(detail.getBody()).path("data");
        assertThat(data.path("clientId").isNull())
                .as("clientId must be null on the wire — never a fabricated id")
                .isTrue();
        assertThat(data.path("clientFirstName").asText())
                .as("the sentinel, never the real client name")
                .isEqualTo("Видалений клієнт");
        assertThat(data.path("clientLastName").isNull()).isTrue();
        assertThat(detail.getBody())
                .as("no raw phone/email of the deleted client leaks onto the provider's read")
                .doesNotContain("@beautica.test");
    }

    @Test
    @DisplayName("multi-service visit: every leg is cancelled and KEPT, but exactly ONE "
            + "CLIENT_CANCELLED outbox row covers the WHOLE visit — never one per leg")
    void should_enqueueExactlyOneClientCancelledForWholeVisit_when_multiServiceVisitClientSelfDeletes()
            throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("mvfb", 2);
        List<UUID> legIds = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                UUID.class, visit.id());
        assertThat(legIds).hasSize(2);
        // Visit CREATE itself enqueues NEW_BOOKING + STATUS_CHANGED against the lead booking
        // (BookingTestFixtures#dropCreateTimeNotifications) — noise for this test, which measures
        // only self-delete's own cascade. Since Phase 338's fix (item 1/3) means self-delete no
        // longer deletes anything from the outbox, these would otherwise survive untouched and
        // inflate the STATUS_CHANGED-survivor assertion below.
        fixtures.dropCreateTimeNotifications(visit.id());

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(visit.clientToken())), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Every leg is KEPT, cancelled and detached.
        for (UUID legId : legIds) {
            assertThat(bookingExists(legId)).isTrue();
            assertThat(bookingStatusOf(legId)).isEqualTo("CANCELLED");
            assertThat(clientIdOf(legId)).isNull();
        }
        // The appointment header itself is kept and detached, never collapsed to childless.
        assertThat(appointmentExists(visit.id())).isTrue();
        assertThat(appointmentClientIdOf(visit.id())).isNull();

        // Exactly ONE CLIENT_CANCELLED row across the WHOLE visit's two legs — never two.
        Integer outboxRowsForVisit = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE aggregate_id IN (?, ?) "
                        + "AND event_type = 'CLIENT_CANCELLED'",
                Integer.class, legIds.get(0), legIds.get(1));
        assertThat(outboxRowsForVisit)
                .as("D12 — one CLIENT_CANCELLED row per VISIT, never per leg")
                .isEqualTo(1);
        Integer statusChangedSurvivors = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE aggregate_id IN (?, ?) "
                        + "AND event_type = 'STATUS_CHANGED'",
                Integer.class, legIds.get(0), legIds.get(1));
        assertThat(statusChangedSurvivors)
                .as("no per-leg STATUS_CHANGED row exists at all — the batched self-delete cancel "
                        + "path never enqueues one (perf audit item 3)")
                .isZero();
    }

    @Test
    @DisplayName("the freed slot is bookable again by a DIFFERENT client once the self-deleting "
            + "client's future booking is cancelled — the no_overlapping_bookings EXCLUDE is "
            + "CONFIRMED-scoped, so a CANCELLED row never blocks a new one")
    void should_freeSlot_when_futureBookingKeptCancelled() throws Exception {
        String masterEmail = "freeslot-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);

        String clientAEmail = "freeslot-clienta-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientAEmail, "CLIENT", null);
        String clientAToken = fixtures.tokenFor(clientAEmail);

        String clientBEmail = "freeslot-clientb-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientBEmail, "CLIENT", null);
        String clientBToken = fixtures.tokenFor(clientBEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(6).withHour(14).withMinute(0).withSecond(0).withNano(0);
        postBooking(clientAToken, masterId, masterServiceId, startsAt);

        // Control — the slot is genuinely taken before the self-delete: a second client's request
        // for the SAME window is rejected.
        ResponseEntity<String> blocked = postBookingRaw(clientBToken, masterId, masterServiceId, startsAt, null);
        assertThat(blocked.getStatusCode())
                .as("control — the slot is taken before clientA self-deletes")
                .isEqualTo(HttpStatus.CONFLICT);

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientAToken)), Void.class);

        ResponseEntity<String> freed = postBookingRaw(clientBToken, masterId, masterServiceId, startsAt, null);
        assertThat(freed.getStatusCode())
                .as("the slot must be bookable again once clientA's booking is CANCELLED — the "
                        + "EXCLUDE constraint is scoped to status = 'CONFIRMED' only")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("the 50-booking self-delete cap still rejects with 422 BEFORE any write, even "
            + "though future bookings are now kept rather than deleted")
    void should_reject422BeforeAnyWrite_when_futureBookingCountExceedsCapEvenThoughTheyAreKept()
            throws Exception {
        UUID clientId = csd.createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();

        // Distinct, non-overlapping start times (spaced an hour apart) — the master's own
        // no_overlapping_bookings EXCLUDE constraint (V113) rejects two CONFIRMED rows on the
        // SAME master whose [starts_at, ends_at) windows collide, so a single shared "now + 10
        // days" instant reused 51 times would itself violate that constraint before the cap is
        // ever reached.
        OffsetDateTime base = OffsetDateTime.now().plusDays(10);
        List<UUID> bookingIds = IntStream.range(0, 51)
                .mapToObj(i -> csd.insertBooking(clientId, salon, "CONFIRMED", base.plusHours(i)))
                .toList();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(csd.userExists(clientId))
                .as("the cap fails before any write — the user row must still exist")
                .isTrue();
        for (UUID bookingId : bookingIds) {
            assertThat(bookingStatusOf(bookingId))
                    .as("no booking may have been mutated once the cap rejects the whole call")
                    .isEqualTo("CONFIRMED");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID postBooking(String clientToken, UUID masterId, UUID masterServiceId, ZonedDateTime startsAt)
            throws Exception {
        ResponseEntity<String> created =
                postBookingRaw(clientToken, masterId, masterServiceId, startsAt, null);
        assertThat(created.getStatusCode())
                .as("booking setup must succeed — body: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(created.getBody()).path("data").path("id").asText());
    }

    private ResponseEntity<String> postBookingRaw(
            String clientToken, UUID masterId, UUID masterServiceId, ZonedDateTime startsAt, String idempotencyKey)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "masterId", masterId.toString(),
                "masterServiceId", masterServiceId.toString(),
                "startsAt", startsAt.toOffsetDateTime().toString()));
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/api/v1/bookings", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private boolean bookingExists(UUID bookingId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE id = ?", Integer.class, bookingId);
        return count != null && count == 1;
    }

    private boolean appointmentExists(UUID appointmentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE id = ?", Integer.class, appointmentId);
        return count != null && count == 1;
    }

    private String bookingStatusOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private UUID clientIdOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT client_id FROM bookings WHERE id = ?", UUID.class, bookingId);
    }

    private UUID appointmentClientIdOf(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT client_id FROM appointments WHERE id = ?", UUID.class, appointmentId);
    }

    private String guestNameOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT guest_name FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String cancellationReasonOf(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private long countByAggregateId(UUID aggregateId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE aggregate_id = ?", Long.class, aggregateId);
        return count == null ? -1 : count;
    }

    private String eventTypeOf(UUID aggregateId) {
        return jdbcTemplate.queryForObject(
                "SELECT event_type FROM notification_outbox WHERE aggregate_id = ?", String.class, aggregateId);
    }

    /**
     * Single-booking sibling of {@code BookingTestFixtures#dropCreateTimeNotifications}, which is
     * keyed by {@code appointmentId} and so cannot target a standalone (non-appointment) booking.
     */
    private void dropOutboxRowsFor(UUID bookingId) {
        jdbcTemplate.update("DELETE FROM notification_outbox WHERE aggregate_id = ?", bookingId);
    }
}
