package com.beautica.location;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack integration test for the Phase 10.4 locality read API, asserting
 * against the <em>real</em> V52–V54 seeded KATOTTH taxonomy (the build-verifier
 * confirms the seed applies; this proves the read endpoints expose it
 * correctly through HTTP → controller → cached service → DB).
 *
 * <p>{@link AbstractIntegrationTest#cleanDb()} deliberately does not truncate
 * the taxonomy tables, so the seed is stable and every test here is read-only
 * and order-independent.
 *
 * <p>Lean by design: the slice/unit tests own ordering, hasDistricts, no-N+1
 * and cache mechanics in isolation. This IT pins only what needs the real
 * stack — seeded territory exclusion, real ordering, real district counts,
 * end-to-end cache hit, and the {@code permitAll}/auth security contract.
 */
@DisplayName("Phase 10.4 locality read API — full-flow integration")
class LocationIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LocationIntegrationTest.class);

    private static final String OBLASTS_URL = "/api/v1/locations/oblasts";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private void ensureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    private static HttpEntity<Void> anonymous() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    private JsonNode getData(String url) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, anonymous(), String.class);
        assertThat(response.getStatusCode())
                .as("locality GET %s is permitAll — must be 200 unauthenticated", url)
                .isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("success").asBoolean()).isTrue();
        return root.path("data");
    }

    private UUID oblastIdByNameUk(String nameUk) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM oblasts WHERE name_uk = ?", UUID.class, nameUk);
    }

    private UUID cityIdByNameUk(String nameUk) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = ? LIMIT 1", UUID.class, nameUk);
    }

    // ── /oblasts — territory exclusion + ordering against real seed ───────────

    @Test
    @DisplayName("GET /oblasts — excludes Донецька/Луганська/Крим/Севастополь and is name_uk-ordered")
    void should_excludeOccupiedAndBeNameUkOrdered_when_getOblasts() throws Exception {
        ensureHttpClient();
        log.debug("Act: GET {} against the real V53 seed — assert exclusion + ordering", OBLASTS_URL);

        JsonNode data = getData(OBLASTS_URL);

        assertThat(data.isArray()).isTrue();
        assertThat(data.size())
                .as("V53 seeds 23 serviced oblasts (22 category-O + Kyiv special-status)")
                .isEqualTo(23);

        List<String> names = new ArrayList<>();
        data.forEach(n -> names.add(n.path("nameUk").asText()));

        assertThat(names)
                .as("occupied / non-serviced oblasts must never be exposed")
                .noneMatch(n -> n.startsWith("Донецька")
                        || n.startsWith("Луганська")
                        || n.contains("Крим")
                        || n.contains("Севастополь"));

        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);
        assertThat(names)
                .as("oblasts must be returned ordered by name_uk ascending")
                .containsExactlyElementsOf(sorted);

        JsonNode first = data.get(0);
        assertThat(first.path("id").asText()).isNotBlank();
        assertThat(first.path("katotthCode").asText()).startsWith("UA");
        assertThat(first.path("nameEn").asText()).isNotBlank();
    }

    // ── /oblasts/{id}/cities — hasDistricts flag against real seed ────────────

    @Test
    @DisplayName("GET /oblasts/{id}/cities — Kyiv carries hasDistricts=true against the real seed")
    void should_flagKyivHasDistrictsTrue_when_getCitiesForKyivOblast() throws Exception {
        ensureHttpClient();
        UUID kyivOblastId = oblastIdByNameUk("Київ"); // Kyiv special-status oblast row
        log.debug("Act: GET /oblasts/{}/cities — Kyiv city must report hasDistricts=true", kyivOblastId);

        JsonNode data = getData("/api/v1/locations/oblasts/" + kyivOblastId + "/cities");

        assertThat(data.isArray()).isTrue();
        JsonNode kyivCity = null;
        for (JsonNode c : data) {
            if ("Київ".equals(c.path("nameUk").asText())) {
                kyivCity = c;
                break;
            }
        }
        assertThat(kyivCity).as("Kyiv city must be present under the Kyiv oblast").isNotNull();
        assertThat(kyivCity.path("oblastId").asText()).isEqualTo(kyivOblastId.toString());
        assertThat(kyivCity.path("hasDistricts").asBoolean())
                .as("Kyiv has 10 urban districts — hasDistricts must be true")
                .isTrue();
    }

    // ── /cities/{id}/districts — documented per-city counts ───────────────────

    @Test
    @DisplayName("GET /cities/{id}/districts — Kyiv returns 10 districts, name_uk-ordered")
    void should_returnTenDistrictsOrdered_when_cityIsKyiv() throws Exception {
        ensureHttpClient();
        UUID kyivCityId = cityIdByNameUk("Київ");
        log.debug("Act: GET /cities/{}/districts — Kyiv must return 10 ordered districts", kyivCityId);

        JsonNode data = getData("/api/v1/locations/cities/" + kyivCityId + "/districts");

        assertThat(data.size())
                .as("Kyiv category-B district count is pinned at 10 by the V53 seed")
                .isEqualTo(10);

        List<String> names = new ArrayList<>();
        data.forEach(n -> {
            assertThat(n.path("cityId").asText())
                    .as("each district must carry its parent cityId from the path")
                    .isEqualTo(kyivCityId.toString());
            names.add(n.path("nameUk").asText());
        });
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);
        assertThat(names)
                .as("districts must be returned ordered by name_uk ascending")
                .containsExactlyElementsOf(sorted);
    }

    @Test
    @DisplayName("GET /cities/{id}/districts — Kharkiv returns 9 districts")
    void should_returnNineDistricts_when_cityIsKharkiv() throws Exception {
        ensureHttpClient();
        UUID kharkivCityId = cityIdByNameUk("Харків");
        log.debug("Act: GET /cities/{}/districts — Kharkiv must return 9 districts", kharkivCityId);

        JsonNode data = getData("/api/v1/locations/cities/" + kharkivCityId + "/districts");

        assertThat(data.size())
                .as("Kharkiv category-B district count is pinned at 9 by the V53 seed")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("GET /cities/{id}/districts — a city with no urban districts returns an empty array")
    void should_returnEmptyArray_when_cityHasNoDistricts() throws Exception {
        ensureHttpClient();
        // A real seeded city that is NOT one of the 17 with category-B districts.
        UUID noDistrictCityId = jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE id NOT IN "
                        + "(SELECT DISTINCT city_id FROM city_districts) LIMIT 1",
                UUID.class);
        log.debug("Act: GET /cities/{}/districts — leaf city must return []", noDistrictCityId);

        JsonNode data = getData("/api/v1/locations/cities/" + noDistrictCityId + "/districts");

        assertThat(data.isArray()).isTrue();
        assertThat(data.size())
                .as("a city without urban districts is a locality leaf — empty list")
                .isZero();
    }

    // ── End-to-end cache — second call served without re-querying ─────────────

    @Test
    @DisplayName("GET /oblasts twice — identical body served from the Spring cache (end-to-end)")
    void should_serveSecondCallFromCache_when_oblastsRequestedTwice() throws Exception {
        ensureHttpClient();
        log.debug("Act: GET {} twice — second response must be cache-served and byte-identical", OBLASTS_URL);

        ResponseEntity<String> first = restTemplate.exchange(
                OBLASTS_URL, HttpMethod.GET, anonymous(), String.class);
        ResponseEntity<String> second = restTemplate.exchange(
                OBLASTS_URL, HttpMethod.GET, anonymous(), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody())
                .as("cached locationOblasts entry must yield an identical payload")
                .isEqualTo(first.getBody());
    }

    // ── Security contract — permitAll reachable + protected still requires auth ─

    @Test
    @DisplayName("Security — all 3 locality GETs reachable unauthenticated; a protected endpoint still 401")
    void should_allowLocalityGetsUnauthenticated_andStillProtectOtherEndpoints() throws Exception {
        ensureHttpClient();
        UUID kyivOblastId = oblastIdByNameUk("Київ");
        UUID kyivCityId = cityIdByNameUk("Київ");

        log.debug("Act: hit all 3 locality GETs anonymously, then a protected endpoint anonymously");
        assertThat(restTemplate.exchange(OBLASTS_URL, HttpMethod.GET, anonymous(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.exchange(
                "/api/v1/locations/oblasts/" + kyivOblastId + "/cities",
                HttpMethod.GET, anonymous(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.exchange(
                "/api/v1/locations/cities/" + kyivCityId + "/districts",
                HttpMethod.GET, anonymous(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // Regression guard: the permitAll() additions in SecurityConfig must not
        // have broadened the matcher — a representative authenticated-only
        // endpoint must still reject anonymous access.
        ResponseEntity<String> protectedResponse = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, anonymous(), String.class);
        assertThat(protectedResponse.getStatusCode())
                .as("GET /users/me must still require authentication — no matcher leak")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
