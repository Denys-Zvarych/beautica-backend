package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.support.BookableMasterSeeder;
import com.beautica.support.LocalityTestLookup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.beautica.service.BookabilityHttp.ids;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client discovery shows only bookable providers (locked product decision 2026-10-05): a master is
 * listed only with ≥1 active service AND a schedule, a salon only with ≥1 such master — the shared
 * {@code MasterBookabilitySql} rule, applied on every search path (static browse, static {@code q},
 * dynamic {@code serviceTypeSlugs}), in the salon price band, and in search suggestions.
 *
 * <p>Fixture (one per test, real Postgres, real caches):
 * <ul>
 *   <li>Salon «Aurora» — an owner-master with a 100.00 service but NO schedule (the user report's
 *       auto-enrolled owner), plus a configured SALON_MASTER (500.00 service + weekly hours).</li>
 *   <li>Salon «Borealis» — only an owner-master with a 300.00 service and NO schedule.</li>
 *   <li>Salon «Cygnus» — only an owner-master with no services and no schedule.</li>
 *   <li>Independent masters: «Kalyna» configured; «Liubystok» with a service but no schedule;
 *       «Mavka» with neither.</li>
 * </ul>
 * Type {@code configuredType} is performed by the configured masters; {@code unscheduledType} only by
 * masters without a schedule.
 */
@DisplayName("Search shows only bookable masters and salons (full HTTP + real Postgres)")
class SearchBookabilityVisibilityIT extends AbstractIntegrationTest {

