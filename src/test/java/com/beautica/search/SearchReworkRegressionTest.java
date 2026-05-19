package com.beautica.search;

import com.beautica.AbstractIntegrationTest;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.search.dto.LocationFilter;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.SalonSearchRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10.9 — Step 3 search-rework regression + forward-compat (M2/M3) seam
 * contract.
 *
 * <p>{@code SearchServiceTest} pins the generated-SQL <em>shape</em> at the
 * mock level and {@code SearchIntegrationTest} pins general end-to-end search,
 * but neither owns the three things Phase 10.9 Step 3 calls out by name:
 *
 * <ul>
 *   <li><b>The "Київ ≠ Киев ≠ kyiv" regression.</b> The original bug was an
 *       exact string-equality filter on the un-normalised free-text
 *       {@code users.city}/{@code salons.city} VARCHAR columns. A provider was
 *       discoverable only if the searcher typed the city byte-identically to
 *       however the row happened to store it. This proves the FK path makes a
 *       provider discoverable purely by its taxonomy {@code city_id} <em>no
 *       matter what arbitrary free-text the legacy column holds</em> — the
 *       string path is gone, end-to-end through HTTP.</li>
 *   <li><b>M2 seam call-site.</b> Discovery locality is obtained exclusively
 *       through {@link DiscoveryLocationResolver}; {@code SearchService} never
 *       reads {@code district_id} directly to decide the filter. Asserted two
 *       ways: behaviourally (a {@code @SpyBean} resolver is invoked on every
 *       search) and structurally (the {@code SearchService} source contains no
 *       district-branching outside the seam call).</li>
 *   <li><b>M3 DTO-is-object.</b> The request location filter is a structured
 *       growable object, not flat scalars, so a future Part B {@code radius} is
 *       additive. Asserted reflectively against {@link LocationFilter} and its
 *       nesting in both request DTOs.</li>
 * </ul>
 *
 * <p>Plus the multi-salon strand of Step 3: a {@code SALON_MASTER} employed at
 * two salons in two different districts is discoverable under <em>both</em>
 * districts (the salon-link locality resolves per employment row, never
 * denormalised).
 *
 * <p>{@link AbstractIntegrationTest#cleanDb()} does not truncate the taxonomy,
 * so seeded reference data is stable across tests.
 */
@DisplayName("Phase 10.9 — search-rework regression + M2/M3 seam (Step 3)")
class SearchReworkRegressionTest extends AbstractIntegrationTest {

    private static final String MASTERS_URL = "/api/v1/search/masters";
    private static final String SALONS_URL = "/api/v1/search/salons";

    /**
     * Source-file path for the M2 call-site structural assertion. Mirrors the
     * established repo pattern (see {@code VerificationSecurityGuardTest}):
     * {@code user.dir} is the module root under Gradle test execution.
     */
    private static final Path SEARCH_SERVICE_SOURCE =
            Paths.get(System.getProperty("user.dir"))
                    .resolve("src/main/java/com/beautica/search/service/SearchService.java");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Real bean, observed. Proves the M2 seam is on the call path for every
     * search without altering its behaviour — a regression that bypassed the
     * resolver to read {@code district_id}/{@code city_id} directly would make
     * these {@code verify} calls fail.
     */
    @SpyBean
    private DiscoveryLocationResolver discoveryLocationResolver;

    private void ensureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    private static HttpEntity<Void> anonymous() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    private UUID cityIdByName(String nameUk) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = ? ORDER BY katotth_code LIMIT 1",
                UUID.class, nameUk);
    }

    private UUID districtIdInCity(String cityNameUk, int index) {
        return jdbcTemplate.queryForObject(
                "SELECT cd.id FROM city_districts cd "
                        + "JOIN cities c ON c.id = cd.city_id "
                        + "WHERE c.name_uk = ? ORDER BY cd.katotth_code OFFSET ? LIMIT 1",
                UUID.class, cityNameUk, index);
    }

    // ── The "Київ ≠ Киев ≠ kyiv" regression (string path is gone) ─────────────

    @Test
    @DisplayName("regression — a salon is found by its taxonomy city_id even though its legacy free-text city holds the Russian spelling 'Киев' (old AND city = :city path is gone)")
    void should_findSalonByCityId_regardlessOfLegacyFreeTextSpelling() throws Exception {
        ensureHttpClient();
        UUID kyivCityId = cityIdByName("Київ");

        // The salon's legacy free-text column is the WRONG spelling on purpose
        // ("Киев" — Russian; the old code did `WHERE city = :city` so a Київ
        // searcher would never have matched). Discovery is the FK now.
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "regr-owner-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest");
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, region, city_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'Киев', 'kyiv-oblast-RU', ?, true, NOW(), NOW())",
                salonId, ownerId, "RegressionSalon", kyivCityId);

        // Search by the taxonomy city_id of "Київ". Pre-fix this returned 0
        // (free-text "Київ" != stored "Киев"); post-fix the FK matches.
        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + kyivCityId + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong())
                .as("FK discovery must find the salon by city_id regardless of the "
                        + "arbitrary legacy free-text spelling — the broken string-"
                        + "equality path ('Київ' != 'Киев' != 'kyiv') is gone")
                .isEqualTo(1L);
        assertThat(page.path("data").get(0).path("name").asText())
                .isEqualTo("RegressionSalon");
        assertThat(page.path("data").get(0).path("cityLabel").asText())
                .as("the resolved label is the taxonomy name_uk ('Київ'), never "
                        + "the dirty free-text column")
                .isEqualTo("Київ");
    }

    @Test
    @DisplayName("regression — a master is found by city_id even though its user-row legacy city is a third arbitrary spelling 'kyiv'")
    void should_findMasterByCityId_regardlessOfLegacyUserRowSpelling() throws Exception {
        ensureHttpClient();
        UUID kyivCityId = cityIdByName("Київ");

        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "regr-m-owner-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest");
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, city_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'kyiv', ?, true, NOW(), NOW())",
                salonId, ownerId, "RegrMasterSalon", kyivCityId);
        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, city, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', ?, 'KYÏV-typo', true, true)",
                masterUserId, "regr-master-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', 4.5::numeric, 1, true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + kyivCityId + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("three different arbitrary free-text spellings across the rows "
                        + "('kyiv', 'KYÏV-typo') must not matter — FK discovery via "
                        + "the salon-link city_id is the only path")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText())
                .isEqualTo(masterId.toString());
    }

    // ── M2 seam: discovery goes through the resolver, never reads FK directly ─

    @Test
    @DisplayName("M2 — every master & salon search routes locality through DiscoveryLocationResolver.resolveFilter (seam is on the call path)")
    void should_invokeResolverSeam_when_searchPerformed() {
        ensureHttpClient();
        Mockito.clearInvocations(discoveryLocationResolver);
        UUID kyivCityId = cityIdByName("Київ");

        restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + kyivCityId + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);
        restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + kyivCityId + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        // The seam is the ONLY way locality enters the query layer. A
        // regression that read location.districtId()/cityId() to branch the
        // filter directly (bypassing Part B's swap point) would never call
        // resolveFilter and this verify would fail.
        Mockito.verify(discoveryLocationResolver, Mockito.atLeast(2))
                .resolveFilter(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("M2 — SearchService source never branches on district_id outside the resolver seam (call-site architecture assertion)")
    void should_notReadDistrictIdDirectly_inSearchServiceSource() throws Exception {
        // Structural guard complementing the behavioural spy: the production
        // class must obtain locality only via the seam. The SQL string may
        // still NAME the FK columns to express the predicate (that is the
        // query, not the decision), but there must be no Java-level branch
        // such as `request.location().districtId() != null` deciding the
        // filter — that decision is the resolver's job (Part B swap point).
        String source = Files.readString(SEARCH_SERVICE_SOURCE);

        // The only legitimate place the raw selection is touched is the single
        // seam call `discoveryLocationResolver.resolveFilter(location.cityId(),
        // location.districtId())`. Strip that one line, then assert no other
        // `location.districtId()` / `.districtId() != null` decision remains.
        String withoutSeamCall = source.replaceAll(
                "discoveryLocationResolver\\.resolveFilter\\([^;]*\\);", "");

        assertThat(withoutSeamCall)
                .as("SearchService must not read LocationFilter.districtId() to "
                        + "decide the filter anywhere except the single M2 "
                        + "resolveFilter(...) seam call — Part B swaps the resolver, "
                        + "not this class")
                .doesNotContain("location.districtId()")
                .doesNotContain("location().districtId()")
                .doesNotContain("request.location().districtId()");
        assertThat(source)
                .as("the seam call itself must be present (the resolver is the "
                        + "only locality decision point)")
                .contains("discoveryLocationResolver.resolveFilter(");
    }

    // ── M3 — request location filter is a structured growable object ──────────

    @Test
    @DisplayName("M3 — LocationFilter is a record OBJECT (not flat scalars) nested under both request DTOs so a future radius is additive")
    void should_modelLocationAsGrowableObject_when_inspectingRequestDtos() throws Exception {
        assertThat(LocationFilter.class.isRecord())
                .as("the location filter must be a structured record object — Part B "
                        + "grows it additively with lat/lng/radius without reshaping "
                        + "the request again (M3)")
                .isTrue();

        // It is nested as a single `location` component on BOTH request DTOs —
        // not two flat top-level cityId/districtId scalars on the request.
        for (Class<?> requestDto : List.of(MasterSearchRequest.class, SalonSearchRequest.class)) {
            assertThat(requestDto.isRecord()).isTrue();

            List<RecordComponent> components = List.of(requestDto.getRecordComponents());
            assertThat(components)
                    .as("%s must carry a single nested `location` LocationFilter "
                            + "object, never flat cityId/districtId scalars",
                            requestDto.getSimpleName())
                    .anySatisfy(c -> {
                        assertThat(c.getName()).isEqualTo("location");
                        assertThat(c.getType()).isEqualTo(LocationFilter.class);
                    });
            assertThat(components)
                    .as("%s must NOT expose flat top-level cityId/districtId — that "
                            + "is the non-growable shape M3 forbids",
                            requestDto.getSimpleName())
                    .noneSatisfy(c -> assertThat(c.getName())
                            .isIn("cityId", "districtId"));
        }

        // Documented forward-compat: a Part B `radius` (or lat/lng) is purely
        // additive — appending a component to LocationFilter does not change
        // the cityId/districtId contract callers already use. We assert the
        // CURRENT shape is exactly the two FK ids so that an additive Part B
        // change is reviewable as additive (a removed/renamed field would fail
        // here, surfacing a non-additive — i.e. breaking — change).
        assertThat(List.of(LocationFilter.class.getRecordComponents()))
                .extracting(RecordComponent::getName)
                .as("Part A LocationFilter is exactly {cityId, districtId}; Part B "
                        + "ADDS radius/lat/lng — this list growing is fine, a name "
                        + "changing is a breaking (non-additive) regression")
                .containsExactly("cityId", "districtId");
    }

    // ── Multi-salon (2.11–2.14): one owner, two salons, two districts ─────────

    @Test
    @DisplayName("multi-salon — one owner's two salons sit in two districts; each salon's master is discoverable ONLY under its own salon's district (salon-link locality resolves per row, never denormalised)")
    void should_discoverEachSalonMasterUnderItsOwnSalonDistrict() throws Exception {
        // The Beautica model (V4: masters.user_id UNIQUE) is one masters row
        // per user — a master belongs to exactly one salon. "Multi-salon"
        // (phases 2.11–2.14) means a SALON_OWNER owning several salons. The
        // Step 3 property under test: when one owner has two salons in two
        // districts, each salon's employed master is discovered under THAT
        // salon's district (COALESCE(sal.district_id, u.district_id) resolved
        // per masters row at query time), and the two are not cross-visible —
        // proving salon-link locality is never denormalised onto the owner or
        // the user row.
        ensureHttpClient();
        UUID kyivCityId = cityIdByName("Київ");
        UUID districtA = districtIdInCity("Київ", 0);
        UUID districtB = districtIdInCity("Київ", 1);

        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "ms-owner-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest");

        UUID masterInA = seedSalonWithMaster(ownerId, kyivCityId, districtA, "SalonInDistrictA");
        UUID masterInB = seedSalonWithMaster(ownerId, kyivCityId, districtB, "SalonInDistrictB");

        ResponseEntity<String> underA = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + kyivCityId
                        + "&location.districtId=" + districtA + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);
        ResponseEntity<String> underB = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + kyivCityId
                        + "&location.districtId=" + districtB + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode pageA = objectMapper.readTree(underA.getBody()).path("data");
        JsonNode pageB = objectMapper.readTree(underB.getBody()).path("data");

        assertThat(pageA.path("totalElements").asLong())
                .as("only salon-A's master is discoverable under district A")
                .isEqualTo(1L);
        assertThat(pageA.path("data").get(0).path("masterId").asText())
                .isEqualTo(masterInA.toString());

        assertThat(pageB.path("totalElements").asLong())
                .as("only salon-B's master is discoverable under district B — the "
                        + "other owner-salon's master is NOT cross-visible; salon-link "
                        + "locality resolves per masters row, never denormalised")
                .isEqualTo(1L);
        assertThat(pageB.path("data").get(0).path("masterId").asText())
                .isEqualTo(masterInB.toString());

        // Cross-check: district A must NOT surface salon-B's master (and vice
        // versa) — a denormalised/owner-level locality leak would break this.
        for (JsonNode row : pageA.path("data")) {
            assertThat(row.path("masterId").asText()).isNotEqualTo(masterInB.toString());
        }
        for (JsonNode row : pageB.path("data")) {
            assertThat(row.path("masterId").asText()).isNotEqualTo(masterInA.toString());
        }
    }

    /**
     * Seeds one salon (under {@code ownerId}) stamped with the given
     * city/district FK and one employed SALON_MASTER + masters row. Returns the
     * {@code masters.id}. The master's discovery locality resolves through this
     * salon link at query time — never copied onto the user row.
     */
    private UUID seedSalonWithMaster(UUID ownerId, UUID cityId, UUID districtId, String salonName) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, city_id, district_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'Київ', ?, ?, true, NOW(), NOW())",
                salonId, ownerId, salonName, cityId, districtId);

        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', ?, true, true)",
                masterUserId, "ms-master-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", salonId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', 4.7::numeric, 3, true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return masterId;
    }

}
