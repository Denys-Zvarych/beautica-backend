package com.beautica.search;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 314 D8 — the salon-search price lateral ({@code SalonSearchSql}'s {@code pmax} `CASE`)
 * must agree with the salon catalogue's Phase 314 aggregate.
 *
 * <h2>The defect this class exists to catch</h2>
 * Before D8, the lateral used {@code ms.price_override} as BOTH floor and ceiling and read
 * {@code sd.price_type}/{@code sd.price_max} with no per-master override awareness — so a master
 * holding a {@code RANGE 500-800} own band was reported to search as a flat {@code 500}. This is
 * native SQL, compiled by nothing: only a Testcontainers run against a real Postgres executes it.
 *
 * <p>{@code pmin} is UNCHANGED by D8 — the floor rule was already correct; only the {@code pmax}
 * {@code CASE} changed. Case 19 is the regression lock proving the common (all-Inherited) case did
 * not move.
 *
 * <h2>REUSE-FIRST</h2>
 * Seeds directly via JDBC, mirroring {@code SearchIntegrationTest}'s own established convention
 * for this package (raw SQL salon/master/service fixtures) — {@code ServiceTestFixtures} is
 * package-private to {@code com.beautica.service} and not visible here. Real bcrypt hashes via the
 * injected {@link PasswordEncoder} (anti-bug §M5 — no fake hash literals).
 */
@DisplayName("GET /search/salons — Phase 314 D8 price-band lateral")
class SalonSearchPriceBandIT extends AbstractIntegrationTest {

    private static final String SALONS_URL = "/api/v1/search/salons";
    private static final String TEST_PASSWORD_HASH_SOURCE = "test-password-314";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private void ensureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    // ── Case 17 — a master's RANGE own band reports its TRUE ceiling, not the floor ────────────

    @Test
    @DisplayName("Case 17 (D8): a salon whose only master holds RANGE 500-800 is returned by "
            + "maxPrice>=800, and its pmax is 800, not 500")
    void should_reportTrueCeiling_when_solesMasterHoldsAnOwnRangeBand() {
        ensureHttpClient();
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);
        seedService(masterId, salonId, "FIXED", new BigDecimal("500.00"), null,
                "RANGE", new BigDecimal("500.00"), new BigDecimal("800.00"));

        ResponseEntity<String> resp = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&maxPrice=800&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode row = onlySalonRow(resp, salonId);
        assertThat(row.path("priceMax").decimalValue())
                .as("the master's TRUE ceiling (800) must be reported, not price_override (500) — "
                        + "the exact pre-D8 defect")
                .isEqualByComparingTo("800.00");
        assertThat(row.path("priceMin").decimalValue()).isEqualByComparingTo("500.00");
    }

    /** Same fixture as case 17, but maxPrice below the ceiling must exclude it (sanity on the other side). */
    @Test
    @DisplayName("Case 17b (sanity): the same RANGE-band salon is EXCLUDED once maxPrice sits "
            + "below its true floor")
    void should_excludeSalon_when_maxPriceBelowRangeBandFloor() {
        ensureHttpClient();
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);
        seedService(masterId, salonId, "FIXED", new BigDecimal("500.00"), null,
                "RANGE", new BigDecimal("500.00"), new BigDecimal("800.00"));

        ResponseEntity<String> resp = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&maxPrice=400&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode data = readData(resp);
        assertThat(data.path("totalElements").asLong()).isZero();
    }

    // ── Case 18 — FIXED-override regression lock (does NOT discriminate pre/post-D8 SQL) ────────
    //
    // NOTE (Phase 314 audit, LOW-fix): this fixture's own-band override is FIXED 700, and the
    // pre-D8 buggy SQL's COALESCE(ms.price_override, …) also returns 700 whenever an override is
    // present, regardless of shape — so pre- and post-D8 SQL coincide on THIS fixture and this
    // case would stay green even under a `pmax` revert to the pre-D8 lateral. It locks a real,
    // correct behaviour (a FIXED override reports its own ceiling, not the definition's), but it
    // is cases 17 and 20 below — both seeded with a RANGE own-band override — that actually
    // discriminate the pre/post-D8 SQL, because the pre-D8 bug specifically collapsed a RANGE
    // override's ceiling down to its floor.

    @Test
    @DisplayName("Case 18 (FIXED-override regression lock): a master holds FIXED 700 on a RANGE "
            + "400-900 definition -> pmax == 700, not 900. Does NOT discriminate pre/post-D8 SQL "
            + "(see cases 17/20 for that)")
    void should_reportOwnFixedCeiling_when_definitionIsARangeTheMasterDidNotTake() {
        ensureHttpClient();
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);
        seedService(masterId, salonId, "RANGE", new BigDecimal("400.00"), new BigDecimal("900.00"),
                "FIXED", new BigDecimal("700.00"), null);

        ResponseEntity<String> resp = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode row = onlySalonRow(resp, salonId);
        assertThat(row.path("priceMax").decimalValue())
                .as("the master's own FIXED 700 must override the definition's RANGE ceiling (900)")
                .isEqualByComparingTo("700.00");
        assertThat(row.path("priceMin").decimalValue()).isEqualByComparingTo("700.00");
    }

    // ── Case 19 — the common (all-Inherited) case is UNCHANGED by D8 (regression lock) ─────────

    @Test
    @DisplayName("Case 19 (D8 regression lock): an all-Inherited salon's search price bounds are "
            + "unchanged from before this phase")
    void should_leaveAllInheritedSalonsBoundsUnchanged() {
        ensureHttpClient();
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);
        // No override columns at all — the pre-311/pre-314 common case.
        seedService(masterId, salonId, "FIXED", new BigDecimal("650.00"), null, null, null, null);

        ResponseEntity<String> resp = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode row = onlySalonRow(resp, salonId);
        assertThat(row.path("priceMin").decimalValue()).isEqualByComparingTo("650.00");
        assertThat(row.path("priceMax").decimalValue())
                .as("FIXED, no override: floor == ceiling, exactly as before D8")
                .isEqualByComparingTo("650.00");
    }

    // ── Case 20 — the catalogue and the search lateral agree on the SAME salon/category ────────

    @Test
    @DisplayName("Case 20 (D8 reconciliation): the catalogue's floor/ceiling for a service equal "
            + "what the search lateral computes for the same salon and category")
    void should_agreeWithTheCatalogue_when_comparingTheSameSalonAndCategory() {
        ensureHttpClient();
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);
        seedUsableSchedule(masterId);
        UUID defId = seedService(masterId, salonId, "FIXED", new BigDecimal("400.00"), null,
                "RANGE", new BigDecimal("500.00"), new BigDecimal("800.00"));

        ResponseEntity<String> searchResp = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&category=MANICURE&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);
        JsonNode searchRow = onlySalonRow(searchResp, salonId);

        ResponseEntity<String> catalogueResp = restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class);
        assertThat(catalogueResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode catalogueRow = onlyCatalogueRow(catalogueResp, defId);

        assertThat(searchRow.path("priceMin").decimalValue())
                .as("search and catalogue must agree on the floor")
                .isEqualByComparingTo(catalogueRow.path("priceMin").decimalValue());
        assertThat(searchRow.path("priceMax").decimalValue())
                .as("search and catalogue must agree on the ceiling")
                .isEqualByComparingTo(catalogueRow.path("priceMax").decimalValue());
    }

    // ── seeding + HTTP plumbing ──────────────────────────────────────────────────────────────

    private UUID seedSalon() {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "owner-314search-" + UUID.randomUUID() + "@beautica.test",
                passwordEncoder.encode(TEST_PASSWORD_HASH_SOURCE));

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Phase 314 Search Salon " + salonId, testCityId());
        return salonId;
    }

    private UUID seedSalonMaster(UUID salonId) {
        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', ?, true, true)",
                masterUserId, "master-314search-" + UUID.randomUUID() + "@beautica.test",
                passwordEncoder.encode(TEST_PASSWORD_HASH_SOURCE), salonId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return masterId;
    }

    /**
     * Seeds one SALON-owned {@code service_definitions} row (category MANICURE) plus an active
     * {@code master_services} link, optionally carrying the assignment's OWN band overrides.
     *
     * @param defPriceType       the definition's own shape ("FIXED"/"RANGE")
     * @param defBasePrice       the definition's own floor
     * @param defPriceMax        the definition's own ceiling ({@code null} for FIXED)
     * @param overridePriceType  the assignment's {@code price_type_override}, or {@code null} to
     *                           stay Inherited (in which case the two override amount params
     *                           below must also be {@code null})
     * @param overridePrice      the assignment's {@code price_override}
     * @param overridePriceMax   the assignment's {@code price_max_override}
     * @return the created {@code service_definitions.id}
     */
    private UUID seedService(UUID masterId, UUID salonId,
            String defPriceType, BigDecimal defBasePrice, BigDecimal defPriceMax,
            String overridePriceType, BigDecimal overridePrice, BigDecimal overridePriceMax) {
        UUID defId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, category, "
                        + "service_type_id, base_duration_minutes, base_price, price_type, price_max, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, ?, 'MANICURE', ?, 60, ?, ?, ?, 0, true, NOW(), NOW())",
                defId, salonId, "Phase 314 Search Service " + defId,
                resolveUnusedServiceTypeId("SALON", salonId), defBasePrice, defPriceType, defPriceMax);

        UUID msId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, price_override, "
                        + "price_type_override, price_max_override, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, true, NOW(), NOW())",
                msId, masterId, defId, overridePrice, overridePriceType, overridePriceMax);
        return defId;
    }

    /** Same recipe as {@code ServiceTestFixtures#seedUsableSchedule} — needed for the catalogue side of case 20. */
    private void seedUsableSchedule(UUID masterId) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, NOW(), NOW())",
                scheduleId, masterId, LocalDate.now(ZoneId.of("Europe/Kyiv")));
        for (int isoDow = 1; isoDow <= 7; isoDow++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), scheduleId, isoDow, LocalTime.of(9, 0), LocalTime.of(17, 0));
        }
    }

    private JsonNode readData(ResponseEntity<String> resp) {
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readTree(resp.getBody()).path("data");
        } catch (Exception e) {
            throw new AssertionError("failed to parse response body: " + resp.getBody(), e);
        }
    }

    private JsonNode onlySalonRow(ResponseEntity<String> resp, UUID salonId) {
        JsonNode data = readData(resp);
        for (JsonNode row : data.path("data")) {
            if (salonId.toString().equals(row.path("salonId").asText())) {
                return row;
            }
        }
        throw new AssertionError("salon " + salonId + " not found in search response: " + data);
    }

    private JsonNode onlyCatalogueRow(ResponseEntity<String> resp, UUID definitionId) {
        try {
            JsonNode data = objectMapper.readTree(resp.getBody()).path("data").path("categories");
            for (JsonNode category : data) {
                for (JsonNode service : category.path("services")) {
                    if (definitionId.toString().equals(service.path("id").asText())) {
                        return service;
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError("failed to parse catalogue response body: " + resp.getBody(), e);
        }
        throw new AssertionError("definition " + definitionId + " not found in catalogue response: " + resp.getBody());
    }

    private static HttpEntity<Void> anonymous() {
        return new HttpEntity<>(new org.springframework.http.HttpHeaders());
    }
}
