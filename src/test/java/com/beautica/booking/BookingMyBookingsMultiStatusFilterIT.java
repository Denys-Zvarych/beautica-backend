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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-authored regression suite closing the Phase 26.1 audit gaps (backend-qa, 2026-07-17):
 * multi-select {@code ?status=} on {@code GET /bookings/me} exercised over the FULL HTTP stack —
 * real Spring Security, a real {@link com.beautica.booking.service.BookingService}, and a real
 * Postgres Testcontainers instance. Mocks cannot validate the {@code (:statuses IS NULL OR
 * b.status IN :statuses)} JPQL binding or the two-query provider ID-page-then-hydrate pattern's
 * ordering — this suite is deliberately real-DB, no {@code @MockBean} on the service/repository.
 *
 * <p>Covers four gaps the pre-existing unit/slice/repository coverage left open:
 * <ol>
 *   <li><b>HTTP-layer enum rejection</b> — {@code ?status=BOGUS} must 400, not 500, and the
 *       body must not echo the valid enum constants (a Jackson-default leak risk).</li>
 *   <li><b>Role-dispatch + ownership scope</b> — INDEPENDENT_MASTER, SALON_OWNER, SALON_ADMIN,
 *       CLIENT, each against REAL seeded data from a second, unrelated actor, proving the
 *       widened {@code List<BookingStatus>} parameter did not loosen the scope predicate.</li>
 *   <li><b>Union / no-filter / de-dup semantics against real Postgres</b> — the provider ID-page
 *       query family ({@code findIdsByMasterIdFiltered}) has NO existing {@code @DataJpaTest}
 *       (unlike the CLIENT projection, which {@code ClientBookingDetailProjectionTest} already
 *       covers) — this is the first real-DB proof for that query.</li>
 *   <li><b>Page-boundary semantics under a multi-status filter</b> — seeds enough rows to span
 *       multiple pages and asserts each page against a ground-truth ordering computed via an
 *       INDEPENDENT SQL query at test time (never hand-picked), so a broken page boundary in
 *       either the provider ID-page+hydrate+reorder path or the CLIENT single-query path would
 *       fail this test even though the wiring (status forwarded correctly, 200 returned) is
 *       otherwise intact.</li>
 * </ol>
 *
 * <p><b>Explicitly out of scope</b> — {@code sort}-param correctness (whitelisting, actual
 * reordering by {@code priceAtBooking}/{@code startsAt}, and the mandatory {@code id} tiebreaker
 * under price ties) is covered separately by {@code BookingMyBookingsSortIT} (Phase 26.3). No
 * test here passes a {@code sort} param. The ground-truth ordering used by the pagination tests
 * below is the endpoint's DEFAULT ordering (no {@code sort} param supplied), which — as of Phase
 * 26.3 — is {@code ORDER BY starts_at DESC, id ASC}, sourced from {@code Pageable.getSort()} (the
 * hardcoded JPQL {@code ORDER BY} these queries used to carry was removed; see
 * {@code BookingService#normalizeBookingSort}). This class pins page-boundary CONSISTENCY (page 2
 * continues exactly where page 1 stopped) under the default sort, not sort-param correctness.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /bookings/me — Phase 26.1 multi-status filter, full HTTP stack over real Postgres")
class BookingMyBookingsMultiStatusFilterIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final OffsetDateTime ANCHOR =
            OffsetDateTime.of(2031, 3, 3, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final List<String> ALL_STATUSES =
            List.of("CONFIRMED", "COMPLETED", "NOT_COMPLETED", "CANCELLED", "DECLINED");

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
    // Gap 1 — HTTP-layer enum rejection
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me — ?status=BOGUS returns 400 (not 500) over the real HTTP stack, and the "
            + "body does not echo the valid BookingStatus constants")
    void should_return400WithoutEchoingEnumConstants_when_statusParamIsUnrecognised() throws Exception {
        String clientEmail = "mbmsf-enum-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);

        ResponseEntity<String> resp = callMyBookings(fixtures.tokenFor(clientEmail), List.of("BOGUS"), null, null);

        assertThat(resp.getStatusCode())
                .as("an unrecognised status value must be rejected as a client error, never a 500")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("success").asBoolean())
                .as("the envelope must report success=false")
                .isFalse();

        String messageLower = root.path("message").asText("").toLowerCase();
        for (String validConstant : ALL_STATUSES) {
            assertThat(messageLower)
                    .as("the 400 body must NOT leak the valid enum surface (%s) — Jackson's default "
                            + "type-mismatch message echoes 'valid values: [...]' unless explicitly "
                            + "overridden, which GlobalExceptionHandler.handleTypeMismatch does",
                            validConstant)
                    .doesNotContain(validConstant.toLowerCase());
        }
        assertThat(messageLower)
                .as("retired PENDING status must never surface in a client-facing error either")
                .doesNotContain("pending");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Gap 2 — role dispatch + ownership scope, real seeded data
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me — INDEPENDENT_MASTER filtering ?status=CONFIRMED&status=COMPLETED sees "
            + "exactly their own bookings in those two statuses, none of their own other-status "
            + "bookings, and none of an unrelated master's bookings")
    void should_returnOnlyOwnBookingsInRequestedStatuses_when_independentMasterFiltersMultiStatus()
            throws Exception {
        String masterAEmail = "mbmsf-im-a-" + System.nanoTime() + "@beautica.test";
        UUID masterAId = fixtures.createIndependentMaster(masterAEmail);
        UUID clientAId = fixtures.createUser("mbmsf-im-a-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceAId = fixtures.createIndependentMasterService(masterAId);

        OffsetDateTime t = ANCHOR;
        Set<UUID> confirmedAndCompletedA = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            confirmedAndCompletedA.add(insertBooking(clientAId, masterAId, serviceAId, null, "CONFIRMED", t));
            t = t.plusMinutes(90);
        }
        for (int i = 0; i < 2; i++) {
            confirmedAndCompletedA.add(insertBooking(clientAId, masterAId, serviceAId, null, "COMPLETED", t));
            t = t.plusMinutes(90);
        }
        UUID cancelledA = insertBooking(clientAId, masterAId, serviceAId, null, "CANCELLED", t);
        t = t.plusMinutes(90);
        UUID declinedA = insertBooking(clientAId, masterAId, serviceAId, null, "DECLINED", t);

        // Unrelated master B — same statuses, different master. Must never leak into A's page.
        String masterBEmail = "mbmsf-im-b-" + System.nanoTime() + "@beautica.test";
        UUID masterBId = fixtures.createIndependentMaster(masterBEmail);
        UUID clientBId = fixtures.createUser("mbmsf-im-b-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceBId = fixtures.createIndependentMasterService(masterBId);
        OffsetDateTime tb = ANCHOR;
        for (int i = 0; i < 2; i++) {
            insertBooking(clientBId, masterBId, serviceBId, null, "CONFIRMED", tb);
            tb = tb.plusMinutes(90);
        }
        for (int i = 0; i < 2; i++) {
            insertBooking(clientBId, masterBId, serviceBId, null, "COMPLETED", tb);
            tb = tb.plusMinutes(90);
        }

        ResponseEntity<String> resp = callMyBookings(
                fixtures.tokenFor(masterAEmail), List.of("CONFIRMED", "COMPLETED"), null, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("data").path("totalElements").asLong())
                .as("master A must see exactly their own 5 CONFIRMED+COMPLETED bookings")
                .isEqualTo(5);

        List<UUID> ids = fixtures.extractIds(root);
        assertThat(ids)
                .as("returned rows must be EXACTLY master A's CONFIRMED+COMPLETED bookings — no "
                        + "master-B leakage, no own-CANCELLED/DECLINED leakage")
                .containsExactlyInAnyOrderElementsOf(confirmedAndCompletedA);
        assertThat(ids)
                .doesNotContain(cancelledA, declinedA)
                .as("own out-of-filter statuses must be excluded")
                .isNotEmpty();
    }

    @Test
    @DisplayName("GET /me — SALON_OWNER filtering multi-status sees only their own salon's matching "
            + "bookings, never another owner's salon")
    void should_returnOnlyOwnSalonBookings_when_salonOwnerFiltersMultiStatus() throws Exception {
        BookingTestFixtures.SalonFixture salonA = fixtures.createSalon("mbmsf-owner-a-" + System.nanoTime() + "@beautica.test");
        UUID clientAId = fixtures.createUser("mbmsf-owner-a-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceAId = fixtures.createSalonService(salonA.salonId(), salonA.masterId());

        OffsetDateTime t = ANCHOR;
        Set<UUID> expectedA = new HashSet<>();
        expectedA.add(insertBooking(clientAId, salonA.masterId(), serviceAId, salonA.salonId(), "CONFIRMED", t));
        t = t.plusMinutes(90);
        expectedA.add(insertBooking(clientAId, salonA.masterId(), serviceAId, salonA.salonId(), "CONFIRMED", t));
        t = t.plusMinutes(90);
        expectedA.add(insertBooking(clientAId, salonA.masterId(), serviceAId, salonA.salonId(), "COMPLETED", t));
        t = t.plusMinutes(90);
        UUID cancelledA = insertBooking(clientAId, salonA.masterId(), serviceAId, salonA.salonId(), "CANCELLED", t);

        BookingTestFixtures.SalonFixture salonB = fixtures.createSalon("mbmsf-owner-b-" + System.nanoTime() + "@beautica.test");
        UUID clientBId = fixtures.createUser("mbmsf-owner-b-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceBId = fixtures.createSalonService(salonB.salonId(), salonB.masterId());
        OffsetDateTime tb = ANCHOR;
        insertBooking(clientBId, salonB.masterId(), serviceBId, salonB.salonId(), "CONFIRMED", tb);
        tb = tb.plusMinutes(90);
        insertBooking(clientBId, salonB.masterId(), serviceBId, salonB.salonId(), "COMPLETED", tb);

        ResponseEntity<String> resp = callMyBookings(
                fixtures.tokenFor(salonA.ownerEmail()), List.of("CONFIRMED", "COMPLETED"), null, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        List<UUID> ids = fixtures.extractIds(root);
        assertThat(ids)
                .as("owner A must see exactly their own salon's 3 CONFIRMED+COMPLETED bookings — "
                        + "never salon B's")
                .containsExactlyInAnyOrderElementsOf(expectedA)
                .doesNotContain(cancelledA);
        assertThat(root.path("data").path("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("GET /me — SALON_ADMIN is still denied with 403 even with the widened multi-value "
            + "status param (end-to-end, real service — not the mocked ForbiddenException unit test)")
    void should_return403_when_salonAdminListsMyBookingsWithMultiStatusParam() throws Exception {
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon("mbmsf-admin-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "mbmsf-admin-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", salon.salonId());

        ResponseEntity<String> resp = callMyBookings(
                fixtures.tokenFor(adminEmail), List.of("CONFIRMED", "COMPLETED"), null, null);

        assertThat(resp.getStatusCode())
                .as("SALON_ADMIN must be rejected by /bookings/me regardless of the status param shape")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /me — CLIENT filtering multi-status sees only their own bookings, never "
            + "another client's")
    void should_returnOnlyOwnBookings_when_clientFiltersMultiStatus() throws Exception {
        String masterEmail = "mbmsf-client-scope-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        String clientAEmail = "mbmsf-client-a-" + System.nanoTime() + "@beautica.test";
        UUID clientAId = fixtures.createUser(clientAEmail, "CLIENT", null);
        UUID clientBId = fixtures.createUser("mbmsf-client-b-" + System.nanoTime() + "@beautica.test", "CLIENT", null);

        OffsetDateTime t = ANCHOR;
        Set<UUID> expectedA = new HashSet<>();
        expectedA.add(insertBooking(clientAId, masterId, serviceId, null, "CONFIRMED", t));
        t = t.plusMinutes(90);
        expectedA.add(insertBooking(clientAId, masterId, serviceId, null, "CONFIRMED", t));
        t = t.plusMinutes(90);
        expectedA.add(insertBooking(clientAId, masterId, serviceId, null, "COMPLETED", t));
        t = t.plusMinutes(90);
        UUID declinedA = insertBooking(clientAId, masterId, serviceId, null, "DECLINED", t);
        t = t.plusMinutes(90);

        insertBooking(clientBId, masterId, serviceId, null, "CONFIRMED", t);
        t = t.plusMinutes(90);
        insertBooking(clientBId, masterId, serviceId, null, "COMPLETED", t);

        ResponseEntity<String> resp = callMyBookings(
                fixtures.tokenFor(clientAEmail), List.of("CONFIRMED", "COMPLETED"), null, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        List<UUID> ids = fixtures.extractIds(root);
        assertThat(ids)
                .as("client A must see exactly their own 3 CONFIRMED+COMPLETED bookings — never "
                        + "client B's, never their own DECLINED")
                .containsExactlyInAnyOrderElementsOf(expectedA)
                .doesNotContain(declinedA);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Gap 3 — union / no-filter / de-dup semantics against real Postgres
    // (the provider ID-page query family — findIdsByMasterIdFiltered — has NO
    // existing @DataJpaTest, unlike the CLIENT projection which
    // ClientBookingDetailProjectionTest already covers)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me — no status param returns ALL statuses (the ':statuses IS NULL' branch), "
            + "proven against real Postgres via the provider ID-page query")
    void should_returnAllStatuses_when_noStatusParamSupplied() throws Exception {
        String masterEmail = "mbmsf-nofilter-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbmsf-nofilter-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        Map<String, UUID> byStatus = seedOneBookingPerStatus(clientId, masterId, serviceId, null);

        ResponseEntity<String> resp = callMyBookings(fixtures.tokenFor(masterEmail), List.of(), null, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("data").path("totalElements").asLong())
                .as("absent status param must return every status, unfiltered")
                .isEqualTo(ALL_STATUSES.size());
        assertThat(fixtures.extractIds(root)).containsExactlyInAnyOrderElementsOf(byStatus.values());
    }

    @Test
    @DisplayName("GET /me — repeated identical ?status=CONFIRMED&status=CONFIRMED de-duplicates to "
            + "the SAME result as a single ?status=CONFIRMED (EnumSet bound, no duplicate rows)")
    void should_returnIdenticalResult_when_statusParamRepeatedWithDuplicateValue() throws Exception {
        String masterEmail = "mbmsf-dedupe-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbmsf-dedupe-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        Map<String, UUID> byStatus = seedOneBookingPerStatus(clientId, masterId, serviceId, null);
        String token = fixtures.tokenFor(masterEmail);

        ResponseEntity<String> singleResp = callMyBookings(token, List.of("CONFIRMED"), null, 20);
        ResponseEntity<String> dupResp = callMyBookings(token, List.of("CONFIRMED", "CONFIRMED"), null, 20);

        JsonNode singleRoot = objectMapper.readTree(singleResp.getBody());
        JsonNode dupRoot = objectMapper.readTree(dupResp.getBody());

        assertThat(dupRoot.path("data").path("totalElements").asLong())
                .as("a duplicated status value must not double-count rows")
                .isEqualTo(1)
                .isEqualTo(singleRoot.path("data").path("totalElements").asLong());
        assertThat(fixtures.extractIds(dupRoot))
                .as("the duplicate-value request must return the identical single-element result "
                        + "as the non-duplicated request")
                .containsExactly(byStatus.get("CONFIRMED"))
                .isEqualTo(fixtures.extractIds(singleRoot));
    }

    @Test
    @DisplayName("GET /me — a two-status filter returns the exact UNION of matching bookings — not "
            + "an intersection (which would be empty) and not only the first status")
    void should_returnExactUnion_when_twoDisjointStatusesSupplied() throws Exception {
        String masterEmail = "mbmsf-union-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbmsf-union-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        Map<String, UUID> byStatus = seedOneBookingPerStatus(clientId, masterId, serviceId, null);

        ResponseEntity<String> resp = callMyBookings(
                fixtures.tokenFor(masterEmail), List.of("CANCELLED", "DECLINED"), null, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("data").path("totalElements").asLong())
                .as("union of two disjoint statuses must be 2, not 0 (intersection) and not 1 "
                        + "(only-first-status bug)")
                .isEqualTo(2);
        assertThat(fixtures.extractIds(root))
                .containsExactlyInAnyOrder(byStatus.get("CANCELLED"), byStatus.get("DECLINED"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Gap 4 — page-boundary semantics under a multi-status filter (THE important one)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider ID-page+hydrate+reorder path) — a "
            + "multi-status filter spanning 3 pages returns each page consistent with the TRUE "
            + "global ordering computed independently at test time, not a hand-picked fixture")
    void should_returnPagesConsistentWithGroundTruthOrdering_when_providerMultiStatusFilterSpansMultiplePages()
            throws Exception {
        String masterEmail = "mbmsf-page-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbmsf-page-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        // 23 in-filter rows (alternating CONFIRMED/COMPLETED) + 3 out-of-filter CANCELLED rows,
        // every row on a DISTINCT starts_at so ORDER BY starts_at DESC is a total order (no ties
        // -> no flaky page-boundary assignment). CONFIRMED rows are 90 min apart (> 60 min
        // duration) so the no_overlapping_bookings EXCLUDE constraint is never hit.
        OffsetDateTime t = ANCHOR;
        int inFilterCount = 23;
        for (int i = 0; i < inFilterCount; i++) {
            String status = (i % 2 == 0) ? "CONFIRMED" : "COMPLETED";
            insertBooking(clientId, masterId, serviceId, null, status, t);
            t = t.plusMinutes(90);
        }
        for (int i = 0; i < 3; i++) {
            insertBooking(clientId, masterId, serviceId, null, "CANCELLED", t);
            t = t.plusMinutes(90);
        }

        // Ground truth: computed by an INDEPENDENT SQL query using the SAME ORDER BY the
        // repository hardcodes today (`ORDER BY b.starts_at DESC`) — never by predicting what
        // the endpoint "should" return. This is page-boundary consistency, not a `sort`-param
        // correctness claim (that param is out of scope — see class javadoc).
        List<UUID> groundTruth = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE master_id = ? AND status IN ('CONFIRMED','COMPLETED') "
                        + "ORDER BY starts_at DESC",
                UUID.class, masterId);
        assertThat(groundTruth).as("fixture sanity check").hasSize(inFilterCount);

        String token = fixtures.tokenFor(masterEmail);
        int pageSize = 10;
        int totalPages = 3;
        List<UUID> collectedAcrossPages = new ArrayList<>();

        for (int page = 0; page < totalPages; page++) {
            ResponseEntity<String> resp = callMyBookings(token, List.of("CONFIRMED", "COMPLETED"), page, pageSize);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode root = objectMapper.readTree(resp.getBody());

            assertThat(root.path("data").path("totalElements").asLong())
                    .as("page %d: totalElements must reflect the FILTERED count, not the unfiltered 26",
                            page)
                    .isEqualTo(inFilterCount);
            assertThat(root.path("data").path("totalPages").asInt())
                    .as("page %d: totalPages must be ceil(23/10) = 3", page)
                    .isEqualTo(totalPages);

            List<UUID> pageIds = fixtures.extractIds(root);
            int from = page * pageSize;
            int to = Math.min(inFilterCount, from + pageSize);
            assertThat(pageIds)
                    .as("page %d must equal the ground-truth slice [%d,%d) of the TRUE DB ordering — "
                            + "a broken page boundary in the ID-page+hydrate+reorder path would "
                            + "duplicate, drop, or misorder rows here even though status filtering "
                            + "and total counts look correct", page, from, to)
                    .containsExactlyElementsOf(groundTruth.subList(from, to));
            collectedAcrossPages.addAll(pageIds);
        }

        assertThat(collectedAcrossPages)
                .as("concatenating all 3 pages must reproduce the full ground-truth set exactly "
                        + "once each — no row duplicated across a page boundary, none dropped")
                .hasSize(inFilterCount)
                .containsExactlyInAnyOrderElementsOf(groundTruth);
        assertThat(new HashSet<>(collectedAcrossPages))
                .as("no duplicate id appears across pages")
                .hasSize(inFilterCount);
    }

    @Test
    @DisplayName("GET /me (CLIENT, single-query direct-pagination path) — a multi-status filter "
            + "spanning 2 pages returns each page consistent with the TRUE global ordering computed "
            + "independently at test time")
    void should_returnPagesConsistentWithGroundTruthOrdering_when_clientMultiStatusFilterSpansMultiplePages()
            throws Exception {
        String masterEmail = "mbmsf-cpage-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        String clientEmail = "mbmsf-cpage-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);

        OffsetDateTime t = ANCHOR;
        int inFilterCount = 15;
        for (int i = 0; i < inFilterCount; i++) {
            String status = (i % 2 == 0) ? "CONFIRMED" : "DECLINED";
            insertBooking(clientId, masterId, serviceId, null, status, t);
            t = t.plusMinutes(90);
        }
        // Out-of-filter noise.
        insertBooking(clientId, masterId, serviceId, null, "CANCELLED", t);

        List<UUID> groundTruth = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE client_id = ? AND status IN ('CONFIRMED','DECLINED') "
                        + "ORDER BY starts_at DESC",
                UUID.class, clientId);
        assertThat(groundTruth).hasSize(inFilterCount);

        String token = fixtures.tokenFor(clientEmail);
        int pageSize = 10;
        List<UUID> collectedAcrossPages = new ArrayList<>();

        for (int page = 0; page < 2; page++) {
            ResponseEntity<String> resp = callMyBookings(token, List.of("CONFIRMED", "DECLINED"), page, pageSize);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode root = objectMapper.readTree(resp.getBody());
            assertThat(root.path("data").path("totalElements").asLong()).isEqualTo(inFilterCount);

            List<UUID> pageIds = fixtures.extractIds(root);
            int from = page * pageSize;
            int to = Math.min(inFilterCount, from + pageSize);
            assertThat(pageIds)
                    .as("CLIENT-path page %d must equal the ground-truth slice [%d,%d)", page, from, to)
                    .containsExactlyElementsOf(groundTruth.subList(from, to));
            collectedAcrossPages.addAll(pageIds);
        }

        assertThat(collectedAcrossPages)
                .as("both pages together must reproduce the full ground-truth set exactly once each")
                .containsExactlyInAnyOrderElementsOf(groundTruth);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * Inserts a booking row directly via SQL (bypassing the create/decline/complete service
     * flows entirely) so the test can seed an arbitrary status + starts_at deterministically.
     * {@code salonId} is nullable — omitted from the INSERT column list for an independent
     * master (rather than bound as a JDBC null) to mirror the sibling ITs' convention.
     */
    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId,
                                String status, OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        if (salonId != null) {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId, salonId, status,
                    startsAt, startsAt.plusMinutes(60));
        } else {
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                    bookingId, clientId, masterId, masterServiceId, status,
                    startsAt, startsAt.plusMinutes(60));
        }
        return bookingId;
    }

    /** Inserts exactly one booking per {@link #ALL_STATUSES} value, 90 minutes apart. */
    private Map<String, UUID> seedOneBookingPerStatus(
            UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId) {
        Map<String, UUID> byStatus = new LinkedHashMap<>();
        OffsetDateTime t = ANCHOR;
        for (String status : ALL_STATUSES) {
            byStatus.put(status, insertBooking(clientId, masterId, masterServiceId, salonId, status, t));
            t = t.plusMinutes(90);
        }
        return byStatus;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private ResponseEntity<String> callMyBookings(String token, List<String> statuses, Integer page, Integer size) {
        List<String> parts = new ArrayList<>();
        for (String s : statuses) {
            parts.add("status=" + s);
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
