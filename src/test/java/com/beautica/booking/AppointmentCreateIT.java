package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Full-HTTP-stack smoke suite for BE-3's {@code POST /api/v1/appointments} — the multi-service
 * single-visit create path. Proves, against a real Postgres instance, that one POST atomically
 * creates ONE appointment header plus N chained CONFIRMED bookings for a single master, and that a
 * repeated idempotency key replays the same visit rather than minting a second.
 *
 * <p>The dev's original smoke pinned the two-service happy path and the idempotent replay. This suite
 * (backend-qa) brings the phase to full HTTP-level coverage of the BE-3 contract: the N-service
 * contiguity/totals shape with buffers folded (with DB-row assertions), the per-item price/duration
 * freeze including a RANGE ceiling, the idempotent replay's single-outbox guarantee, the master-busy
 * overlap 409 with hard atomicity (zero rows persisted on rollback), the total-duration cap 400, the
 * authorization matrix (non-CLIENT → 403; foreign masterService → uniform 404; client is always the
 * caller), and the request-validation boundary (empty / oversized list, past start, malformed
 * idempotency key). The V126 polymorphic guest-fields CHECK is exercised transitively by every APP
 * create here (client_id NOT NULL, guest_* / cancel_token NULL) and asserted directly by BE-1's
 * {@code AppointmentEntityIT}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("POST /appointments — multi-service single-visit create over real Postgres")
class AppointmentCreateIT extends AbstractIntegrationTest {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("a two-service visit creates ONE appointment and TWO chained CONFIRMED bookings, "
            + "back-to-back, under one appointment_id, with exactly ONE new-booking notification")
    void should_create2ChainedConfirmedBookings_when_twoServiceVisitPosted() throws Exception {
        String masterEmail = "appt-create-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-create-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        // Two distinct 60-min / 0-buffer services (distinct service_types via the fixture resolver).
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        ResponseEntity<String> resp = postVisit(clientToken, masterId, startsAt, serviceA, serviceB);

        assertThat(resp.getStatusCode())
                .as("visit creation must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        UUID appointmentId = UUID.fromString(data.path("id").asText());

        // Response shape: one visit, two ordered items, both CONFIRMED and back-to-back.
        assertThat(data.path("status").asText()).isEqualTo("CONFIRMED");
        JsonNode items = data.path("items");
        assertThat(items).as("expected exactly two service items").hasSize(2);
        assertThat(items.get(0).path("endsAt").asText())
                .as("the second service must start exactly when the first ends (contiguous chain)")
                .isEqualTo(items.get(1).path("startsAt").asText());
        assertThat(data.path("totalDurationMinutes").asInt())
                .as("two 60-min services with no buffer sum to a 120-min block")
                .isEqualTo(120);

        // Persistence: exactly one appointments row, two bookings both pointing at it, all CONFIRMED.
        Long appointmentRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE id = ?", Long.class, appointmentId);
        assertThat(appointmentRows).isEqualTo(1L);
        Long chainedBookings = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE appointment_id = ? AND status = 'CONFIRMED'",
                Long.class, appointmentId);
        assertThat(chainedBookings)
                .as("both services must persist as CONFIRMED bookings under the one appointment")
                .isEqualTo(2L);

        // Exactly ONE new-visit notification for the whole appointment (not one per service).
        Long newBookingEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox nb "
                        + "WHERE nb.event_type = 'NEW_BOOKING' AND nb.aggregate_id IN "
                        + "(SELECT id FROM bookings WHERE appointment_id = ?)",
                Long.class, appointmentId);
        assertThat(newBookingEvents)
                .as("exactly ONE NEW_BOOKING notification is enqueued per visit, not N")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("re-POSTing the same visit with the same idempotency key replays the SAME "
            + "appointment — it does not mint a second visit or duplicate the chained bookings")
    void should_replaySameVisit_when_sameIdempotencyKeyPostedTwice() throws Exception {
        String masterEmail = "appt-idem-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-idem-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(9).withMinute(0).withSecond(0).withNano(0);
        String key = "visit-idem-" + Long.toString(System.nanoTime(), 36);

        ResponseEntity<String> first = postVisit(clientToken, key, masterId, startsAt, serviceA, serviceB);
        ResponseEntity<String> second = postVisit(clientToken, key, masterId, startsAt, serviceA, serviceB);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode())
                .as("a replay must be a clean 201 returning the existing visit — body: %s", second.getBody())
                .isEqualTo(HttpStatus.CREATED);
        UUID firstId = UUID.fromString(objectMapper.readTree(first.getBody()).path("data").path("id").asText());
        UUID secondId = UUID.fromString(objectMapper.readTree(second.getBody()).path("data").path("id").asText());
        assertThat(secondId).as("the replay must return the SAME appointment id").isEqualTo(firstId);

