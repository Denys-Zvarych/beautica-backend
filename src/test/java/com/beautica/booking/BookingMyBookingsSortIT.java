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
 * QA-authored regression suite for Phase 26.3 (backend-qa, 2026-07-17), re-shaped by Phase 26.8
 * (2026-07-19): proves {@code GET /bookings/me}'s {@code sort} query parameter ACTUALLY reorders
 * results, over the full HTTP stack — real Spring Security, a real
 * {@link com.beautica.booking.service.BookingService}, and a real Postgres Testcontainers
 * instance. Mocks cannot validate an {@code ORDER BY}; only a real query planner can.
 *
 * <p>The pre-Phase-26.3 bug was precisely "the {@code sort} param is accepted (200) but silently
 * inert" — {@code findIdsByMasterIdFiltered} / {@code findIdsBySalonIdsFiltered} /
 * {@code findClientBookingDetails} each hardcoded {@code ORDER BY b.startsAt DESC} in JPQL, and
 * Spring Data APPENDS a caller's {@code Pageable} sort to an existing {@code ORDER BY} rather than
 * replacing it. Phase 26.3 fixed that and whitelisted two properties, {@code startsAt} and
 * {@code priceAtBooking}.
 *
 * <p><b>Phase 26.8</b> retired {@code priceAtBooking} from the whitelist: mobile Phase 7.8 deleted
 * the provider "Мої записи" sort sheet — {@code priceAtBooking}'s only caller anywhere in the
 * product — once that screen became a timeline (a card's position derives from {@code startsAt};
 * no ordering of the result set can move it). Every test that used to assert price-descending /
 * price-ascending ORDERING now asserts price sort is REJECTED (400), paired with a same-fixture,
 * same-token positive control on {@code sort=startsAt,...} — proving the 400 is attributable
 * specifically to {@code priceAtBooking} no longer being whitelisted, not to some unrelated
 * breakage of the endpoint (auth, pagination, or the whitelist collapsing to reject everything).
 * With only one order ever sent per request in these tests, {@code MAX_SORT_ORDERS} cannot be the
 * trigger either — the whitelist check is the only remaining 400 source for a single-order sort.
 * {@link com.beautica.common.exception.GlobalExceptionHandler#handleBusiness} genericises every
 * {@code BAD_REQUEST} {@link com.beautica.common.exception.BusinessException} message to the same
 * static {@code "Invalid request"} string by design (Anti-Bug §I/§N — no enum-surface leak), so
 * the response body cannot further discriminate "rejected: not whitelisted" from "rejected: too
 * many orders" by message content; the paired positive control is the mechanism this suite uses
 * instead.
 *
 * <p>Also covers:
 * <ul>
 *   <li>the mandatory {@code id ASC} tiebreaker's pagination-determinism guarantee under ties on
 *       the sole surviving sortable property, {@code startsAt} (many bookings sharing an
 *       identical {@code startsAt}, paged with {@code OFFSET}) — see
 *       {@link #should_maintainDeterministicPagination_when_manyBookingsShareIdenticalStartsAt()}'s
 *       javadoc for why that test filters by {@code ?status=COMPLETED} and deliberately reorders
 *       the Postgres heap ({@code CLUSTER}) between page fetches: the obvious no-filter version of
 *       this test is mutation-proof against the tiebreaker being deleted entirely (Phase 26.8
 *       audit finding) because {@code EXPLAIN} shows the unfiltered query plan always resolves
 *       ties via an index whose own key order already includes {@code id}, independent of the
 *       Java-level {@code Sort};</li>
 *   <li>the security whitelist's HTTP-layer boundary — {@code ?sort=master.user.passwordHash,asc},
 *       {@code ?sort=client.email,asc}, and {@code ?sort=bogus,desc} must all 400 without ever
 *       reaching the JPQL/Criteria layer, and the 400 body must not echo the rejected property
 *       string back to the caller.</li>
 * </ul>
 *
 * @see BookingMyBookingsMultiStatusFilterIT for the sibling multi-status-filter suite (Phase
 *      26.1), whose ground-truth ordering deliberately uses only the endpoint's DEFAULT sort.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /bookings/me — sort param (Phase 26.3, narrowed by Phase 26.8), full HTTP stack over real Postgres")
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
    @DisplayName("GET /me — ?sort=client.email,asc returns 400 — a dotted join-path that names a "
            + "column on the caller's OWN association (not an obviously sensitive one like "
            + "passwordHash) is rejected identically; the whitelist's exact-match Set#contains does "
            + "not special-case which dotted path looks dangerous")
    void should_return400_when_sortPropertyIsDottedClientEmailPath() throws Exception {
        String clientEmail = "mbsort-clientemail-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);

        ResponseEntity<String> resp = callMyBookings(
                fixtures.tokenFor(clientEmail), "client.email,asc", null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("success").asBoolean()).isFalse();
        assertThat(root.path("message").asText(""))
                .isEqualTo("Invalid request")
                .doesNotContainIgnoringCase("email");
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

    @Test
    @DisplayName("GET /me — ?sort=createdAt,desc returns 400 — createdAt was never in "
            + "SORTABLE_BOOKING_PROPERTIES (the approved design sorts only by time); pins the "
            + "single-property whitelist {startsAt} end-to-end over the real HTTP stack, not just by "
            + "code trace")
    void should_return400_when_sortPropertyIsCreatedAt() throws Exception {
        String clientEmail = "mbsort-createdat-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);

        ResponseEntity<String> resp = callMyBookings(fixtures.tokenFor(clientEmail), "createdAt,desc", null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // priceAtBooking is RETIRED (Phase 26.8) — provider ID-page + hydrate path
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider ID-page+hydrate path) — "
            + "?sort=priceAtBooking,desc returns 400, and an identical request against the SAME "
            + "fixture/token with ?sort=startsAt,desc instead succeeds and orders correctly — the "
            + "paired positive control proves the 400 is attributable specifically to "
            + "priceAtBooking no longer being whitelisted, not to a broken endpoint")
    void should_return400_when_sortPriceAtBookingDescRequested_onProviderPath() throws Exception {
        String masterEmail = "mbsort-provider-desc-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbsort-provider-desc-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        String[] prices = {"300.00", "700.00", "100.00", "900.00", "500.00"};
        seedBookingsWithPrices(clientId, masterId, serviceId, prices);
        String token = fixtures.tokenFor(masterEmail);

        ResponseEntity<String> rejected = callMyBookings(token, "priceAtBooking,desc", 0, 20);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode rejectedBody = objectMapper.readTree(rejected.getBody());
        assertThat(rejectedBody.path("success").asBoolean()).isFalse();
        assertThat(rejectedBody.path("message").asText("")).isEqualTo("Invalid request");

        List<UUID> groundTruthStartsAtDesc = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE master_id = ? ORDER BY starts_at DESC, id ASC",
                UUID.class, masterId);
        ResponseEntity<String> accepted = callMyBookings(token, "startsAt,desc", 0, 20);
        assertThat(accepted.getStatusCode())
                .as("the same master/token/pagination that just 400'd on priceAtBooking must "
                        + "succeed on the surviving whitelisted property — proves the 400 above was "
                        + "caused by the property name, not by an unrelated fault")
                .isEqualTo(HttpStatus.OK);
        JsonNode acceptedBody = objectMapper.readTree(accepted.getBody());
        assertThat(fixtures.extractIds(acceptedBody)).containsExactlyElementsOf(groundTruthStartsAtDesc);
    }

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider ID-page+hydrate path) — "
            + "?sort=priceAtBooking,asc also returns 400, with the same paired startsAt positive "
            + "control")
    void should_return400_when_sortPriceAtBookingAscRequested_onProviderPath() throws Exception {
        String masterEmail = "mbsort-provider-asc-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbsort-provider-asc-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        String[] prices = {"300.00", "700.00", "100.00", "900.00", "500.00"};
        seedBookingsWithPrices(clientId, masterId, serviceId, prices);
        String token = fixtures.tokenFor(masterEmail);

        ResponseEntity<String> rejected = callMyBookings(token, "priceAtBooking,asc", 0, 20);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        List<UUID> groundTruthStartsAtAsc = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE master_id = ? ORDER BY starts_at ASC, id ASC",
                UUID.class, masterId);
        ResponseEntity<String> accepted = callMyBookings(token, "startsAt,asc", 0, 20);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode acceptedBody = objectMapper.readTree(accepted.getBody());
        assertThat(fixtures.extractIds(acceptedBody)).containsExactlyElementsOf(groundTruthStartsAtAsc);
    }

    @Test
    @DisplayName("GET /me (INDEPENDENT_MASTER, provider path) — ?sort=startsAt,asc returns "
            + "oldest-first — the one property Phase 26.8 kept whitelisted must still actually "
            + "reorder results, not merely be accepted")
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
                        + "the response")
                .containsExactlyElementsOf(groundTruthAsc);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // priceAtBooking is RETIRED (Phase 26.8) — CLIENT path (single-query projection)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (CLIENT, single-query projection path) — ?sort=priceAtBooking,desc "
            + "returns 400, with the same-fixture ?sort=startsAt,desc positive control proving "
            + "findClientBookingDetails' Pageable-driven ORDER BY still works independently of the "
            + "provider ID-page path")
    void should_return400_when_sortPriceAtBookingDescRequested_onClientPath() throws Exception {
        String clientEmail = "mbsort-client-desc-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        String masterEmail = "mbsort-client-desc-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        String[] prices = {"200.00", "800.00", "50.00", "650.00", "400.00"};
        seedBookingsWithPrices(clientId, masterId, serviceId, prices);
        String token = fixtures.tokenFor(clientEmail);

        ResponseEntity<String> rejected = callMyBookings(token, "priceAtBooking,desc", 0, 20);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode rejectedBody = objectMapper.readTree(rejected.getBody());
        assertThat(rejectedBody.path("success").asBoolean()).isFalse();
        assertThat(rejectedBody.path("message").asText("")).isEqualTo("Invalid request");

        List<UUID> groundTruthStartsAtDesc = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE client_id = ? ORDER BY starts_at DESC, id ASC",
                UUID.class, clientId);
        ResponseEntity<String> accepted = callMyBookings(token, "startsAt,desc", 0, 20);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode acceptedBody = objectMapper.readTree(accepted.getBody());
        assertThat(fixtures.extractIds(acceptedBody)).containsExactlyElementsOf(groundTruthStartsAtDesc);
    }

    @Test
    @DisplayName("GET /me (CLIENT, single-query projection path) — ?sort=startsAt,asc returns "
            + "oldest-first, proving the surviving whitelisted property actually reorders results "
            + "on the CLIENT path too, not only the provider path")
    void should_returnOldestFirst_when_sortStartsAtAscRequested_onClientPath() throws Exception {
        String clientEmail = "mbsort-client-startsasc-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        String masterEmail = "mbsort-client-startsasc-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        OffsetDateTime t = ANCHOR;
        for (int i = 0; i < 5; i++) {
            insertBooking(clientId, masterId, serviceId, null, "CONFIRMED", t, "500.00");
            t = t.plusMinutes(90);
        }

        List<UUID> groundTruthAsc = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE client_id = ? ORDER BY starts_at ASC, id ASC",
                UUID.class, clientId);

        ResponseEntity<String> resp = callMyBookings(fixtures.tokenFor(clientEmail), "startsAt,asc", 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(fixtures.extractIds(root)).containsExactlyElementsOf(groundTruthAsc);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Backward compatibility — no sort param still yields the pre-existing default
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /me (provider path) — no sort param at all still defaults to startsAt DESC, "
            + "byte-identical to pre-Phase-26.8 behaviour (backward-compat guard — the shipped "
            + "client «Мої записи» relies on exactly this default)")
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
    // The mandatory id tiebreaker — pagination determinism under startsAt ties
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * <b>Mutation-verified (Phase 26.8 audit).</b> The obvious version of this test — seed 25
     * tied-{@code startsAt} rows, page {@code ?sort=startsAt,desc} three times, assert no
     * dup/gap — CANNOT FAIL: deleting {@code .and(ID_TIEBREAKER_SORT)} from
     * {@code BookingService#normalizeBookingSort} and re-running that version still passes.
     * {@code EXPLAIN (ANALYZE, BUFFERS)} against the seeded fixture shows why — an unfiltered
     * {@code WHERE master_id = ? ORDER BY starts_at DESC} always plans an
     * <i>Index Only Scan using {@code idx_bookings_master_starts_at}</i> (V117's
     * {@code (master_id, starts_at DESC, id ASC)} composite), and that scan returns tied rows in
     * {@code id ASC} order because it is the index's own key order — a structural property of the
     * index, not a consequence of the {@code Sort} object Java built. Removing the code-level
     * tiebreaker is invisible on this query shape regardless of concurrent writes, CLUSTER, or
     * anything else: the index makes the ordering true whether or not the code asks for it.
     *
     * <p>Adding {@code ?status=COMPLETED} changes the plan (also EXPLAIN-verified): Postgres
     * switches to <i>Index Scan Backward using {@code idx_bookings_master_completed_starts_at}</i>
     * (V43's {@code (master_id, starts_at) WHERE status = 'COMPLETED'} partial index) — which has
     * <b>no {@code id} column</b>. Ties on that index resolve via the row's heap TID, i.e.
     * whatever physical order the heap happens to be in — which a {@code CLUSTER} between page
     * fetches can deliberately change. Empirically (this audit): re-running the identical
     * {@code SELECT ... ORDER BY starts_at DESC} (no {@code id}) before and after
     * {@code CLUSTER bookings USING <a heap-reordering index>} returns the 25 tied ids in a
     * DIFFERENT order — proof the plan Postgres actually picks under this filter is physical-order
     * sensitive. With the code-level {@code id ASC} tiebreaker present, {@code EXPLAIN} shows
     * Postgres switches BACK to the id-inclusive {@code idx_bookings_master_starts_at} (applying
     * {@code status} as a {@code Filter} instead) — immune to the same {@code CLUSTER}, because
     * that index's key order fixes the tie order independent of heap layout. That is exactly the
     * dependent-variable this test needs: mutate the tiebreaker away and the {@code status}-filtered,
     * heap-reordered run duplicates/skips ids across pages; leave it in and it does not — verified
     * by actually deleting {@code .and(ID_TIEBREAKER_SORT)} and re-running this exact test (FAILED),
     * then restoring it (PASSED).
     */
    @Test
    @DisplayName("GET /me?status=COMPLETED (provider path) — 25 bookings sharing an IDENTICAL "
            + "startsAt, paged with size=10 across 3 pages under ?sort=startsAt,desc, with the "
            + "physical heap deliberately reordered (CLUSTER) between page fetches, still "
            + "reproduce the full seeded set exactly once each — no duplicates, no gaps. The "
            + "?status=COMPLETED filter is load-bearing: EXPLAIN shows it moves the query plan "
            + "off the id-inclusive idx_bookings_master_starts_at onto the id-less partial index "
            + "idx_bookings_master_completed_starts_at, whose ties resolve by heap physical order "
            + "— the only plan shape the id ASC tiebreaker can actually be proven necessary "
            + "against; the unfiltered version of this test cannot fail regardless of the "
            + "tiebreaker (see class javadoc)")
    void should_maintainDeterministicPagination_when_manyBookingsShareIdenticalStartsAt() throws Exception {
        String masterEmail = "mbsort-tiebreak-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID clientId = fixtures.createUser("mbsort-tiebreak-client-" + System.nanoTime() + "@beautica.test",
                "CLIENT", null);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);

        int total = 25;
        Set<UUID> seeded = new LinkedHashSet<>();
        for (int i = 0; i < total; i++) {
            seeded.add(insertBooking(clientId, masterId, serviceId, null, "COMPLETED", ANCHOR, "500.00"));
        }

        String token = fixtures.tokenFor(masterEmail);
        int pageSize = 10;
        List<UUID> collected = new ArrayList<>();

        // A throwaway index purely to give CLUSTER a physical order to rewrite the heap into that
        // differs from however the rows currently sit (insertion order). Dropped in a finally so
        // it never leaks into any later test against the same singleton container.
        //
        // CLUSTER cost note (Phase 26.8 audit): CLUSTER rebuilds EVERY index on bookings (~15,
        // including a GiST exclusion constraint) twice per run — not just the one it clusters by —
        // and it runs against the singleton Testcontainers container's cumulative table state, not
        // a fresh 25-row fixture: AbstractIntegrationTest starts the container once per JVM and
        // never stops it between test classes, and cleanDb() uses DELETE (not TRUNCATE), so
        // bookings accumulates dead tuples across the whole IT suite. It is still fast enough at
        // this suite's current size to be worth the determinism proof; revisit if IT runtime
        // becomes a complaint.
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS tmp_tiebreak_probe_id_desc_idx ON bookings (id DESC)");
        try {
            collected.addAll(fetchCompletedPage(token, 0, pageSize, total));

            jdbcTemplate.execute("CLUSTER bookings USING tmp_tiebreak_probe_id_desc_idx");
            jdbcTemplate.execute("ANALYZE bookings");
            collected.addAll(fetchCompletedPage(token, 1, pageSize, total));

            jdbcTemplate.execute("CLUSTER bookings USING bookings_pkey");
            jdbcTemplate.execute("ANALYZE bookings");
            collected.addAll(fetchCompletedPage(token, 2, pageSize, total));
        } finally {
            jdbcTemplate.execute("DROP INDEX IF EXISTS tmp_tiebreak_probe_id_desc_idx");
        }

        assertThat(collected)
                .as("concatenating all 3 pages of an all-tied startsAt sort, with the heap "
                        + "physically reordered between each fetch, must reproduce the full seeded "
                        + "set exactly once each — a broken/missing id tiebreaker duplicates or "
                        + "skips rows across OFFSET page boundaries once Postgres's tie order "
                        + "depends on physical layout (see method javadoc for the EXPLAIN evidence "
                        + "that ?status=COMPLETED is what puts the query on that plan)")
                .hasSize(total)
                .containsExactlyInAnyOrderElementsOf(seeded);
        assertThat(new HashSet<>(collected))
                .as("no id appears on more than one page")
                .hasSize(total);
    }

    /** Fetches one {@code ?status=COMPLETED&sort=startsAt,desc} page and returns its ids. */
    private List<UUID> fetchCompletedPage(String token, int page, int pageSize, long expectedTotal)
            throws Exception {
        ResponseEntity<String> resp = callMyBookings(token, "startsAt,desc", page, pageSize, "COMPLETED");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("data").path("totalElements").asLong()).isEqualTo(expectedTotal);
        return fixtures.extractIds(root);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * Seeds one CONFIRMED booking per element of {@code prices}, 90 minutes apart in ASCENDING
     * {@code startsAt} order (insertion order), so {@code startsAt} order is always [0..n). Used
     * by the retired-sort tests purely to prove {@code priceAtBooking} is REJECTED regardless of
     * whether the underlying price data varies — the prices themselves are no longer asserted on.
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
     * master, mirroring {@code BookingMyBookingsMultiStatusFilterIT}'s convention. A non-CONFIRMED
     * {@code status} (e.g. {@code COMPLETED}) bypasses the {@code no_overlapping_bookings} EXCLUDE
     * constraint, which only covers {@code status = 'CONFIRMED'} rows (V113) — needed to seed
     * multiple rows sharing an identical {@code startsAt} for the tiebreaker test.
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
        return callMyBookings(token, sort, page, size, null);
    }

    private ResponseEntity<String> callMyBookings(
            String token, String sort, Integer page, Integer size, String status) {
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
        if (status != null) {
            parts.add("status=" + status);
        }
        String url = BOOKINGS_URL + "/me" + (parts.isEmpty() ? "" : "?" + String.join("&", parts));
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }
}
