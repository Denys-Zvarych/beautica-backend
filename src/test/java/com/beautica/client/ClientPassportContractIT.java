package com.beautica.client;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.client.dto.PassportResponse;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 249 (31.6) — the BEAUTY PASSPORT contract driven end to end over real HTTP against a
 * real Postgres, with real seeded KATOTTH taxonomy rows.
 *
 * <h2>Why this class exists on top of the three layers that already test the passport</h2>
 * <ul>
 *   <li>{@code ClientPassportServiceTest} is a Mockito unit test — its repository is a stub, so
 *       it can never observe a WHERE clause, a GROUP BY rank order, or a label join.</li>
 *   <li>{@code ClientAggregationRepositoryTest} is a {@code @DataJpaTest} — it proves the SQL but
 *       stops below the service's label resolution, the DTO and the HTTP envelope.</li>
 *   <li>{@code ClientControllerTest} is a {@code @WebMvcTest} with a mocked service — it proves
 *       the status codes and the principal wiring, never a value derived from data.</li>
 * </ul>
 * Nothing before this class asserted that a real client's real completed bookings come back out
 * of {@code GET /clients/me/passport} as the right labels in the right order. That whole-chain
 * property is what phases 244/245 changed, and it is what this class pins.
 *
 * <h2>Occupied-territory data ban</h2>
 * No locality name is written into this file. Every city / district id AND its expected
 * {@code name_uk} label are read out of the seeded taxonomy at runtime and the response is
 * asserted against the value the database itself holds — so the assertions stay honest without a
 * single locality string being committed.
 */
@Import(TestSecurityConfig.class)
@DisplayName("BEAUTY PASSPORT — GET /clients/me/passport contract (HTTP + real Postgres)")
class ClientPassportContractIT extends AbstractIntegrationTest {

    private static final String PASSPORT_URL = "/api/v1/clients/me/passport";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ── 244: districts + cities, rank-ordered, capped at three ──────────────────

    @Test
    @DisplayName("GET passport — favoriteCities and favoriteDistricts come back rank-ordered by "
            + "COMPLETED-booking count and capped at three, over the wire")
    void should_returnDistrictsAndCitiesRankedAndCapped_when_clientHasCompletedBookings() throws Exception {
        // Four localities so the top-3 cap is actually exercised: a test seeded with three could
        // not tell "LIMIT 3" from "no limit at all".
        List<Locality> localities = fourDistinctCitiesWithDistricts();
        UUID clientId = createClient("passport-rank-client@beautica.test");

        // Descending, distinct booking counts — the SQL ORDER BY carries no tiebreaker, so equal
        // counts would make the expected order a coin flip rather than a contract.
        int[] bookingCounts = {4, 3, 2, 1};
        for (int i = 0; i < localities.size(); i++) {
            UUID master = createIndependentMasterAt(
                    "passport-rank-master-" + i + "@beautica.test", localities.get(i));
            UUID masterService = createMasterService(master);
            for (int n = 0; n < bookingCounts[i]; n++) {
                insertCompletedBooking(clientId, master, masterService, new BigDecimal("500.00"));
            }
        }

        PassportResponse passport = getPassport(clientId);

        assertThat(passport.favoriteCities())
                .as("three most-visited cities, most-booked first — the fourth is dropped by the "
                        + "top-3 page, not by chance")
                .containsExactly(
                        localities.get(0).cityLabel(),
                        localities.get(1).cityLabel(),
                        localities.get(2).cityLabel());
        assertThat(passport.favoriteDistricts())
                .as("the district ranking is a second, independent aggregate over the same bookings")
                .containsExactly(
                        localities.get(0).districtLabel(),
                        localities.get(1).districtLabel(),
                        localities.get(2).districtLabel());
        assertThat(passport.bookingsConsidered()).isEqualTo(10);
    }

    // ── 244 D-rule 1: the salon wins over the master's stale user mirror ─────────

