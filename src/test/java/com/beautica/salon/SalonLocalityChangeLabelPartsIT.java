package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end, real-HTTP proof of the user-visible saved-settlement label path across a salon
 * LOCALITY CHANGE (Phase 330): an owner opens a salon in an ambiguous VILLAGE («с. Іванівка,
 * &lt;hromada&gt; громада, Полтавська обл.»), every read surface is WARMED into its cache, the salon is
 * PATCHed to a non-ambiguous CITY in a different oblast, and every surface must then serve
 * {@code CITY}, a null {@code cityHromadaNameUk} and the new oblast as {@code region}.
 *
 * <p>Nothing on the request path is mocked (only the base class's outbound e-mail beans). Each
 * cache is asserted populated before the PATCH, so a post-PATCH read that returns the new parts
 * proves the entry was EVICTED — not merely that a cold read resolves correctly (that half is
 * {@code SavedSettlementLabelPartsIT}).
 *
 * <p>Surfaces: {@code GET /salons/{id}} ({@code salon-detail}), {@code GET /salons/mine}
 * ({@code ownerSalons}), the owner's {@code GET /users/me} ({@code user-profile}) and the public
 * {@code GET /masters/{ownerMasterId}} nested salon block ({@code master-detail}, keyed by
 * masterId) — the last one is evicted ONLY by the {@code localityChanged} branch of
 * {@code SalonService#updateSalon}, which is what this test pins.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon locality change — VILLAGE→CITY label parts reach every warmed surface after eviction")
class SalonLocalityChangeLabelPartsIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final String POLTAVA_OBLAST = "Полтавська";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    @Test
    @DisplayName("owner moves a salon from an ambiguous Poltava VILLAGE to a CITY — /salons/{id}, /salons/mine, /users/me and the public master's salon all flip to CITY, null hromada, new region")
    void should_serveCityPartsOnEveryWarmedSurface_when_salonMovesFromAmbiguousVillageToCity()
            throws Exception {
        // Arrange — fixtures chosen by predicate from the Flyway-seeded taxonomy, never by id.
        UUID villageId = ambiguousPoltavaVillageId();
        String villageHromada = storedHromada(villageId);
        UUID cityId = unambiguousCityOutside(POLTAVA_OBLAST);
        String cityOblast = oblastName(cityId);
        assertThat(villageHromada).as("precondition: an ambiguous village stores a hromada").isNotBlank();
        assertThat(cityOblast).as("precondition: the move changes the oblast").isNotEqualTo(POLTAVA_OBLAST);

        UUID ownerId = UUID.randomUUID();
        String token = registerOwner(ownerId);
        UUID salonId = UUID.fromString(createSalon(token, villageId).path("id").asText());
        UUID masterId = UUID.fromString(get("/api/v1/masters/me", token).path("masterId").asText());

        // Warm every surface and prove each one reads VILLAGE + the hromada.
        assertVillage(getPublicSalon(salonId), villageHromada, "GET /salons/{id} (warm)");
        assertVillage(getMine(token, salonId), villageHromada, "GET /salons/mine (warm)");
        assertVillage(get("/api/v1/users/me", token), villageHromada, "GET /users/me (warm)");
        assertVillage(getPublicMaster(masterId).path("salon"), villageHromada,
                "GET /masters/{id} nested salon (warm)");
        assertCached("salon-detail", salonId);
        assertCached("ownerSalons", ownerId);
        assertCached(UserProfileCacheEvictor.USER_PROFILE_CACHE, ownerId);
        assertCached("master-detail", masterId);

        // Act — the owner moves the salon to a CITY in another oblast.
        JsonNode patched = patchSalonCity(token, salonId, cityId);

        // Assert — the PATCH body and every previously-warmed surface.
        assertCity(patched, cityOblast, "PATCH /salons/{id} body");
        assertCity(getPublicSalon(salonId), cityOblast, "GET /salons/{id} (after PATCH)");
        assertCity(getMine(token, salonId), cityOblast, "GET /salons/mine (after PATCH)");
        JsonNode me = get("/api/v1/users/me", token);
        assertCityParts(me, "GET /users/me (after PATCH)");
        assertThat(me.path("oblastName").asText()).as("/users/me oblast half of the label")
                .isEqualTo(cityOblast);
        assertCity(getPublicMaster(masterId).path("salon"), cityOblast,
                "GET /masters/{id} nested salon (after PATCH) — master-detail evicted by localityChanged");
    }

    // ── assertions ────────────────────────────────────────────────────────────────

    private static void assertVillage(JsonNode node, String hromada, String surface) {
        assertThat(node.path("citySettlementType").asText())
                .as("%s citySettlementType", surface).isEqualTo("VILLAGE");
        assertThat(node.path("cityHromadaNameUk").asText())
                .as("%s cityHromadaNameUk", surface).isEqualTo(hromada);
    }

    private static void assertCity(JsonNode node, String oblast, String surface) {
        assertCityParts(node, surface);
        assertThat(node.path("region").asText()).as("%s region", surface).isEqualTo(oblast);
    }

    private static void assertCityParts(JsonNode node, String surface) {
        assertThat(node.path("citySettlementType").asText())
                .as("%s citySettlementType", surface).isEqualTo("CITY");
        assertThat(node.path("cityHromadaNameUk").isNull())
                .as("%s cityHromadaNameUk must be null — got %s", surface, node.path("cityHromadaNameUk"))
                .isTrue();
    }

    private void assertCached(String cacheName, UUID key) {
        var cache = cacheManager.getCache(cacheName);
        assertThat(cache).as("cache %s exists", cacheName).isNotNull();
        assertThat(cache.get(key)).as("precondition: %s[%s] warmed before the PATCH", cacheName, key)
                .isNotNull();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private UUID ambiguousPoltavaVillageId() {
        return jdbcTemplate.queryForObject("""
                SELECT c.id FROM cities c JOIN oblasts o ON o.id = c.oblast_id
                 WHERE o.name_uk = ?
                   AND c.settlement_type = 'VILLAGE'
                   AND c.ambiguous_in_oblast
                   AND c.hromada_name_uk IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM city_districts d WHERE d.city_id = c.id)
                 ORDER BY c.katotth_code
                 LIMIT 1
                """, UUID.class, POLTAVA_OBLAST);
    }

    private UUID unambiguousCityOutside(String oblast) {
        return jdbcTemplate.queryForObject("""
                SELECT c.id FROM cities c JOIN oblasts o ON o.id = c.oblast_id
                 WHERE o.name_uk <> ?
                   AND c.settlement_type = 'CITY'
                   AND NOT c.ambiguous_in_oblast
                   AND NOT EXISTS (SELECT 1 FROM city_districts d WHERE d.city_id = c.id)
                 ORDER BY c.katotth_code
                 LIMIT 1
                """, UUID.class, oblast);
    }

    private String storedHromada(UUID cityId) {
        return jdbcTemplate.queryForObject(
                "SELECT hromada_name_uk FROM cities WHERE id = ?", String.class, cityId);
    }

    private String oblastName(UUID cityId) {
        return jdbcTemplate.queryForObject(
                "SELECT o.name_uk FROM cities c JOIN oblasts o ON o.id = c.oblast_id WHERE c.id = ?",
                String.class, cityId);
    }

    private String registerOwner(UUID ownerId) throws Exception {
        String email = "owner-move-" + ownerId + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, email, passwordEncoder.encode(TEST_PASSWORD));
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login — body: %s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<AuthResponse>>() { }).data().accessToken();
    }

    private JsonNode createSalon(String token, UUID cityId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Move Studio", "cityId", cityId,
                "street", "Shevchenka St", "buildingNo", "7"));
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/salons", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(token)), String.class);
        assertThat(response.getStatusCode()).as("POST /salons — body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private JsonNode patchSalonCity(String token, UUID salonId, UUID cityId) throws Exception {
        Map<String, Object> patch = new HashMap<>();
        patch.put("cityId", cityId);
        patch.put("street", "Hrushevskoho St");
        patch.put("buildingNo", "12");
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/salons/" + salonId,
                HttpMethod.PATCH, new HttpEntity<>(objectMapper.writeValueAsString(patch),
                        bearerHeaders(token)), String.class);
        assertThat(response.getStatusCode()).as("PATCH /salons/{id} — body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private JsonNode getPublicSalon(UUID salonId) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/salons/" + salonId, String.class);
        assertThat(response.getStatusCode()).as("GET /salons/{id} — body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private JsonNode getPublicMaster(UUID masterId) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/masters/" + masterId, String.class);
        assertThat(response.getStatusCode()).as("GET /masters/{id} — body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private JsonNode getMine(String token, UUID salonId) throws Exception {
        for (JsonNode salon : get("/api/v1/salons/mine", token)) {
            if (salonId.toString().equals(salon.path("id").asText())) {
                return salon;
            }
        }
        throw new AssertionError("salon " + salonId + " missing from GET /salons/mine");
    }

    private JsonNode get(String url, String token) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(response.getStatusCode()).as("GET %s — body: %s", url, response.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
