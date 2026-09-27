package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.UpdateMasterServiceBandRequest;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.entity.PriceType;
import com.beautica.service.service.ServiceCatalogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 314 — {@code GET /salons/{salonId}/services} prices each service as the union hull of
 * its bookable masters' RESOLVED bands, not the shared {@link com.beautica.service.entity.ServiceDefinition}'s
 * own band, which after 311 describes nobody in particular.
 *
 * <h2>D1 — resolved bands via {@code ServicePricing}, never raw columns</h2>
 * Cases 2-4 exercise every direction the aggregate can diverge from a naive
 * {@code sd.getBasePrice()}/{@code sd.getPriceMax()} read — the exact drift mutation 1 pins.
 *
 * <h2>D2 — only BOOKABLE assignments contribute</h2>
 * Cases 9-11 isolate each of the three ways a contributor can be filtered out (inactive master,
 * no working hours, inactive assignment) while a bookable sibling keeps the row visible.
 * Phase 314 audit note (LOW-fix): {@code findBookableAssignmentsBySalon} already filters
 * {@code m.is_active = true} and {@code msa.is_active = true} in SQL, so cases 9 and 11 pin the
 * END-TO-END behaviour via that SQL layer — D2's in-memory Java reshape never actually runs for
 * either fixture, because the disqualified row is never in {@code candidates} to begin with. Case
 * 10 (no working hours) is the one that genuinely exercises D2's in-memory filter, since
 * bookability-by-schedule has no boolean column for SQL to filter on and can only be computed in
 * Java.
 *
 * <h2>D3 — the rendering rule</h2>
 * Case 1 pins the common (min == max -&gt; FIXED) case; cases 2/4/5/6 exercise the rest of the
 * table. See {@code ServiceCatalogServiceCatalogTest} for the same table proven at unit level
 * (cases 21-23), including the D1 null-floor fallback (case 14 in the phase doc) — that fixture
 * needs a {@code base_price IS NULL} row, which {@code V67}'s {@code SET NOT NULL} makes
 * impossible to construct via a real INSERT against a fresh schema, so it is proven in-memory
 * instead (see this class's javadoc note at the bottom for the full explanation).
 *
 * <h2>D5 — no new query</h2>
 * Case 15 is the statement-count invariance gate — see its javadoc for why the measurement
 * varies DEFINITION count (not master count, as the phase doc's prose literally suggests): the
 * pre-existing, pre-314 bookability filter already scales with master count for reasons unrelated
 * to this phase, so master count cannot isolate what D5 actually cares about.
 *
 * <h2>REUSE-FIRST</h2>
 * Built on the same {@link ServiceTestFixtures} harness as {@code SalonCatalogueVisibilityIT} and
 * {@code MasterServiceBandEditIT} — {@code createSalonOwnerAndGetToken}, {@code createSalon},
 * {@code createSalonMaster}, {@code seedUsableSchedule}, {@code createClientAndGetToken},
 * {@code activeSelectableServiceTypes} and {@code bearerHeaders} are all shared fixture methods.
 */
@Slf4j
@Import(TestSecurityConfig.class)
@DisplayName("GET /salons/{salonId}/services — Phase 314 cross-master price-band aggregate")
class SalonCatalogueAggregatePriceIT extends AbstractIntegrationTest {

    private static final String SALON_CATALOG_CACHE = "salon-service-catalog";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ServiceCatalogService serviceCatalogService;

    @Autowired
    private EntityManagerFactory emf;

    private ServiceTestFixtures fixtures;
    private List<ServiceTestFixtures.SeededServiceType> seededTypes;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        seededTypes = fixtures.activeSelectableServiceTypes(1);
        assertThat(seededTypes).hasSize(1);
    }

    // ── Case 1 — the common case: all-Inherited masters render a single price, not a range ────

    @Test
    @DisplayName("Case 1 (D3): FIXED definition, three masters all Inherited -> ONE row, FIXED, "
            + "priceMin == base, priceMax null, single-price display (the degenerate band is NOT emitted)")
    void should_renderSinglePrice_when_allBookableMastersAreInherited() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c1-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 1 Salon");
        UUID typeId = seededTypes.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("700.00"));

        UUID masterB = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterB);
        assignOnSameDefinition(ownerToken, salonId, masterB, a.definitionId());

        UUID masterC = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterC);
        assignOnSameDefinition(ownerToken, salonId, masterC, a.definitionId());

        ServiceDefinitionResponse row = catalogueRow(salonId, a.definitionId());

        assertThat(row.priceType()).isEqualTo(PriceType.FIXED);
        assertThat(row.priceMin()).isEqualByComparingTo("700.00");
        assertThat(row.priceMax()).isNull();
        assertThat(row.priceDisplay()).isEqualTo("700 ₴");
    }

    // ── Case 2 — masters with their own FIXED bands spread the definition's flat price into a RANGE ──

    @Test
    @DisplayName("Case 2 (D1/D3): FIXED definition, masters with own FIXED bands at 600/750/900 "
            + "-> RANGE, 600, 900, \"від 600 до 900 ₴\"")
    void should_renderRange_when_mastersHoldDifferingOwnFixedBands() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c2-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 2 Salon");
        UUID typeId = seededTypes.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("999.00"));
        patchBand(ownerToken, salonId, masterA, a.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("600.00"), null, null, null, null));

        UUID masterB = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterB);
        Assignment b = assignOnSameDefinition(ownerToken, salonId, masterB, a.definitionId());
        patchBand(ownerToken, salonId, masterB, b.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("750.00"), null, null, null, null));

        UUID masterC = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterC);
        Assignment c = assignOnSameDefinition(ownerToken, salonId, masterC, a.definitionId());
        patchBand(ownerToken, salonId, masterC, c.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("900.00"), null, null, null, null));

        ServiceDefinitionResponse row = catalogueRow(salonId, a.definitionId());

        assertThat(row.priceType()).isEqualTo(PriceType.RANGE);
        assertThat(row.priceMin()).isEqualByComparingTo("600.00");
        assertThat(row.priceMax()).isEqualByComparingTo("900.00");
        assertThat(row.priceDisplay()).isEqualTo("від 600 до 900 ₴");
    }

    // ── Case 3 — the aggregate reads RESOLVED bands, not overrides alone ───────────────────────

    @Test
    @DisplayName("Case 3 (D1): one master Inherited (base = 500), one with own FIXED 900 -> "
            + "floor 500, ceiling 900 — the aggregate reads RESOLVED bands, not overrides alone")
    void should_resolveInheritedFloorAlongsideAnOwnBand() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c3-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 3 Salon");
        UUID typeId = seededTypes.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("500.00"));
        // masterA stays Inherited — deliberately no patchBand call.

        UUID masterB = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterB);
        Assignment b = assignOnSameDefinition(ownerToken, salonId, masterB, a.definitionId());
        patchBand(ownerToken, salonId, masterB, b.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("900.00"), null, null, null, null));

        ServiceDefinitionResponse row = catalogueRow(salonId, a.definitionId());

        assertThat(row.priceType()).isEqualTo(PriceType.RANGE);
        assertThat(row.priceMin()).isEqualByComparingTo("500.00");
        assertThat(row.priceMax()).isEqualByComparingTo("900.00");
    }

    // ── Case 4 — mixed FIXED/RANGE shapes need no special case (the corrected brief premise) ──

    @Test
    @DisplayName("Case 4 (D3, corrected premise): master A FIXED 700, master B RANGE 500-800 -> "
            + "floor 500, ceiling 800 — the hull spans both shapes with no special case")
    void should_spanMixedShapes_when_oneMasterIsFixedAndAnotherIsRange() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c4-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 4 Salon");
        UUID typeId = seededTypes.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("999.00"));
        patchBand(ownerToken, salonId, masterA, a.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("700.00"), null, null, null, null));

        UUID masterB = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterB);
        Assignment b = assignOnSameDefinition(ownerToken, salonId, masterB, a.definitionId());
        patchBand(ownerToken, salonId, masterB, b.definitionId(),
                new UpdateMasterServiceBandRequest(
                        PriceType.RANGE, new BigDecimal("500.00"), new BigDecimal("800.00"), null, null, null));

        ServiceDefinitionResponse row = catalogueRow(salonId, a.definitionId());

        // Case 8 (D4): assert priceMax >= priceMin explicitly over this mixed-shape fixture.
        assertThat(row.priceMax()).isGreaterThanOrEqualTo(row.priceMin());
        assertThat(row.priceType()).isEqualTo(PriceType.RANGE);
        assertThat(row.priceMin()).isEqualByComparingTo("500.00");
        assertThat(row.priceMax()).isEqualByComparingTo("800.00");
    }

    // ── Case 5 — the rendered shape is COMPUTED, never read from the definition's own shape ────

    @Test
    @DisplayName("Case 5 (D3): RANGE 400-900 definition where every bookable master took the "
            + "same FIXED 700 band -> renders FIXED 700, not the definition's RANGE")
    void should_renderDefinitionsShapeless_when_everyMasterConvergesOnTheSameFixedBand() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c5-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 5 Salon");
        UUID typeId = seededTypes.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateRange(
                ownerToken, salonId, masterA, typeId, new BigDecimal("400.00"), new BigDecimal("900.00"));
        patchBand(ownerToken, salonId, masterA, a.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("700.00"), null, null, null, null));

        UUID masterB = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterB);
        Assignment b = assignOnSameDefinition(ownerToken, salonId, masterB, a.definitionId());
        patchBand(ownerToken, salonId, masterB, b.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("700.00"), null, null, null, null));

        ServiceDefinitionResponse row = catalogueRow(salonId, a.definitionId());

        assertThat(row.priceType())
                .as("computed display shape, never sd.getPriceType() (RANGE on the definition itself)")
                .isEqualTo(PriceType.FIXED);
        assertThat(row.priceMin()).isEqualByComparingTo("700.00");
        assertThat(row.priceMax()).isNull();
    }

    // ── Case 6 — a solo bookable master reproduces their own band exactly, field for field ─────

    @Test
    @DisplayName("Case 6 (D3): exactly one bookable master -> the salon page and that master's "
            + "own page agree field-for-field")
    void should_matchTheSoleMastersOwnPage_when_onlyOneMasterIsBookable() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c6-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 6 Salon");
        UUID typeId = seededTypes.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("400.00"));
        patchBand(ownerToken, salonId, masterA, a.definitionId(), new UpdateMasterServiceBandRequest(
                PriceType.RANGE, new BigDecimal("500.00"), new BigDecimal("800.00"), null, null, null));

        ServiceDefinitionResponse catalogueRow = catalogueRow(salonId, a.definitionId());
        MasterServiceResponse masterRow = publicMasterServiceRow(masterA, a.definitionId());

        assertThat(catalogueRow.priceType()).isEqualTo(masterRow.priceType());
        assertThat(catalogueRow.priceMin()).isEqualByComparingTo(masterRow.priceMin());
        assertThat(catalogueRow.priceMax()).isEqualByComparingTo(masterRow.priceMax());
        assertThat(catalogueRow.priceDisplay()).isEqualTo(masterRow.priceDisplay());
    }

    // ── Case 7 — the provider-side and per-master surfaces are UNTOUCHED (D6) ──────────────────

    @Test
    @DisplayName("Case 7 (D6): GET /salons/{s}/masters/{m}/services still shows that master's OWN "
            + "band, and PATCH /services/{id} still returns the DEFINITION's own band — the two "
            + "contexts stay separate from the salon-catalogue aggregate")
    void should_keepPerMasterAndProviderSurfacesOnTheirOwnBands() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c7-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 7 Salon");
        UUID typeId = seededTypes.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("400.00"));

        UUID masterB = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterB);
        Assignment b = assignOnSameDefinition(ownerToken, salonId, masterB, a.definitionId());
        patchBand(ownerToken, salonId, masterB, b.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("900.00"), null, null, null, null));

        // Sanity — the aggregate itself IS a RANGE (400..900), so the two per-context surfaces
        // below are genuinely diverging from the aggregate, not coincidentally matching it.
        ServiceDefinitionResponse aggregate = catalogueRow(salonId, a.definitionId());
        assertThat(aggregate.priceType()).isEqualTo(PriceType.RANGE);

        // GET /masters/{masterA}/services — masterA is still Inherited: the DEFINITION's own band.
        MasterServiceResponse masterARow = publicMasterServiceRow(masterA, a.definitionId());
        assertThat(masterARow.priceType()).isEqualTo(PriceType.FIXED);
        assertThat(masterARow.priceMin()).isEqualByComparingTo("400.00");

        // PATCH /services/{id} — the provider-side response, untouched by the aggregate.
        ResponseEntity<String> patchResp = restTemplate.exchange(
                "/api/v1/services/" + a.definitionId(), HttpMethod.PATCH,
                new HttpEntity<>(new UpdateServiceDefinitionRequest(
                        null, "Phase 314 case 7 description", null, null, null, null, null, null, null, null),
                        fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ServiceDefinitionResponse patched = objectMapper.readValue(patchResp.getBody(),
                new TypeReference<ApiResponse<ServiceDefinitionResponse>>() {}).data();
        assertThat(patched.priceType())
                .as("the provider-side PATCH response prices the DEFINITION's own band, never the salon aggregate")
                .isEqualTo(PriceType.FIXED);
        assertThat(patched.priceMin()).isEqualByComparingTo("400.00");
        assertThat(patched.priceMax()).isNull();
    }

    // ── Case 9 — an inactive MASTER does not contribute; a bookable sibling keeps it visible ───
    //
    // NOTE (Phase 314 audit, LOW-fix): this pins the END-TO-END behaviour via the SQL layer —
    // findBookableAssignmentsBySalon already filters m.is_active = true, so D2's in-memory Java
    // reshape never runs for this fixture (the deactivated master's row is never in `candidates`).
    // See case 10 for the fixture that actually exercises D2's in-memory filter.

    @Test
    @DisplayName("Case 9 (D2 end-to-end, via SQL): an is_active=false master does not contribute "
            + "its band, though a bookable sibling keeps the row visible — see case 10 for D2's "
            + "in-memory filter")
    void should_excludeInactiveMastersBand_when_aBookableSiblingRemains() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c9-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 9 Salon");
        UUID typeId = seededTypes.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("500.00"));

        UUID masterB = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterB);
        Assignment b = assignOnSameDefinition(ownerToken, salonId, masterB, a.definitionId());
        patchBand(ownerToken, salonId, masterB, b.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("900.00"), null, null, null, null));

        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", masterB);

        ServiceDefinitionResponse row = catalogueRow(salonId, a.definitionId());
        assertThat(row.priceType())
                .as("the deactivated master's 900 must not reach the hull")
                .isEqualTo(PriceType.FIXED);
        assertThat(row.priceMin()).isEqualByComparingTo("500.00");
        assertThat(row.priceMax()).isNull();
    }

    // ── Case 10 — a master with NO working hours does not contribute (the D2 assignment-set trap) ──

    @Test
    @DisplayName("Case 10 (D2): a master with no working hours does not contribute its band, and "
            + "the row still appears via a bookable sibling — the assignment-set-vs-definition-set trap")
    void should_excludeScheduleLessMastersBand_when_aBookableSiblingRemains() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c10-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 10 Salon");
        UUID typeId = seededTypes.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("500.00"));

        UUID masterB = fixtures.createSalonMaster(salonId);
        // Deliberately NO seedUsableSchedule for masterB.
        Assignment b = assignOnSameDefinition(ownerToken, salonId, masterB, a.definitionId());
        patchBand(ownerToken, salonId, masterB, b.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("900.00"), null, null, null, null));

        ServiceDefinitionResponse row = catalogueRow(salonId, a.definitionId());
        assertThat(row.priceType())
                .as("a schedule-less master's 900 must not reach the hull, even though ITS OWN "
                        + "assignment row is fully active — this is D2's gate, not an ownership check")
                .isEqualTo(PriceType.FIXED);
        assertThat(row.priceMin()).isEqualByComparingTo("500.00");
        assertThat(row.priceMax()).isNull();
    }

    // ── Case 11 — an inactive ASSIGNMENT does not contribute ────────────────────────────────────
    //
    // NOTE (Phase 314 audit, LOW-fix): same caveat as case 9 — findBookableAssignmentsBySalon
    // already filters msa.is_active = true in SQL, so this pins the END-TO-END behaviour via that
    // SQL layer, not D2's in-memory Java reshape (which never runs for this fixture). See case 10
    // for the fixture that actually exercises D2's in-memory filter.

    @Test
    @DisplayName("Case 11 (D2 end-to-end, via SQL): a master whose ASSIGNMENT is is_active=false "
            + "does not contribute — see case 10 for D2's in-memory filter")
    void should_excludeInactiveAssignmentsBand_when_aBookableSiblingRemains() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c11-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 11 Salon");
        UUID typeId = seededTypes.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("500.00"));

        UUID masterB = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterB);
        Assignment b = assignOnSameDefinition(ownerToken, salonId, masterB, a.definitionId());
        patchBand(ownerToken, salonId, masterB, b.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("900.00"), null, null, null, null));

        jdbcTemplate.update("UPDATE master_services SET is_active = false WHERE id = ?", b.assignmentId());

        ServiceDefinitionResponse row = catalogueRow(salonId, a.definitionId());
        assertThat(row.priceType()).isEqualTo(PriceType.FIXED);
        assertThat(row.priceMin()).isEqualByComparingTo("500.00");
        assertThat(row.priceMax()).isNull();
    }

    // ── Case 12 — the dedup contract is not regressed by the aggregation reshape ────────────────

    @Test
    @DisplayName("Case 12: the service appears EXACTLY ONCE despite N masters performing it — the "
            + "existing dedup contract is not regressed by the D2 reshape")
    void should_appearExactlyOnce_when_threeMastersPerformTheSameService() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c12-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 12 Salon");
        UUID typeId = seededTypes.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("500.00"));

        for (int i = 0; i < 2; i++) {
            UUID master = fixtures.createSalonMaster(salonId);
            fixtures.seedUsableSchedule(master);
            assignOnSameDefinition(ownerToken, salonId, master, a.definitionId());
        }

        List<ServiceDefinitionResponse> rows = catalogueRows(salonId);
        assertThat(rows.stream().filter(r -> r.id().equals(a.definitionId())).count())
                .as("three performing masters must never triple the catalogue entry")
                .isEqualTo(1L);
    }

    // ── Case 13 — a per-master band edit reaches the hull on the very next read (D7 + 311 D11) ──

    @Test
    @DisplayName("Case 13 (D7 + 311 D11): read the catalogue (populating the cache), PATCH one "
            + "master's band, read again IMMEDIATELY — the hull reflects it, no manual eviction")
    void should_reflectABandEditImmediately_when_readingAfterPatch() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c13-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 13 Salon");
        UUID typeId = seededTypes.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("500.00"));

        // Populate the cache with the pre-edit snapshot — no manual eviction from here on.
        assertThat(catalogueRowNoEviction(salonId, a.definitionId()).priceMin())
                .isEqualByComparingTo("500.00");

        patchBand(ownerToken, salonId, masterA, a.definitionId(),
                new UpdateMasterServiceBandRequest(PriceType.FIXED, new BigDecimal("950.00"), null, null, null, null));

        ServiceDefinitionResponse row = catalogueRowNoEviction(salonId, a.definitionId());
        assertThat(row.priceMin())
                .as("311's evictSalonCatalogAfterCommit + this phase's aggregate compose so the "
                        + "very next read reflects the edit with no manual eviction workaround")
                .isEqualByComparingTo("950.00");
    }

    // ── Case 15 — statement-count invariance (D5) ───────────────────────────────────────────────

    /**
     * <b>Historical note (measurement corrected from the phase doc's literal "1 master vs 10
     * masters" framing, PRE-Phase-315).</b> When this test was first written, a 1-master salon cost
     * 6 {@code prepareStatementCount} and a 10-master salon cost 41 — NOT equal, and NOT a Phase 314
     * regression. {@code SlotCalculationService#filterBookableAssignments} ran one schedule-resolve
     * query ({@code resolveEffectiveRange}) and one booking-load query ({@code loadOccupiedByDay})
     * PER MASTER (see its own javadoc at the time: "O(distinct masters) heavy loads") — a cost that
     * predated Phase 314 and legitimately scaled with master count. <b>Phase 315 changed this</b>:
     * {@code filterBookableAssignmentsBatch} now resolves every master's schedule and bookings in
     * ONE statement each, for the WHOLE salon — master-count invariance is asserted directly by
     * {@code SalonCatalogueBatchLoadIT} case 9, with a named absolute constant for the 1-master
     * base and an explicit caveat for {@code EXPLICIT_TIMES} fixtures (case 12). This test's OWN
     * assertion below is unchanged and stays green under the batched gate — it was never actually
     * measuring master-count scaling (see the next paragraph), so Phase 315 does not touch it.
     *
     * <p>What D5 actually guards against — a per-definition repository call inside the
     * aggregation loop (mutation 9: replacing the in-memory grouping with
     * {@code findBookableAssignmentsBySalonAndServiceDef}) — is correctly isolated by holding
     * MASTER COUNT FIXED (one master, so the schedule/booking cost above is identical in both
     * runs, whether that cost is the pre-315 per-master load or the post-315 batched-for-one load)
     * and varying the NUMBER OF DISTINCT SERVICE DEFINITIONS that master offers instead:
     * {@code bookableDefinitions}/{@code priceForSalonCatalogue} are pure in-memory work over the
     * ALREADY-loaded {@code findBookableAssignmentsBySalon} result set (D5), so growing the
     * definition count must add zero additional statements. A per-definition lookup bug would
     * show up here as a rise proportional to definition count, exactly as mutation 9 requires.
     */
    @Test
    @DisplayName("Case 15 (D5, corrected measurement): getSalonServiceCatalog's prepareStatementCount "
            + "is invariant in the NUMBER OF DISTINCT DEFINITIONS a single master offers — the "
            + "aggregation issues no per-definition repository call")
    void should_keepStatementCountInvariant_when_definitionCountGrows() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c15-" + System.nanoTime() + "@beautica.test");
        List<ServiceTestFixtures.SeededServiceType> types = fixtures.activeSelectableServiceTypes(22);
        assertThat(types)
                .as("case 15 needs 22 distinct selectable service types to build a disjoint "
                        + "2-definition and 20-definition menu for the SAME single master")
                .hasSize(22);

        UUID fewSalonId = fixtures.createSalon(ownerToken, "Phase 314 Case 15 Few-Definitions Salon");
        UUID fewMasterId = fixtures.createSalonMaster(fewSalonId);
        fixtures.seedUsableSchedule(fewMasterId);
        bulkCreateMany(ownerToken, fewSalonId, fewMasterId, types.subList(0, 2));

        UUID manySalonId = fixtures.createSalon(ownerToken, "Phase 314 Case 15 Many-Definitions Salon");
        UUID manyMasterId = fixtures.createSalonMaster(manySalonId);
        fixtures.seedUsableSchedule(manyMasterId);
        bulkCreateMany(ownerToken, manySalonId, manyMasterId, types.subList(2, 22));

        // Warm the process-wide PlatformCategoryOrderLookup#getApprovedActive() @Cacheable cache
        // BEFORE measuring either salon — otherwise whichever salon is read first pays a one-time
        // cache-miss statement that has nothing to do with definition count, and the comparison
        // below is confounded by call ORDER rather than measuring D5's actual claim.
        serviceCatalogService.getSalonServiceCatalog(fewSalonId);

        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        evictCatalogue(fewSalonId);
        statistics.clear();
        serviceCatalogService.getSalonServiceCatalog(fewSalonId);
        long fewStatements = statistics.getPrepareStatementCount();

        evictCatalogue(manySalonId);
        statistics.clear();
        serviceCatalogService.getSalonServiceCatalog(manySalonId);
        long manyStatements = statistics.getPrepareStatementCount();

        log.info("Case 15 prepareStatementCount (master count fixed at 1): 2 definitions -> {}, "
                + "20 definitions -> {}", fewStatements, manyStatements);

        assertThat(manyStatements)
                .as("O(1) in definition count for a fixed master count. A rise here means the "
                        + "aggregation introduced a per-definition repository call: a 2-definition "
                        + "menu cost %s statements against %s for 20 definitions.",
                        fewStatements, manyStatements)
                .isEqualTo(fewStatements);
    }

    private void bulkCreateMany(String ownerToken, UUID salonId, UUID masterId,
            List<ServiceTestFixtures.SeededServiceType> types) throws Exception {
        List<BulkServiceItemRequest> items = types.stream()
                .map(t -> new BulkServiceItemRequest(t.id(), 60, PriceType.FIXED, new BigDecimal("500.00"), null, null))
                .toList();
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(items), fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── Case 16 — isFavorite stays null inside the cache, decorated only per-request (regression) ──

    @Test
    @DisplayName("Case 16 (D6/D7 regression): isFavorite is null inside the cached aggregate value "
            + "and true/false only after per-request decoration for an authenticated CLIENT")
    void should_keepIsFavoriteOutsideTheCache_when_aggregateIsCached() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-314-c16-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 314 Case 16 Salon");
        UUID typeId = seededTypes.get(0).id();
        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        Assignment a = bulkCreateFixed(ownerToken, salonId, masterA, typeId, new BigDecimal("500.00"));

        String clientToken = fixtures.createClientAndGetToken(
                "client-314-c16-" + System.nanoTime() + "@beautica.test");
        favoriteSalonService(clientToken, a.definitionId());

        ServiceDefinitionResponse anonRow = getSalonServiceRow(salonId, a.definitionId(), null);
        assertThat(anonRow.isFavorite()).as("anonymous caller must see null, never true/false").isNull();

        Cache cache = cacheManager.getCache(SALON_CATALOG_CACHE);
        assertThat(cache).isNotNull();
        SalonServiceCatalogResponse cached = cache.get(salonId, SalonServiceCatalogResponse.class);
        assertThat(cached).as("the read above must have populated the cache").isNotNull();
        assertThat(cached.categories()).flatExtracting(g -> g.services())
                .as("the CACHED value must never carry a per-client isFavorite flag")
                .allSatisfy(row -> assertThat(row.isFavorite()).isNull());

        ServiceDefinitionResponse clientRow = getSalonServiceRow(salonId, a.definitionId(), clientToken);
        assertThat(clientRow.isFavorite())
                .as("the authenticated CLIENT who favourited this service must see true")
                .isTrue();
    }

    // ── shared setup + HTTP plumbing ────────────────────────────────────────────────────────────

    private record Assignment(UUID assignmentId, UUID definitionId) {
    }

    private Assignment bulkCreateFixed(String ownerToken, UUID salonId, UUID masterId, UUID serviceTypeId,
            BigDecimal price) throws Exception {
        return bulkCreate(ownerToken, salonId, masterId,
                new BulkServiceItemRequest(serviceTypeId, 60, PriceType.FIXED, price, null, null));
    }

    private Assignment bulkCreateRange(String ownerToken, UUID salonId, UUID masterId, UUID serviceTypeId,
            BigDecimal priceMin, BigDecimal priceMax) throws Exception {
        return bulkCreate(ownerToken, salonId, masterId,
                new BulkServiceItemRequest(serviceTypeId, 60, PriceType.RANGE, null, priceMin, priceMax));
    }

    private Assignment bulkCreate(String ownerToken, UUID salonId, UUID masterId, BulkServiceItemRequest item)
            throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(List.of(item)), fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MasterServiceResponse created = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data().get(0);
        return new Assignment(created.id(), created.serviceDefinition().id());
    }

    /** A SECOND (or later) master assigned to the SAME salon-owned definition an existing assignment reuses. */
    private Assignment assignOnSameDefinition(String ownerToken, UUID salonId, UUID masterId, UUID definitionId)
            throws Exception {
        var request = new AssignServiceToMasterRequest(definitionId, null, null, null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(ownerToken)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MasterServiceResponse created = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<MasterServiceResponse>>() {}).data();
        return new Assignment(created.id(), created.serviceDefinition().id());
    }

    private MasterServiceResponse patchBand(String token, UUID salonId, UUID masterId, UUID serviceDefId,
            UpdateMasterServiceBandRequest request) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/" + serviceDefId,
                HttpMethod.PATCH, new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("band PATCH must succeed, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<MasterServiceResponse>>() {}).data();
    }

    private MasterServiceResponse publicMasterServiceRow(UUID masterId, UUID definitionId) throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity("/api/v1/masters/" + masterId + "/services", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<MasterServiceResponse> services = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data();
        return services.stream().filter(s -> s.serviceDefinition().id().equals(definitionId)).findFirst()
                .orElseThrow(() -> new AssertionError("service not found in public master services: " + definitionId));
    }

    private void evictCatalogue(UUID salonId) {
        Cache cache = cacheManager.getCache(SALON_CATALOG_CACHE);
        assertThat(cache).as("salon-service-catalog must be a registered cache").isNotNull();
        cache.evict(salonId);
    }

    /** Reads one catalogue row after explicitly evicting the cache entry (query-contract assertions). */
    private ServiceDefinitionResponse catalogueRow(UUID salonId, UUID definitionId) throws Exception {
        evictCatalogue(salonId);
        return catalogueRowNoEviction(salonId, definitionId);
    }

    /** Reads one catalogue row exactly as a real client would — no eviction workaround (case 13). */
    private ServiceDefinitionResponse catalogueRowNoEviction(UUID salonId, UUID definitionId) throws Exception {
        return byId(catalogueRowsNoEviction(salonId), definitionId);
    }

    private List<ServiceDefinitionResponse> catalogueRows(UUID salonId) throws Exception {
        evictCatalogue(salonId);
        return catalogueRowsNoEviction(salonId);
    }

    private List<ServiceDefinitionResponse> catalogueRowsNoEviction(UUID salonId) throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        SalonServiceCatalogResponse catalog = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<SalonServiceCatalogResponse>>() {}).data();
        return catalog.categories().stream().flatMap(g -> g.services().stream()).toList();
    }

    private ServiceDefinitionResponse getSalonServiceRow(UUID salonId, UUID definitionId, String tokenOrNull)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tokenOrNull != null) {
            headers.setBearerAuth(tokenOrNull);
        }
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        SalonServiceCatalogResponse catalog = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<SalonServiceCatalogResponse>>() {}).data();
        return byId(catalog.categories().stream().flatMap(g -> g.services().stream()).toList(), definitionId);
    }

    private void favoriteSalonService(String token, UUID serviceDefId) {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/favorites", HttpMethod.POST,
                new HttpEntity<>(
                        "{\"targetType\":\"SALON_SERVICE\",\"targetId\":\"" + serviceDefId + "\"}",
                        fixtures.bearerHeaders(token)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static ServiceDefinitionResponse byId(List<ServiceDefinitionResponse> list, UUID id) {
        Optional<ServiceDefinitionResponse> found = list.stream().filter(r -> r.id().equals(id)).findFirst();
        assertThat(found).as("row %s must be present in the catalogue response", id).isPresent();
        return found.get();
    }
}
