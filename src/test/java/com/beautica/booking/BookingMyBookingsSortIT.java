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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-authored regression suite for Phase 26.3 (backend-qa, 2026-07-17): proves {@code GET
 * /bookings/me}'s {@code sort} query parameter ACTUALLY reorders results, over the full HTTP
 * stack — real Spring Security, a real {@link com.beautica.booking.service.BookingService}, and a
 * real Postgres Testcontainers instance. Mocks cannot validate an {@code ORDER BY}; only a real
 * query planner can.
 *
 * <p>The pre-Phase-26.3 bug was precisely "the {@code sort} param is accepted (200) but silently
 * inert" — {@code findIdsByMasterIdFiltered} / {@code findIdsBySalonIdsFiltered} /
 * {@code findClientBookingDetails} each hardcoded {@code ORDER BY b.startsAt DESC} in JPQL, and
 * Spring Data APPENDS a caller's {@code Pageable} sort to an existing {@code ORDER BY} rather than
 * replacing it — so {@code ?sort=priceAtBooking,desc} silently became {@code ORDER BY
 * b.starts_at DESC, b.price_at_booking DESC}, where {@code startsAt} (unique per master — no
 * double-booking) already fully determines the order and the price term never fires. A test that
 * only asserts {@code 200} proves nothing here; every fixture below seeds price order and
 * {@code startsAt} order to DISAGREE, so a test correctly asserting "prices descend" fails against
 * the pre-fix code (which would still be startsAt-ordered) and passes only once the hardcoded
 * {@code ORDER BY} is gone and {@code Pageable.getSort()} is the sole ordering source.
 *
 * <p>Also covers:
 * <ul>
 *   <li>the mandatory {@code id ASC} tiebreaker's pagination-determinism guarantee under ties
 *       (many bookings sharing an identical {@code priceAtBooking}, paged with {@code OFFSET});</li>
 *   <li>the security whitelist's HTTP-layer boundary — {@code ?sort=master.user.passwordHash,asc}
 *       and {@code ?sort=bogus,desc} must both 400 without ever reaching the JPQL/Criteria layer,
 *       and the 400 body must not echo the rejected property string back to the caller.</li>
 * </ul>
 *
 * @see BookingMyBookingsMultiStatusFilterIT for the sibling multi-status-filter suite (Phase
 *      26.1), whose ground-truth ordering deliberately uses only the endpoint's DEFAULT sort.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /bookings/me — Phase 26.3 sort param, full HTTP stack over real Postgres")
class BookingMyBookingsSortIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final OffsetDateTime ANCHOR =
            OffsetDateTime.of(2032, 4, 4, 8, 0, 0, 0, ZoneOffset.UTC);

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
    // Security tripwire — HTTP-layer whitelist boundary (backend-security gap)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me — ?sort=master.user.passwordHash,asc returns 400 over the real HTTP stack, "
            + "and the generic BAD_REQUEST body neither echoes the rejected property string nor "
            + "reaches the JPQL/Criteria layer — proves the whitelist closes the credential-ordering "
            + "side channel end-to-end, not just by code trace")
    void should_return400WithoutEchoingProperty_when_sortPropertyIsDottedPasswordHashPath() throws Exception {
        String clientEmail = "mbsort-idor-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);

        ResponseEntity<String> resp = callMyBookings(
                fixtures.tokenFor(clientEmail), "master.user.passwordHash,asc", null, null);

        assertThat(resp.getStatusCode())
                .as("a dotted join-path sort property must be rejected as a client error, never "
                        + "silently accepted or a 500")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("success").asBoolean()).isFalse();

        String message = root.path("message").asText("");
        assertThat(message)
                .as("BAD_REQUEST BusinessExceptions are genericised by GlobalExceptionHandler — the "
                        + "rejected property string (which would confirm to an attacker that the "
                        + "server actually evaluated their dotted path) must never be echoed")
                .isEqualTo("Invalid request")
                .doesNotContainIgnoringCase("passwordHash")
                .doesNotContainIgnoringCase("master.user");
    }

    @Test
    @DisplayName("GET /me — ?sort=bogus,desc returns 400 (not 200, not 500) for a plain unrecognised "
            + "sort property")
    void should_return400_when_sortPropertyIsBogus() throws Exception {
        String clientEmail = "mbsort-bogus-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);

        ResponseEntity<String> resp = callMyBookings(fixtures.tokenFor(clientEmail), "bogus,desc", null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The sort must ACTUALLY reorder results — provider path (ID-page + hydrate)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider ID-page+hydrate path) — "
            + "?sort=priceAtBooking,desc returns rows in DESCENDING price order, which DISAGREES "
            + "with the startsAt-insertion order of the fixture — a test that only checked 200 or "
            + "reused startsAt-correlated prices would have passed identically before Phase 26.3, "
            + "proving nothing about the fix")
    void should_reorderByPriceDescending_when_sortPriceAtBookingDescRequested_onProviderPath()
            throws Exception {
        String masterEmail = "mbsort-provider-desc-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbsort-provider-desc-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        // Prices deliberately NOT monotonic with insertion/startsAt order.
        String[] prices = {"300.00", "700.00", "100.00", "900.00", "500.00"};
        seedBookingsWithPrices(clientId, masterId, serviceId, prices);

        List<UUID> groundTruth = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE master_id = ? ORDER BY price_at_booking DESC, id ASC",
                UUID.class, masterId);
        List<UUID> startsAtDescOrder = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE master_id = ? ORDER BY starts_at DESC, id ASC",
                UUID.class, masterId);
        assertThat(groundTruth)
                .as("fixture sanity check: price-desc order must differ from startsAt-desc order, or "
                        + "this test cannot distinguish the fix from the pre-fix no-op tiebreaker bug")
                .isNotEqualTo(startsAtDescOrder);

        ResponseEntity<String> resp = callMyBookings(fixtures.tokenFor(masterEmail), "priceAtBooking,desc", 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root))
                .as("response order must match the TRUE price-descending ground truth, not the "
                        + "default startsAt-descending order")
                .containsExactlyElementsOf(groundTruth);
    }

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider ID-page+hydrate path) — "
            + "?sort=priceAtBooking,asc returns rows in ASCENDING price order")
    void should_reorderByPriceAscending_when_sortPriceAtBookingAscRequested_onProviderPath()
            throws Exception {
        String masterEmail = "mbsort-provider-asc-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbsort-provider-asc-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        String[] prices = {"300.00", "700.00", "100.00", "900.00", "500.00"};
        seedBookingsWithPrices(clientId, masterId, serviceId, prices);

        List<UUID> groundTruth = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE master_id = ? ORDER BY price_at_booking ASC, id ASC",
                UUID.class, masterId);

        ResponseEntity<String> resp = callMyBookings(fixtures.tokenFor(masterEmail), "priceAtBooking,asc", 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root)).containsExactlyElementsOf(groundTruth);
    }

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider path) — ?sort=startsAt,asc returns "
            + "oldest-first — this exact param was broken identically to the price case pre-26.3 "
            + "(appended AFTER the hardcoded startsAt DESC, making it a no-op)")
    void should_returnOldestFirst_when_sortStartsAtAscRequested_onProviderPath() throws Exception {
        String masterEmail = "mbsort-startsasc-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbsort-startsasc-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        OffsetDateTime t = ANCHOR;
        for (int i = 0; i < 5; i++) {
            insertBooking(clientId, masterId, serviceId, null, "CONFIRMED", t, "500.00");
            t = t.plusMinutes(90);
        }

        List<UUID> groundTruthAsc = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE master_id = ? ORDER BY starts_at ASC, id ASC",
                UUID.class, masterId);

        ResponseEntity<String> resp = callMyBookings(fixtures.tokenFor(masterEmail), "startsAt,asc", 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root))
                .as("oldest-first: the FIRST inserted booking (earliest startsAt) must be first in "
                        + "the response — pre-26.3 the hardcoded ORDER BY startsAt DESC dominated and "
                        + "this would have returned newest-first instead")
                .containsExactlyElementsOf(groundTruthAsc);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The sort must ACTUALLY reorder results — CLIENT path (single-query projection)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (CLIENT, single-query projection path) — ?sort=priceAtBooking,desc "
            + "returns rows in DESCENDING price order, proving findClientBookingDetails' Pageable-"
            + "driven ORDER BY works independently of the provider ID-page path")
    void should_reorderByPriceDescending_when_sortPriceAtBookingDescRequested_onClientPath()
            throws Exception {
        String clientEmail = "mbsort-client-desc-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        String masterEmail = "mbsort-client-desc-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        String[] prices = {"200.00", "800.00", "50.00", "650.00", "400.00"};
        seedBookingsWithPrices(clientId, masterId, serviceId, prices);

        List<UUID> groundTruth = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE client_id = ? ORDER BY price_at_booking DESC, id ASC",
                UUID.class, clientId);
        List<UUID> startsAtDescOrder = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE client_id = ? ORDER BY starts_at DESC, id ASC",
                UUID.class, clientId);
        assertThat(groundTruth).isNotEqualTo(startsAtDescOrder);

        ResponseEntity<String> resp = callMyBookings(fixtures.tokenFor(clientEmail), "priceAtBooking,desc", 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root)).containsExactlyElementsOf(groundTruth);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Backward compatibility — no sort param still yields the pre-existing default
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (provider path) — no sort param at all still defaults to startsAt DESC, "
            + "unchanged from pre-Phase-26.3 behaviour (backward-compat guard, not a bug "
            + "reproduction — this is the one sort test that ALSO passed before the fix)")
    void should_useDefaultStartsAtDescending_when_noSortParamSupplied() throws Exception {
        String masterEmail = "mbsort-default-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbsort-default-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        OffsetDateTime t = ANCHOR;
        for (int i = 0; i < 5; i++) {
            insertBooking(clientId, masterId, serviceId, null, "CONFIRMED", t, "500.00");
            t = t.plusMinutes(90);
        }

        List<UUID> groundTruthDesc = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE master_id = ? ORDER BY starts_at DESC, id ASC",
                UUID.class, masterId);

        ResponseEntity<String> resp = callMyBookings(fixtures.tokenFor(masterEmail), null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root)).containsExactlyElementsOf(groundTruthDesc);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The mandatory id tiebreaker — pagination determinism under price ties
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (provider path) — 25 bookings sharing an IDENTICAL priceAtBooking, paged "
            + "with size=10 across 3 pages under ?sort=priceAtBooking,desc, reproduce the full "
            + "seeded set exactly once each — no duplicates, no gaps. Price ties are the COMMON "
            + "case (every booking of one service shares a price), so without the mandatory id "
            + "tiebreaker OFFSET pagination over tied rows has no guaranteed stable order")
    void should_maintainDeterministicPagination_when_manyBookingsShareIdenticalPrice() throws Exception {
        String masterEmail = "mbsort-tiebreak-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbsort-tiebreak-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        int total = 25;
        OffsetDateTime t = ANCHOR;
        Set<UUID> seeded = new LinkedHashSet<>();
        for (int i = 0; i < total; i++) {
            seeded.add(insertBooking(clientId, masterId, serviceId, null, "CONFIRMED", t, "500.00"));
            t = t.plusMinutes(90);
        }

        String token = fixtures.tokenFor(masterEmail);
        int pageSize = 10;
        int totalPages = 3;
        List<UUID> collected = new ArrayList<>();

        for (int page = 0; page < totalPages; page++) {
            ResponseEntity<String> resp = callMyBookings(token, "priceAtBooking,desc", page, pageSize);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode root = objectMapper.readTree(resp.getBody());
            assertThat(root.path("data").path("totalElements").asLong()).isEqualTo(total);
            collected.addAll(fixtures.extractIds(root));
        }

        assertThat(collected)
                .as("concatenating all 3 pages of an all-tied price sort must reproduce the full "
                        + "seeded set exactly once each — a broken/missing id tiebreaker can "
                        + "duplicate or skip rows across OFFSET page boundaries when Postgres orders "
                        + "tied rows differently per query")
                .hasSize(total)
                .containsExactlyInAnyOrderElementsOf(seeded);
        assertThat(new HashSet<>(collected))
                .as("no id appears on more than one page")
                .hasSize(total);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * Seeds one CONFIRMED booking per element of {@code prices}, 90 minutes apart in ASCENDING
     * {@code startsAt} order (insertion order), so {@code startsAt} order is always [0..n) while
     * price order is whatever the caller supplied — deliberately decoupled, so a test asserting
     * "price order" cannot be satisfied by accident via startsAt order.
     */
    private void seedBookingsWithPrices(UUID clientId, UUID masterId, UUID masterServiceId, String[] prices) {
        OffsetDateTime t = ANCHOR;
        for (String price : prices) {
            insertBooking(clientId, masterId, masterServiceId, null, "CONFIRMED", t, price);
            t = t.plusMinutes(90);
        }
    }

    /**
     * Inserts a booking row directly via SQL (bypassing the create/decline/complete service
     * flows) so the test can seed an arbitrary status + startsAt + price deterministically.
     * {@code salonId} is nullable — omitted from the INSERT column list for an independent
     * master, mirroring {@code BookingMyBookingsMultiStatusFilterIT}'s convention.
     */
    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId,
                                String status, OffsetDateTime startsAt, String price) {
        UUID bookingId = UUID.randomUUID();
        BigDecimal priceAtBooking = new BigDecimal(price);
        if (salonId != null) {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 60, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId, salonId, status,
                    startsAt, startsAt.plusMinutes(60), priceAtBooking);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 60, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId, status,
                    startsAt, startsAt.plusMinutes(60), priceAtBooking);
        }
        return bookingId;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private ResponseEntity<String> callMyBookings(String token, String sort, Integer page, Integer size) {
        List<String> parts = new ArrayList<>();
        if (sort != null) {
            parts.add("sort=" + sort);
        }
        if (page != null) {
            parts.add("page=" + page);
        }
        if (size != null) {
            parts.add("size=" + size);
        }
        String url = BOOKINGS_URL + "/me" + (parts.isEmpty() ? "" : "?" + String.join("&", parts));
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }
}
