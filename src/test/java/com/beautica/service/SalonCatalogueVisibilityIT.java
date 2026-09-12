package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.entity.PriceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 305 — the single owner of the D2 salon-catalogue visibility matrix.
 *
 * <p><b>D2 — a service appears in {@code GET /salons/{salonId}/services} iff ALL of:</b>
 * <ol>
 *   <li>{@code service_definitions.owner_type = 'SALON'} and {@code owner_id = salonId}</li>
 *   <li>{@code service_definitions.is_active = true}</li>
 *   <li>at least one {@code master_services} row for it with {@code is_active = true}</li>
 *   <li>that assignment's master has {@code salon_id = salonId} and {@code is_active = true}</li>
 *   <li>that master has a free future slot for the service's effective duration (D1)</li>
 * </ol>
 * Each condition gets a positive and a negative case below (cases 1-4 for conditions 1-4 combined
 * with condition 5 always satisfied; case 5/6 isolate condition 5). Phases 302-304 each carry their
 * own focused unit/IT cases for the mechanics; this class is the cross-cutting proof that composes
 * all five conditions end to end — precedent: {@code phase-262} is the same shape, a dedicated
 * matrix phase after a multi-phase behavioural change.
 *
 * <h2>D1 — the free-slot gate (condition 5) is DELIBERATE, not a bug</h2>
 * {@link #should_notBeVisible_when_masterHasNoWorkingHours_pinningD1AsDeliberate()} is the load-bearing
 * test in this class. {@code GET /salons/{salonId}/services} answers "what can a client book at this
 * salon right now?", not "what does this salon's staff list on paper?" — a master with no working
 * hours cannot be booked, so surfacing their services would produce a catalogue entry that dead-ends.
 * <b>Any future audit finding titled "a master's services are missing from the salon catalogue" must
 * first check whether that master has working hours.</b> If they do not, this is the documented
 * contract, not a regression of Phase 302/303 — do not "fix" it; see the phase doc's D1 for the full
 * rationale. {@link #should_becomeVisible_when_masterGainsWorkingHoursAfterBeingGateClosed()} proves
 * the flip side: the gate is a genuine schedule check, not an ownership failure masquerading as one.
 *
 * <h2>REUSE-FIRST</h2>
 * Built on the same {@link ServiceTestFixtures} harness as {@code BulkServiceSetupIntegrationTest}
 * and {@code ServiceCatalogFavoriteCacheIT} — {@code createSalonMaster}, {@code seedUsableSchedule},
 * {@code enableOwnerAsMaster}, {@code insertActiveDefinitionWithoutAssignment} and
 * {@code applyV164Backfill} are all shared fixture methods (the last three promoted here from
 * {@code BulkServiceSetupIntegrationTest} so this class's case 12 reuses the exact V164 recipe
 * Phase 303 already established, rather than re-deriving it).
 *
 * <h2>Cache-bypass convention</h2>
 * Cases 1-12 read the catalogue through {@link #catalogueServiceIds(UUID)}, which evicts the
 * {@code salon-service-catalog} cache entry before every GET — deliberately, so those cases assert
 * the D2 QUERY CONTRACT alone, independent of cache-eviction wiring. Case 13 is the ONE case that
 * must NOT bypass the cache: {@link #should_reflectBulkCreateImmediately_when_firstGetAfterWrite()}
 * uses {@link #catalogueServiceIdsNoEviction(UUID)}, a real client's GET with no workaround, because
 * its whole point is proving Phase 304's automatic afterCommit eviction (mutation check (c)).
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /salons/{salonId}/services — Phase 305 D2 visibility matrix (full HTTP + real Postgres + real cache)")
class SalonCatalogueVisibilityIT extends AbstractIntegrationTest {

    private static final String SALON_CATALOG_CACHE = "salon-service-catalog";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    private ServiceTestFixtures fixtures;
    private List<ServiceTestFixtures.SeededServiceType> seededTypes;

    @BeforeEach
    void setUp() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        seededTypes = fixtures.activeSelectableServiceTypes(2);
        assertThat(seededTypes)
                .as("the seeded catalog must provide at least 2 selectable service types")
                .hasSize(2);
    }

    // ── Case 1 — the fully-conforming positive case (all five D2 conditions met) ───────────────

    @Test
    @DisplayName("Case 1: a salon master WITH working hours, an active assignment, and an active "
            + "SALON-owned definition — VISIBLE (all five D2 conditions satisfied)")
    void should_beVisible_when_allFiveD2ConditionsAreSatisfied() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c1-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 1 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);

        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, seededTypes.get(0).id());

        assertThat(catalogueServiceIds(salonId))
                .as("all five D2 conditions hold — the service must be visible")
                .contains(assignment.definitionId());
    }

    // ── Case 2 — condition 2 negative: definition inactive ──────────────────────────────────────

    @Test
    @DisplayName("Case 2: the definition is is_active=false — NOT visible (D2 condition 2)")
    void should_notBeVisible_when_definitionIsInactive() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c2-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 2 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, seededTypes.get(0).id());

        jdbcTemplate.update(
                "UPDATE service_definitions SET is_active = false WHERE id = ?", assignment.definitionId());

        assertThat(catalogueServiceIds(salonId))
                .as("an inactive definition must never appear, even with an active assignment")
                .doesNotContain(assignment.definitionId());
    }

    // ── Case 3 — condition 3 negative: assignment inactive ──────────────────────────────────────

    @Test
    @DisplayName("Case 3: the assignment is is_active=false — NOT visible (D2 condition 3)")
    void should_notBeVisible_when_assignmentIsInactive() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c3-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 3 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, seededTypes.get(0).id());

        jdbcTemplate.update(
                "UPDATE master_services SET is_active = false WHERE id = ?", assignment.assignmentId());

        assertThat(catalogueServiceIds(salonId))
                .as("a deactivated assignment must never appear, even with an active definition")
                .doesNotContain(assignment.definitionId());
    }

    // ── Case 4 — condition 4 negative: master inactive ──────────────────────────────────────────

    @Test
    @DisplayName("Case 4: the master is is_active=false — NOT visible (D2 condition 4)")
    void should_notBeVisible_when_masterIsInactive() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c4-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 4 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, seededTypes.get(0).id());

        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", masterId);

        assertThat(catalogueServiceIds(salonId))
                .as("an inactive master's services must never appear in the salon catalogue")
                .doesNotContain(assignment.definitionId());
    }

    // ── Case 5 — condition 5 (D1) negative: NO working hours. THE load-bearing test. ───────────

    /**
     * <b>D1, pinned deliberate — not a bug.</b> A master with NO working hours configured has no
     * effective schedule days, therefore no free future slot, therefore NONE of their services
     * reach the salon catalogue, even though every ownership/activity condition (1-4) is satisfied.
     *
     * <p>This is the documented contract: {@code GET /salons/{salonId}/services} answers "what can
     * a client book here right now?", not "what does the staff list on paper?". A schedule-less
     * master cannot be booked, so surfacing their services would produce a catalogue entry that
     * dead-ends at an empty slot picker.
     *
     * <p><b>Do not "fix" this.</b> Any future finding titled "a master's services are missing from
     * the salon catalogue" must first check whether that master has working hours configured. If
     * they do not, this test — and the phase-305 doc's D1 — is the answer, not a bug report.
     *
     * <p><b>Mutation check (a):</b> removing the {@code filterBookableAssignmentsBatch} call from
     * {@code ServiceCatalogService#bookableDefinitions} (Phase 315: batched, one call for the whole
     * salon — the pre-315 per-master {@code filterBookableAssignments} this note used to name no
     * longer exists) must turn this test red. If it stays green, this test is asserting the wrong
     * thing and D1 is unpinned.
     */
    @Test
    @DisplayName("Case 5 (D1 — DELIBERATE, not a bug): a master with NO working hours configured — "
            + "NOT visible, even though ownership/activity conditions 1-4 all hold")
    void should_notBeVisible_when_masterHasNoWorkingHours_pinningD1AsDeliberate() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c5-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 5 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        // Deliberately NO fixtures.seedUsableSchedule(masterId) call — this master has no
        // working hours at all, which is exactly the state D1 documents as catalogue-invisible.

        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, seededTypes.get(0).id());

        assertThat(catalogueServiceIds(salonId))
                .as("D1: a schedule-less master's service must be ABSENT from the catalogue — this "
                        + "is the deliberate free-slot gate, not a Phase 302/303 ownership regression")
                .doesNotContain(assignment.definitionId());
    }

    // ── Case 6 — composed proof that case 5 is the SCHEDULE gate, not an ownership failure ─────

    @Test
    @DisplayName("Case 6: giving the case-5 master working hours makes the service appear on the "
            + "next GET — proves case 5 is the schedule gate, not an ownership failure")
    void should_becomeVisible_when_masterGainsWorkingHoursAfterBeingGateClosed() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c6-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 6 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, seededTypes.get(0).id());

        assertThat(catalogueServiceIds(salonId))
                .as("precondition: no working hours yet, exactly like case 5")
                .doesNotContain(assignment.definitionId());

        fixtures.seedUsableSchedule(masterId);

        assertThat(catalogueServiceIds(salonId))
                .as("the SAME assignment, unchanged, becomes visible purely because a schedule now "
                        + "exists — proving case 5 was the schedule gate, not an ownership problem")
                .contains(assignment.definitionId());
    }

    // ── Case 7 — condition 4 boundary: a different salon's master ──────────────────────────────

    @Test
    @DisplayName("Case 7: a master of a DIFFERENT salon offering the same service type — never in "
            + "THIS salon's catalogue")
    void should_notAppear_when_masterBelongsToADifferentSalon() throws Exception {
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c7-a-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = fixtures.createSalon(ownerAToken, "Phase 305 Case 7 Salon A");

        String ownerBToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c7-b-" + System.nanoTime() + "@beautica.test");
        UUID salonBId = fixtures.createSalon(ownerBToken, "Phase 305 Case 7 Salon B");
        UUID masterBId = fixtures.createSalonMaster(salonBId);
        fixtures.seedUsableSchedule(masterBId);
        Assignment assignmentB = bulkCreateOneService(ownerBToken, salonBId, masterBId, seededTypes.get(0).id());

        assertThat(catalogueServiceIds(salonBId))
                .as("sanity: salon B's own catalogue does list its own master's service")
                .contains(assignmentB.definitionId());
        assertThat(catalogueServiceIds(salonAId))
                .as("salon A's catalogue must never list salon B's master's service")
                .doesNotContain(assignmentB.definitionId());
    }

    // ── Case 8 — two masters of the SAME salon offering the same type: ONE entry ────────────────

    @Test
    @DisplayName("Case 8: two masters of the SAME salon offering the same service type — the "
            + "catalogue lists it exactly ONCE, not twice")
    void should_appearExactlyOnce_when_twoMastersOfSameSalonOfferSameServiceType() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c8-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 8 Salon");
        UUID firstMasterId = fixtures.createSalonMaster(salonId);
        UUID secondMasterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(firstMasterId);
        fixtures.seedUsableSchedule(secondMasterId);
        UUID typeId = seededTypes.get(0).id();

        Assignment first = bulkCreateOneService(ownerToken, salonId, firstMasterId, typeId);
        // V121 forces the SAME definition to be reused for the second master (Phase 302 D2/D3).
        Assignment second = bulkCreateOneService(ownerToken, salonId, secondMasterId, typeId);

        assertThat(second.definitionId())
                .as("both masters share the ONE salon-owned definition for this type")
                .isEqualTo(first.definitionId());

        List<UUID> catalogue = catalogueServiceIds(salonId);
        assertThat(catalogue).contains(first.definitionId());
        assertThat(catalogue.stream().filter(id -> id.equals(first.definitionId())).count())
                .as("two performing masters must never double the catalogue entry")
                .isEqualTo(1L);
    }

    // ── Case 8b (QA-added, beyond the phase doc's 13) — OR-across-masters: one gated master must
    // not hide a shared definition that a SIBLING master keeps bookable ─────────────────────────

    /**
     * <b>QA gap closed (backend-qa, Phase 305 audit).</b> Case 8 proves dedup when BOTH masters are
     * bookable; case 5/6 prove the D1 gate on a SOLO master. Neither proves the aggregation is a
     * per-definition UNION across masters rather than an (incorrect) requirement that every master
     * offering a shared definition be individually bookable. {@link ServiceCatalogService#bookableDefinitions}
     * groups candidates by master and unions each master's bookable set via
     * {@code bookableById.putIfAbsent(...)} — so a schedule-less sibling must never revoke visibility
     * a bookable master already earned for the SAME shared definition.
     *
     * <p><b>Mutation:</b> rewriting the union aggregation in {@code bookableDefinitions} to an
     * intersection (a definition survives only if EVERY master offering it is individually bookable)
     * turns this red while cases 1-13 and case 8 stay green — none of them exercise two masters
     * sharing one definition with split bookability.
     */
    @Test
    @DisplayName("Case 8b (QA-added): two masters share ONE definition; only ONE has working hours "
            + "— the definition is still visible (union across masters, not intersection)")
    void should_beVisible_when_onlyOneOfTwoMastersSharingADefinitionIsBookable() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c8b-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 8b Salon");
        UUID scheduledMasterId = fixtures.createSalonMaster(salonId);
        UUID gatedMasterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(scheduledMasterId);
        // gatedMasterId deliberately gets NO working hours — the D1 gate stays closed for it alone.
        UUID typeId = seededTypes.get(0).id();

        Assignment scheduled = bulkCreateOneService(ownerToken, salonId, scheduledMasterId, typeId);
        // V121 reuses the SAME salon-owned definition for the second master (Phase 302 D2/D3).
        Assignment gated = bulkCreateOneService(ownerToken, salonId, gatedMasterId, typeId);
        assertThat(gated.definitionId())
                .as("both masters share the ONE salon-owned definition for this type")
                .isEqualTo(scheduled.definitionId());

        assertThat(catalogueServiceIds(salonId))
                .as("one bookable master must keep the shared definition visible even though the "
                        + "OTHER master offering it has no working hours — union, not intersection")
                .contains(scheduled.definitionId());
    }

    // ── Case 9 — condition 1 negative: an independent master's services never appear ───────────

    @Test
    @DisplayName("Case 9: an independent master's own services never appear in ANY salon catalogue "
            + "(D2 condition 1 — owner_type != SALON)")
    void should_neverAppearInAnySalonCatalogue_when_masterIsIndependent() throws Exception {
        String indepEmail = "indep-305-c9-" + System.nanoTime() + "@beautica.test";
        String indepToken = fixtures.createIndependentMasterAndGetToken(indepEmail);
        UUID indepDefId = fixtures.createIndependentMasterService(indepToken, "Independent Case 9 Service");

        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c9-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 9 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        Assignment ownSalonService = bulkCreateOneService(ownerToken, salonId, masterId, seededTypes.get(0).id());

        List<UUID> catalogue = catalogueServiceIds(salonId);
        assertThat(catalogue)
                .as("sanity: the salon's own service is genuinely present")
                .contains(ownSalonService.definitionId());
        assertThat(catalogue)
                .as("an INDEPENDENT_MASTER-owned definition must never leak into a salon's catalogue")
                .doesNotContain(indepDefId);
    }

    // ── Case 10 — owner-as-master appears in their OWN salon's catalogue (Phase 302 D5) ─────────

    @Test
    @DisplayName("Case 10: an owner-as-master row's services appear in their OWN salon's catalogue "
            + "(Phase 302 D5 — no special-casing)")
    void should_appearInOwnCatalogue_when_ownerActsAsMaster() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c10-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 10 Salon");
        UUID ownerMasterId = fixtures.enableOwnerAsMaster(ownerToken, salonId);
        fixtures.seedUsableSchedule(ownerMasterId);

        Assignment assignment = bulkCreateOneService(ownerToken, salonId, ownerMasterId, seededTypes.get(0).id());

        assertThat(catalogueServiceIds(salonId))
                .as("an owner-operated master row takes the salon branch with no special-casing")
                .contains(assignment.definitionId());
    }

    // ── Case 11 — a detached master (user_id IS NULL, Phase 294/297) with is_active=false ──────

    @Test
    @DisplayName("Case 11: a DETACHED master (user_id IS NULL, Phase 294/297) with is_active=false "
            + "— NOT visible")
    void should_notBeVisible_when_masterIsDetachedAndInactive() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c11-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 11 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);
        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, seededTypes.get(0).id());

        assertThat(catalogueServiceIds(salonId))
                .as("precondition: visible before detachment")
                .contains(assignment.definitionId());

        // Exact detachment recipe chk_masters_detachment_coherent requires (Phase 294 V157) —
        // mirrors StaffDetachCoherenceIT's fully-formed detached row.
        jdbcTemplate.update(
                "UPDATE masters SET user_id = NULL, detached_first_name = 'Тест', "
                        + "detached_last_name = 'Майстер', detached_at = NOW(), is_active = false "
                        + "WHERE id = ?",
                masterId);

        assertThat(catalogueServiceIds(salonId))
                .as("a detached, inactive master's assignment must not surface in the catalogue")
                .doesNotContain(assignment.definitionId());
    }

    // ── Case 12 — a V164-backfilled service is visible on the same terms (Phase 303) ───────────

    @Test
    @DisplayName("Case 12: a V164-backfilled service is visible on the same terms as a "
            + "natively-created one (Phase 303 composed)")
    void should_beVisibleOnSameTerms_when_serviceWasBackfilledByV164() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c12-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 12 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);

        // The exact pre-Phase-302 legacy shape: INDEPENDENT_MASTER-owned definition + an active
        // assignment for a salon-bound master. Reused from BulkServiceSetupIntegrationTest's
        // Phase 303 recipe (REUSE-FIRST).
        UUID legacyDefId = fixtures.insertActiveDefinitionWithoutAssignment(masterId, seededTypes.get(0).id());
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), masterId, legacyDefId);

        assertThat(catalogueServiceIds(salonId))
                .as("precondition: before V164 runs, the legacy INDEPENDENT_MASTER-owned row is "
                        + "invisible — reproduces the defect Phase 303 fixes")
                .doesNotContain(legacyDefId);

        fixtures.applyV164Backfill();

        assertThat(catalogueServiceIds(salonId))
                .as("after V164 promotes ownership to SALON, the backfilled service is visible on "
                        + "the same terms as a natively-created one")
                .contains(legacyDefId);
    }

    // ── Case 13 — the first GET after a bulk-create reflects it (Phase 304 composed) ───────────

    /**
     * <b>Mutation check (c):</b> reverting Phase 304's {@code evictSalonCatalogAfterCommit} call
     * from {@code bulkCreateForMaster}'s SALON branch must turn THIS test red while cases 1-12 stay
     * green — every other case reads through {@link #catalogueServiceIds(UUID)}, which evicts
     * manually before every GET and so is blind to a missing eviction. This test deliberately reads
     * through {@link #catalogueServiceIdsNoEviction(UUID)} instead, exactly as a real mobile client
     * would, so a missing eviction serves the stale (empty) cached entry instead of the fresh row.
     */
    @Test
    @DisplayName("Case 13: the very first GET after a bulk-create reflects it — no manual eviction, "
            + "exactly as a real client would observe (Phase 304 composed)")
    void should_reflectBulkCreateImmediately_when_firstGetAfterWrite() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-305-c13-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 305 Case 13 Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterId);

        // Populate the cache with the pre-write (empty) catalogue — no manual eviction from here on.
        assertThat(catalogueServiceIdsNoEviction(salonId)).isEmpty();

        Assignment assignment = bulkCreateOneService(ownerToken, salonId, masterId, seededTypes.get(0).id());

        assertThat(catalogueServiceIdsNoEviction(salonId))
                .as("the very next catalogue read (no manual eviction) must reflect the bulk-created "
                        + "service — a missing Phase 304 eviction would keep serving the stale, "
                        + "pre-write empty list for the whole 60s TTL")
                .contains(assignment.definitionId());
    }

    // ── shared setup + HTTP plumbing ────────────────────────────────────────────────────────────

    private record Assignment(UUID assignmentId, UUID definitionId) {
    }

    /** Bulk-creates exactly ONE FIXED-price service for {@code masterId} via the salon on-behalf endpoint. */
    private Assignment bulkCreateOneService(
            String ownerToken, UUID salonId, UUID masterId, UUID serviceTypeId) throws Exception {
        BulkServiceItemRequest item = new BulkServiceItemRequest(
                serviceTypeId, 60, PriceType.FIXED, new BigDecimal("350.00"), null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(List.of(item)), fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MasterServiceResponse created = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data().get(0);
        return new Assignment(created.id(), created.serviceDefinition().id());
    }

    /**
     * Reads the salon catalogue after explicitly evicting the {@code salon-service-catalog} cache
     * entry — used by cases 1-12 so they assert the D2 QUERY CONTRACT alone, independent of cache
     * staleness or eviction wiring (that composition is case 13's job alone).
     */
    private List<UUID> catalogueServiceIds(UUID salonId) throws Exception {
        Cache cache = cacheManager.getCache(SALON_CATALOG_CACHE);
        assertThat(cache).as("salon-service-catalog must be a registered cache").isNotNull();
        cache.evict(salonId);
        return catalogueServiceIdsNoEviction(salonId);
    }

    /** GETs the public salon catalogue exactly as a real client would — no eviction workaround. */
    private List<UUID> catalogueServiceIdsNoEviction(UUID salonId) throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        SalonServiceCatalogResponse catalog = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<SalonServiceCatalogResponse>>() {}).data();
        return catalog.categories().stream()
                .flatMap(group -> group.services().stream())
                .map(ServiceDefinitionResponse::id)
                .toList();
    }
}