    @Test
    @DisplayName("GET passport — a salon-employed master resolves to the SALON's current locality, "
            + "not the stale users.city_id/district_id mirror taken at salon-creation time")
    void should_preferSalonLocality_when_masterUserMirrorIsStale() throws Exception {
        // This is the exact bug a COALESCE(s.cityId, mu.cityId) would reintroduce: the master's
        // users row still points at the salon's PRE-relocation locality (SalonService.updateSalon
        // never re-syncs it), so a fall-through resolves the locality the salon has left.
        List<Locality> localities = fourDistinctCitiesWithDistricts();
        Locality stale = localities.get(0);
        Locality current = localities.get(1);

        UUID clientId = createClient("passport-stale-client@beautica.test");
        UUID salonId = createSalonAt("passport-stale-owner@beautica.test", current);
        UUID master = createSalonMasterAt(salonId, "passport-stale-master@beautica.test", stale);
        UUID masterService = createSalonMasterService(master, salonId);
        insertCompletedBooking(clientId, master, masterService, new BigDecimal("500.00"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT city_id FROM users WHERE id = "
                        + "(SELECT user_id FROM masters WHERE id = ?)", UUID.class, master))
                .as("precondition: the master's user mirror genuinely still holds the STALE city, "
                        + "so a fall-through would be observable")
                .isEqualTo(stale.cityId());

        PassportResponse passport = getPassport(clientId);

        assertThat(passport.favoriteCities())
                .as("salon presence wins outright — the stale mirror must never surface")
                .containsExactly(current.cityLabel());
        assertThat(passport.favoriteDistricts())
                .containsExactly(current.districtLabel());
    }

    // ── the occupied-territory drop, over the wire ───────────────────────────────

    @Test
    @DisplayName("GET passport — a locality whose taxonomy label does not resolve is DROPPED: the "
            + "response carries neither the label nor a raw UUID")
    void should_omitUnresolvableLocality_when_labelMissing() throws Exception {
        // The drop is the occupied-territory ban's enforcement point for this endpoint: labels come
        // only from the joined taxonomy, and an id that resolves to nothing usable is omitted rather
        // than surfaced. users.city_id / district_id are FK-constrained, so a DANGLING id is not
        // reachable through the schema; the reachable failure is a taxonomy row whose name_uk is
        // unusable, which exercises the same `label == null || label.isBlank()` filter.
        List<Locality> localities = fourDistinctCitiesWithDistricts();
        Locality resolvable = localities.get(0);
        Locality unresolvable = localities.get(1);

        UUID clientId = createClient("passport-drop-client@beautica.test");

        UUID goodMaster = createIndependentMasterAt("passport-drop-good@beautica.test", resolvable);
        insertCompletedBooking(clientId, goodMaster, createMasterService(goodMaster), new BigDecimal("500.00"));

        UUID blankMaster = createIndependentMasterAt("passport-drop-blank@beautica.test", unresolvable);
        insertCompletedBooking(clientId, blankMaster, createMasterService(blankMaster), new BigDecimal("500.00"));

        // The taxonomy is REFERENCE data — cleanDb() never restores it, and the Postgres container
        // is shared by every IT in the JVM. Blank it inside a try/finally so a failing assertion
        // cannot leave a corrupted seed behind for whichever class runs next.
        jdbcTemplate.update("UPDATE cities SET name_uk = '   ' WHERE id = ?", unresolvable.cityId());
        jdbcTemplate.update("UPDATE city_districts SET name_uk = '   ' WHERE id = ?", unresolvable.districtId());
        try {
            String rawBody = getPassportRaw(clientId);
            PassportResponse passport = parsePassport(rawBody);

            assertThat(passport.favoriteCities())
                    .as("only the resolvable locality survives; the blank one is omitted, not blanked")
                    .containsExactly(resolvable.cityLabel());
            assertThat(passport.favoriteDistricts())
                    .containsExactly(resolvable.districtLabel());
            assertThat(rawBody)
                    .as("a raw taxonomy UUID must never leak into the response as a stand-in label")
                    .doesNotContain(unresolvable.cityId().toString())
                    .doesNotContain(unresolvable.districtId().toString());
        } finally {
            jdbcTemplate.update("UPDATE cities SET name_uk = ? WHERE id = ?",
                    unresolvable.cityLabel(), unresolvable.cityId());
            jdbcTemplate.update("UPDATE city_districts SET name_uk = ? WHERE id = ?",
                    unresolvable.districtLabel(), unresolvable.districtId());
        }
    }

    // ── 245: the empty-state trap ────────────────────────────────────────────────

    @Test
    @DisplayName("GET passport — a client with NO completed bookings still gets a real "
            + "memberSinceYear and reviewsWritten alongside the empty derived state")
    void should_returnIdentityFields_when_clientHasNoCompletedBookings() throws Exception {
        UUID clientId = createClient("passport-empty-client@beautica.test");
        // The year comes from the users row Postgres actually wrote — never a literal, and never
        // the current year, which would make this assertion pass against a fabricated value.
        int registrationYear = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(YEAR FROM created_at AT TIME ZONE 'Europe/Kyiv')::int "
                        + "FROM users WHERE id = ?", Integer.class, clientId);

