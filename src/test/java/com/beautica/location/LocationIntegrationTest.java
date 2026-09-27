package com.beautica.location;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /**
     * Resolves a city by its KATOTTH code, never by name.
     *
     * <p>Phase 325 made {@code name_uk} ambiguous: the taxonomy now holds a VILLAGE called «Київ»
     * in Миколаївська oblast and three settlements called «Львів». The previous
     * {@code WHERE name_uk = ? LIMIT 1} helper picked whichever row the planner returned first and
     * silently resolved the Kyiv district test onto the Mykolaiv village. That ambiguity is the
     * whole reason phase-325 D1 keeps {@code oblast_id} — so the fixture keys on the code.
     */
    private UUID cityIdByKatotthCode(String katotthCode) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE katotth_code = ?", UUID.class, katotthCode);
    }

    private static final String KYIV_CITY_CODE = "UA80000000000093317";
    private static final String KHARKIV_CITY_CODE = "UA63120270010096107";

    // ── /oblasts — territory exclusion + ordering against real seed ───────────

    @Test
    @DisplayName("GET /oblasts — excludes Крим/Севастополь and the dead-end Луганська, INCLUDES Донецька, name_uk-ordered")
    void should_excludeWholesaleExcludedAndBeNameUkOrdered_when_getOblasts() throws Exception {
        log.debug("Act: GET {} against the real V53 seed — assert exclusion + ordering", OBLASTS_URL);

        JsonNode data = getData(OBLASTS_URL);

        assertThat(data.isArray()).isTrue();
        assertThat(data.size())
                .as("24 offered oblasts — the 25 seeded rows minus Луганська, which holds no "
                        + "CITY row and so leads to an unconditionally empty second tier")
                .isEqualTo(24);

        List<String> names = new ArrayList<>();
        data.forEach(n -> names.add(n.path("nameUk").asText()));

        assertThat(names)
                .as("Crimea and Sevastopol are excluded wholesale (phase-325 D1) and must never "
                        + "be exposed")
                .noneMatch(n -> n.contains("Крим") || n.contains("Севастополь"));

        assertThat(names)
                .as("Донецька IS served: Краматорськ and Слов'янськ are cities, so picking the "
                        + "oblast leads somewhere. The occupied settlements inside it were never "
                        + "imported (D3), so there is nothing for the oblast row to leak.")
                .contains("Донецька");

        assertThat(names)
                .as("Луганська is seeded — V170 adds the row so V171 can resolve its 13 free "
                        + "settlements' oblast_id — but NOT offered: all 13 are villages/селища, "
                        + "so the CITY-bounded second tier returns [] and the user is stranded "
                        + "one tap in with nothing explaining why. The full-settlement surface is "
                        + "Phase 326's search endpoint, not this cascade.")
                .doesNotContain("Луганська");

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

    /**
     * The positive control for the exclusion above: Луганська must be ABSENT FROM THE RESPONSE
     * but PRESENT IN THE TABLE. Without this, deleting the oblast row outright — which would
     * break V171's oblast_id resolution for its 13 free settlements and lose them from Phase
     * 326's search — would also make the exclusion assertion pass.
     */
    @Test
    @DisplayName("GET /oblasts — Луганська is seeded and holds settlements; it is filtered, not deleted")
    void should_keepLuhanskSeeded_when_itIsWithheldFromThePicker() {
        Integer oblastRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oblasts WHERE name_uk = ?", Integer.class, "Луганська");
        Integer settlements = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cities c JOIN oblasts o ON o.id = c.oblast_id "
                        + "WHERE o.name_uk = ?", Integer.class, "Луганська");
        Integer cities = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cities c JOIN oblasts o ON o.id = c.oblast_id "
                        + "WHERE o.name_uk = ? AND c.settlement_type = 'CITY'",
                Integer.class, "Луганська");

        assertThat(oblastRows).as("V170 adds the row; V171 needs it to resolve oblast_id").isOne();
        assertThat(settlements)
                .as("its free settlements were imported and stay discoverable through Phase 326")
                .isEqualTo(13);
        assertThat(cities)
                .as("and none of them is a CITY — which is precisely why the cascade withholds "
                        + "the oblast")
                .isZero();
    }

    // ── /oblasts/{id}/cities — hasDistricts flag against real seed ────────────

    @Test
    @DisplayName("GET /oblasts/{id}/cities — Kyiv carries hasDistricts=true against the real seed")
    void should_flagKyivHasDistrictsTrue_when_getCitiesForKyivOblast() throws Exception {
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

    @Test
    @DisplayName("GET /oblasts/{id}/cities — returns CITY rows only, never the oblast's villages")
    void should_returnOnlyCitySettlements_when_getCitiesForTheLargestOblast() throws Exception {
        // The worst case on purpose: whichever oblast holds the most settlements after V170+V171.
        UUID largestOblastId = jdbcTemplate.queryForObject(
                "SELECT oblast_id FROM cities GROUP BY oblast_id ORDER BY COUNT(*) DESC LIMIT 1",
                UUID.class);
        Integer allSettlements = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cities WHERE oblast_id = ?", Integer.class, largestOblastId);
        Integer cityTypeOnly = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cities WHERE oblast_id = ? AND settlement_type = 'CITY'",
                Integer.class, largestOblastId);

        JsonNode data = getData("/api/v1/locations/oblasts/" + largestOblastId + "/cities");

        // Guard the guard: if the taxonomy ever shrinks back to V53's scale this assertion's
        // premise is gone and the test below would pass vacuously.
        assertThat(allSettlements)
                .as("premise — Phase 325 put four figures' worth of settlements in this oblast")
                .isGreaterThan(1_000);
        assertThat(data.size())
                .as("the cascading picker's second tier is bounded to settlement_type = 'CITY'; "
                        + "unfiltered it would ship all %d settlements of this oblast", allSettlements)
                .isEqualTo(cityTypeOnly)
                .isLessThan(100);
    }

    // ── /cities/{id}/districts — documented per-city counts ───────────────────

    @Test
    @DisplayName("GET /cities/{id}/districts — Kyiv returns 10 districts, name_uk-ordered")
    void should_returnTenDistrictsOrdered_when_cityIsKyiv() throws Exception {
        UUID kyivCityId = cityIdByKatotthCode(KYIV_CITY_CODE);
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
        UUID kharkivCityId = cityIdByKatotthCode(KHARKIV_CITY_CODE);
        log.debug("Act: GET /cities/{}/districts — Kharkiv must return 9 districts", kharkivCityId);

        JsonNode data = getData("/api/v1/locations/cities/" + kharkivCityId + "/districts");

        assertThat(data.size())
                .as("Kharkiv category-B district count is pinned at 9 by the V53 seed")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("GET /cities/{id}/districts — a city with no urban districts returns an empty array")
    void should_returnEmptyArray_when_cityHasNoDistricts() throws Exception {
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
        UUID kyivOblastId = oblastIdByNameUk("Київ");
        UUID kyivCityId = cityIdByKatotthCode(KYIV_CITY_CODE);

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
