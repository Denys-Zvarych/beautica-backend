package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.UpdateMasterServiceBandRequest;
import com.beautica.service.entity.PriceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 465 — salon bulk setup resolves each item to a salon COPY (type + duration + name) instead
 * of one definition per type. Acceptance criteria 1–10 of the phase doc, end to end against real
 * Postgres, plus the D1a item-order pin. Every case builds its own salon, so the cases are
 * independent of each other and of execution order.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Phase 465 — salon service copies in bulk setup")
class SalonServiceCopiesIT extends AbstractIntegrationTest {

    private static final String CATEGORY = "NAIL_SERVICE";
    private static final String OTHER_NAME = "Масаж спини авторський";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;
    private UUID typeId;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        typeId = fixtures.resolveServiceTypeIdForCategory(CATEGORY);
    }

    // ── AC1 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC1: master A holds the 60 copy; bulk at 90 → a new salon copy, A holds both, 201")
    void should_createSecondCopy_when_masterAlreadyHoldsTheTypeAtAnotherDuration() throws Exception {
        Salon salon = newSalon("ac1");
        UUID masterA = fixtures.createSalonMaster(salon.id());
        String copy60 = defIdOf(bulk(salon, masterA, item(60, "500.00")), 0);

        ResponseEntity<String> resp = bulk(salon, masterA, item(90, "700.00"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        String copy90 = defIdOf(resp, 0);
        assertThat(copy90).isNotEqualTo(copy60);
        assertThat(salonCopyDurations(salon.id())).containsExactlyInAnyOrder(60, 90);
        assertThat(activeDefIdsOf(masterA)).containsExactlyInAnyOrder(copy60, copy90);
    }

    // ── AC2 / AC3 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC2: salon holds 60 + 90; master B bulk at 90 → B is assigned the 90 copy, no third copy")
    void should_reuseMatchingCopy_when_salonHoldsSeveralCopies() throws Exception {
        Salon salon = twoCopySalon("ac2");
        UUID masterB = fixtures.createSalonMaster(salon.id());

        ResponseEntity<String> resp = bulk(salon, masterB, item(90, "650.00"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(defIdOf(resp, 0)).isEqualTo(salon.copy90());
        assertThat(salonCopyDurations(salon.id())).containsExactlyInAnyOrder(60, 90);
    }

    @Test
    @DisplayName("AC3: salon holds 60 + 90; master B bulk at 60 → B reuses the 60 copy, inherited band, no duration override")
    void should_reuseSixtyCopy_when_itemMatchesIt() throws Exception {
        Salon salon = twoCopySalon("ac3");
        UUID masterB = fixtures.createSalonMaster(salon.id());

        ResponseEntity<String> resp = bulk(salon, masterB, item(60, "500.00"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode row = fixtures.data(resp).path(0);
        assertThat(row.path("serviceDefinition").path("id").asText()).isEqualTo(salon.copy60());
        assertThat(row.path("priceOverride").isNull()).isTrue();
        assertThat(row.path("durationOverrideMinutes").isNull()).isTrue();
        assertThat(salonCopyDurations(salon.id())).containsExactlyInAnyOrder(60, 90);
    }

    // ── AC4 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC4: master A already holds the 60 copy; bulk at 60 again → 409 DUPLICATE_SERVICE naming it")
    void should_return409_when_masterAlreadyHoldsTheExactCopy() throws Exception {
        Salon salon = twoCopySalon("ac4");

        ResponseEntity<String> resp = bulk(salon, salon.masterA(), item(60, "800.00"));

        fixtures.assertDuplicateOf(resp, salon.copy60());
        assertThat(salonCopyDurations(salon.id())).containsExactlyInAnyOrder(60, 90);
    }

    // ── AC5 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC5: a per-master price override on A's 90 copy leaves A's 60 copy unchanged")
    void should_keepBandsPerCopy_when_overridingOneCopy() throws Exception {
        Salon salon = twoCopySalon("ac5");

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salon.id() + "/masters/" + salon.masterA() + "/services/" + salon.copy90(),
                HttpMethod.PATCH,
                new HttpEntity<>(new UpdateMasterServiceBandRequest(
                        PriceType.FIXED, new BigDecimal("999.00"), null, null, null, null),
                        fixtures.bearerHeaders(salon.ownerToken())),
                String.class);

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(priceOverrideOf(salon.masterA(), salon.copy90())).isEqualByComparingTo("999.00");
        assertThat(priceOverrideOf(salon.masterA(), salon.copy60())).isNull();
    }

    // ── AC6 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC6: unassign A's 90 copy, bulk at 90 again → the old assignment row is reactivated, not a new one")
    void should_reactivateSameRow_when_reAddingAnUnassignedCopy() throws Exception {
        Salon salon = twoCopySalon("ac6");
        UUID rowId = assignmentIdOf(salon.masterA(), salon.copy90());
        // unassign(...) asserts the DELETE succeeded — a precondition, not the behaviour under test.
        unassign(salon, salon.masterA(), salon.copy90());

        ResponseEntity<String> resp = bulk(salon, salon.masterA(), item(90, "700.00"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(fixtures.data(resp).path(0).path("id").asText()).isEqualTo(rowId.toString());
        assertThat(rowCountOf(salon.masterA(), salon.copy90())).isEqualTo(1L);
        assertThat(activeDefIdsOf(salon.masterA())).containsExactlyInAnyOrder(salon.copy60(), salon.copy90());
    }

    // ── AC7 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC7a: a salon admin's run produces exactly the owner's per-copy results on a twin salon")
    void should_matchOwnerResults_when_salonAdminDrivesBulk() throws Exception {
        Salon ownerDriven = newSalon("ac7-owner");
        Salon twin = newSalon("ac7-admin");
        String adminToken = fixtures.createSalonAdminAndGetToken(
                twin.id(), "admin465-" + System.nanoTime() + "@beautica.test");
        Salon adminDriven = new Salon(twin.id(), adminToken, null, null, null);

        CopiesRun ownerRun = runCopiesSequence(ownerDriven);
        CopiesRun adminRun = runCopiesSequence(adminDriven);

        // Pin the owner baseline first so two equally broken runs cannot pass as "the same".
        assertThat(ownerRun.statuses()).containsExactly(201, 201, 201, 409);
        assertThat(ownerRun.reusedCopy()).isEqualTo("copy90");
        assertThat(ownerRun.duplicateCopy()).isEqualTo("copy60");
        assertThat(adminRun).isEqualTo(ownerRun);
    }

    @Test
    @DisplayName("AC7b: an admin of another salon bulks into this salon → 403, nothing created")
    void should_return403_when_foreignSalonAdminDrivesBulk() throws Exception {
        Salon salon = newSalon("ac7-target");
        UUID masterB = fixtures.createSalonMaster(salon.id());
        Salon other = newSalon("ac7-other");
        String foreignAdmin = fixtures.createSalonAdminAndGetToken(
                other.id(), "fadmin465-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> resp = bulk(new Salon(salon.id(), foreignAdmin, null, null, null),
                masterB, item(120, "900.00"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(salonCopyDurations(salon.id())).isEmpty();
        assertThat(activeDefIdsOf(masterB)).isEmpty();
    }

    // ── AC8 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC8: salon holds 60 only; one bulk for B with 60 + 90 → B reuses 60 and gets a new 90 copy")
    void should_reuseAndCreate_when_oneBulkCarriesTwoDurations() throws Exception {
        Salon salon = oneCopySalon("ac8");
        UUID masterB = fixtures.createSalonMaster(salon.id());

        ResponseEntity<String> resp = bulk(salon, masterB, item(60, "500.00"), item(90, "700.00"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(defIdOf(resp, 0)).isEqualTo(salon.copy60());
        assertThat(defIdOf(resp, 1)).isNotEqualTo(salon.copy60());
        assertThat(salonCopyDurations(salon.id())).containsExactlyInAnyOrder(60, 90);
    }

    @Test
    @DisplayName("AC8 order-independence: 90 + 60 against a single 60 copy → same outcome as 60 + 90")
    void should_resolveIndependentlyOfItemOrder_when_exactMatchComesLast() throws Exception {
        Salon salon = oneCopySalon("ac8r");
        UUID masterB = fixtures.createSalonMaster(salon.id());

        ResponseEntity<String> resp = bulk(salon, masterB, item(90, "700.00"), item(60, "500.00"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(defIdOf(resp, 1)).isEqualTo(salon.copy60());
        assertThat(fixtures.data(resp).path(1).path("durationOverrideMinutes").isNull()).isTrue();
        assertThat(defIdOf(resp, 0)).isNotEqualTo(salon.copy60());
        assertThat(salonCopyDurations(salon.id())).containsExactlyInAnyOrder(60, 90);
    }

    // ── AC9 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC9: C bulk 90 + 90 named → two copies matched/created per item, C holds both; a repeat → 409, C's assignments unchanged")
    void should_resolvePerItem_when_oneBulkCarriesNamedAndUnnamedCopies() throws Exception {
        Salon salon = twoCopySalon("ac9");
        UUID masterC = fixtures.createSalonMaster(salon.id());

        ResponseEntity<String> first = bulk(salon, masterC, item(90, "700.00"), namedItem(90, "750.00", OTHER_NAME));
        List<String> afterFirst = activeDefIdsOf(masterC);
        ResponseEntity<String> repeat = bulk(salon, masterC, item(90, "700.00"), namedItem(90, "750.00", OTHER_NAME));

        assertThat(first.getStatusCode()).as("body: %s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(defIdOf(first, 0)).isEqualTo(salon.copy90());
        String named = defIdOf(first, 1);
        assertThat(nameOf(named)).isEqualTo(OTHER_NAME);
        assertThat(afterFirst).as("C holds exactly the reused 90 copy and the new named one")
                .containsExactlyInAnyOrder(salon.copy90(), named);
        fixtures.assertDuplicateOf(repeat, salon.copy90());
        assertThat(activeDefIdsOf(masterC)).as("the 409 changed none of C's assignments")
                .containsExactlyInAnyOrder(salon.copy90(), named);
        assertThat(salonCopyDurations(salon.id())).containsExactlyInAnyOrder(60, 90, 90);
    }

    // ── AC10 ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC10: D bulk with the type at 120 twice, no name → 400, nothing created")
    void should_return400_when_salonBulkCarriesTheSameCopyTwice() throws Exception {
        Salon salon = oneCopySalon("ac10");
        UUID masterD = fixtures.createSalonMaster(salon.id());

        ResponseEntity<String> resp = bulk(salon, masterD, item(120, "900.00"), item(120, "950.00"));

        fixtures.assertInRequestDuplicate400(resp);
        assertThat(salonCopyDurations(salon.id())).containsExactly(60);
        assertThat(activeDefIdsOf(masterD)).isEmpty();
    }

    // ── D1a item-order pin ─────────────────────────────────────────────────────

    @Test
    @DisplayName("D1a: salon holds 60 only; B bulk 90 + 120 → 90 reuses the 60 copy (rule 2), 120 is a new copy (rule 4)")
    void should_countEarlierItems_when_resolvingLaterItemsOfTheSameType() throws Exception {
        Salon salon = oneCopySalon("d1a");
        UUID masterB = fixtures.createSalonMaster(salon.id());

        ResponseEntity<String> resp = bulk(salon, masterB, item(90, "700.00"), item(120, "900.00"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(defIdOf(resp, 0)).isEqualTo(salon.copy60());
        assertThat(fixtures.data(resp).path(0).path("durationOverrideMinutes").asInt()).isEqualTo(90);
        assertThat(defIdOf(resp, 1)).isNotEqualTo(salon.copy60());
        assertThat(salonCopyDurations(salon.id())).containsExactlyInAnyOrder(60, 120);
    }

    // ── a deactivated copy is never a reuse candidate ──────────────────────────

    @Test
    @DisplayName("Deactivated copy: the owner retires the salon's only 60 copy; B bulk at 60 → a NEW copy, the retired one stays retired and unassigned")
    void should_neverReuseDeactivatedCopy_when_itMatchesTheItemExactly() throws Exception {
        Salon salon = oneCopySalon("retired");
        ResponseEntity<String> retired = restTemplate.exchange("/api/v1/services/" + salon.copy60(), HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(salon.ownerToken())), String.class);
        UUID masterB = fixtures.createSalonMaster(salon.id());

        ResponseEntity<String> resp = bulk(salon, masterB, item(60, "500.00"));

        assertThat(retired.getStatusCode()).as("precondition, body: %s", retired.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        String fresh = defIdOf(resp, 0);
        assertThat(fresh).as("the inactive exact match must never be resolved (sd.isActive filter)")
                .isNotEqualTo(salon.copy60());
        assertThat(activeDefIdsOf(masterB)).containsExactly(fresh);
        assertThat(rowCountOf(masterB, salon.copy60())).isZero();
        assertThat(Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM service_definitions WHERE id = ?::uuid", Boolean.class, salon.copy60())))
                .as("the retired copy was not revived").isFalse();
        assertThat(salonCopyDurations(salon.id())).containsExactly(60);
    }

    // ── cross-salon scoping ────────────────────────────────────────────────────

    @Test
    @DisplayName("Scoping: salon X holds an exact 90 copy; salon Y's master bulk at 90 → Y gets its own copy, X's copy untouched")
    void should_neverReuseAnotherSalonsCopy_when_itMatchesTheItemExactly() throws Exception {
        Salon salonX = twoCopySalon("scope-x");
        Salon salonY = oneCopySalon("scope-y");

        ResponseEntity<String> resp = bulk(salonY, salonY.masterA(), item(90, "700.00"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        String yCopy90 = defIdOf(resp, 0);
        assertThat(yCopy90).as("salon X's exact 90 copy must never be resolved for salon Y").isNotEqualTo(salonX.copy90());
        assertThat(salonOwnerOf(yCopy90)).isEqualTo(salonY.id());
        assertThat(salonCopyDurations(salonY.id())).containsExactlyInAnyOrder(60, 90);
        assertThat(salonCopyDurations(salonX.id())).containsExactlyInAnyOrder(60, 90);
        assertThat(rowCountOf(salonY.masterA(), salonX.copy90())).isZero();
    }

    // ── reactivation picks the resolved copy (D3) ──────────────────────────────

    @Test
    @DisplayName("D3: A unassigns BOTH 60 and 90; bulk at 90 → only the 90 row is reactivated, the 60 row stays inactive")
    void should_reactivateOnlyTheResolvedCopy_when_masterHasSeveralInactiveCopiesOfTheType() throws Exception {
        Salon salon = twoCopySalon("d3");
        UUID row60 = assignmentIdOf(salon.masterA(), salon.copy60());
        UUID row90 = assignmentIdOf(salon.masterA(), salon.copy90());
        unassign(salon, salon.masterA(), salon.copy60());
        unassign(salon, salon.masterA(), salon.copy90());

        ResponseEntity<String> resp = bulk(salon, salon.masterA(), item(90, "700.00"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(fixtures.data(resp).path(0).path("id").asText()).isEqualTo(row90.toString());
        assertThat(defIdOf(resp, 0)).isEqualTo(salon.copy90());
        assertThat(activeDefIdsOf(salon.masterA())).containsExactly(salon.copy90());
        assertThat(isAssignmentActive(row60)).as("the 60 copy must not be revived by a 90 item").isFalse();
        assertThat(rowCountOf(salon.masterA(), salon.copy90())).isEqualTo(1L);
    }

    // ── role gate ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Role gate: a SALON_MASTER holding the 60 copy bulks a 90 copy for themselves → 403, no copy created")
    void should_return403_when_salonMasterBulksANewCopyForThemselves() throws Exception {
        Salon salon = newSalon("role");
        var self = fixtures.createSalonMasterWithRowAndGetToken(salon.id(), "m465-" + System.nanoTime() + "@beautica.test");
        String copy60 = defIdOf(bulk(salon, self.masterId(), item(60, "500.00")), 0);

        ResponseEntity<String> resp = bulk(new Salon(salon.id(), self.token(), null, null, null),
                self.masterId(), item(90, "700.00"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(salonCopyDurations(salon.id())).containsExactly(60);
        assertThat(activeDefIdsOf(self.masterId())).containsExactly(copy60);
    }

    // ── unnamed vs named items ─────────────────────────────────────────────────

    @Test
    @DisplayName("Naming: single default-name 60 copy; B bulk at 60 named «Масаж спини авторський» → a new named copy, never the default one")
    void should_createNamedCopy_when_namedItemDiffersFromTheSingleCopysName() throws Exception {
        Salon salon = oneCopySalon("named-new");
        UUID masterB = fixtures.createSalonMaster(salon.id());

        ResponseEntity<String> resp = bulk(salon, masterB, namedItem(60, "550.00", OTHER_NAME));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        String named = defIdOf(resp, 0);
        assertThat(named).isNotEqualTo(salon.copy60());
        assertThat(nameOf(named)).isEqualTo(OTHER_NAME);
        assertThat(salonCopyDurations(salon.id())).containsExactlyInAnyOrder(60, 60);
    }

    @Test
    @DisplayName("Naming: single 60 copy; B bulk at 90 named like the type (other case, edge spaces) → rule 2 reuses the copy with a 90 override")
    void should_reuseSingleCopy_when_namedItemMatchesItsNameCaseAndSpaceInsensitively() throws Exception {
        Salon salon = oneCopySalon("named-default");
        UUID masterB = fixtures.createSalonMaster(salon.id());
        String typeName = jdbcTemplate.queryForObject(
                "SELECT name_uk FROM service_types WHERE id = ?", String.class, typeId);

        ResponseEntity<String> resp = bulk(salon, masterB,
                namedItem(90, "700.00", "  " + typeName.toUpperCase(Locale.ROOT) + " "));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(defIdOf(resp, 0)).isEqualTo(salon.copy60());
        assertThat(fixtures.data(resp).path(0).path("durationOverrideMinutes").asInt()).isEqualTo(90);
        assertThat(salonCopyDurations(salon.id())).containsExactly(60);
    }

    @Test
    @DisplayName("Naming: salon holds default 60 + named 90; B bulk unnamed 90 → a new default-name 90, the named copy is not reused")
    void should_createDefaultCopy_when_unnamedItemMeetsOnlyANamedCopyAtItsDuration() throws Exception {
        Salon salon = oneCopySalon("unnamed");
        String named90 = defIdOf(bulk(salon, salon.masterA(), namedItem(90, "750.00", OTHER_NAME)), 0);
        UUID masterB = fixtures.createSalonMaster(salon.id());

        ResponseEntity<String> resp = bulk(salon, masterB, item(90, "700.00"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        String default90 = defIdOf(resp, 0);
        assertThat(default90).isNotIn(named90, salon.copy60());
        assertThat(nameOf(default90)).isEqualTo(nameOf(salon.copy60()));
        assertThat(salonCopyDurations(salon.id())).containsExactlyInAnyOrder(60, 90, 90);
    }

    @Test
    @DisplayName("Naming: salon holds default 60 + named 90; B bulk named 90 → rule 3 reuses the named copy, no third copy")
    void should_reuseNamedCopy_when_namedItemMatchesItExactly() throws Exception {
        Salon salon = oneCopySalon("named-reuse");
        String named90 = defIdOf(bulk(salon, salon.masterA(), namedItem(90, "750.00", OTHER_NAME)), 0);
        UUID masterB = fixtures.createSalonMaster(salon.id());

        ResponseEntity<String> resp = bulk(salon, masterB, namedItem(90, "800.00", OTHER_NAME));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(defIdOf(resp, 0)).isEqualTo(named90);
        assertThat(fixtures.data(resp).path(0).path("durationOverrideMinutes").isNull()).isTrue();
        assertThat(salonCopyDurations(salon.id())).containsExactlyInAnyOrder(60, 90);
    }

    // ── legacy INDEPENDENT_MASTER rows keep the per-type 409 ───────────────────

    @Test
    @DisplayName("Legacy: salon master holds a pre-302 INDEPENDENT_MASTER-owned 60 row; owner bulk at 90 → per-type 409 naming it, no salon copy")
    void should_keepPerType409_when_masterHoldsALegacyIndependentRowOfTheType() throws Exception {
        Salon salon = newSalon("legacy");
        UUID masterA = fixtures.createSalonMaster(salon.id());
        UUID legacyDefId = fixtures.insertActiveDefinitionWithoutAssignment(masterA, typeId);
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), masterA, legacyDefId);

        ResponseEntity<String> resp = bulk(salon, masterA, item(90, "700.00"));

        fixtures.assertDuplicateOf(resp, legacyDefId.toString());
        assertThat(salonCopyDurations(salon.id())).isEmpty();
    }

    @Test
    @DisplayName("Legacy + held salon copy: the per-type 409 names the SALON copy the master holds (deep-linkable), not the legacy row")
    void should_reportHeldSalonCopyId_when_masterHoldsBothALegacyRowAndASalonCopyOfTheType() throws Exception {
        Salon salon = newSalon("legacy-held");
        UUID masterA = fixtures.createSalonMaster(salon.id());
        String salonCopy60 = defIdOf(bulk(salon, masterA, item(60, "500.00")), 0);
        UUID legacyDefId = fixtures.insertActiveDefinitionWithoutAssignment(masterA, typeId);
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), masterA, legacyDefId);

        ResponseEntity<String> resp = bulk(salon, masterA, item(90, "700.00"));

        fixtures.assertDuplicateOf(resp, salonCopy60);
        assertThat(salonCopy60).isNotEqualTo(legacyDefId.toString());
        assertThat(salonCopyDurations(salon.id())).containsExactly(60);
    }

    // ── fixtures / helpers ─────────────────────────────────────────────────────

    /**
     * The AC7 sequence's outcome with every definition id replaced by its role label
     * ({@code copy60}/{@code copy90}), so runs against two different salons compare equal.
     */
    private record CopiesRun(List<Integer> statuses, String reusedCopy, Integer reuseDurationOverride,
            String duplicateCode, String duplicateCopy, List<Integer> copyDurations,
            List<String> masterACopies, List<String> masterBCopies) {
    }

    /** A creates 60 then 90, B re-adds 90 (reuse), A repeats 60 (409) — driven by {@code driver}'s token. */
    private CopiesRun runCopiesSequence(Salon driver) throws Exception {
        UUID masterA = fixtures.createSalonMaster(driver.id());
        UUID masterB = fixtures.createSalonMaster(driver.id());
        ResponseEntity<String> first = bulk(driver, masterA, item(60, "500.00"));
        ResponseEntity<String> second = bulk(driver, masterA, item(90, "700.00"));
        ResponseEntity<String> reuse = bulk(driver, masterB, item(90, "650.00"));
        ResponseEntity<String> repeat = bulk(driver, masterA, item(60, "500.00"));
        Map<String, String> labels = Map.of(
                defIdOf(first, 0), "copy60", defIdOf(second, 0), "copy90");
        Function<String, String> label = id -> labels.getOrDefault(id, "unknown");
        JsonNode reuseRow = fixtures.data(reuse).path(0);
        return new CopiesRun(
                List.of(first.getStatusCode().value(), second.getStatusCode().value(),
                        reuse.getStatusCode().value(), repeat.getStatusCode().value()),
                label.apply(reuseRow.path("serviceDefinition").path("id").asText()),
                reuseRow.path("durationOverrideMinutes").isNull()
                        ? null : reuseRow.path("durationOverrideMinutes").asInt(),
                fixtures.data(repeat).path("code").asText(),
                label.apply(fixtures.data(repeat).path("existingServiceDefId").asText()),
                salonCopyDurations(driver.id()).stream().sorted().toList(),
                activeDefIdsOf(masterA).stream().map(label).sorted().toList(),
                activeDefIdsOf(masterB).stream().map(label).sorted().toList());
    }

    /** A salon, its owner token, and (when seeded) master A holding the 60 and/or 90 copies. */
    private record Salon(UUID id, String ownerToken, UUID masterA, String copy60, String copy90) {
    }

    private Salon newSalon(String tag) throws Exception {
        String token = fixtures.createSalonOwnerAndGetToken("c465-" + tag + "-" + System.nanoTime() + "@beautica.test");
        return new Salon(fixtures.createSalon(token, "Copies " + tag), token, null, null, null);
    }

    private Salon oneCopySalon(String tag) throws Exception {
        Salon salon = newSalon(tag);
        UUID masterA = fixtures.createSalonMaster(salon.id());
        String copy60 = defIdOf(bulk(salon, masterA, item(60, "500.00")), 0);
        return new Salon(salon.id(), salon.ownerToken(), masterA, copy60, null);
    }

    private Salon twoCopySalon(String tag) throws Exception {
        Salon salon = oneCopySalon(tag);
        String copy90 = defIdOf(bulk(salon, salon.masterA(), item(90, "700.00")), 0);
        return new Salon(salon.id(), salon.ownerToken(), salon.masterA(), salon.copy60(), copy90);
    }

    private BulkServiceItemRequest item(int duration, String price) {
        return ServiceTestFixtures.bulkItem(typeId, duration, price);
    }

    private BulkServiceItemRequest namedItem(int duration, String price, String name) {
        return ServiceTestFixtures.namedBulkItem(typeId, duration, price, name);
    }

    private ResponseEntity<String> bulk(Salon salon, UUID masterId, BulkServiceItemRequest... items) {
        return fixtures.postSalonBulk(salon.ownerToken(), salon.id(), masterId, List.of(items));
    }

    private String defIdOf(ResponseEntity<String> resp, int index) throws Exception {
        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return fixtures.data(resp).path(index).path("serviceDefinition").path("id").asText();
    }

    private List<Integer> salonCopyDurations(UUID salonId) {
        return jdbcTemplate.queryForList(
                "SELECT base_duration_minutes FROM service_definitions "
                        + "WHERE owner_type = 'SALON' AND owner_id = ? AND service_type_id = ? AND is_active",
                Integer.class, salonId, typeId);
    }

    private List<String> activeDefIdsOf(UUID masterId) {
        return jdbcTemplate.queryForList(
                "SELECT service_def_id::text FROM master_services WHERE master_id = ? AND is_active",
                String.class, masterId);
    }

    private UUID assignmentIdOf(UUID masterId, String defId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? AND service_def_id = ?::uuid",
                UUID.class, masterId, defId);
    }

    private long rowCountOf(UUID masterId, String defId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ?::uuid",
                Long.class, masterId, defId);
        return count == null ? 0L : count;
    }

    private void unassign(Salon salon, UUID masterId, String defId) {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salon.id() + "/masters/" + masterId + "/services/" + defId,
                HttpMethod.DELETE, new HttpEntity<>(fixtures.bearerHeaders(salon.ownerToken())), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("unassign body: %s", resp.getBody()).isTrue();
    }

    private boolean isAssignmentActive(UUID assignmentId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM master_services WHERE id = ?", Boolean.class, assignmentId));
    }

    private String nameOf(String defId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM service_definitions WHERE id = ?::uuid", String.class, defId);
    }

    private UUID salonOwnerOf(String defId) {
        return jdbcTemplate.queryForObject(
                "SELECT owner_id FROM service_definitions WHERE id = ?::uuid AND owner_type = 'SALON'",
                UUID.class, defId);
    }

    private BigDecimal priceOverrideOf(UUID masterId, String defId) {
        return jdbcTemplate.queryForObject(
                "SELECT price_override FROM master_services WHERE master_id = ? AND service_def_id = ?::uuid",
                BigDecimal.class, masterId, defId);
    }
}