        // One authored review, so reviewsWritten is provably NOT gated behind bookingsConsidered > 0:
        // a test with zero reviews cannot tell "resolved before the short-circuit" from "zeroed by it".
        UUID master = createIndependentMasterAt("passport-empty-master@beautica.test", null);
        UUID masterService = createMasterService(master);
        UUID reviewedBooking =
                insertCompletedBooking(clientId, master, masterService, new BigDecimal("500.00"));
        insertReview(reviewedBooking);
        // Take the booking back out of COMPLETED so the derived half is genuinely empty while the
        // review survives — the exact shape the 245 short-circuit has to get right.
        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ?", reviewedBooking);

        PassportResponse passport = getPassport(clientId);

        assertThat(passport.bookingsConsidered()).isZero();
        assertThat(passport.favoriteDistricts()).isEmpty();
        assertThat(passport.favoriteCities()).isEmpty();
        assertThat(passport.budget())
                .as("the empty state is a null band, never a band of nulls")
                .isNull();
        assertThat(passport.reviewsWritten())
                .as("identity standing is resolved BEFORE the considered == 0 short-circuit")
                .isEqualTo(1);
        assertThat(passport.memberSinceYear())
                .as("a real users.created_at year — the page's design rule forbids fabricating one")
                .isEqualTo(registrationYear);
    }

    @Test
    @DisplayName("GET passport — reviewsWritten counts only the caller's own reviews when another "
            + "client reviewed the same master")
    void should_countOnlyOwnReviews_when_anotherClientAlsoReviewed() throws Exception {
        UUID asking = createClient("passport-reviews-asking@beautica.test");
        UUID other = createClient("passport-reviews-other@beautica.test");
        UUID master = createIndependentMasterAt("passport-reviews-master@beautica.test", null);
        UUID masterService = createMasterService(master);

        insertReview(insertCompletedBooking(asking, master, masterService, new BigDecimal("500.00")));
        // Two reviews from the OTHER client — an unscoped COUNT would report 3 here, and a
        // single foreign review would be indistinguishable from an off-by-one.
        insertReview(insertCompletedBooking(other, master, masterService, new BigDecimal("500.00")));
        insertReview(insertCompletedBooking(other, master, masterService, new BigDecimal("500.00")));

        assertThat(getPassport(asking).reviewsWritten())
                .as("the correlated subquery is scoped to u.id — a foreign review must not count")
                .isEqualTo(1);
        assertThat(getPassport(other).reviewsWritten()).isEqualTo(2);
    }

    // ── Phase 250: favoriteProcedures retired ─────────────────────────────────────

    @Test
    @DisplayName("GET passport — the favoriteProcedures key is absent from the response body "
            + "(Phase 250 retirement; a re-introduction must fail this test)")
    void should_omitFavoriteProceduresKey_when_retiredByPhase250() throws Exception {
        UUID clientId = createClient("passport-no-favprocs-client@beautica.test");
        UUID master = createIndependentMasterAt("passport-no-favprocs-master@beautica.test", null);
        UUID masterService = createMasterService(master);
        insertCompletedBooking(clientId, master, masterService, new BigDecimal("500.00"));

        String rawBody = getPassportRaw(clientId);

        assertThat(rawBody)
                .as("favoriteProcedures must never reappear in the passport payload")
                .doesNotContain("favoriteProcedures");
    }

    // ── the budget band: avg is real data, and Phase 251 must not drop it silently ─

    @Test
    @DisplayName("GET passport — the budget band exposes a real AVG alongside MIN and MAX, all "
            + "three visibly different")
    void should_exposeAvgAlongsideMinAndMax_when_budgetIsPresent() throws Exception {
        UUID clientId = createClient("passport-budget-client@beautica.test");
        UUID master = createIndependentMasterAt("passport-budget-master@beautica.test", null);
        UUID masterService = createMasterService(master);

        // A deliberately SKEWED spread. A fixture of twenty similar prices makes avg, min and max
        // indistinguishable at one decimal, so the test would pass with any one of the three wired
        // into all three fields. 200/300/2500 → avg 1000, min 200, max 2500: three numbers no
        // mis-wiring can satisfy at once.
        insertCompletedBooking(clientId, master, masterService, new BigDecimal("200.00"));
        insertCompletedBooking(clientId, master, masterService, new BigDecimal("300.00"));
        insertCompletedBooking(clientId, master, masterService, new BigDecimal("2500.00"));

        PassportResponse passport = getPassport(clientId);

        assertThat(passport.budget()).isNotNull();
        assertThat(passport.budget().avg())
                .as("AVG(priceAtBooking) over COMPLETED, all-time — pinned so Phase 251 cannot "
                        + "silently drop it while removing min/max")
                .isEqualByComparingTo("1000.00");
        assertThat(passport.budget().min()).isEqualByComparingTo("200.00");
        assertThat(passport.budget().max()).isEqualByComparingTo("2500.00");
        assertThat(passport.budget().currency()).isEqualTo("UAH");
        assertThat(passport.bookingsConsidered()).isEqualTo(3);
    }

    // ── HTTP plumbing ───────────────────────────────────────────────────────────

    private PassportResponse getPassport(UUID clientId) throws Exception {
        return parsePassport(getPassportRaw(clientId));
    }

    private String getPassportRaw(UUID clientId) throws Exception {
        String email = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, clientId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(loginAndGetToken(email));
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                PASSPORT_URL, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private PassportResponse parsePassport(String body) throws Exception {
        return objectMapper
                .readValue(body, new TypeReference<ApiResponse<PassportResponse>>() {})
                .data();
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper
                .readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data()
                .accessToken();
    }

    // ── taxonomy fixtures (no locality name is ever written into this file) ──────

    /**
     * One seeded (city, district) pair plus the {@code name_uk} labels the database holds for
     * them. The labels are read out of the taxonomy at runtime rather than hardcoded: the
     * occupied-territory ban forbids committing locality data to this repository, and reading
     * them back also makes the assertions immune to a seed refresh.
     */
    private record Locality(UUID cityId, String cityLabel, UUID districtId, String districtLabel) {
    }

    private List<Locality> fourDistinctCitiesWithDistricts() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT DISTINCT ON (c.id)
                       c.id AS city_id, c.name_uk AS city_label,
                       d.id AS district_id, d.name_uk AS district_label
                FROM cities c
                JOIN city_districts d ON d.city_id = c.id
                ORDER BY c.id, d.id
                LIMIT 4
                """);
        assertThat(rows)
                .as("the V53 KATOTTH seed must supply four districted cities; fewer means the seed "
                        + "changed and the rank/cap assertions below would silently weaken")
                .hasSize(4);

        List<Locality> localities = new ArrayList<>(4);
        for (Map<String, Object> row : rows) {
            localities.add(new Locality(
                    (UUID) row.get("city_id"), (String) row.get("city_label"),
                    (UUID) row.get("district_id"), (String) row.get("district_label")));
        }
        return localities;
    }

    // ── seed helpers ────────────────────────────────────────────────────────────

    private UUID createClient(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD));
        return id;
    }

    /** An independent master whose own users row carries the given locality ({@code null} = none). */
    private UUID createIndependentMasterAt(String email, Locality locality) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, city_id, district_id, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', ?, ?, true, true)",
                userId, email,
                locality == null ? null : locality.cityId(),
                locality == null ? null : locality.districtId());
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createSalonAt(String ownerEmail, Locality locality) {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'SALON_OWNER', true, true)",
                ownerId, ownerEmail);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city_id, district_id, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'Test Salon', ?, ?, true, NOW(), NOW())",
                salonId, ownerId, locality.cityId(), locality.districtId());
        return salonId;
    }

    /** A salon-employed master whose users row holds a DIFFERENT (stale) locality than the salon. */
    private UUID createSalonMasterAt(UUID salonId, String email, Locality staleMirror) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, city_id, district_id, "
                        + "is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'SALON_MASTER', ?, ?, ?, true, true)",
                userId, email, salonId, staleMirror.cityId(), staleMirror.districtId());
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
    }

    private UUID createMasterService(UUID masterId) {
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        return insertServiceDefinitionAndAssignment(masterId, "INDEPENDENT_MASTER", ownerId);
    }

    private UUID createSalonMasterService(UUID masterId, UUID salonId) {
        return insertServiceDefinitionAndAssignment(masterId, "SALON", salonId);
    }

    private UUID insertServiceDefinitionAndAssignment(UUID masterId, String ownerType, UUID ownerId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, ownerType, ownerId, resolveUnusedServiceTypeId(ownerType, ownerId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID insertCompletedBooking(UUID clientId, UUID masterId, UUID masterServiceId,
                                        BigDecimal price) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', "
                        + "NOW() - interval '1 hour', ?, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, price);
        return bookingId;
    }

    private void insertReview(UUID bookingId) {
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, "
                        + "comment, created_at, updated_at) "
                        + "SELECT ?, b.id, b.client_id, b.master_id, NULL, 5, 'seeded review', "
                        + "NOW(), NOW() FROM bookings b WHERE b.id = ?",
                UUID.randomUUID(), bookingId);
    }
}
