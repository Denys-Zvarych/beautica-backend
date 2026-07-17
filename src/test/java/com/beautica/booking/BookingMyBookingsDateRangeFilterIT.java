package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-authored regression suite for Phase 26.2 (backend-qa, 2026-07-17): {@code from}/{@code to}
 * date-range filter on {@code GET /bookings/me}, exercised over the FULL HTTP stack — real Spring
 * Security, a real {@link com.beautica.booking.service.BookingService}, and a real Postgres
 * Testcontainers instance.
 *
 * <p><b>Why this suite exists.</b> Both the 26.2 implementation PR and its build-verifier pass
 * mechanically updated every pre-existing {@code getMyBookings}/{@code findClientBookingDetails}
 * call site to pass {@code null, null} for the two new parameters — so prior to this suite, NO
 * test ever exercised a non-null {@code from}/{@code to} on either the provider (Criteria/
 * {@code Specification}) path or the client (JPQL sentinel-{@code CAST}) path. Mocks cannot prove
 * a {@code startsAt} range predicate or a Kyiv-vs-UTC boundary — everything here runs against a
 * real Postgres instance.
 *
 * <p>Covers four areas:
 * <ol>
 *   <li><b>Core positive proof</b> — a {@code from}/{@code to} window returns exactly the
 *       in-window bookings on BOTH the provider (INDEPENDENT_MASTER, {@link
 *       com.beautica.booking.repository.BookingSpecifications#startsAtOnOrAfter}/{@code
 *       startsAtBefore}) and client (CLIENT, {@code findClientBookingDetails}'s
 *       {@code CAST(:from AS ...) IS NULL OR ...} predicate) paths.</li>
 *   <li><b>Half-open upper-bound boundary</b> — {@code to} is inclusive of its whole local day: a
 *       booking at 19:30 Kyiv on the {@code to} day must be INCLUDED, while a booking at exactly
 *       00:00 Kyiv on {@code to + 1} day must be EXCLUDED. A naive {@code <= to.atStartOfDay()}
 *       upper bound would wrongly EXCLUDE the first booking (19:30 Kyiv is after 00:00 Kyiv on the
 *       same day); an off-by-one in the {@code plusDays(1)} arithmetic (e.g. {@code plusDays(2)})
 *       would wrongly INCLUDE the second. Both failure modes are pinned, on both query paths.</li>
 *   <li><b>Kyiv-zone (not UTC) attribution</b> — a booking at 00:30 Kyiv local time on day D has a
 *       UTC instant that falls on day D-1. {@code from = D} must still include it; a UTC-resolved
 *       boundary would wrongly exclude it.</li>
 *   <li><b>Validation → 400, never 500 or an empty 200</b> — {@code from > to}; span &gt; 366 days;
 *       and the {@link LocalDate#MAX} overflow guard ({@code assertToPlusOneDayRepresentable}),
 *       which is the regression test for a real pre-fix bug: {@code to.plusDays(1)} on
 *       {@code LocalDate.MAX} threw an uncaught {@link java.time.DateTimeException} (500), even
 *       though {@code @DateTimeFormat(iso = DATE)} parses {@code +999999999-12-31} without
 *       complaint.</li>
 * </ol>
 *
 * <p><b>Explicitly out of scope</b> (per user instruction) — the client-path sentinel's
 * non-sargability under a generic query plan (backend-perf MEDIUM, routed to Phase 26.7) and
 * plan-cache shape growth (backend-perf INFO, backlogged). This suite tests 26.2's behavioural
 * correctness only, not its query-plan shape.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /bookings/me — Phase 26.2 date-range filter, full HTTP stack over real Postgres")
class BookingMyBookingsDateRangeFilterIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1 — core positive proof: window returns exactly the in-window bookings
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider Specification path) — from/to window "
            + "returns exactly the bookings whose startsAt falls inside it, excluding one booking "
            + "just before and one just after — the ONLY real-DB proof of "
            + "BookingSpecifications.startsAtOnOrAfter/startsAtBefore, which backend-dev reported "
            + "never observing the generated SQL for")
    void should_returnOnlyInWindowBookings_when_providerFiltersByDateRange() throws Exception {
        String masterEmail = "mbdrf-provider-window-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createIndependentMaster(masterEmail);
        UUID clientId = createUser("mbdrf-provider-window-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = createIndependentMasterService(masterId);

        LocalDate from = LocalDate.of(2031, 7, 10);
        LocalDate to = LocalDate.of(2031, 7, 12);

        UUID beforeWindow = insertBooking(clientId, masterId, serviceId, null,
                kyiv(from.minusDays(1), 12, 0));
        UUID atWindowStart = insertBooking(clientId, masterId, serviceId, null,
                kyiv(from, 0, 5));
        UUID midWindow = insertBooking(clientId, masterId, serviceId, null,
                kyiv(from.plusDays(1), 12, 0));
        UUID nearWindowEnd = insertBooking(clientId, masterId, serviceId, null,
                kyiv(to, 23, 30));
        UUID afterWindow = insertBooking(clientId, masterId, serviceId, null,
                kyiv(to.plusDays(1), 0, 30));

        ResponseEntity<String> resp = callMyBookings(tokenFor(masterEmail), from, to);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(extractIds(root))
                .as("only bookings strictly inside [from, to] (Kyiv, to inclusive) must be returned")
                .containsExactlyInAnyOrder(atWindowStart, midWindow, nearWindowEnd)
                .doesNotContain(beforeWindow, afterWindow);
        assertThat(root.path("data").path("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("GET /me (CLIENT, findClientBookingDetails JPQL sentinel path) — from/to window "
            + "returns exactly the bookings whose startsAt falls inside it; this is the first "
            + "execution of the non-null CAST(:from AS ...) >= / < branch in CI — previously only "
            + "null,null was ever passed")
    void should_returnOnlyInWindowBookings_when_clientFiltersByDateRange() throws Exception {
        String masterEmail = "mbdrf-client-window-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createIndependentMaster(masterEmail);
        String clientEmail = "mbdrf-client-window-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        UUID serviceId = createIndependentMasterService(masterId);

        LocalDate from = LocalDate.of(2031, 8, 5);
        LocalDate to = LocalDate.of(2031, 8, 7);

        UUID beforeWindow = insertBooking(clientId, masterId, serviceId, null,
                kyiv(from.minusDays(1), 12, 0));
        UUID atWindowStart = insertBooking(clientId, masterId, serviceId, null,
                kyiv(from, 0, 5));
        UUID midWindow = insertBooking(clientId, masterId, serviceId, null,
                kyiv(from.plusDays(1), 12, 0));
        UUID nearWindowEnd = insertBooking(clientId, masterId, serviceId, null,
                kyiv(to, 23, 30));
        UUID afterWindow = insertBooking(clientId, masterId, serviceId, null,
                kyiv(to.plusDays(1), 0, 30));

        ResponseEntity<String> resp = callMyBookings(tokenFor(clientEmail), from, to);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(extractIds(root))
                .as("only bookings strictly inside [from, to] (Kyiv, to inclusive) must be returned")
                .containsExactlyInAnyOrder(atWindowStart, midWindow, nearWindowEnd)
                .doesNotContain(beforeWindow, afterWindow);
        assertThat(root.path("data").path("totalElements").asLong()).isEqualTo(3);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2 — half-open upper-bound boundary (the subtle correctness trap)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider path) — to is INCLUSIVE of the whole local "
            + "day: a 19:30 Kyiv booking on the `to` day is returned, a 00:00 Kyiv booking on "
            + "`to`+1 day is not. FAILS against a naive `<= to.atStartOfDay()` upper bound (which "
            + "would drop the 19:30 booking) and against an off-by-one plusDays arithmetic (which "
            + "would leak in the `to`+1 00:00 booking)")
    void should_applyHalfOpenToBoundaryCorrectly_when_providerFiltersByToOnly() throws Exception {
        String masterEmail = "mbdrf-provider-halfopen-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createIndependentMaster(masterEmail);
        UUID clientId = createUser("mbdrf-provider-halfopen-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = createIndependentMasterService(masterId);

        LocalDate to = LocalDate.of(2031, 7, 17);
        UUID lateOnToDay = insertBooking(clientId, masterId, serviceId, null, kyiv(to, 19, 30));
        UUID midnightNextDay = insertBooking(clientId, masterId, serviceId, null,
                kyiv(to.plusDays(1), 0, 0));

        ResponseEntity<String> resp = callMyBookings(tokenFor(masterEmail), null, to);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(extractIds(root))
                .as("19:30 Kyiv on the `to` day must be INCLUDED (half-open upper bound is "
                        + "`to`+1 day at Kyiv midnight, not `to` itself); 00:00 Kyiv on `to`+1 day "
                        + "must be EXCLUDED (it is exactly the exclusive boundary)")
                .containsExactly(lateOnToDay)
                .doesNotContain(midnightNextDay);
    }

    @Test
    @DisplayName("GET /me (CLIENT path) — to is INCLUSIVE of the whole local day: a 19:30 Kyiv "
            + "booking on the `to` day is returned, a 00:00 Kyiv booking on `to`+1 day is not. "
            + "Same discriminating property as the provider-path test, proven against the "
            + "independent JPQL sentinel predicate")
    void should_applyHalfOpenToBoundaryCorrectly_when_clientFiltersByToOnly() throws Exception {
        String masterEmail = "mbdrf-client-halfopen-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createIndependentMaster(masterEmail);
        String clientEmail = "mbdrf-client-halfopen-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        UUID serviceId = createIndependentMasterService(masterId);

        LocalDate to = LocalDate.of(2031, 9, 22);
        UUID lateOnToDay = insertBooking(clientId, masterId, serviceId, null, kyiv(to, 19, 30));
        UUID midnightNextDay = insertBooking(clientId, masterId, serviceId, null,
                kyiv(to.plusDays(1), 0, 0));

        ResponseEntity<String> resp = callMyBookings(tokenFor(clientEmail), null, to);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(extractIds(root))
                .as("19:30 Kyiv on the `to` day must be INCLUDED, 00:00 Kyiv on `to`+1 day EXCLUDED")
                .containsExactly(lateOnToDay)
                .doesNotContain(midnightNextDay);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3 — Kyiv-zone (not UTC) attribution
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider path) — a booking at 00:30 Kyiv local time "
            + "on day D is attributed to D, not D-1: its UTC instant falls on D-1 (Kyiv is UTC+3 in "
            + "July), so a UTC-resolved `from = D` boundary would wrongly exclude it, but the "
            + "correct Kyiv-resolved boundary (D 00:00 Kyiv = D-1 21:00 UTC) includes it")
    void should_attributeEarlyKyivLocalBookingToItsKyivDay_when_providerFiltersFromThatDay()
            throws Exception {
        String masterEmail = "mbdrf-kyiv-zone-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createIndependentMaster(masterEmail);
        UUID clientId = createUser("mbdrf-kyiv-zone-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = createIndependentMasterService(masterId);

        LocalDate day = LocalDate.of(2031, 7, 10);
        OffsetDateTime kyivLocalStart = kyiv(day, 0, 30);
        // Sanity check on the fixture itself: the UTC calendar date must differ from the Kyiv
        // calendar date, or this test would not actually discriminate Kyiv vs UTC resolution.
        assertThat(kyivLocalStart.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate())
                .as("fixture sanity: 00:30 Kyiv on %s must fall on the PREVIOUS UTC calendar day, "
                        + "otherwise this test cannot distinguish a Kyiv-zone bug from a UTC-zone bug",
                        day)
                .isEqualTo(day.minusDays(1));

        UUID earlyKyivBooking = insertBooking(clientId, masterId, serviceId, null, kyivLocalStart);

        ResponseEntity<String> resp = callMyBookings(tokenFor(masterEmail), day, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(extractIds(root))
                .as("a UTC-resolved `from` boundary would place fromInstant at D 00:00Z, AFTER this "
                        + "booking's D-1 21:30Z UTC instant, wrongly excluding it; the correct "
                        + "Kyiv-resolved boundary (D-1 21:00Z) correctly includes it")
                .containsExactly(earlyKyivBooking);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4 — validation: 400, never 500 or an empty 200
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me — from after to returns 400 with a clear message, not an empty 200 "
            + "(an empty page would hide a client bug)")
    void should_return400_when_fromIsAfterTo() throws Exception {
        String masterEmail = "mbdrf-from-after-to-" + System.nanoTime() + "@beautica.test";
        createIndependentMaster(masterEmail);

        LocalDate from = LocalDate.of(2031, 7, 20);
        LocalDate to = LocalDate.of(2031, 7, 10);
        ResponseEntity<String> resp = callMyBookings(tokenFor(masterEmail), from, to);

        assertThat(resp.getStatusCode())
                .as("from > to must be rejected as a 400, never silently return an empty page")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("success").asBoolean()).isFalse();
        // GlobalExceptionHandler.handleBusiness deliberately genericises every BAD_REQUEST
        // BusinessException message to "Invalid request" (never echoes ex.getMessage()) — so the
        // envelope's message is intentionally NOT parameter-specific here. The 400 status code
        // (asserted above) is the real signal that from > to was rejected, not an empty page.
        assertThat(root.path("message").asText(""))
                .as("BAD_REQUEST BusinessExceptions are genericised by design (see "
                        + "GlobalExceptionHandler.handleBusiness) — this asserts that contract holds "
                        + "for the from>to case too, not that it leaks parameter detail")
                .isEqualTo("Invalid request");
    }

    @Test
    @DisplayName("GET /me — a from/to span wider than 366 days (366-day diff) returns 400, reusing "
            + "the Phase 15.11 ScheduleDateMath range-cap guard")
    void should_return400_when_spanExceedsMaximum() throws Exception {
        String masterEmail = "mbdrf-span-too-wide-" + System.nanoTime() + "@beautica.test";
        createIndependentMaster(masterEmail);

        LocalDate from = LocalDate.of(2031, 1, 1);
        LocalDate to = from.plusDays(366); // 366-day diff > the 365-day-diff cap -> rejected
        ResponseEntity<String> resp = callMyBookings(tokenFor(masterEmail), from, to);

        assertThat(resp.getStatusCode())
                .as("a span wider than the 366-day cap must be rejected, not silently truncated "
                        + "or allowed to scan an unbounded range")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /me — a from/to span of exactly 366 calendar days (365-day diff, the "
            + "documented cap) is ACCEPTED, not rejected — pins the boundary is inclusive, not "
            + "off-by-one in either direction")
    void should_return200_when_spanIsExactlyAtTheCap() throws Exception {
        String masterEmail = "mbdrf-span-at-cap-" + System.nanoTime() + "@beautica.test";
        createIndependentMaster(masterEmail);

        LocalDate from = LocalDate.of(2031, 1, 1);
        LocalDate to = from.plusDays(365); // exactly at the cap -> must be accepted
        ResponseEntity<String> resp = callMyBookings(tokenFor(masterEmail), from, to);

        assertThat(resp.getStatusCode())
                .as("a span of exactly the documented 366-day cap must be accepted, not rejected")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /me — to alone at LocalDate.MAX (+999999999-12-31) returns 400, not a 500. "
            + "Regression test for the pre-fix bug: `to.plusDays(1)` on LocalDate.MAX threw an "
            + "uncaught DateTimeException, even though @DateTimeFormat(iso=DATE) parses the value "
            + "without complaint")
    void should_return400NotServerError_when_toAloneIsLocalDateMax() throws Exception {
        String masterEmail = "mbdrf-to-max-" + System.nanoTime() + "@beautica.test";
        createIndependentMaster(masterEmail);

        ResponseEntity<String> resp = callMyBookingsRaw(
                tokenFor(masterEmail), null, enc(LocalDate.MAX.toString()));

        assertThat(resp.getStatusCode())
                .as("to = LocalDate.MAX must be rejected as a clean 400 by "
                        + "ScheduleDateMath.assertToPlusOneDayRepresentable — the pre-guard behaviour "
                        + "was an uncaught DateTimeException surfacing as a 500")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("GET /me — a VALID small span (11-day diff, well within the 366-day cap) whose to "
            + "lands exactly on LocalDate.MAX still returns 400 — the overflow guard fires on `to` "
            + "alone, independent of whether the span itself would otherwise be accepted")
    void should_return400NotServerError_when_validSmallSpanEndsAtLocalDateMax() throws Exception {
        String masterEmail = "mbdrf-span-ends-at-max-" + System.nanoTime() + "@beautica.test";
        createIndependentMaster(masterEmail);

        LocalDate to = LocalDate.MAX;
        LocalDate from = to.minusDays(10);
        ResponseEntity<String> resp = callMyBookingsRaw(
                tokenFor(masterEmail), enc(from.toString()), enc(to.toString()));

        assertThat(resp.getStatusCode())
                .as("even though the from/to span itself (10 days) is comfortably within the "
                        + "366-day cap, the LocalDate.MAX overflow guard on `to` must still fire "
                        + "and produce a clean 400, not a 500")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Resolves a Kyiv-local wall-clock instant to its UTC-backed {@link OffsetDateTime}. */
    private static OffsetDateTime kyiv(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(TimeZones.KYIV).toOffsetDateTime();
    }

    private UUID createIndependentMaster(String email) {
        UUID userId = createUser(email, "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    /** Resolves a real, selectable {@code service_types.id} (V111 made this column NOT NULL). */
    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    /**
     * Inserts a CONFIRMED booking row directly via SQL (bypassing the create-booking service flow
     * entirely) so the test can seed an arbitrary {@code starts_at} deterministically. {@code
     * salonId} is nullable — omitted from the INSERT column list for an independent master.
     */
    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId,
                                OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        if (salonId != null) {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId, salonId,
                    startsAt, startsAt.plusMinutes(60));
        } else {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId,
                    startsAt, startsAt.plusMinutes(60));
        }
        return bookingId;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    /** Percent-encodes a query-parameter value (UTF-8) — needed for LocalDate.MAX's leading '+'. */
    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> callMyBookings(String token, LocalDate from, LocalDate to) {
        return callMyBookingsRaw(token, from == null ? null : from.toString(), to == null ? null : to.toString());
    }

    /**
     * Builds an absolute, ALREADY-ENCODED {@link URI} via {@code URI.create} and calls the
     * {@code exchange(URI, ...)} overload directly — never {@code exchange(String, ...)}. The
     * String-template overload routes through {@code DefaultUriBuilderFactory}, which does not
     * treat a pre-percent-encoded value (e.g. {@code %2B} for the LocalDate.MAX tests' literal
     * {@code '+'}) as already-encoded and corrupts it before the request is sent — confirmed by a
     * failing debug run where the server received the literal string {@code "%2B999999999-12-31"}
     * instead of {@code "+999999999-12-31"}. {@code URI.create} performs no further encoding, so
     * callers here are responsible for passing pre-encoded values (see {@link #enc}). Mirrors
     * {@code SearchIntegrationTest.rawUri}.
     */
    private ResponseEntity<String> callMyBookingsRaw(String token, String fromRaw, String toRaw) {
        List<String> parts = new ArrayList<>();
        if (fromRaw != null) {
            parts.add("from=" + fromRaw);
        }
        if (toRaw != null) {
            parts.add("to=" + toRaw);
        }
        String pathAndQuery = BOOKINGS_URL + "/me" + (parts.isEmpty() ? "" : "?" + String.join("&", parts));
        URI uri = URI.create("http://localhost:" + port + pathAndQuery);
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(bearerHeaders(token)), String.class);
    }

    private List<UUID> extractIds(JsonNode root) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode row : root.path("data").path("data")) {
            ids.add(UUID.fromString(row.path("id").asText()));
        }
        return ids;
    }

    private String tokenFor(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
