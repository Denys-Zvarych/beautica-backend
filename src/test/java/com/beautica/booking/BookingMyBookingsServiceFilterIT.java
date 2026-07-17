package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-authored regression suite for Phase 26.4 (backend-qa, 2026-07-17): {@code serviceId}
 * multi-select filter on {@code GET /bookings/me}, exercised over the FULL HTTP stack — real
 * Spring Security, a real {@link com.beautica.booking.service.BookingService}, and a real
 * Postgres Testcontainers instance.
 *
 * <p><b>Why this suite exists.</b> Prior to this suite, {@code BookingSpecifications.
 * masterServiceIdIn} — the PROVIDER-path Criteria predicate ({@code root.get("masterService").
 * get("id").in(...)}) — had only Mockito ({@code BookingServiceTest}) and MockMvc
 * ({@code BookingControllerTest}) coverage; neither proves the predicate actually narrows a real
 * Postgres query. The CLIENT-path JPQL sentinel predicate already has real-DB coverage via
 * {@code ClientBookingDetailProjectionTest} (backend-dev, 3 tests: single filter, union, deleted-
 * service-still-lists) — this suite does NOT duplicate those, it only adds the two cross-actor
 * IDOR-safety tests the client path was still missing (see Gap 2 below) plus full real-DB
 * coverage of the provider path (Gap 1).
 *
 * <p>Covers two gaps:
 * <ol>
 *   <li><b>Gap 1 — provider Criteria path, real Postgres, positive + union + no-filter.</b> An
 *       INDEPENDENT_MASTER with bookings spread across &ge;2 {@code MasterService} rows: a
 *       single-serviceId filter returns exactly that service's bookings (excluding the sibling
 *       service — this is the assertion that would FAIL if {@code masterServiceIdIn} were
 *       dropped from {@code BookingRepositoryCustomImpl.applyServiceFilter}, i.e. it is a true
 *       predicate-pinning test, not a no-op-passing 200 check); a two-serviceId filter returns
 *       the union, not an intersection; no {@code serviceId} param returns every booking
 *       regardless of service, unfiltered.</li>
 *   <li><b>Gap 2 — cross-actor IDOR safety, BOTH paths.</b> The core security property this
 *       filter must never violate: filtering by a {@code serviceId} the caller does NOT own
 *       returns an EMPTY page, never another actor's bookings — the master/client scope
 *       predicate stays the outer boundary, the service filter only narrows WITHIN it (locked
 *       decision, see phase doc "No ownership check on the supplied IDs"). Proven on the
 *       provider path (Master A filtered by Master B's serviceId) and the client path (Client A
 *       filtered by a serviceId tied to a booking that belongs to a different client), both
 *       against real seeded rows so a leak would show up as a non-empty page, not merely a
 *       wrong-shaped response.</li>
 * </ol>
 *
 * <p><b>Explicitly out of scope</b> (per user instruction, already covered elsewhere or routed
 * to a later phase) — the {@code serviceId} &gt; 50 cap and the malformed-UUID-in-query-param
 * case are HTTP-layer, pre-service-dispatch concerns proven at the {@code @WebMvcTest} level in
 * {@code BookingControllerTest} (no real DB round-trip needed to prove a {@code @Size}/type-
 * mismatch rejection); the service-level {@code MAX_SERVICE_ID_FILTER} defense-in-depth check is
 * proven directly in {@code BookingServiceTest}. The catalogue-limitation "deleted service still
 * lists in the unfiltered view" acceptance criterion is already covered by
 * {@code ClientBookingDetailProjectionTest#should_stillListBooking_when_itsMasterServiceIsLaterDeleted}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /bookings/me — Phase 26.4 serviceId filter, full HTTP stack over real Postgres")
class BookingMyBookingsServiceFilterIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final OffsetDateTime ANCHOR =
            OffsetDateTime.of(2031, 9, 3, 8, 0, 0, 0, ZoneOffset.UTC);

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

    // ══════════════════════════════════════════════════════════════════════════
    // Gap 1 — provider Criteria path, real Postgres
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider Specification/Criteria path) — a single "
            + "?serviceId filter returns ONLY that service's bookings, excluding the sibling "
            + "service's bookings — this assertion would FAIL if masterServiceIdIn were dropped "
            + "from the composed Specification, proving the predicate actually reaches Postgres")
    void should_returnOnlyMatchingServiceBookings_when_independentMasterFiltersBySingleServiceId()
            throws Exception {
        String masterEmail = "mbsf-single-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbsf-single-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);

        OffsetDateTime t = ANCHOR;
        List<UUID> serviceABookings = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            serviceABookings.add(insertBooking(clientId, masterId, serviceA, t));
            t = t.plusMinutes(90);
        }
        List<UUID> serviceBBookings = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            serviceBBookings.add(insertBooking(clientId, masterId, serviceB, t));
            t = t.plusMinutes(90);
        }

        ResponseEntity<String> resp = callMyBookings(fixtures.tokenFor(masterEmail), List.of(serviceA), 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("data").path("totalElements").asLong())
                .as("countQuery must carry the same masterServiceIdIn predicate as the id-page query")
                .isEqualTo(3);
        assertThat(fixtures.extractIds(root))
                .as("filtering by service A must return EXACTLY service A's 3 bookings — none of "
                        + "service B's 2 bookings. If masterServiceIdIn were a no-op this would "
                        + "return all 5 and fail here.")
                .containsExactlyInAnyOrderElementsOf(serviceABookings)
                .doesNotContainAnyElementsOf(serviceBBookings);
    }

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider path) — a two-serviceId filter returns the "
            + "UNION of both services' bookings, not an intersection (which would be empty here) "
            + "and not only the first service's bookings")
    void should_returnUnionOfMatchingServiceBookings_when_independentMasterFiltersByTwoServiceIds()
            throws Exception {
        String masterEmail = "mbsf-union-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbsf-union-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        UUID serviceC = fixtures.createIndependentMasterService(masterId);

        OffsetDateTime t = ANCHOR;
        UUID bookingA = insertBooking(clientId, masterId, serviceA, t);
        t = t.plusMinutes(90);
        UUID bookingB = insertBooking(clientId, masterId, serviceB, t);
        t = t.plusMinutes(90);
        UUID bookingC = insertBooking(clientId, masterId, serviceC, t);

        ResponseEntity<String> resp = callMyBookings(
                fixtures.tokenFor(masterEmail), List.of(serviceA, serviceB), 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("data").path("totalElements").asLong())
                .as("union of two disjoint services must be 2, not 0 (intersection) and not 1 "
                        + "(only-first-service bug)")
                .isEqualTo(2);
        assertThat(fixtures.extractIds(root))
                .containsExactlyInAnyOrder(bookingA, bookingB)
                .doesNotContain(bookingC);
    }

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider path) — omitting serviceId entirely returns "
            + "every booking regardless of which service it is for (the no-predicate branch), "
            + "matching Phase 26.2's unfiltered output shape")
    void should_returnAllBookingsAcrossServices_when_noServiceIdParamSupplied() throws Exception {
        String masterEmail = "mbsf-nofilter-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbsf-nofilter-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);

        OffsetDateTime t = ANCHOR;
        UUID bookingA = insertBooking(clientId, masterId, serviceA, t);
        t = t.plusMinutes(90);
        UUID bookingB = insertBooking(clientId, masterId, serviceB, t);

        ResponseEntity<String> resp = callMyBookings(fixtures.tokenFor(masterEmail), List.of(), 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("data").path("totalElements").asLong()).isEqualTo(2);
        assertThat(fixtures.extractIds(root)).containsExactlyInAnyOrder(bookingA, bookingB);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Gap 2 — cross-actor IDOR safety, BOTH paths (real Postgres, real seeded rows)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider path) — Master A filtering by Master B's "
            + "serviceId gets an EMPTY page (200, not 403/404) — Master B's real, existing "
            + "bookings must never leak into Master A's response. Pins the locked "
            + "no-ownership-check-on-supplied-ids decision: the master scope predicate is the "
            + "security boundary, the service filter only narrows within it")
    void should_returnEmpty_when_serviceIdBelongsToAnotherMaster() throws Exception {
        String masterAEmail = "mbsf-idor-a-" + System.nanoTime() + "@beautica.test";
        UUID masterAId = fixtures.createIndependentMaster(masterAEmail);
        UUID clientAId = fixtures.createUser("mbsf-idor-a-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceAId = fixtures.createIndependentMasterService(masterAId);
        insertBooking(clientAId, masterAId, serviceAId, ANCHOR);
        insertBooking(clientAId, masterAId, serviceAId, ANCHOR.plusMinutes(90));

        String masterBEmail = "mbsf-idor-b-" + System.nanoTime() + "@beautica.test";
        UUID masterBId = fixtures.createIndependentMaster(masterBEmail);
        UUID clientBId = fixtures.createUser("mbsf-idor-b-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceBId = fixtures.createIndependentMasterService(masterBId);
        UUID masterBBooking = insertBooking(clientBId, masterBId, serviceBId, ANCHOR);

        ResponseEntity<String> resp = callMyBookings(
                fixtures.tokenFor(masterAEmail), List.of(serviceBId), 20);

        assertThat(resp.getStatusCode())
                .as("a foreign serviceId must still 200 — never a 403/404 existence oracle")
                .isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("data").path("totalElements").asLong())
                .as("Master A owns no booking on Master B's service, so the filtered scope is empty")
                .isEqualTo(0);
        assertThat(fixtures.extractIds(root))
                .as("Master B's real booking must never appear in Master A's page")
                .doesNotContain(masterBBooking)
                .isEmpty();
    }

    @Test
    @DisplayName("GET /me (CLIENT, single-query direct-pagination path) — Client A filtering by a "
            + "serviceId tied to a booking that belongs to Client B gets an EMPTY page — Client "
            + "B's real, existing booking on that exact service must never leak into Client A's "
            + "response, proving the client scope predicate (b.client.id = :clientId) is applied "
            + "BEFORE, not instead of, the serviceId narrowing")
    void should_returnEmpty_when_serviceIdBelongsToAnotherClient() throws Exception {
        String masterEmail = "mbsf-idor-c-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID sharedServiceId = fixtures.createIndependentMasterService(masterId);

        String clientAEmail = "mbsf-idor-c-a-" + System.nanoTime() + "@beautica.test";
        UUID clientAId = fixtures.createUser(clientAEmail, "CLIENT", null);
        UUID clientBId = fixtures.createUser("mbsf-idor-c-b-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        // Client A books a DIFFERENT service with the same master, never the shared one.
        UUID otherServiceId = fixtures.createIndependentMasterService(masterId);
        insertBooking(clientAId, masterId, otherServiceId, ANCHOR);

        // Client B is the only one who ever books the shared service.
        UUID clientBBooking = insertBooking(clientBId, masterId, sharedServiceId, ANCHOR.plusMinutes(90));

        ResponseEntity<String> resp = callMyBookings(
                fixtures.tokenFor(clientAEmail), List.of(sharedServiceId), 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("data").path("totalElements").asLong())
                .as("Client A has no booking on the shared service, so the filtered scope is empty")
                .isEqualTo(0);
        assertThat(fixtures.extractIds(root))
                .as("Client B's real booking on the shared service must never appear in Client A's page")
                .doesNotContain(clientBBooking)
                .isEmpty();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * Inserts a CONFIRMED booking row directly via SQL (bypassing the create-booking service flow
     * entirely) so the test can seed an arbitrary {@code masterService.id} + {@code starts_at}
     * deterministically. Deliberately NOT moved into {@link BookingTestFixtures} — its javadoc
     * documents that {@code insertBooking} helpers across the 26.x IT family have genuinely
     * diverged in column shape (this one is always CONFIRMED and takes no {@code status} param,
     * unlike the multi-status suite's), so each IT keeps its own local copy.
     */
    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, startsAt, startsAt.plusMinutes(60));
        return bookingId;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private ResponseEntity<String> callMyBookings(String token, List<UUID> serviceIds, Integer size) {
        List<String> parts = new ArrayList<>();
        for (UUID serviceId : serviceIds) {
            parts.add("serviceId=" + serviceId);
        }
        if (size != null) {
            parts.add("size=" + size);
        }
        String url = BOOKINGS_URL + "/me" + (parts.isEmpty() ? "" : "?" + String.join("&", parts));
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }
}