        Long appointmentRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE client_id = "
                        + "(SELECT id FROM users WHERE email = ?)", Long.class, clientEmail);
        assertThat(appointmentRows).as("exactly one visit persisted for the client").isEqualTo(1L);
        Long chainedBookings = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE appointment_id = ?", Long.class, firstId);
        assertThat(chainedBookings)
                .as("the replay must not double the chained bookings")
                .isEqualTo(2L);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. Happy path, N services — contiguity, buffers folded, totals, client_id,
    //    V126 transitive (DB rows AND response body)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a THREE-service visit with mixed durations/buffers chains contiguously (buffers "
            + "folded), sums totals correctly, persists 3 CONFIRMED bookings under one appointment, "
            + "and stamps the caller as client (guest fields NULL — V126 APP branch)")
    void should_chainThreeServicesContiguously_when_multiServiceVisitPosted() throws Exception {
        String masterEmail = "appt-3svc-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-3svc-client-" + System.nanoTime() + "@beautica.test";
        UUID clientUserId = fixtures.createUser(clientEmail, "CLIENT", null);
        // 60min/+10buf/₴300, 45min/+0buf/₴200, 30min/+15buf/₴150 → block = 70+45+45 = 160, Σfloor = 650.
        UUID serviceA = createFixedService(masterId, 60, 10, new BigDecimal("300.00"));
        UUID serviceB = createFixedService(masterId, 45, 0, new BigDecimal("200.00"));
        UUID serviceC = createFixedService(masterId, 30, 15, new BigDecimal("150.00"));
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        ResponseEntity<String> resp = postVisit(clientToken, masterId, startsAt, serviceA, serviceB, serviceC);

        assertThat(resp.getStatusCode())
                .as("three-service visit must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        UUID appointmentId = UUID.fromString(data.path("id").asText());

        // Response body: three ordered items, each starting exactly where the previous one ended
        // (buffers are folded into each item's endsAt), header totals summed.
        JsonNode items = data.path("items");
        assertThat(items).as("expected exactly three service items").hasSize(3);
        assertThat(items.get(1).path("startsAt").asText())
                .as("item[1] must start where item[0] ends — buffer folded into item[0].endsAt")
                .isEqualTo(items.get(0).path("endsAt").asText());
        assertThat(items.get(2).path("startsAt").asText())
                .as("item[2] must start where item[1] ends (contiguous chain)")
                .isEqualTo(items.get(1).path("endsAt").asText());
        assertThat(data.path("totalDurationMinutes").asInt())
                .as("block spans Σ(duration+buffer) incl. the trailing buffer = 70+45+45")
                .isEqualTo(160);
        assertThat(data.path("totalPrice").decimalValue())
                .as("totalPrice = Σ of the item price floors")
                .isEqualByComparingTo("650.00");
        assertThat(data.path("totalPriceMax").isNull())
                .as("no item was a genuine range, so the visit ceiling is null (single total price)")
                .isTrue();

        // Persistence: exactly one appointment, three CONFIRMED bookings pointing at it.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE id = ?", Long.class, appointmentId))
                .isEqualTo(1L);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "price_at_booking, price_max_at_booking FROM bookings "
                        + "WHERE appointment_id = ? ORDER BY starts_at", appointmentId);
        assertThat(rows).as("three chained booking rows persisted in visit order").hasSize(3);
        assertThat(rows).allSatisfy(r ->
                assertThat(r.get("status")).isEqualTo("CONFIRMED"));
        // Each row's frozen duration+buffer snapshot matches the service it was cut from.
        assertThat(rows).extracting(
                        r -> ((Number) r.get("duration_minutes_at_booking")).intValue(),
                        r -> ((Number) r.get("buffer_minutes_at_booking")).intValue())
                .as("frozen (duration, buffer) per item, in order")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(60, 10),
                        org.assertj.core.groups.Tuple.tuple(45, 0),
                        org.assertj.core.groups.Tuple.tuple(30, 15));

        // client_id is the caller (the DTO has NO client field — it cannot be spoofed); guest fields
        // are NULL, i.e. this row takes the APP branch of the V126 chk_appointment_guest_fields CHECK.
        Map<String, Object> appt = jdbcTemplate.queryForMap(
                "SELECT client_id, guest_name, guest_phone, guest_surname, cancel_token, booking_source "
                        + "FROM appointments WHERE id = ?", appointmentId);
        assertThat(appt.get("client_id")).isEqualTo(clientUserId);
        assertThat(appt.get("booking_source")).isEqualTo("APP");
        assertThat(appt.get("guest_name")).isNull();
        assertThat(appt.get("guest_phone")).isNull();
        assertThat(appt.get("guest_surname")).isNull();
        assertThat(appt.get("cancel_token")).isNull();
    }

    @Test
    @DisplayName("the SAME masterServiceId posted twice is accepted verbatim (locked decision: "
            + "duplicates allowed, the block sums repeats) → 201 with TWO contiguous CONFIRMED items "
            + "and a total of 2 × the service's effective duration")
    void should_acceptDuplicateService_when_sameMasterServiceIdPostedTwice() throws Exception {
        String masterEmail = "appt-dup-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-dup-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        // One 50-min / +5-buffer service → effective block per item = 55 min; two repeats = 110 min.
        UUID serviceA = createFixedService(masterId, 50, 5, new BigDecimal("300.00"));
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        ResponseEntity<String> resp = postVisit(clientToken, masterId, startsAt, serviceA, serviceA);

        assertThat(resp.getStatusCode())
                .as("the same service twice is a legal visit — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        UUID appointmentId = UUID.fromString(data.path("id").asText());

        JsonNode items = data.path("items");
        assertThat(items).as("the repeated service yields two distinct items").hasSize(2);
        assertThat(items.get(1).path("startsAt").asText())
                .as("the second repeat starts exactly where the first ends (contiguous chain)")
                .isEqualTo(items.get(0).path("endsAt").asText());
        assertThat(data.path("totalDurationMinutes").asInt())
                .as("the block SUMS the repeat: 2 × (50 + 5) effective minutes")
                .isEqualTo(110);

        Long chainedBookings = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE appointment_id = ? AND status = 'CONFIRMED'",
                Long.class, appointmentId);
        assertThat(chainedBookings)
                .as("both repeats persist as CONFIRMED bookings under the one appointment")
                .isEqualTo(2L);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. Price / duration freeze — per item, including a RANGE ceiling
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("each item freezes its own price/duration; a RANGE service (no override) freezes its "
            + "ceiling into priceMaxAtBooking while a FIXED service freezes null, and the visit totals "
            + "reflect Σ floors and the summed ceiling")
    void should_freezePerItemPriceAndDuration_when_visitMixesRangeAndFixed() throws Exception {
        String masterEmail = "appt-freeze-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-freeze-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        // Item 0: RANGE 300–500 (no override) → floor 300, ceiling 500.
        UUID rangeService = createRangeService(masterId, 60, 0, new BigDecimal("300.00"), new BigDecimal("500.00"));
        // Item 1: FIXED 200 → floor 200, ceiling null.
        UUID fixedService = createFixedService(masterId, 90, 0, new BigDecimal("200.00"));
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(11).withMinute(0).withSecond(0).withNano(0);
        ResponseEntity<String> resp = postVisit(clientToken, masterId, startsAt, rangeService, fixedService);

        assertThat(resp.getStatusCode())
                .as("mixed range/fixed visit must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        UUID appointmentId = UUID.fromString(data.path("id").asText());
        JsonNode items = data.path("items");

        JsonNode rangeItem = items.get(0);
        assertThat(rangeItem.path("durationMinutesAtBooking").asInt()).isEqualTo(60);
        assertThat(rangeItem.path("priceAtBooking").decimalValue())
                .as("the RANGE floor is frozen as the charged price")
                .isEqualByComparingTo("300.00");
        assertThat(rangeItem.path("priceMaxAtBooking").decimalValue())
                .as("the RANGE ceiling is frozen into priceMaxAtBooking")
                .isEqualByComparingTo("500.00");

        JsonNode fixedItem = items.get(1);
        assertThat(fixedItem.path("durationMinutesAtBooking").asInt()).isEqualTo(90);
        assertThat(fixedItem.path("priceAtBooking").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(fixedItem.path("priceMaxAtBooking").isNull())
                .as("a FIXED service freezes NO ceiling")
                .isTrue();

        assertThat(data.path("totalPrice").decimalValue())
                .as("Σ floors = 300 + 200")
                .isEqualByComparingTo("500.00");
        assertThat(data.path("totalPriceMax").decimalValue())
                .as("Σ (priceMax ?? floor) = 500 + 200, present because one item was a genuine range")
                .isEqualByComparingTo("700.00");

        // The freeze is on the ROW, not just the response mapper: assert the persisted columns.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT price_at_booking, price_max_at_booking, duration_minutes_at_booking "
                        + "FROM bookings WHERE appointment_id = ? ORDER BY starts_at", appointmentId);
        assertThat((BigDecimal) rows.get(0).get("price_at_booking")).isEqualByComparingTo("300.00");
        assertThat((BigDecimal) rows.get(0).get("price_max_at_booking")).isEqualByComparingTo("500.00");
        assertThat(rows.get(1).get("price_max_at_booking"))
                .as("the FIXED item's ceiling column stays NULL on the row")
                .isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. Idempotent replay — one appointment, no duplicate bookings, ONE outbox row
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("re-POSTing the same visit under the same idempotency key enqueues EXACTLY ONE "
            + "NEW_BOOKING outbox row in total — the replay must not re-notify the master")
    void should_enqueueExactlyOneNewBookingOutboxRow_when_sameKeyPostedTwice() throws Exception {
        String masterEmail = "appt-idem-outbox-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-idem-outbox-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(4).withHour(9).withMinute(0).withSecond(0).withNano(0);
        String key = "visit-outbox-" + Long.toString(System.nanoTime(), 36);

        ResponseEntity<String> first = postVisit(clientToken, key, masterId, startsAt, serviceA, serviceB);
        ResponseEntity<String> second = postVisit(clientToken, key, masterId, startsAt, serviceA, serviceB);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID appointmentId = UUID.fromString(
                objectMapper.readTree(first.getBody()).path("data").path("id").asText());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'NEW_BOOKING' "
                        + "AND aggregate_id IN (SELECT id FROM bookings WHERE appointment_id = ?)",
                Long.class, appointmentId))
                .as("the idempotent replay must reuse the visit WITHOUT enqueuing a second notification")
                .isEqualTo(1L);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. Overlap 409 — master busy → conflict, NOTHING persisted (hard atomicity)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a chain colliding with an EXISTING CONFIRMED booking on that master → 409, and the "
            + "whole visit rolls back atomically: zero appointments AND zero chained bookings persist")
    void should_return409AndPersistNothing_when_chainOverlapsExistingBooking() throws Exception {
        String masterEmail = "appt-overlap-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-overlap-client-" + System.nanoTime() + "@beautica.test";
        UUID clientUserId = fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId); // 60/0
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(5).withHour(12).withMinute(0).withSecond(0).withNano(0);
        // A pre-existing guest (LINK) CONFIRMED booking occupies [12:00, 13:00] on this master — a
        // DIFFERENT booking than the client's, so the master-busy overlap check (not the client
        // conflict check) is what fires.
        seedGuestConfirmedBooking(masterId, serviceA, startsAt, 60);

        ResponseEntity<String> resp = postVisit(clientToken, masterId, startsAt, serviceA, serviceB);

        assertThat(resp.getStatusCode())
                .as("the first item overlaps the occupied slot — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(resp.getBody()).path("success").asBoolean())
                .as("conflict envelope carries success=false")
                .isFalse();

        // Hard atomicity — the highest-value guarantee of the phase. The pre-seeded guest booking
        // (appointment_id NULL) is the ONLY booking that may remain; no appointment and no chained
        // booking may have been left behind.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE client_id = ?", Long.class, clientUserId))
                .as("a rolled-back overlap must leave ZERO appointment rows")
                .isEqualTo(0L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE appointment_id IS NOT NULL", Long.class))
                .as("a rolled-back overlap must leave ZERO chained booking rows")
                .isEqualTo(0L);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. Cap 400 — summed duration > 600 min → rejected, nothing persisted
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a visit whose summed service duration exceeds the 600-min ceiling → 400, and no "
            + "appointment is persisted")
    void should_return400AndPersistNothing_when_summedDurationExceedsCap() throws Exception {
        String masterEmail = "appt-cap-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-cap-client-" + System.nanoTime() + "@beautica.test";
        UUID clientUserId = fixtures.createUser(clientEmail, "CLIENT", null);
        // 350 + 350 = 700 min > MAX_TOTAL_DURATION_MINUTES (600). Two distinct services (V121-safe).
        UUID serviceA = createFixedService(masterId, 350, 0, new BigDecimal("300.00"));
        UUID serviceB = createFixedService(masterId, 350, 0, new BigDecimal("300.00"));
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);
        ResponseEntity<String> resp = postVisit(clientToken, masterId, startsAt, serviceA, serviceB);

        assertThat(resp.getStatusCode())
                .as("700 > 600 must be rejected as a bad request — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE client_id = ?", Long.class, clientUserId))
                .as("a capped request must persist no appointment")
                .isEqualTo(0L);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 6. Authorization — non-CLIENT 403; foreign masterService 404; client = caller
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a non-CLIENT principal (INDEPENDENT_MASTER token) is denied by the endpoint's "
            + "hasRole('CLIENT') gate → 403")
    void should_return403_when_nonClientPrincipalPostsVisit() throws Exception {
        String masterEmail = "appt-authz-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        // The master authenticates with its OWN token and tries to create a client-visit.
        String masterToken = fixtures.tokenFor(masterEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        ResponseEntity<String> resp = postVisit(masterToken, masterId, startsAt, serviceA);

        assertThat(resp.getStatusCode())
                .as("only CLIENT may create appointments — a master token is forbidden")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a masterServiceId that belongs to a DIFFERENT master → uniform 404 (no existence "
            + "oracle: unknown, foreign and inactive all answer the same)")
    void should_return404_when_masterServiceBelongsToAnotherMaster() throws Exception {
        String masterAEmail = "appt-foreign-a-" + System.nanoTime() + "@beautica.test";
        UUID masterA = fixtures.createIndependentMaster(masterAEmail);
        addWorkingHoursForEveryDay(masterA);
        String masterBEmail = "appt-foreign-b-" + System.nanoTime() + "@beautica.test";
        UUID masterB = fixtures.createIndependentMaster(masterBEmail);
        UUID serviceOfB = fixtures.createIndependentMasterService(masterB);
        String clientEmail = "appt-foreign-client-" + System.nanoTime() + "@beautica.test";
        UUID clientUserId = fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        // masterId = A, but the service belongs to B → the master-scoped lookup returns empty → 404.
        ResponseEntity<String> resp = postVisit(clientToken, masterA, startsAt, serviceOfB);

        assertThat(resp.getStatusCode())
                .as("a service not owned by masterId must 404, never leak that it exists elsewhere")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE client_id = ?", Long.class, clientUserId))
                .isEqualTo(0L);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 7. Request-validation boundary — empty / oversized list, past start, bad key
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("an empty masterServiceIds list is rejected by @NotEmpty → 400")
    void should_return400_when_serviceListEmpty() throws Exception {
        String clientEmail = "appt-empty-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        ResponseEntity<String> resp = postVisit(clientToken, UUID.randomUUID(), startsAt); // no serviceIds

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a masterServiceIds list larger than the 10-service cap is rejected by @Size → 400")
    void should_return400_when_serviceListExceedsMax() throws Exception {
        String clientEmail = "appt-oversize-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        UUID[] elevenServices = new UUID[11];
        for (int i = 0; i < elevenServices.length; i++) {
            elevenServices[i] = UUID.randomUUID();
        }
        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        ResponseEntity<String> resp = postVisit(clientToken, UUID.randomUUID(), startsAt, elevenServices);

        assertThat(resp.getStatusCode())
                .as("11 > 10 must fail at the DTO before any per-id lookup runs")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a startsAt in the past is rejected the same way the single-service path rejects it "
            + "(@Future) → 400")
    void should_return400_when_startsAtInThePast() throws Exception {
        String clientEmail = "appt-past-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime pastStart = ZonedDateTime.now(TimeZones.KYIV)
                .minusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        ResponseEntity<String> resp = postVisit(clientToken, UUID.randomUUID(), pastStart, UUID.randomUUID());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a malformed idempotency key (contains a space) is rejected by @Pattern → 400")
    void should_return400_when_idempotencyKeyMalformed() throws Exception {
        String clientEmail = "appt-badkey-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        ResponseEntity<String> resp = postVisit(
                clientToken, "bad key with spaces", UUID.randomUUID(), startsAt, UUID.randomUUID());

        assertThat(resp.getStatusCode())
                .as("' ' is not [A-Za-z0-9-_]; the key must be rejected before creation")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<String> postVisit(
            String clientToken, UUID masterId, ZonedDateTime startsAt, UUID... serviceIds) {
        return postVisit(clientToken, null, masterId, startsAt, serviceIds);
    }

    private ResponseEntity<String> postVisit(
            String clientToken, String idempotencyKey, UUID masterId, ZonedDateTime startsAt, UUID... serviceIds) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < serviceIds.length; i++) {
            if (i > 0) {
                ids.append(',');
            }
            ids.append('"').append(serviceIds[i]).append('"');
        }
        String body = """
                {"masterId":"%s","masterServiceIds":[%s],"startsAt":"%s"%s}
                """.formatted(
                masterId, ids, startsAt.toOffsetDateTime(),
                idempotencyKey == null ? "" : ",\"idempotencyKey\":\"" + idempotencyKey + "\"");
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                APPOINTMENTS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /**
     * Open-ended weekly schedule with all seven ISO weekdays 08:00–20:00, so a near-future visit can
     * be booked on any day. Mirrors {@code BookingPriceRangeContractIT}'s local helper of the same
     * name (that copy stays local per the established convention; this suite is the first appointment
     * IT to need it).
     */
    private void addWorkingHoursForEveryDay(UUID masterId) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) "
                        + "VALUES (?, ?, DATE '2020-01-01', NULL)",
                scheduleId, masterId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, '08:00', '20:00')",
                    UUID.randomUUID(), scheduleId, day);
        }
    }

    /**
     * A FIXED-priced {@code service_definitions} row (+ active {@code master_services} assignment,
     * no {@code priceOverride}) for an INDEPENDENT_MASTER, with a caller-chosen duration/buffer/price
     * — the smoke fixture's {@code createIndependentMasterService} is hardcoded to 60min/0-buffer/₴500,
     * which cannot exercise contiguity-with-buffers, the total-duration cap, or per-item duration
     * freeze. Uses {@link com.beautica.AbstractIntegrationTest#resolveUnusedServiceTypeId} (via the
     * fixture) so a second service for the same owner does not collide on V121's
     * {@code ux_service_def_owner_service_type_active}.
     */
    private UUID createFixedService(UUID masterId, int durationMinutes, int bufferMinutes, BigDecimal basePrice) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, price_max, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Fixed Svc', ?, ?, 'FIXED', ?, NULL, ?, "
                        + "true, NOW(), NOW())",
                serviceDefId, userId, fixtures.resolveUnusedServiceTypeId("INDEPENDENT_MASTER", userId),
                durationMinutes, basePrice, bufferMinutes);
        return insertAssignment(masterId, serviceDefId, null);
    }

    /**
     * A RANGE-priced {@code service_definitions} row (no {@code priceOverride}) so the create path
     * freezes {@code basePrice} as the floor and {@code priceMax} as the ceiling — the only shape
     * that produces a non-null {@code priceMaxAtBooking}.
     */
    private UUID createRangeService(UUID masterId, int durationMinutes, int bufferMinutes,
                                    BigDecimal basePrice, BigDecimal priceMax) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, price_max, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Range Svc', ?, ?, 'RANGE', ?, ?, ?, "
                        + "true, NOW(), NOW())",
                serviceDefId, userId, fixtures.resolveUnusedServiceTypeId("INDEPENDENT_MASTER", userId),
                durationMinutes, basePrice, priceMax, bufferMinutes);
        return insertAssignment(masterId, serviceDefId, null);
    }

    private UUID insertAssignment(UUID masterId, UUID serviceDefId, BigDecimal priceOverride) {
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, price_override, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId, priceOverride);
        return masterServiceId;
    }

    /**
     * Occupies {@code [startsAt, startsAt + durationMinutes)} on {@code masterId} with a CONFIRMED
     * guest (LINK) booking — {@code client_id} NULL, so the appointment create's per-item master-busy
     * overlap check ({@code existsOverlap}) is what rejects a colliding chain, distinct from the
     * same-client conflict check. Mirrors {@code BookingMasterServiceIT#seedConfirmedBooking}.
     */
    private void seedGuestConfirmedBooking(UUID masterId, UUID masterServiceId,
                                           ZonedDateTime startsAt, int durationMinutes) {
        OffsetDateTime start = startsAt.toOffsetDateTime();
        OffsetDateTime end = start.plusMinutes(durationMinutes);
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, status, booking_source, "
                        + "guest_name, guest_phone, cancel_token, starts_at, ends_at, price_at_booking, "
                        + "duration_minutes_at_booking, buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CONFIRMED', 'LINK', 'Guest', '+380501112233', ?, ?, ?, "
                        + "500.00, ?, 0, NOW(), NOW())",
                UUID.randomUUID(), masterId, masterServiceId, UUID.randomUUID(),
                start, end, durationMinutes);
    }
}