    private static final String MASTERS_URL = "/api/v1/search/masters";
    private static final String SALONS_URL = "/api/v1/search/salons";
    private static final String SUGGESTIONS_URL = "/api/v1/search/suggestions";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;
    private BookabilityHttp http;
    private Fixture fx;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        http = new BookabilityHttp(restTemplate, objectMapper);
        fx = seed();
    }

    // ── master search ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("master browse lists only the configured independent master; totalElements == 1")
    void should_listOnlyConfiguredMaster_when_masterBrowse() throws Exception {
        JsonNode page = http.get(MASTERS_URL + "?page=0&size=20");

        assertThat(ids(page, "masterId")).containsExactly(fx.kalyna().toString());
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("master ?q by name: an unscheduled master is not found; the configured one is")
    void should_hideUnscheduledMaster_when_masterSearchedByName() throws Exception {
        JsonNode unscheduled = http.get(MASTERS_URL + "?q=Liubystok");
        JsonNode empty = http.get(MASTERS_URL + "?q=Mavka");
        JsonNode configured = http.get(MASTERS_URL + "?q=Kalyna");

        assertThat(ids(unscheduled, "masterId")).isEmpty();
        assertThat(ids(empty, "masterId")).isEmpty();
        assertThat(ids(configured, "masterId")).containsExactly(fx.kalyna().toString());
    }

    // ── salon search ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("salon browse (static path) lists only Aurora; totalElements == 1")
    void should_listOnlySalonWithBookableMaster_when_salonBrowse() throws Exception {
        JsonNode page = http.get(SALONS_URL + "?page=0&size=20");

        assertThat(ids(page, "salonId")).containsExactly(fx.aurora().toString());
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("salon ?q by name (static path): Borealis and Cygnus are not found, Aurora is")
    void should_hideUnbookableSalons_when_salonSearchedByName() throws Exception {
        assertThat(ids(http.get(SALONS_URL + "?q=Borealis"), "salonId")).isEmpty();
        assertThat(ids(http.get(SALONS_URL + "?q=Cygnus"), "salonId")).isEmpty();
        assertThat(ids(http.get(SALONS_URL + "?q=Aurora"), "salonId")).containsExactly(fx.aurora().toString());
    }

    @Test
    @DisplayName("salon serviceTypeSlugs (dynamic path): the configured type lists Aurora only; a type "
            + "performed only by unscheduled masters lists nothing")
    void should_gateDynamicSalonPath_when_filteredByServiceType() throws Exception {
        JsonNode configured = http.get(SALONS_URL + "?serviceTypeSlugs=" + fx.configuredType().slug());
        JsonNode unscheduled = http.get(SALONS_URL + "?serviceTypeSlugs=" + fx.unscheduledType().slug());

        assertThat(ids(configured, "salonId")).containsExactly(fx.aurora().toString());
        assertThat(ids(unscheduled, "salonId")).isEmpty();
        assertThat(unscheduled.path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("the salon price band ignores the unscheduled owner-master's cheaper service — "
            + "500–500 on the static browse AND the dynamic filtered path")
    void should_priceSalonFromBookableMastersOnly_when_ownerMasterIsUnscheduled() throws Exception {
        JsonNode browseRow = http.get(SALONS_URL + "?page=0&size=20").path("data").get(0);
        JsonNode dynamicRow = http.get(SALONS_URL + "?serviceTypeSlugs=" + fx.configuredType().slug())
                .path("data").get(0);

        for (JsonNode row : List.of(browseRow, dynamicRow)) {
            assertThat(new BigDecimal(row.path("priceMin").asText())).isEqualByComparingTo("500.00");
            assertThat(new BigDecimal(row.path("priceMax").asText())).isEqualByComparingTo("500.00");
        }
    }

    // ── suggestions ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("suggestions offer the configured type but never a type performed only by "
            + "unscheduled masters (salon AND independent branches)")
    void should_suggestOnlyBookableTypes_when_suggestionsQueried() throws Exception {
        JsonNode configured = http.get(SUGGESTIONS_URL + "?limit=8&q=" + fx.configuredType().query());
        JsonNode unscheduled = http.get(SUGGESTIONS_URL + "?limit=8&q=" + fx.unscheduledType().query());

        assertThat(configured.findValuesAsText("serviceTypeSlug")).contains(fx.configuredType().slug());
        assertThat(unscheduled.findValuesAsText("serviceTypeSlug")).doesNotContain(fx.unscheduledType().slug());
    }

    // ── membership changes reach the cached search ──────────────────────────────────────────

    @Test
    @DisplayName("an unscheduled independent master who gains a schedule via the API appears on the "
            + "next (cached) browse — the schedule write clears the search cache")
    void should_listMaster_when_scheduleCreatedAfterSearchWasCached() throws Exception {
        assertThat(ids(http.get(MASTERS_URL + "?page=0&size=20"), "masterId"))
                .as("precondition: Liubystok hidden, page now cached")
                .doesNotContain(fx.liubystok().toString());

        ResponseEntity<String> created = restTemplate.exchange(
                "/api/v1/masters/" + fx.liubystok() + "/weekly-schedules", HttpMethod.POST,
                new HttpEntity<>(weeklyScheduleBody(), fixtures.bearerHeaders(fx.liubystokToken())),
                String.class);

        assertThat(created.getStatusCode().is2xxSuccessful()).as("schedule create: %s", created.getBody()).isTrue();
        assertThat(ids(http.get(MASTERS_URL + "?page=0&size=20"), "masterId"))
                .containsExactlyInAnyOrder(fx.kalyna().toString(), fx.liubystok().toString());
    }

    // ── fixture ─────────────────────────────────────────────────────────────────────────────

    private Fixture seed() {
        TypeRow configuredType = activeType(0);
        TypeRow unscheduledType = activeType(1);

        UUID aurora = insertSalon("Aurora Studio");
        UUID auroraOwner = insertOwnerMaster(aurora);
        assignSalonService(aurora, auroraOwner, unscheduledType, "100.00");
        UUID auroraConfigured = fixtures.createSalonMaster(aurora);
        assignSalonService(aurora, auroraConfigured, configuredType, "500.00");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, auroraConfigured);

        UUID borealis = insertSalon("Borealis Studio");
        UUID borealisOwner = insertOwnerMaster(borealis);
        assignSalonService(borealis, borealisOwner, unscheduledType, "300.00");

        UUID cygnus = insertSalon("Cygnus Studio");
        insertOwnerMaster(cygnus);

        UUID kalyna = insertIndependentMaster("Kalyna");
        assignIndependentService(kalyna, configuredType);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, kalyna);

        String liubystokEmail = "liubystok-" + UUID.randomUUID() + "@beautica.test";
        String liubystokToken = registerIndependentMaster(liubystokEmail, "Liubystok");
        UUID liubystok = fixtures.resolveMasterIdForUserEmail(liubystokEmail);
        assignIndependentService(liubystok, unscheduledType);

        insertIndependentMaster("Mavka");

        return new Fixture(aurora, kalyna, liubystok, liubystokToken, configuredType, unscheduledType);
    }

    private String registerIndependentMaster(String email, String lastName) {
        try {
            String token = fixtures.createIndependentMasterAndGetToken(email);
            jdbcTemplate.update("UPDATE users SET first_name = ?, last_name = ? WHERE email = ?",
                    lastName, lastName, email);
            return token;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TypeRow activeType(int offset) {
        return jdbcTemplate.queryForObject(
                """
                SELECT st.id, st.slug, st.name_uk FROM service_types st
                JOIN platform_categories pc ON pc.name = st.platform_category_name
                WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED'
                  AND char_length(st.name_uk) <= 50
                ORDER BY st.slug OFFSET ? LIMIT 1
                """,
                (rs, i) -> new TypeRow((UUID) rs.getObject("id"), rs.getString("slug"), rs.getString("name_uk")),
                offset);
    }

    private UUID insertSalon(String name) {
        UUID ownerUserId = insertUser("SALON_OWNER", "Owner", name);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, (SELECT city_id FROM users WHERE id = ?), true, NOW(), NOW())",
                salonId, ownerUserId, name, ownerUserId);
        return salonId;
    }

    /** The auto-enrolled SALON_OWNER-typed master row of {@code salonId}'s owner. */
    private UUID insertOwnerMaster(UUID salonId) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "SELECT ?, owner_id, id, 'SALON_OWNER', true, NOW(), NOW() FROM salons WHERE id = ?",
                masterId, salonId);
        return masterId;
    }

    private UUID insertIndependentMaster(String lastName) {
        UUID userId = insertUser("INDEPENDENT_MASTER", lastName, lastName);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID insertUser(String role, String firstName, String lastName) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, city_id, "
                        + "is_active, email_verified) VALUES (?, ?, ?, ?, ?, ?, ?, true, true)",
                userId, "bk-" + UUID.randomUUID() + "@beautica.test",
                passwordEncoder.encode("test-password"), role, firstName, lastName,
                LocalityTestLookup.majorCityIdByName(jdbcTemplate, "Вінниця"));
        return userId;
    }

    private void assignSalonService(UUID salonId, UUID masterId, TypeRow type, String price) {
        UUID defId = insertDefinition("SALON", salonId, type, price);
        assign(masterId, defId);
    }

    private void assignIndependentService(UUID masterId, TypeRow type) {
        assign(masterId, insertDefinition("INDEPENDENT_MASTER", masterId, type, "500.00"));
    }

    private UUID insertDefinition(String ownerType, UUID ownerId, TypeRow type, String price) {
        UUID defId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 60, 'FIXED', ?, 0, true, NOW(), NOW())",
                defId, ownerType, ownerId, type.nameUk(), type.id(), new BigDecimal(price));
        return defId;
    }

    private void assign(UUID masterId, UUID defId) {
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), masterId, defId);
    }

    private static String weeklyScheduleBody() {
        String day = "{\"dayOfWeek\":%d,\"mode\":\"INTERVAL\",\"intervals\":[{\"startTime\":\"09:00\",\"endTime\":\"17:00\"}]}";
        List<String> days = new ArrayList<>();
        for (int isoDow = 1; isoDow <= 7; isoDow++) {
            days.add(day.formatted(isoDow));
        }
        return "{\"validFrom\":\"" + java.time.LocalDate.now(java.time.ZoneId.of("Europe/Kyiv"))
                + "\",\"days\":[" + String.join(",", days) + "]}";
    }

    private record TypeRow(UUID id, String slug, String nameUk) {
        String query() {
            return nameUk;
        }
    }

    private record Fixture(UUID aurora, UUID kalyna, UUID liubystok, String liubystokToken,
                           TypeRow configuredType, TypeRow unscheduledType) {
    }
}
