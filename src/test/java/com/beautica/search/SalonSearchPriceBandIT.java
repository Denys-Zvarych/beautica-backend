package com.beautica.search;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    // ── Case 17 — a master's RANGE own band reports its TRUE ceiling, not the floor ────────────

    @Test
    @DisplayName("Case 17 (D8): a salon whose only master holds RANGE 500-800 is returned by "
            + "maxPrice>=800, and its pmax is 800, not 500")
    void should_reportTrueCeiling_when_solesMasterHoldsAnOwnRangeBand() {
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);
        // A usable schedule is now REQUIRED for a master to price the salon in search
        // (2026-09-13 audit, H4 — the lateral gates on weekly_schedules exactly as the
        // catalogue gates on bookability). Cases 21-23 below are what pin that gate.
        seedUsableSchedule(masterId);
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
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);
        // A usable schedule is now REQUIRED for a master to price the salon in search
        // (2026-09-13 audit, H4 — the lateral gates on weekly_schedules exactly as the
        // catalogue gates on bookability). Cases 21-23 below are what pin that gate.
        seedUsableSchedule(masterId);
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
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);
        // A usable schedule is now REQUIRED for a master to price the salon in search
        // (2026-09-13 audit, H4 — the lateral gates on weekly_schedules exactly as the
        // catalogue gates on bookability). Cases 21-23 below are what pin that gate.
        seedUsableSchedule(masterId);
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
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);
        // A usable schedule is now REQUIRED for a master to price the salon in search
        // (2026-09-13 audit, H4 — the lateral gates on weekly_schedules exactly as the
        // catalogue gates on bookability). Cases 21-23 below are what pin that gate.
        seedUsableSchedule(masterId);
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

    /**
     * <b>Strengthened 2026-09-13 (H4).</b> This case was labelled "reconciliation" but seeded ONE
     * master with a usable schedule, so the two sides could not disagree about anything the
     * catalogue's bookability gate decides — it could not have caught the missing gate on the
     * search lateral, which is what H4 found. It now seeds a SECOND, differently-banded bookable
     * master, so the comparison is between two genuinely aggregated hulls rather than between two
     * renderings of a single row, and asserts the absolute expected values as well as the equality
     * (an equality alone stays green if BOTH sides break the same way).
     */
    @Test
    @DisplayName("Case 20 (D8 reconciliation): the catalogue's floor/ceiling for a service equal "
            + "what the search lateral computes for the same salon and category — across TWO "
            + "bookable masters with different bands")
    void should_agreeWithTheCatalogue_when_comparingTheSameSalonAndCategory() {
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);
        seedUsableSchedule(masterId);
        UUID defId = seedService(masterId, salonId, "FIXED", new BigDecimal("400.00"), null,
                "RANGE", new BigDecimal("500.00"), new BigDecimal("800.00"));

        // A second bookable master on the SAME definition, banded outside the first's interval on
        // BOTH ends, so the hull is genuinely wider than either contributor and an implementation
        // that returned just one master's band would be visibly wrong.
        UUID secondMasterId = seedSalonMaster(salonId);
        seedUsableSchedule(secondMasterId);
        assignExistingDefinition(secondMasterId, defId, "RANGE",
                new BigDecimal("450.00"), new BigDecimal("950.00"));

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

        // ABSOLUTE values too: an equality assertion alone stays green when both sides regress
        // identically (e.g. both collapsing to a single master's band).
        assertThat(searchRow.path("priceMin").decimalValue())
                .as("the hull's floor is the CHEAPER master's 450, not the first master's 500")
                .isEqualByComparingTo(new BigDecimal("450.00"));
        assertThat(searchRow.path("priceMax").decimalValue())
                .as("the hull's ceiling is the DEARER master's 950, not the first master's 800")
                .isEqualByComparingTo(new BigDecimal("950.00"));
    }

    // ── Case 21/22 (H4, 2026-09-13 audit) — a NON-BOOKABLE master must not price the salon ─────
    //
    // The catalogue drops a master with no usable schedule entirely (Phase 305 D1, pinned by
    // SalonCatalogueVisibilityIT and SalonCatalogueAggregatePriceIT case 10), so NONE of their
    // services reach GET /salons/{id}/services. The search price lateral had NO bookability gate at
    // all, so that same master still set the salon's advertised floor and ceiling: a salon could be
    // FOUND at a price its catalogue does not offer. Case 20 could not detect this — it seeds a
    // usable schedule and a single master, so the gate is never the variable.
    //
    // FIX DIRECTION: search was made to agree with the catalogue (SalonSearchSql's pr lateral now
    // requires a currently-valid weekly_schedules row). The OCCUPANCY half of the catalogue's gate
    // — day-off overrides, a fully-booked calendar, elapsed EXPLICIT_TIMES — is deliberately NOT
    // replicated; see STATIC_PROJECTION_HEAD's javadoc for why that residual divergence is
    // intentional rather than an omission.

    @Test
    @DisplayName("Case 21 (H4): a master with NO schedule at all does not price the salon in "
            + "search — the same master the catalogue drops entirely")
    void should_excludeUnschedulableMasterFromTheSearchBand() {
        UUID salonId = seedSalon();

        UUID bookableMaster = seedSalonMaster(salonId);
        seedUsableSchedule(bookableMaster);
        UUID bookableDefId = seedService(bookableMaster, salonId, "FIXED", new BigDecimal("600.00"),
                null, null, null, null);

        // Same salon, same category, NO weekly_schedules row — cheaper than everything above and
        // dearer than nothing, so if it contributed it would MOVE the band in a visible direction.
        UUID unschedulableMaster = seedSalonMaster(salonId);
        seedService(unschedulableMaster, salonId, "RANGE", new BigDecimal("100.00"),
                new BigDecimal("2000.00"), null, null, null);

        JsonNode searchRow = onlySalonRow(restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&category=MANICURE&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class), salonId);

        assertThat(searchRow.path("priceMin").decimalValue())
                .as("H4 — the unschedulable master's 100 floor must NOT reach the search band")
                .isEqualByComparingTo(new BigDecimal("600.00"));
        assertThat(searchRow.path("priceMax").decimalValue())
                .as("H4 — nor their 2000 ceiling")
                .isEqualByComparingTo(new BigDecimal("600.00"));

        // And the reconciliation the divergence broke: the catalogue agrees, master for master.
        ResponseEntity<String> catalogueResp = restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class);
        JsonNode catalogueRow = onlyCatalogueRow(catalogueResp, bookableDefId);
        assertThat(searchRow.path("priceMin").decimalValue())
                .as("search and catalogue must agree even when a non-bookable master exists")
                .isEqualByComparingTo(catalogueRow.path("priceMin").decimalValue());
    }

    @Test
    @DisplayName("Case 22 (H4): a master whose ONLY weekly template has already EXPIRED "
            + "(valid_to in the past) does not price the salon either")
    void should_excludeMasterWithExpiredScheduleFromTheSearchBand() {
        UUID salonId = seedSalon();

        UUID bookableMaster = seedSalonMaster(salonId);
        seedUsableSchedule(bookableMaster);
        seedService(bookableMaster, salonId, "FIXED", new BigDecimal("600.00"), null, null, null, null);

        UUID expiredMaster = seedSalonMaster(salonId);
        seedExpiredSchedule(expiredMaster);
        seedService(expiredMaster, salonId, "FIXED", new BigDecimal("90.00"), null, null, null, null);

        JsonNode searchRow = onlySalonRow(restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&category=MANICURE&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class), salonId);

        assertThat(searchRow.path("priceMin").decimalValue())
                .as("H4 — an expired template is not a usable schedule; the 90 floor must not show")
                .isEqualByComparingTo(new BigDecimal("600.00"));
    }

    @Test
    @DisplayName("Case 23 (H4 non-vacuity): the SAME cheap master DOES move the band once given a "
            + "usable schedule — cases 21/22 exclude on bookability, not on anything else")
    void should_includeTheSameCheapMaster_onceItHasAUsableSchedule() {
        UUID salonId = seedSalon();

        UUID bookableMaster = seedSalonMaster(salonId);
        seedUsableSchedule(bookableMaster);
        seedService(bookableMaster, salonId, "FIXED", new BigDecimal("600.00"), null, null, null, null);

        UUID cheapMaster = seedSalonMaster(salonId);
        seedUsableSchedule(cheapMaster);
        seedService(cheapMaster, salonId, "RANGE", new BigDecimal("100.00"),
                new BigDecimal("2000.00"), null, null, null);

        JsonNode searchRow = onlySalonRow(restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&category=MANICURE&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class), salonId);

        assertThat(searchRow.path("priceMin").decimalValue())
                .as("with a schedule, the cheap master's floor IS the salon's floor — so cases "
                        + "21/22 cannot be passing because the fixture never contributes")
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(searchRow.path("priceMax").decimalValue())
                .isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    // ── Cases 24-26 (2026-09-13 cycle-2 audit, B1/B3) — the gate's two missing arms, and the
    //    one divergence that is deliberately NOT closed ─────────────────────────────────────────

    @Test
    @DisplayName("Case 24 (B1a): a master with ZERO weekly_schedules rows but ONE future working "
            + "schedule_exceptions row IS bookable, reaches the catalogue, and therefore MUST "
            + "price the salon in search — the H4 gate's template-only EXISTS dropped them")
    void should_priceTheSalon_when_theOnlyMasterIsScheduledSolelyByAFutureOverride() {
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);

        // No weekly_schedules row AT ALL. The catalogue's fold resolves override-beats-template-
        // beats-gap, so a CUSTOM_HOURS override alone makes this master bookable.
        seedWorkingOverride(masterId, 7);
        UUID defId = seedService(masterId, salonId, "FIXED", new BigDecimal("450.00"), null,
                null, null, null);

        JsonNode searchRow = onlySalonRow(restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&category=MANICURE&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class), salonId);
        JsonNode catalogueRow = onlyCatalogueRow(restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class), defId);

        assertThat(searchRow.path("priceMin").decimalValue())
                .as("B1a — the override-only master IS in the catalogue hull, so the search band "
                        + "must equal it; a template-only EXISTS leaves this NULL")
                .isEqualByComparingTo(catalogueRow.path("priceMin").decimalValue());
        // FIXED definitions carry a null catalogue `priceMax` by contract (it is the RANGE ceiling),
        // so the ceiling is asserted against the seeded value directly rather than against the
        // catalogue node — comparing a null JSON node would assert 0 == 0 and prove nothing.
        assertThat(searchRow.path("priceMax").decimalValue())
                .as("the FIXED price doubles as the search band's ceiling")
                .isEqualByComparingTo(new BigDecimal("450.00"));

        // The consequence a NULL band has on the FIRST screen of discovery: NULL fails both
        // pr.pmax >= :minPrice and pr.pmin <= :maxPrice, so the salon vanishes from every
        // price-filtered search, not merely from the band column.
        JsonNode filtered = readData(restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId()
                        + "&category=MANICURE&minPrice=100&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class));
        assertThat(filtered.path("data").findValuesAsText("salonId"))
                .as("B1a — a price-filtered search must still return the salon; on NULL semantics "
                        + "it is silently omitted")
                .contains(salonId.toString());
    }

    @Test
    @DisplayName("Case 25 (B1b): a master whose ONLY template starts BEYOND the 180-day booking "
            + "window is dropped by the catalogue, so it must not price the salon in search either")
    void should_excludeMasterWhoseOnlyTemplateStartsBeyondTheBookingWindow() {
        UUID salonId = seedSalon();

        UUID bookableMaster = seedSalonMaster(salonId);
        seedUsableSchedule(bookableMaster);
        UUID bookableDefId = seedService(bookableMaster, salonId, "FIXED",
                new BigDecimal("600.00"), null, null, null, null);

        // valid_from = today + 400. @FutureOrPresent on WeeklyScheduleRequest#validFrom has NO
        // upper bound, so this is reachable over the public API.
        UUID farFutureMaster = seedSalonMaster(salonId);
        seedFarFutureSchedule(farFutureMaster);
        seedService(farFutureMaster, salonId, "FIXED", new BigDecimal("90.00"), null, null, null, null);

        JsonNode searchRow = onlySalonRow(restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&category=MANICURE&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class), salonId);
        JsonNode catalogueRow = onlyCatalogueRow(restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class), bookableDefId);

        assertThat(searchRow.path("priceMin").decimalValue())
                .as("B1b — the catalogue's own predicate is validFrom <= today+180; a valid_to-only "
                        + "EXISTS lets this master's 90 floor price the salon anyway")
                .isEqualByComparingTo(new BigDecimal("600.00"));
        assertThat(searchRow.path("priceMin").decimalValue())
                .as("search and catalogue must agree, master for master")
                .isEqualByComparingTo(catalogueRow.path("priceMin").decimalValue());
    }

    /**
     * Case 26 — the residual OCCUPANCY divergence, pinned as DELIBERATE.
     *
     * <p>Follows the convention {@code SalonCatalogueVisibilityIT
     * #should_notBeVisible_when_masterHasNoWorkingHours_pinningD1AsDeliberate} established for a
     * deliberate rule: state the asymmetry as an assertion so a future "fix" that closes it in SQL
     * — or a refactor that closes it by accident — turns this RED instead of silently passing, and
     * the reviewer reads WHY before changing it.
     *
     * <p><b>Why it is deliberate.</b> {@code SalonSearchSql}'s lateral gates on the STRUCTURAL
     * half of bookability (a schedule source exists inside the booking window) because that is
     * expressible as two index-backed {@code EXISTS}es. The catalogue's gate additionally walks the
     * free-slot calendar per master over 180 days in Java — day-off overrides, CONFIRMED bookings
     * filling a day, elapsed {@code EXPLICIT_TIMES}. Replicating THAT in SQL means either a
     * per-row function call inside a paginated Top-N over every salon in the result set, or a
     * second implementation of the slot calculator — the fork {@code ServicePricing} and
     * {@code SlotCalculationService} exist to prevent. Search answers "what price range might I
     * find at this salon" (a discovery band); the catalogue answers "what can I book here right
     * now". Do not merge the two predicates.
     */
    @Test
    @DisplayName("Case 26: a structurally-scheduled but fully day-offed master KEEPS the search "
            + "band while its catalogue is EMPTY — pinning the occupancy divergence as deliberate")
    void should_keepTheSearchBand_when_theCatalogueIsEmptyForAFullyOccupiedMaster_pinningTheOccupancyDivergenceAsDeliberate() {
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);

        // Structurally scheduled: an open-ended weekly template covering every weekday — the
        // lateral's template arm is satisfied.
        seedUsableSchedule(masterId);
        // ...but every single date of the booking window is overridden to DAY_OFF, so the
        // catalogue's fold resolves no working day and drops the master entirely.
        seedDayOffAcrossTheWholeBookingWindow(masterId);
        seedService(masterId, salonId, "FIXED", new BigDecimal("777.00"), null, null, null, null);

        JsonNode searchRow = onlySalonRow(restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&category=MANICURE&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class), salonId);
        ResponseEntity<String> catalogueResp = restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class);

        assertThat(searchRow.path("priceMin").decimalValue())
                .as("DELIBERATE: search keeps the discovery band — it gates on schedule STRUCTURE, "
                        + "never on occupancy")
                .isEqualByComparingTo(new BigDecimal("777.00"));
        assertThat(catalogueServiceCount(catalogueResp))
                .as("DELIBERATE: the catalogue is empty for the same salon, because its gate DOES "
                        + "walk the calendar. If you are here because you closed this gap in SQL, "
                        + "read this test's javadoc first — the asymmetry is the contract.")
                .isZero();
    }

    // ── Cases 27-28 (2026-09-13 cycle-3 audit, A5/A6) — two MORE divergences that are deliberately
    //    NOT closed, pinned the same way case 26 pins the occupancy one ─────────────────────────

    /**
     * <h2>A5 — three definitions of "bookable" coexist in one query, on purpose</h2>
     *
     * <p>Since the {@code pr} price lateral gained its two-armed schedule gate, this query carries
     * three different membership predicates: {@code pr} means "active service + active assignment +
     * active master + a schedule that could resolve a working day", while
     * {@code SalonSearchSql#STATIC_CATEGORY_GATE} and
     * {@code SalonSearchSql#STATIC_NAME_PREVIEW_LATERAL} still mean only "active service + active
     * assignment + active master".</p>
     *
     * <p>So a salon whose ONLY master has no resolvable schedule still passes the category gate and
     * still renders its service names on the card — with a NULL band, which sorts NULLS-LAST in
     * both price directions and is excluded by {@code STATIC_PRICE_PREDICATE} from every
     * price-bounded search. The salon appears in an unbounded category browse and vanishes the
     * instant a price filter is applied.</p>
     *
     * <p><b>Do not "fix" this by copying the two-armed EXISTS into the other two gates.</b> That
     * triples the schedule probe on the hottest public query — three correlated subplans per
     * candidate salon inside a paginated Top-N — to buy a card that shows no names instead of names
     * with no price. The honest alternative is a denormalised "has a bookable master" column
     * maintained on every schedule write, which is a write-path + migration change, not an
     * adjustment to this SQL. If you are here because you narrowed one of those gates, this test is
     * the contract you broke.</p>
     */
    @Test
    @DisplayName("Case 27 (A5): a salon whose ONLY master has no resolvable schedule still passes "
            + "the category gate and still previews its service names, with a NULL price band — "
            + "pinning the three-gate asymmetry as deliberate")
    void should_stillPassTheCategoryGateAndPreviewNames_whenTheOnlyMasterHasNoResolvableSchedule_pinningTheThreeGateAsymmetryAsDeliberate() {
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);

        // No weekly_schedules row and no schedule_exceptions row: the pr lateral's BOTH arms fail.
        seedService(masterId, salonId, "FIXED", new BigDecimal("640.00"), null, null, null, null);

        // 1. The CATEGORY gate still admits the salon — it never probes the schedule.
        JsonNode categoryRow = onlySalonRow(restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&category=MANICURE&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class), salonId);

        // 2. ...and the NAME PREVIEW lateral still renders the service, for the same reason.
        assertThat(categoryRow.path("serviceNames"))
                .as("DELIBERATE: STATIC_NAME_PREVIEW_LATERAL has no schedule probe, so the card "
                        + "still advertises the service of an unbookable master")
                .isNotEmpty();

        // 3. ...but the PRICE lateral does probe it, so the band is NULL, not a number.
        assertThat(categoryRow.path("priceMin").isNull() || categoryRow.path("priceMin").isMissingNode())
                .as("DELIBERATE: pr DOES gate on the schedule, so this salon is priced at NULL "
                        + "while being listed — the asymmetry this case exists to pin")
                .isTrue();

        // 4. The observable consequence: the same salon disappears the moment a price bound applies.
        JsonNode pricedData = readData(restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&category=MANICURE"
                        + "&minPrice=1&maxPrice=100000&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class));
        assertThat(pricedData.path("data").findValuesAsText("salonId"))
                .as("NULL fails both pr.pmax >= :minPrice and pr.pmin <= :maxPrice, so a "
                        + "price-bounded search drops the very salon the category browse listed")
                .doesNotContain(salonId.toString());
    }

    /**
     * <h2>A6 — the template arm mirrors the RANGE, not the fold's working-day outcome</h2>
     *
     * <p>{@code WeeklyScheduleRequest#days} carries only {@code @Size(max = 7)} with no
     * {@code min}, so {@code {"days":[]}} is reachable over the public API and persists a
     * {@code weekly_schedules} row with zero {@code working_intervals} and zero
     * {@code working_interval_times}. Such a master satisfies the template arm's {@code EXISTS}
     * (the ROW is there and its validity range overlaps the window) but the catalogue's fold
     * resolves no working day and drops them — so they price a salon the catalogue will not stock.
     *
     * <p><b>Deliberately not tightened (cycle-3 decision).</b> Tightening correctly needs
     * {@code EXISTS working_intervals OR EXISTS working_interval_times} correlated to
     * {@code ws.id} — BOTH, because an {@code EXPLICIT_TIMES} weekday (V84) is genuinely bookable
     * with zero {@code working_intervals} rows. Probing only the interval table would drop a
     * bookable master to a NULL band, i.e. out of every price-bounded search: strictly worse than
     * the gap, and exactly the over-narrowing cycle 2 corrected as B1. The correct form costs two
     * more correlated probes per candidate row inside a paginated Top-N on the hottest public
     * query, to remove a band-too-wide case that only arises from a provider saving an EMPTY
     * schedule. So the residue is accepted and pinned here instead.</p>
     */
    @Test
    @DisplayName("Case 28 (A6): a master whose ONLY weekly template carries ZERO working intervals "
            + "is dropped by the catalogue but STILL prices the salon in search — pinning the "
            + "empty-template gap in the template arm as deliberate")
    void should_keepTheSearchBand_when_theOnlyMasterTemplateHasNoWorkingIntervals_pinningTheEmptyTemplateGapAsDeliberate() {
        UUID salonId = seedSalon();
        UUID masterId = seedSalonMaster(salonId);

        // A weekly_schedules ROW whose validity range overlaps the window, but with no
        // working_intervals and no working_interval_times — the `{"days":[]}` shape.
        seedEmptyTemplate(masterId);
        seedService(masterId, salonId, "FIXED", new BigDecimal("512.00"), null, null, null, null);

        JsonNode searchRow = onlySalonRow(restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + testCityId() + "&category=MANICURE&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class), salonId);
        ResponseEntity<String> catalogueResp = restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class);

        assertThat(catalogueServiceCount(catalogueResp))
                .as("the fold resolves no working day from an interval-less template, so the "
                        + "catalogue drops the master entirely")
                .isZero();
        assertThat(searchRow.path("priceMin").decimalValue())
                .as("DELIBERATE: the template arm mirrors the validity RANGE only, so the same "
                        + "master still sets the search band. If you are here because you tightened "
                        + "the arm, read this test's javadoc — probing working_intervals ALONE "
                        + "would drop EXPLICIT_TIMES masters and is strictly worse than this gap.")
                .isEqualByComparingTo(new BigDecimal("512.00"));
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

    /** Assigns an EXISTING salon definition to a second master, with its own band. */
    private void assignExistingDefinition(UUID masterId, UUID defId, String overridePriceType,
            BigDecimal overridePrice, BigDecimal overridePriceMax) {
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, price_override, "
                        + "price_type_override, price_max_override, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), masterId, defId, overridePrice, overridePriceType, overridePriceMax);
    }

    /**
     * A weekly template whose {@code valid_to} is already in the past — a master who HAS schedule
     * history but no CURRENT schedule. Distinct from "no row at all" (case 21) because the two
     * exercise different halves of the lateral's {@code EXISTS} predicate.
     */
    private void seedExpiredSchedule(UUID masterId) {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Kyiv"));
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                scheduleId, masterId, today.minusDays(60), today.minusDays(1));
        for (int isoDow = 1; isoDow <= 7; isoDow++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), scheduleId, isoDow, LocalTime.of(9, 0), LocalTime.of(17, 0));
        }
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

    /**
     * A weekly template whose validity range DOES overlap the booking window but which carries no
     * {@code working_intervals} and no {@code working_interval_times} at all — the
     * {@code {"days":[]}} shape {@code WeeklyScheduleRequest} permits. Structurally present for the
     * search lateral's template arm, resolvable to nothing by the catalogue's fold (case 28 / A6).
     */
    private void seedEmptyTemplate(UUID masterId) {
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, NOW(), NOW())",
                UUID.randomUUID(), masterId, LocalDate.now(ZoneId.of("Europe/Kyiv")));
    }

    /**
     * A {@code CUSTOM_HOURS} override {@code daysAhead} days from now, with a 09:00-17:00 interval
     * and NO {@code weekly_schedules} row anywhere — the shape only the fold's override branch can
     * resolve (case 24 / B1a).
     */
    private void seedWorkingOverride(UUID masterId, int daysAhead) {
        LocalDate date = LocalDate.now(ZoneId.of("Europe/Kyiv")).plusDays(daysAhead);
        UUID exceptionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO schedule_exceptions (id, master_id, date, kind, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CUSTOM_HOURS', NOW(), NOW())",
                exceptionId, masterId, date);
        jdbcTemplate.update(
                "INSERT INTO schedule_exception_intervals (id, exception_id, start_time, end_time) "
                        + "VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), exceptionId, LocalTime.of(9, 0), LocalTime.of(17, 0));
    }

    /**
     * A weekly template whose {@code valid_from} sits far BEYOND the 180-day booking window
     * ({@code BookingWindow#MAX_DAYS_AHEAD}) the catalogue folds over — so the catalogue never
     * sees it, even though its {@code valid_to} is open-ended (case 25 / B1b).
     */
    private void seedFarFutureSchedule(UUID masterId) {
        LocalDate start = LocalDate.now(ZoneId.of("Europe/Kyiv")).plusDays(400);
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, NOW(), NOW())",
                scheduleId, masterId, start);
        for (int isoDow = 1; isoDow <= 7; isoDow++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), scheduleId, isoDow, LocalTime.of(9, 0), LocalTime.of(17, 0));
        }
    }

    /**
     * One {@code DAY_OFF} override for EVERY date the catalogue folds over (today-1 .. today+181,
     * padded a day either side so a UTC/Kyiv civil-date straddle cannot leave an uncovered edge).
     * Seeded as a single {@code generate_series} insert rather than 183 round-trips.
     */
    private void seedDayOffAcrossTheWholeBookingWindow(UUID masterId) {
        jdbcTemplate.update(
                "INSERT INTO schedule_exceptions (id, master_id, date, kind, created_at, updated_at) "
                        + "SELECT gen_random_uuid(), ?, d::date, 'DAY_OFF', NOW(), NOW() "
                        + "FROM generate_series(CURRENT_DATE - 1, CURRENT_DATE + 181, interval '1 day') d",
                masterId);
    }

    /** Total number of services the public salon catalogue returns, across every category. */
    private int catalogueServiceCount(ResponseEntity<String> resp) {
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            int count = 0;
            for (JsonNode category : objectMapper.readTree(resp.getBody())
                    .path("data").path("categories")) {
                count += category.path("services").size();
            }
            return count;
        } catch (Exception e) {
            throw new AssertionError("failed to parse catalogue response body: " + resp.getBody(), e);
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
