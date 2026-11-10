package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.DuplicateServiceException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.entity.PriceType;
import com.beautica.service.service.ServiceCatalogService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Phase 464 — the duplicate-service rule after V233: one owner may hold several ACTIVE copies of a
 * service type as long as every pair differs in base duration OR in name (trimmed,
 * case-insensitive). The same type + same duration + same name is still 409 {@code DUPLICATE_SERVICE};
 * price never participates. Acceptance criteria 1–5, 7 and 8 of the phase doc, end to end against
 * real Postgres. Criterion 6 (V233 applies on a populated V232 schema) is covered by the full
 * migration chain every IT boots on plus {@code ServiceDefinitionUniqueActiveCopyTest}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Phase 464 — service copies: duplicate rule (type + duration + name)")
class ServiceCopiesDuplicateRuleIT extends AbstractIntegrationTest {

    private static final String CATEGORY = "NAIL_SERVICE";
    private static final String NAME = "Класичний масаж";
    private static final String NAME_CASE_SPACES = " класичний МАСАЖ ";
    private static final String OTHER_NAME = "Масаж спини авторський";
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long POLL_MILLIS = 100;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ServiceCatalogService serviceCatalogService;
    @Autowired private PlatformTransactionManager transactionManager;

    private ServiceTestFixtures fixtures;
    private UUID typeId;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        typeId = fixtures.resolveServiceTypeIdForCategory(CATEGORY);
    }

    // ── Acceptance 1 — independent master, single create ───────────────────────

    @Test
    @DisplayName("AC1: independent master — duration or name makes a new copy; same copy (case/space or price only) → 409 naming the first")
    void should_applyCopyMatrix_when_independentMasterCreatesSingleServices() throws Exception {
        String token = fixtures.createIndependentMasterAndGetToken("ac1-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> first = postIndependent(token, create(NAME, 60, "500.00"));
        ResponseEntity<String> longer = postIndependent(token, create(NAME, 90, "500.00"));
        ResponseEntity<String> caseSpaces = postIndependent(token, create(NAME_CASE_SPACES, 60, "500.00"));
        ResponseEntity<String> otherName = postIndependent(token, create(OTHER_NAME, 60, "500.00"));
        ResponseEntity<String> otherPrice = postIndependent(token, create(NAME, 60, "999.00"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(longer.getStatusCode()).as("same type, 90 хв").isEqualTo(HttpStatus.CREATED);
        assertDuplicateOf(caseSpaces, data(first).path("serviceDefinition").path("id").asText());
        assertThat(otherName.getStatusCode()).as("same type + 60 хв, another name").isEqualTo(HttpStatus.CREATED);
        assertDuplicateOf(otherPrice, data(first).path("serviceDefinition").path("id").asText());
    }

    @Test
    @DisplayName("audit: NBSP-padded name = the same copy → 409 naming the first; a padded new name is stored stripped")
    void should_return409AndStoreStrippedName_when_copyNameIsNbspPadded() throws Exception {
        String token = fixtures.createIndependentMasterAndGetToken("nbsp-" + System.nanoTime() + "@beautica.test");
        ResponseEntity<String> first = postIndependent(token, create(NAME, 60, "500.00"));

        ResponseEntity<String> nbspPadded = postIndependent(token, create("\u00A0" + NAME + "\u00A0", 60, "500.00"));
        ResponseEntity<String> paddedOther = postIndependent(token, create("\u00A0 " + OTHER_NAME + "\u00A0", 60, "500.00"));

        assertDuplicateOf(nbspPadded, data(first).path("serviceDefinition").path("id").asText());
        assertThat(paddedOther.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM service_definitions WHERE id = ?::uuid",
                String.class, data(paddedOther).path("serviceDefinition").path("id").asText()))
                .as("stored name carries no edge whitespace, so lower(btrim(name)) agrees with Java")
                .isEqualTo(OTHER_NAME);
    }

    // ── Acceptance 2 — the same matrix for a salon ─────────────────────────────

    @Test
    @DisplayName("AC2: salon — the same copy matrix holds on POST /salons/{id}/services")
    void should_applyCopyMatrix_when_salonCreatesSingleServices() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("ac2-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "AC2 Salon");

        ResponseEntity<String> first = postSalon(ownerToken, salonId, create(NAME, 60, "500.00"));
        ResponseEntity<String> longer = postSalon(ownerToken, salonId, create(NAME, 90, "500.00"));
        ResponseEntity<String> caseSpaces = postSalon(ownerToken, salonId, create(NAME_CASE_SPACES, 60, "500.00"));
        ResponseEntity<String> otherName = postSalon(ownerToken, salonId, create(OTHER_NAME, 60, "500.00"));
        ResponseEntity<String> otherPrice = postSalon(ownerToken, salonId, create(NAME, 60, "999.00"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(longer.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertDuplicateOf(caseSpaces, data(first).path("id").asText());
        assertThat(otherName.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertDuplicateOf(otherPrice, data(first).path("id").asText());
        assertThat(activeCount("SALON", salonId)).as("three distinct copies").isEqualTo(3L);
    }

    // ── Acceptance 3 — PATCH ───────────────────────────────────────────────────

    @Test
    @DisplayName("AC3: PATCH copy B onto copy A's duration + name → 409 naming A; PATCH B to its own values → 200")
    void should_rejectPatchOntoAnotherCopy_andAcceptNoOpPatch() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("ac3-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "AC3 Salon");
        String copyA = data(postSalon(ownerToken, salonId, create(NAME, 60, "500.00"))).path("id").asText();
        String copyB = data(postSalon(ownerToken, salonId, create(OTHER_NAME, 90, "500.00"))).path("id").asText();

        ResponseEntity<String> collide = patch(ownerToken, copyB, new UpdateServiceDefinitionRequest(
                NAME_CASE_SPACES, null, null, 60, null, null, null, null, null, null));
        ResponseEntity<String> noOp = patch(ownerToken, copyB, new UpdateServiceDefinitionRequest(
                OTHER_NAME, null, null, 90, null, null, null, null, null, typeId));

        assertDuplicateOf(collide, copyA);
        assertThat(noOp.getStatusCode()).as("excludeId: a row never collides with itself").isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT name, base_duration_minutes FROM service_definitions WHERE id = ?::uuid", copyB))
                .containsEntry("name", OTHER_NAME)
                .containsEntry("base_duration_minutes", 90);
    }

    @Test
    @DisplayName("AC3: PATCH duration only — onto copy A's duration (same name) → 409 naming A; onto a free duration → 200, persisted")
    void should_rejectDurationPatchOntoCopy_andAcceptDurationPatchOntoFreeCombo() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("ac3d-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "AC3d Salon");
        String copyA = data(postSalon(ownerToken, salonId, create(NAME, 60, "500.00"))).path("id").asText();
        String copyB = data(postSalon(ownerToken, salonId, create(NAME, 90, "500.00"))).path("id").asText();

        ResponseEntity<String> collide = patch(ownerToken, copyB, new UpdateServiceDefinitionRequest(
                null, null, null, 60, null, null, null, null, null, null));
        ResponseEntity<String> free = patch(ownerToken, copyB, new UpdateServiceDefinitionRequest(
                null, null, null, 120, null, null, null, null, null, null));

        assertDuplicateOf(collide, copyA);
        assertThat(free.getStatusCode()).as("body: %s", free.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT base_duration_minutes FROM service_definitions WHERE id = ?::uuid", Integer.class, copyB))
                .as("the free-combo PATCH persisted; the rejected one did not").isEqualTo(120);
    }

    @Test
    @DisplayName("AC3: PATCH name only — onto copy A's name (case/spaces, same duration) → 409 naming A; onto a free name → 200, persisted")
    void should_rejectNamePatchOntoCopy_andAcceptNamePatchOntoFreeName() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("ac3n-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "AC3n Salon");
        String copyA = data(postSalon(ownerToken, salonId, create(NAME, 60, "500.00"))).path("id").asText();
        String copyB = data(postSalon(ownerToken, salonId, create(OTHER_NAME, 60, "500.00"))).path("id").asText();
        String freeName = "Масаж обличчя вечірній";

        ResponseEntity<String> collide = patch(ownerToken, copyB, new UpdateServiceDefinitionRequest(
                NAME_CASE_SPACES, null, null, null, null, null, null, null, null, null));
        ResponseEntity<String> free = patch(ownerToken, copyB, new UpdateServiceDefinitionRequest(
                freeName, null, null, null, null, null, null, null, null, null));

        assertDuplicateOf(collide, copyA);
        assertThat(free.getStatusCode()).as("body: %s", free.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM service_definitions WHERE id = ?::uuid", String.class, copyB))
                .isEqualTo(freeName);
    }

    // ── Owner scoping — the key carries (owner_type, owner_id) ─────────────────

    @Test
    @DisplayName("scoping: salon B creates salon A's exact copy → 201; A's duplicate 409 names A's copy, never B's")
    void should_scopeDuplicateRuleToOwner_when_twoSalonsCreateTheSameCopy() throws Exception {
        String ownerA = fixtures.createSalonOwnerAndGetToken("scope-a-" + System.nanoTime() + "@beautica.test");
        String ownerB = fixtures.createSalonOwnerAndGetToken("scope-b-" + System.nanoTime() + "@beautica.test");
        UUID salonA = fixtures.createSalon(ownerA, "Scope Salon A");
        UUID salonB = fixtures.createSalon(ownerB, "Scope Salon B");
        String copyA = data(postSalon(ownerA, salonA, create(NAME, 60, "500.00"))).path("id").asText();

        ResponseEntity<String> copyOfB = postSalon(ownerB, salonB, create(NAME, 60, "500.00"));
        String copyB = data(copyOfB).path("id").asText();
        ResponseEntity<String> duplicateOfA = postSalon(ownerA, salonA, create(NAME, 60, "700.00"));

        assertThat(copyOfB.getStatusCode()).as("another owner's identical copy is no conflict")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(copyB).isNotEqualTo(copyA);
        assertDuplicateOf(duplicateOfA, copyA);
        assertThat(data(duplicateOfA).path("existingServiceDefId").asText())
                .as("the 409 must never leak another owner's id").isNotEqualTo(copyB);
    }

    @Test
    @DisplayName("scoping: independent master B creates master A's exact copy → 201; A's duplicate 409 names A's copy, never B's")
    void should_scopeDuplicateRuleToOwner_when_twoIndependentMastersCreateTheSameCopy() throws Exception {
        String emailA = "scope-ia-" + System.nanoTime() + "@beautica.test";
        String tokenA = fixtures.createIndependentMasterAndGetToken(emailA);
        String tokenB = fixtures.createIndependentMasterAndGetToken("scope-ib-" + System.nanoTime() + "@beautica.test");
        String copyA = data(postIndependent(tokenA, create(NAME, 60, "500.00")))
                .path("serviceDefinition").path("id").asText();

        ResponseEntity<String> copyOfB = postIndependent(tokenB, create(NAME, 60, "500.00"));
        String copyB = data(copyOfB).path("serviceDefinition").path("id").asText();
        ResponseEntity<String> duplicateOfA = postIndependent(tokenA, create(NAME, 60, "700.00"));

        assertThat(copyOfB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(copyB).isNotEqualTo(copyA);
        assertDuplicateOf(duplicateOfA, copyA);
        assertThat(data(duplicateOfA).path("existingServiceDefId").asText()).isNotEqualTo(copyB);
    }

    @Test
    @DisplayName("scoping: salon B PATCHes its copy onto salon A's exact (duration, name) → 200; the guard never sees A's row")
    void should_acceptPatch_when_targetCopyBelongsToAnotherOwner() throws Exception {
        String ownerA = fixtures.createSalonOwnerAndGetToken("scope-pa-" + System.nanoTime() + "@beautica.test");
        String ownerB = fixtures.createSalonOwnerAndGetToken("scope-pb-" + System.nanoTime() + "@beautica.test");
        UUID salonA = fixtures.createSalon(ownerA, "Scope Patch A");
        UUID salonB = fixtures.createSalon(ownerB, "Scope Patch B");
        postSalon(ownerA, salonA, create(NAME, 60, "500.00"));
        String copyB = data(postSalon(ownerB, salonB, create(OTHER_NAME, 90, "500.00"))).path("id").asText();

        ResponseEntity<String> resp = patch(ownerB, copyB, new UpdateServiceDefinitionRequest(
                NAME, null, null, 60, null, null, null, null, null, null));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT name, base_duration_minutes FROM service_definitions WHERE id = ?::uuid", copyB))
                .containsEntry("name", NAME)
                .containsEntry("base_duration_minutes", 60);
    }

    // ── Acceptance 4 — deactivate, then re-create ──────────────────────────────

    @Test
    @DisplayName("AC4: deactivating copy A frees its key — re-creating the same copy → 201")
    void should_allowRecreation_when_copyWasDeactivated() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("ac4-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "AC4 Salon");
        String copyA = data(postSalon(ownerToken, salonId, create(NAME, 60, "500.00"))).path("id").asText();

        ResponseEntity<Void> deleted = restTemplate.exchange("/api/v1/services/" + copyA, HttpMethod.DELETE,
                new HttpEntity<>(null, fixtures.bearerHeaders(ownerToken)), Void.class);
        ResponseEntity<String> recreated = postSalon(ownerToken, salonId, create(NAME, 60, "650.00"));

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(recreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(recreated).path("id").asText()).isNotEqualTo(copyA);
    }

    // ── Acceptance 5 — race ────────────────────────────────────────────────────

    /**
     * Deterministic interleaving (pattern of {@code PendingRequestConcurrentSubmitIT}): create #1 runs
     * in a held-open transaction, so create #2's pre-check cannot see it and passes; #2's INSERT then
     * blocks on the V233 index until #1 commits and fails there. The flush-time translation by
     * constraint NAME must turn that into the 409 — never a 500. Reverting
     * {@code DUPLICATE_SERVICE_INDEX} to V121's name makes this red.
     */
    @Test
    @DisplayName("AC5: two parallel creates of the same copy → one 201, one 409 DUPLICATE_SERVICE (race translated, never 500)")
    void should_translateIndexRace_when_twoIdenticalCopiesAreCreatedConcurrently() throws Exception {
        String email = "ac5-" + System.nanoTime() + "@beautica.test";
        fixtures.createIndependentMasterAndGetToken(email);
        UUID userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        CreateServiceDefinitionRequest request = create(NAME, 60, "500.00");

        Throwable loser = raceTwoCreates(() -> serviceCatalogService.addIndependentMasterService(userId, request));

        assertThat(loser)
                .as("the loser reaches the index at flush; the constraint-name translation makes it the 409")
                .isInstanceOf(DuplicateServiceException.class);
        assertThat(((DuplicateServiceException) loser).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(activeCount("INDEPENDENT_MASTER", masterId)).as("exactly one copy committed").isEqualTo(1L);
    }

    // ── Acceptance 7 — several copies of one type in one bulk request ──────────

    @Test
    @DisplayName("AC7: bulk 60 + 90 of one type → 201, two copies")
    void should_createTwoCopies_when_bulkCarriesOneTypeAtTwoDurations() throws Exception {
        String email = "ac7a-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        ResponseEntity<String> resp = postBulk(token, List.of(item(60, "350.00"), item(90, "500.00")));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForList(
                "SELECT base_duration_minutes FROM service_definitions WHERE owner_id = ? AND is_active "
                        + "ORDER BY base_duration_minutes", Integer.class, masterId))
                .containsExactly(60, 90);
    }

    @Test
    @DisplayName("AC7: bulk 60 + 60 of one type (same effective name) → 400, nothing inserted")
    void should_return400AndInsertNothing_when_bulkCarriesTheSameCopyTwice() throws Exception {
        String email = "ac7b-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        ResponseEntity<String> resp = postBulk(token, List.of(item(60, "350.00"), item(60, "420.00")));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fixtures.countServiceDefinitionsForMaster(masterId)).as("zero inserted").isZero();
    }

    @Test
    @DisplayName("AC7: bulk 60 unnamed + 60 named « класичний МАСАЖ » (= the type name up to case/spaces) → 400, nothing inserted")
    void should_return400AndInsertNothing_when_namedBulkItemEqualsTheDefaultName() throws Exception {
        String email = "ac7c-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        String typeName = jdbcTemplate.queryForObject(
                "SELECT name_uk FROM service_types WHERE id = ?", String.class, typeId);
        String caseSpaces = "  " + typeName.toUpperCase(java.util.Locale.ROOT) + " ";

        ResponseEntity<String> resp = postBulk(token, List.of(
                item(60, "350.00"), namedItem(60, "420.00", caseSpaces)));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fixtures.countServiceDefinitionsForMaster(masterId)).as("zero inserted").isZero();
    }

    @Test
    @DisplayName("AC7: bulk 60 unnamed + 60 named «Масаж спини авторський» → 201, two copies with their names")
    void should_createTwoCopies_when_bulkItemsDifferOnlyInName() throws Exception {
        String email = "ac7d-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        String typeName = jdbcTemplate.queryForObject(
                "SELECT name_uk FROM service_types WHERE id = ?", String.class, typeId);

        ResponseEntity<String> resp = postBulk(token, List.of(
                item(60, "350.00"), namedItem(60, "420.00", OTHER_NAME)));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForList(
                "SELECT name FROM service_definitions WHERE owner_id = ? AND is_active", String.class, masterId))
                .containsExactlyInAnyOrder(typeName, OTHER_NAME);
    }

    @Test
    @DisplayName("phase 465 lifted the salon per-type 400: one type at two durations in one SALON bulk → 201, two copies")
    void should_return201_when_salonBulkCarriesOneTypeAtTwoDurations() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("salon465-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Copies Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);

        ResponseEntity<String> resp = postSalonBulk(ownerToken, salonId, masterId,
                List.of(item(60, "350.00"), item(90, "450.00")));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(activeCount("SALON", salonId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("phase 465: a SALON bulk with an exact in-request pair (unnamed 120 twice) → 400, nothing written")
    void should_return400_when_salonBulkCarriesAnExactPair() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("salon465x-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Exact Pair Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);

        ResponseEntity<String> resp = postSalonBulk(ownerToken, salonId, masterId,
                List.of(item(120, "350.00"), item(120, "450.00")));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activeCount("SALON", salonId)).isZero();
    }

    private ResponseEntity<String> postSalonBulk(String token, UUID salonId, UUID masterId,
            List<BulkServiceItemRequest> items) {
        return restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(items), fixtures.bearerHeaders(token)), String.class);
    }

    // ── Acceptance 8 — bulk against a held copy ────────────────────────────────

    @Test
    @DisplayName("AC8: bulk 90 + 120 of a type held at 90 → 409 naming the held copy, nothing inserted")
    void should_return409AndRollBack_when_bulkRepeatsAHeldCopy() throws Exception {
        String email = "ac8-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        ResponseEntity<String> held = postBulk(token, List.of(item(90, "500.00")));
        String heldId = data(held).path(0).path("serviceDefinition").path("id").asText();

        ResponseEntity<String> resp = postBulk(token, List.of(item(90, "450.00"), item(120, "700.00")));

        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertDuplicateOf(resp, heldId);
        assertThat(fixtures.countServiceDefinitionsForMaster(masterId))
                .as("the 120 copy rolled back with the batch").isEqualTo(1L);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private CreateServiceDefinitionRequest create(String name, int duration, String price) {
        return new CreateServiceDefinitionRequest(name, null, CATEGORY, duration, 0,
                PriceType.FIXED, new BigDecimal(price), null, null, typeId);
    }

    private BulkServiceItemRequest item(int duration, String price) {
        return new BulkServiceItemRequest(typeId, duration, PriceType.FIXED, new BigDecimal(price), null, null);
    }

    private BulkServiceItemRequest namedItem(int duration, String price, String name) {
        return new BulkServiceItemRequest(typeId, duration, PriceType.FIXED, new BigDecimal(price), null, null, name);
    }

    private ResponseEntity<String> postIndependent(String token, CreateServiceDefinitionRequest request) {
        return restTemplate.exchange("/api/v1/independent-masters/me/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> postSalon(String token, UUID salonId, CreateServiceDefinitionRequest request) {
        return restTemplate.exchange("/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> postBulk(String token, List<BulkServiceItemRequest> items) {
        return restTemplate.exchange("/api/v1/independent-masters/me/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(items), fixtures.bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> patch(String token, String serviceDefId, UpdateServiceDefinitionRequest request) {
        return restTemplate.exchange("/api/v1/services/" + serviceDefId, HttpMethod.PATCH,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }

    private JsonNode data(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private void assertDuplicateOf(ResponseEntity<String> resp, String existingId) throws Exception {
        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = data(resp);
        assertThat(body.path("code").asText()).isEqualTo("DUPLICATE_SERVICE");
        assertThat(body.path("existingServiceDefId").asText()).isEqualTo(existingId);
    }

    private long activeCount(String ownerType, UUID ownerId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE owner_type = ? AND owner_id = ? AND is_active",
                Long.class, ownerType, ownerId);
        return count == null ? 0L : count;
    }

    private Throwable raceTwoCreates(Runnable create) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            create.run();
            firstInserted.countDown();
            awaitQuietly(releaseFirst);
        }));
        assertThat(firstInserted.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("create #1 inserted").isTrue();

        CompletableFuture<Void> second = CompletableFuture.runAsync(create);
        awaitBlockedOrDone(second);
        releaseFirst.countDown();

        first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        try {
            second.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            return ex.getCause();
        }
        return fail("create #2 succeeded — a second identical copy was committed");
    }

    private void awaitBlockedOrDone(CompletableFuture<Void> second) throws Exception {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            try {
                second.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                return;
            } catch (ExecutionException done) {
                return;
            } catch (TimeoutException stillRunning) {
                Integer waiting = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                                + "AND lower(query) LIKE 'insert into service_definitions%'", Integer.class);
                if (waiting != null && waiting > 0) {
                    return;
                }
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("create #1 was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
