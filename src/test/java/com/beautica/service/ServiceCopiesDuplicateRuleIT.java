package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
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
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private static final String ASCII_NAME = "SPA ритуал";
    /** {@link #ASCII_NAME} with full-width Latin letters (U+FF33 U+FF30 U+FF21) — NFKC folds them back. */
    private static final String FULL_WIDTH_NAME = "\uFF33\uFF30\uFF21 ритуал";
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long POLL_MILLIS = 100;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ServiceCatalogService serviceCatalogService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private com.beautica.service.repository.ServiceRepository serviceRepository;
    @Autowired private DataSource dataSource;

    private ServiceTestFixtures fixtures;
    private UUID typeId;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        typeId = fixtures.resolveServiceTypeIdForCategory(CATEGORY);
    }

    // ── Acceptance 1 — independent master, single create (one behaviour per test) ──

    @Test
    @DisplayName("AC1: independent master — the same type + name at another duration is a new copy → 201, two rows")
    void should_createSecondCopy_when_independentMasterAddsTheTypeAtAnotherDuration() throws Exception {
        IndependentMaster master = independentMaster("ac1-dur");
        postIndependent(master.token(), create(NAME, 60, "500.00"));

        ResponseEntity<String> longer = postIndependent(master.token(), create(NAME, 90, "500.00"));

        assertThat(longer.getStatusCode()).as("body: %s", longer.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(activeCount("INDEPENDENT_MASTER", master.masterId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("AC1: independent master — the same copy in another case with edge spaces → 409 naming the first, nothing written")
    void should_return409AndWriteNothing_when_independentNameDiffersOnlyInCaseAndEdgeSpaces() throws Exception {
        IndependentMaster master = independentMaster("ac1-case");
        String first = independentDefId(postIndependent(master.token(), create(NAME, 60, "500.00")));

        ResponseEntity<String> caseSpaces = postIndependent(master.token(), create(NAME_CASE_SPACES, 60, "500.00"));

        fixtures.assertDuplicateOf(caseSpaces, first);
        assertThat(activeCount("INDEPENDENT_MASTER", master.masterId())).as("the 409 wrote nothing").isEqualTo(1L);
    }

    @Test
    @DisplayName("AC1: independent master — the same type + duration under another name is a new copy → 201, two rows")
    void should_createSecondCopy_when_independentMasterAddsTheTypeUnderAnotherName() throws Exception {
        IndependentMaster master = independentMaster("ac1-name");
        postIndependent(master.token(), create(NAME, 60, "500.00"));

        ResponseEntity<String> otherName = postIndependent(master.token(), create(OTHER_NAME, 60, "500.00"));

        assertThat(otherName.getStatusCode()).as("body: %s", otherName.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(activeCount("INDEPENDENT_MASTER", master.masterId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("AC1: independent master — only the price differs → 409 naming the first (price never participates), nothing written")
    void should_return409AndWriteNothing_when_independentCopyDiffersOnlyInPrice() throws Exception {
        IndependentMaster master = independentMaster("ac1-price");
        String first = independentDefId(postIndependent(master.token(), create(NAME, 60, "500.00")));

        ResponseEntity<String> otherPrice = postIndependent(master.token(), create(NAME, 60, "999.00"));

        fixtures.assertDuplicateOf(otherPrice, first);
        assertThat(activeCount("INDEPENDENT_MASTER", master.masterId())).as("the 409 wrote nothing").isEqualTo(1L);
    }

    @Test
    @DisplayName("audit: NBSP-padded name = the same copy → 409 naming the first; a padded new name is stored stripped")
    void should_return409AndStoreStrippedName_when_copyNameIsNbspPadded() throws Exception {
        String token = fixtures.createIndependentMasterAndGetToken("nbsp-" + System.nanoTime() + "@beautica.test");
        ResponseEntity<String> first = postIndependent(token, create(NAME, 60, "500.00"));

        ResponseEntity<String> nbspPadded = postIndependent(token, create("\u00A0" + NAME + "\u00A0", 60, "500.00"));
        ResponseEntity<String> paddedOther = postIndependent(token, create("\u00A0 " + OTHER_NAME + "\u00A0", 60, "500.00"));

        fixtures.assertDuplicateOf(nbspPadded, independentDefId(first));
        assertThat(paddedOther.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM service_definitions WHERE id = ?::uuid",
                String.class, independentDefId(paddedOther)))
                .as("stored name carries no edge whitespace, so lower(btrim(name)) agrees with Java")
                .isEqualTo(OTHER_NAME);
    }

    // ── name canonicalisation end to end (audit item 6) ─────────────────────────

    @Test
    @DisplayName("canonical name: an internal double space folds to one → the same copy, 409 naming the first, nothing written")
    void should_return409_when_nameDiffersOnlyByAnInternalDoubleSpace() throws Exception {
        IndependentMaster master = independentMaster("dbl-space");
        String first = independentDefId(postIndependent(master.token(), create(NAME, 60, "500.00")));

        ResponseEntity<String> doubleSpaced = postIndependent(master.token(),
                create(NAME.replace(" ", "  "), 60, "500.00"));

        fixtures.assertDuplicateOf(doubleSpaced, first);
        assertThat(activeCount("INDEPENDENT_MASTER", master.masterId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("canonical name: full-width «ＳＰＡ» folds to «SPA» under NFKC → the same copy, 409 naming the first, nothing written")
    void should_return409_when_nameDiffersOnlyByNfkcFullWidthLetters() throws Exception {
        IndependentMaster master = independentMaster("nfkc");
        String first = independentDefId(postIndependent(master.token(), create(ASCII_NAME, 60, "500.00")));

        ResponseEntity<String> fullWidth = postIndependent(master.token(), create(FULL_WIDTH_NAME, 60, "500.00"));

        fixtures.assertDuplicateOf(fullWidth, first);
        assertThat(activeCount("INDEPENDENT_MASTER", master.masterId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("canonical name: a named bulk item padded with NBSP matches the held copy → 409 naming it, nothing written")
    void should_return409_when_namedBulkItemIsNbspPaddedHeldName() throws Exception {
        IndependentMaster master = independentMaster("bulk-nbsp-held");
        String held = independentDefId(postIndependent(master.token(), create(OTHER_NAME, 60, "500.00")));

        ResponseEntity<String> resp = fixtures.postIndependentBulk(master.token(),
                List.of(namedItem(60, "420.00", "\u00A0" + OTHER_NAME + "\u00A0")));

        fixtures.assertDuplicateOf(resp, held);
        assertThat(activeCount("INDEPENDENT_MASTER", master.masterId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("canonical name: two named bulk items equal up to NBSP padding → in-request 400, nothing written")
    void should_return400_when_twoNamedBulkItemsDifferOnlyByNbspPadding() throws Exception {
        IndependentMaster master = independentMaster("bulk-nbsp-pair");

        ResponseEntity<String> resp = fixtures.postIndependentBulk(master.token(), List.of(
                namedItem(60, "420.00", OTHER_NAME), namedItem(60, "450.00", "\u00A0" + OTHER_NAME + "\u00A0")));

        fixtures.assertInRequestDuplicate400(resp);
        assertThat(activeCount("INDEPENDENT_MASTER", master.masterId())).isZero();
    }

    @Test
    @DisplayName("canonical name: a new named bulk item padded with NBSP is stored stripped → 201")
    void should_storeStrippedName_when_newNamedBulkItemIsNbspPadded() throws Exception {
        IndependentMaster master = independentMaster("bulk-nbsp-new");

        ResponseEntity<String> resp = fixtures.postIndependentBulk(master.token(),
                List.of(namedItem(60, "420.00", "\u00A0" + OTHER_NAME + "\u00A0")));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForList(
                "SELECT name FROM service_definitions WHERE owner_id = ? AND is_active", String.class,
                master.masterId())).containsExactly(OTHER_NAME);
    }

    // ── Acceptance 2 — the same matrix for a salon (one behaviour per test) ────

    @Test
    @DisplayName("AC2: salon — the same type + name at another duration is a new copy → 201, two rows")
    void should_createSecondCopy_when_salonAddsTheTypeAtAnotherDuration() throws Exception {
        SalonOwner salon = salonOwner("ac2-dur");
        postSalon(salon.token(), salon.salonId(), create(NAME, 60, "500.00"));

        ResponseEntity<String> longer = postSalon(salon.token(), salon.salonId(), create(NAME, 90, "500.00"));

        assertThat(longer.getStatusCode()).as("body: %s", longer.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(activeCount("SALON", salon.salonId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("AC2: salon — the same copy in another case with edge spaces → 409 naming the first, nothing written")
    void should_return409AndWriteNothing_when_salonNameDiffersOnlyInCaseAndEdgeSpaces() throws Exception {
        SalonOwner salon = salonOwner("ac2-case");
        String first = salonDefId(postSalon(salon.token(), salon.salonId(), create(NAME, 60, "500.00")));

        ResponseEntity<String> caseSpaces = postSalon(salon.token(), salon.salonId(),
                create(NAME_CASE_SPACES, 60, "500.00"));

        fixtures.assertDuplicateOf(caseSpaces, first);
        assertThat(activeCount("SALON", salon.salonId())).as("the 409 wrote nothing").isEqualTo(1L);
    }

    @Test
    @DisplayName("AC2: salon — the same type + duration under another name is a new copy → 201, two rows")
    void should_createSecondCopy_when_salonAddsTheTypeUnderAnotherName() throws Exception {
        SalonOwner salon = salonOwner("ac2-name");
        postSalon(salon.token(), salon.salonId(), create(NAME, 60, "500.00"));

        ResponseEntity<String> otherName = postSalon(salon.token(), salon.salonId(), create(OTHER_NAME, 60, "500.00"));

        assertThat(otherName.getStatusCode()).as("body: %s", otherName.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(activeCount("SALON", salon.salonId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("AC2: salon — only the price differs → 409 naming the first, nothing written")
    void should_return409AndWriteNothing_when_salonCopyDiffersOnlyInPrice() throws Exception {
        SalonOwner salon = salonOwner("ac2-price");
        String first = salonDefId(postSalon(salon.token(), salon.salonId(), create(NAME, 60, "500.00")));

        ResponseEntity<String> otherPrice = postSalon(salon.token(), salon.salonId(), create(NAME, 60, "999.00"));

        fixtures.assertDuplicateOf(otherPrice, first);
        assertThat(activeCount("SALON", salon.salonId())).as("the 409 wrote nothing").isEqualTo(1L);
    }

    // ── Acceptance 3 — PATCH ───────────────────────────────────────────────────

    @Test
    @DisplayName("AC3: PATCH copy B onto copy A's duration + name → 409 naming A; PATCH B to its own values → 200")
    void should_rejectPatchOntoAnotherCopy_andAcceptNoOpPatch() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("ac3-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "AC3 Salon");
        String copyA = fixtures.data(postSalon(ownerToken, salonId, create(NAME, 60, "500.00"))).path("id").asText();
        String copyB = fixtures.data(postSalon(ownerToken, salonId, create(OTHER_NAME, 90, "500.00"))).path("id").asText();

        ResponseEntity<String> collide = patch(ownerToken, copyB, new UpdateServiceDefinitionRequest(
                NAME_CASE_SPACES, null, null, 60, null, null, null, null, null, null));
        ResponseEntity<String> noOp = patch(ownerToken, copyB, new UpdateServiceDefinitionRequest(
                OTHER_NAME, null, null, 90, null, null, null, null, null, typeId));

        fixtures.assertDuplicateOf(collide, copyA);
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
        String copyA = fixtures.data(postSalon(ownerToken, salonId, create(NAME, 60, "500.00"))).path("id").asText();
        String copyB = fixtures.data(postSalon(ownerToken, salonId, create(NAME, 90, "500.00"))).path("id").asText();

        ResponseEntity<String> collide = patch(ownerToken, copyB, new UpdateServiceDefinitionRequest(
                null, null, null, 60, null, null, null, null, null, null));
        ResponseEntity<String> free = patch(ownerToken, copyB, new UpdateServiceDefinitionRequest(
                null, null, null, 120, null, null, null, null, null, null));

        fixtures.assertDuplicateOf(collide, copyA);
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
        String copyA = fixtures.data(postSalon(ownerToken, salonId, create(NAME, 60, "500.00"))).path("id").asText();
        String copyB = fixtures.data(postSalon(ownerToken, salonId, create(OTHER_NAME, 60, "500.00"))).path("id").asText();
        String freeName = "Масаж обличчя вечірній";

        ResponseEntity<String> collide = patch(ownerToken, copyB, new UpdateServiceDefinitionRequest(
                NAME_CASE_SPACES, null, null, null, null, null, null, null, null, null));
        ResponseEntity<String> free = patch(ownerToken, copyB, new UpdateServiceDefinitionRequest(
                freeName, null, null, null, null, null, null, null, null, null));

        fixtures.assertDuplicateOf(collide, copyA);
        assertThat(free.getStatusCode()).as("body: %s", free.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM service_definitions WHERE id = ?::uuid", String.class, copyB))
                .isEqualTo(freeName);
    }

    @Test
    @DisplayName("AC3: independent master PATCHes copy B onto copy A's duration + name → 409 naming A, B unchanged")
    void should_rejectPatchOntoAnotherCopy_when_independentMasterOwnsBoth() throws Exception {
        IndependentMaster master = independentMaster("ac3-indep");
        String copyA = independentDefId(postIndependent(master.token(), create(NAME, 60, "500.00")));
        String copyB = independentDefId(postIndependent(master.token(), create(OTHER_NAME, 90, "500.00")));

        ResponseEntity<String> collide = patch(master.token(), copyB, new UpdateServiceDefinitionRequest(
                NAME_CASE_SPACES, null, null, 60, null, null, null, null, null, null));

        fixtures.assertDuplicateOf(collide, copyA);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT name, base_duration_minutes FROM service_definitions WHERE id = ?::uuid", copyB))
                .containsEntry("name", OTHER_NAME)
                .containsEntry("base_duration_minutes", 90);
    }

    @Test
    @DisplayName("AC3: PATCH serviceTypeId moves copy B (type T2) onto copy A's key (type T1, same duration + name) → 409 naming A, B keeps T2")
    void should_rejectPatch_when_serviceTypeChangeLandsOnAnotherCopy() throws Exception {
        SalonOwner salon = salonOwner("ac3-type");
        UUID secondTypeId = secondTypeInCategory();
        String copyA = salonDefId(postSalon(salon.token(), salon.salonId(), create(NAME, 60, "500.00")));
        String copyB = salonDefId(postSalon(salon.token(), salon.salonId(),
                createOfType(NAME, 60, "500.00", secondTypeId)));

        ResponseEntity<String> collide = patch(salon.token(), copyB, new UpdateServiceDefinitionRequest(
                null, null, null, null, null, null, null, null, null, typeId));

        fixtures.assertDuplicateOf(collide, copyA);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT service_type_id FROM service_definitions WHERE id = ?::uuid", UUID.class, copyB))
                .as("the rejected type change did not persist").isEqualTo(secondTypeId);
    }

    // ── Owner scoping — the key carries (owner_type, owner_id) ─────────────────

    @Test
    @DisplayName("scoping: salon B creates salon A's exact copy → 201; A's duplicate 409 names A's copy, never B's")
    void should_scopeDuplicateRuleToOwner_when_twoSalonsCreateTheSameCopy() throws Exception {
        String ownerA = fixtures.createSalonOwnerAndGetToken("scope-a-" + System.nanoTime() + "@beautica.test");
        String ownerB = fixtures.createSalonOwnerAndGetToken("scope-b-" + System.nanoTime() + "@beautica.test");
        UUID salonA = fixtures.createSalon(ownerA, "Scope Salon A");
        UUID salonB = fixtures.createSalon(ownerB, "Scope Salon B");
        String copyA = fixtures.data(postSalon(ownerA, salonA, create(NAME, 60, "500.00"))).path("id").asText();

        ResponseEntity<String> copyOfB = postSalon(ownerB, salonB, create(NAME, 60, "500.00"));
        String copyB = fixtures.data(copyOfB).path("id").asText();
        ResponseEntity<String> duplicateOfA = postSalon(ownerA, salonA, create(NAME, 60, "700.00"));

        assertThat(copyOfB.getStatusCode()).as("another owner's identical copy is no conflict")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(copyB).isNotEqualTo(copyA);
        fixtures.assertDuplicateOf(duplicateOfA, copyA);
        assertThat(fixtures.data(duplicateOfA).path("existingServiceDefId").asText())
                .as("the 409 must never leak another owner's id").isNotEqualTo(copyB);
    }

    @Test
    @DisplayName("scoping: independent master B creates master A's exact copy → 201; A's duplicate 409 names A's copy, never B's")
    void should_scopeDuplicateRuleToOwner_when_twoIndependentMastersCreateTheSameCopy() throws Exception {
        String emailA = "scope-ia-" + System.nanoTime() + "@beautica.test";
        String tokenA = fixtures.createIndependentMasterAndGetToken(emailA);
        String tokenB = fixtures.createIndependentMasterAndGetToken("scope-ib-" + System.nanoTime() + "@beautica.test");
        String copyA = fixtures.data(postIndependent(tokenA, create(NAME, 60, "500.00")))
                .path("serviceDefinition").path("id").asText();

        ResponseEntity<String> copyOfB = postIndependent(tokenB, create(NAME, 60, "500.00"));
        String copyB = fixtures.data(copyOfB).path("serviceDefinition").path("id").asText();
        ResponseEntity<String> duplicateOfA = postIndependent(tokenA, create(NAME, 60, "700.00"));

        assertThat(copyOfB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(copyB).isNotEqualTo(copyA);
        fixtures.assertDuplicateOf(duplicateOfA, copyA);
        assertThat(fixtures.data(duplicateOfA).path("existingServiceDefId").asText()).isNotEqualTo(copyB);
    }

    @Test
    @DisplayName("scoping: salon B PATCHes its copy onto salon A's exact (duration, name) → 200; the guard never sees A's row")
    void should_acceptPatch_when_targetCopyBelongsToAnotherOwner() throws Exception {
        String ownerA = fixtures.createSalonOwnerAndGetToken("scope-pa-" + System.nanoTime() + "@beautica.test");
        String ownerB = fixtures.createSalonOwnerAndGetToken("scope-pb-" + System.nanoTime() + "@beautica.test");
        UUID salonA = fixtures.createSalon(ownerA, "Scope Patch A");
        UUID salonB = fixtures.createSalon(ownerB, "Scope Patch B");
        postSalon(ownerA, salonA, create(NAME, 60, "500.00"));
        String copyB = fixtures.data(postSalon(ownerB, salonB, create(OTHER_NAME, 90, "500.00"))).path("id").asText();

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
        String copyA = fixtures.data(postSalon(ownerToken, salonId, create(NAME, 60, "500.00"))).path("id").asText();

        ResponseEntity<Void> deleted = restTemplate.exchange("/api/v1/services/" + copyA, HttpMethod.DELETE,
                new HttpEntity<>(null, fixtures.bearerHeaders(ownerToken)), Void.class);
        ResponseEntity<String> recreated = postSalon(ownerToken, salonId, create(NAME, 60, "650.00"));

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(recreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fixtures.data(recreated).path("id").asText()).isNotEqualTo(copyA);
    }

    // ── Acceptance 5 — races (the V233 index decides; translated, never a 500) ──

    /**
     * Deterministic interleaving (pattern of {@code PendingRequestConcurrentSubmitIT}): write #1 runs
     * in a held-open transaction, so write #2's pre-check cannot see it and passes; #2's statement then
     * blocks on the V233 index until #1 commits and fails there. The flush-time translation by
     * constraint NAME must turn that into the 409 — never a 500. Reverting
     * {@code DUPLICATE_SERVICE_INDEX} to V121's name makes these red.
     */
    @Test
    @DisplayName("AC5: two parallel independent creates of the same copy → one commits, the loser is 409 DUPLICATE_SERVICE naming it")
    void should_translateIndexRace_when_twoIdenticalCopiesAreCreatedConcurrently() throws Exception {
        String email = "ac5-" + System.nanoTime() + "@beautica.test";
        fixtures.createIndependentMasterAndGetToken(email);
        UUID userId = userIdOf(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        CreateServiceDefinitionRequest request = create(NAME, 60, "500.00");
        Runnable add = () -> serviceCatalogService.addIndependentMasterService(userId, request);

        Throwable loser = raceTwo(add, add);

        assertThat(activeCount("INDEPENDENT_MASTER", masterId)).as("exactly one copy committed").isEqualTo(1L);
        assertRaceLoserNames(loser, NAME, activeIdsOf("INDEPENDENT_MASTER", masterId).get(0));
    }

    @Test
    @DisplayName("AC5: two parallel SALON creates of the same copy → one commits, the loser is 409 DUPLICATE_SERVICE naming it")
    void should_translateIndexRace_when_twoIdenticalSalonCopiesAreCreatedConcurrently() throws Exception {
        SalonOwner salon = salonOwner("race-salon");
        CreateServiceDefinitionRequest request = create(NAME, 60, "500.00");
        Runnable add = () -> serviceCatalogService.addServiceToSalon(salon.salonId(), request);

        Throwable loser = raceTwo(add, add);

        assertThat(activeCount("SALON", salon.salonId())).as("exactly one copy committed").isEqualTo(1L);
        assertRaceLoserNames(loser, NAME, activeIdsOf("SALON", salon.salonId()).get(0));
    }

    @Test
    @DisplayName("AC5: two parallel PATCHes moving copies B and C onto one key → B wins; C's 409 names B and C is unchanged")
    void should_translateIndexRace_when_twoCopiesArePatchedOntoTheSameKeyConcurrently() throws Exception {
        SalonOwner salon = salonOwner("race-patch");
        UUID copyB = UUID.fromString(salonDefId(postSalon(salon.token(), salon.salonId(), create(OTHER_NAME, 90, "500.00"))));
        UUID copyC = UUID.fromString(salonDefId(postSalon(salon.token(), salon.salonId(), create(OTHER_NAME, 120, "500.00"))));
        UpdateServiceDefinitionRequest ontoKey = new UpdateServiceDefinitionRequest(
                NAME, null, null, 60, null, null, null, null, null, null);

        Throwable loser = raceTwo(
                asOwner(salon, () -> serviceCatalogService.updateServiceDefinition(salon.userId(), copyB, ontoKey)),
                asOwner(salon, () -> serviceCatalogService.updateServiceDefinition(salon.userId(), copyC, ontoKey)));

        assertRaceLoserNames(loser, NAME, copyB);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT name, base_duration_minutes FROM service_definitions WHERE id = ?", copyC))
                .as("the losing PATCH rolled back").containsEntry("name", OTHER_NAME)
                .containsEntry("base_duration_minutes", 120);
    }

    @Test
    @DisplayName("AC5: two parallel SALON bulks creating the same new copy for two masters → never a 500, never a second copy; the loser 409s or reuses")
    void should_neverDuplicateCopy_when_twoSalonBulksCreateTheSameCopyConcurrently() throws Exception {
        SalonOwner salon = salonOwner("race-bulk");
        UUID master1 = fixtures.createSalonMaster(salon.salonId());
        UUID master2 = fixtures.createSalonMaster(salon.salonId());
        BulkCreateServicesRequest request = new BulkCreateServicesRequest(List.of(item(90, "700.00")));

        RaceOutcome outcome = raceTwoOutcome(
                asOwner(salon, () -> serviceCatalogService.bulkCreateSalonMasterServices(
                        salon.userId(), salon.salonId(), master1, request)),
                asOwner(salon, () -> serviceCatalogService.bulkCreateSalonMasterServices(
                        salon.userId(), salon.salonId(), master2, request)));

        assertThat(activeCount("SALON", salon.salonId())).as("one salon copy, never two").isEqualTo(1L);
        UUID copy = activeIdsOf("SALON", salon.salonId()).get(0);
        if (outcome.loserFailure() == null) {
            assertThat(fixtures.activeDefinitionIdsAssignedToMaster(master2))
                    .as("the loser that did not 409 reused the winner's copy").containsExactly(copy);
        } else {
            assertRaceLoserNames(outcome.loserFailure(), null, copy);
            assertThat(fixtures.activeDefinitionIdsAssignedToMaster(master2))
                    .as("the losing bulk rolled back its assignment").isEmpty();
        }
        assertThat(fixtures.activeDefinitionIdsAssignedToMaster(master1)).containsExactly(copy);
    }

    /**
     * HTTP end to end on the race path when the {@code DuplicateServiceCopyLookup} is skipped: the
     * winner is a raw, non-pooled connection, so once the loser is blocked on the index this test
     * can take every remaining pooled connection — the lookup then sees no idle connection, never
     * waits on the pool, and the loser answers with the plain {@code DUPLICATE_SERVICE} 409.
     *
     * <p>Deterministic: the drain holds exactly {@code max - 1} connections (the loser holds the
     * last), waiting out any background borrower instead of stopping at "none idle right now". So
     * when the lookup runs, every pooled connection is held by this test or by the loser itself —
     * no scheduler or outbox job holds one it could hand back — and all stay held until the
     * response arrives.
     */
    @Test
    @DisplayName("AC5 HTTP: race loser with no idle pool connection for the lookup → 409 DUPLICATE_SERVICE, existingServiceDefId null, no 500")
    void should_return409WithNullExistingId_when_lookupFindsNoIdlePoolConnection() throws Exception {
        IndependentMaster master = independentMaster("race-http");
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        ResponseEntity<String> resp;
        try (Connection winner = DriverManager.getConnection(
                hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword())) {
            winner.setAutoCommit(false);
            insertRawCopy(winner, master.masterId(), NAME, 60);
            CompletableFuture<ResponseEntity<String>> loser = CompletableFuture.supplyAsync(
                    () -> postIndependent(master.token(), create(NAME, 60, "500.00")));
            awaitBlockedOrDone(loser);
            assertThat(loser).as("precondition: the loser is blocked on the V233 index, holding its connection")
                    .isNotDone();
            assertThat(lockWaiters()).as("precondition: the loser waits on the winner's index entry").isPositive();
            List<Connection> drained = drainPool(hikari);
            try {
                winner.commit();
                resp = loser.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            } finally {
                for (Connection c : drained) {
                    c.close();
                }
            }
        }

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = fixtures.data(resp);
        assertThat(body.path("code").asText()).isEqualTo("DUPLICATE_SERVICE");
        assertThat(body.path("existingServiceDefId").isNull() || body.path("existingServiceDefId").isMissingNode())
                .as("lookup skipped on a busy pool → the pre-lookup 409 (null id), body: %s", resp.getBody()).isTrue();
        assertThat(activeCount("INDEPENDENT_MASTER", master.masterId())).as("only the raw winner").isEqualTo(1L);
    }

    // ── Deterministic candidate order (created_at, then id) ────────────────────

    @Test
    @DisplayName("bulk-setup candidates come back oldest copy first (created_at), id breaking a created_at tie — never insertion/heap order")
    void should_returnCandidatesOldestFirstWithIdTiebreak_when_salonHoldsSeveralCopiesOfOneType() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("order-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Order Salon");
        UUID first = UUID.fromString(fixtures.data(postSalon(ownerToken, salonId, create(NAME, 60, "500.00"))).path("id").asText());
        UUID second = UUID.fromString(fixtures.data(postSalon(ownerToken, salonId, create(NAME, 90, "500.00"))).path("id").asText());
        UUID third = UUID.fromString(fixtures.data(postSalon(ownerToken, salonId, create(OTHER_NAME, 60, "500.00"))).path("id").asText());
        // Created LAST becomes the OLDEST; the other two share one instant so only the id can order them.
        // Updated in this order so the new heap tuples sit first, second, third: an unordered scan
        // would return `third` LAST, so the assertion cannot pass by physical order.
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        jdbcTemplate.update("UPDATE service_definitions SET created_at = ? WHERE id IN (?, ?)",
                java.sql.Timestamp.from(base.plusSeconds(60)), first, second);
        jdbcTemplate.update("UPDATE service_definitions SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(base), third);
        List<UUID> tied = java.util.stream.Stream.of(first, second)
                .sorted(java.util.Comparator.comparing(UUID::toString)).toList();

        List<UUID> order = serviceRepository.findSalonBulkSetupCandidates(salonId, UUID.randomUUID(), java.util.Set.of(typeId))
                .stream().map(c -> c.definition().getId()).toList();

        assertThat(order)
                .as("oldest copy first, then the created_at tie broken by id (Postgres uuid order = canonical text order)")
                .containsExactly(third, tied.get(0), tied.get(1));
    }

    // ── Acceptance 7 — several copies of one type in one bulk request ──────────

    @Test
    @DisplayName("AC7: bulk 60 + 90 of one type → 201, two copies")
    void should_createTwoCopies_when_bulkCarriesOneTypeAtTwoDurations() throws Exception {
        String email = "ac7a-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        ResponseEntity<String> resp = fixtures.postIndependentBulk(token, List.of(item(60, "350.00"), item(90, "500.00")));

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

        ResponseEntity<String> resp = fixtures.postIndependentBulk(token, List.of(item(60, "350.00"), item(60, "420.00")));

        fixtures.assertInRequestDuplicate400(resp);
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

        ResponseEntity<String> resp = fixtures.postIndependentBulk(token, List.of(
                item(60, "350.00"), namedItem(60, "420.00", caseSpaces)));

        fixtures.assertInRequestDuplicate400(resp);
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

        ResponseEntity<String> resp = fixtures.postIndependentBulk(token, List.of(
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

        ResponseEntity<String> resp = fixtures.postSalonBulk(ownerToken, salonId, masterId,
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

        ResponseEntity<String> resp = fixtures.postSalonBulk(ownerToken, salonId, masterId,
                List.of(item(120, "350.00"), item(120, "450.00")));

        fixtures.assertInRequestDuplicate400(resp);
        assertThat(activeCount("SALON", salonId)).isZero();
    }

    // ── Acceptance 8 — bulk against a held copy ────────────────────────────────

    @Test
    @DisplayName("AC8: bulk 90 + 120 of a type held at 90 → 409 naming the held copy, nothing inserted")
    void should_return409AndRollBack_when_bulkRepeatsAHeldCopy() throws Exception {
        String email = "ac8-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        ResponseEntity<String> held = fixtures.postIndependentBulk(token, List.of(item(90, "500.00")));
        String heldId = fixtures.data(held).path(0).path("serviceDefinition").path("id").asText();

        ResponseEntity<String> resp = fixtures.postIndependentBulk(token, List.of(item(90, "450.00"), item(120, "700.00")));

        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        fixtures.assertDuplicateOf(resp, heldId);
        assertThat(fixtures.countServiceDefinitionsForMaster(masterId))
                .as("the 120 copy rolled back with the batch").isEqualTo(1L);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private record IndependentMaster(String token, UUID masterId) {
    }

    private record SalonOwner(String token, UUID userId, UUID salonId) {
    }

    /** The second write's outcome in {@link #raceTwoOutcome}: {@code null} when it committed. */
    private record RaceOutcome(Throwable loserFailure) {
    }

    private IndependentMaster independentMaster(String tag) throws Exception {
        String email = tag + "-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        return new IndependentMaster(token, fixtures.resolveMasterIdForUserEmail(email));
    }

    private SalonOwner salonOwner(String tag) throws Exception {
        String email = tag + "-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createSalonOwnerAndGetToken(email);
        UUID salonId = fixtures.createSalon(token, "Salon " + tag);
        return new SalonOwner(token, userIdOf(email), salonId);
    }

    /**
     * Runs {@code write} on its worker thread as the salon owner — the authz layer reads the
     * caller's role from the {@code SecurityContext}, exactly as {@code JwtAuthenticationFilter} sets it.
     */
    private static Runnable asOwner(SalonOwner owner, Runnable write) {
        return () -> {
            var authentication = new UsernamePasswordAuthenticationToken(
                    owner.userId().toString(), null, List.of(new SimpleGrantedAuthority(Role.SALON_OWNER.springRole)));
            authentication.setDetails(owner.userId());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                write.run();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private UUID userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    /** Another active, approved type of {@link #CATEGORY} — a PATCH may move a copy between them. */
    private UUID secondTypeInCategory() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.platform_category_name = ? AND st.is_active = TRUE AND st.id <> ? "
                        + "AND pc.active = TRUE AND pc.status = 'APPROVED' ORDER BY st.name_uk LIMIT 1",
                UUID.class, CATEGORY, typeId);
    }

    private CreateServiceDefinitionRequest create(String name, int duration, String price) {
        return createOfType(name, duration, price, typeId);
    }

    private CreateServiceDefinitionRequest createOfType(String name, int duration, String price, UUID serviceTypeId) {
        return new CreateServiceDefinitionRequest(name, null, CATEGORY, duration, 0,
                PriceType.FIXED, new BigDecimal(price), null, null, serviceTypeId);
    }

    private BulkServiceItemRequest item(int duration, String price) {
        return ServiceTestFixtures.bulkItem(typeId, duration, price);
    }

    private BulkServiceItemRequest namedItem(int duration, String price, String name) {
        return ServiceTestFixtures.namedBulkItem(typeId, duration, price, name);
    }

    private ResponseEntity<String> postIndependent(String token, CreateServiceDefinitionRequest request) {
        return restTemplate.exchange("/api/v1/independent-masters/me/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> postSalon(String token, UUID salonId, CreateServiceDefinitionRequest request) {
        return restTemplate.exchange("/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> patch(String token, String serviceDefId, UpdateServiceDefinitionRequest request) {
        return restTemplate.exchange("/api/v1/services/" + serviceDefId, HttpMethod.PATCH,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }

    /** Definition id of a 201 from {@code POST /independent-masters/me/services}. */
    private String independentDefId(ResponseEntity<String> resp) throws Exception {
        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return fixtures.data(resp).path("serviceDefinition").path("id").asText();
    }

    /** Definition id of a 201 from {@code POST /salons/{id}/services}. */
    private String salonDefId(ResponseEntity<String> resp) throws Exception {
        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return fixtures.data(resp).path("id").asText();
    }

    private long activeCount(String ownerType, UUID ownerId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE owner_type = ? AND owner_id = ? AND is_active",
                Long.class, ownerType, ownerId);
        return count == null ? 0L : count;
    }

    private List<UUID> activeIdsOf(String ownerType, UUID ownerId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM service_definitions WHERE owner_type = ? AND owner_id = ? AND is_active",
                UUID.class, ownerType, ownerId);
    }

    /** The race loser is the branchable 409 naming the committed winner (and its name, when given). */
    private static void assertRaceLoserNames(Throwable loser, String expectedName, UUID winnerId) {
        assertThat(loser)
                .as("the loser reaches the index at flush; the constraint-name translation makes it the 409")
                .isInstanceOf(DuplicateServiceException.class);
        DuplicateServiceException duplicate = (DuplicateServiceException) loser;
        assertThat(duplicate.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getExistingServiceDefId())
                .as("the committed winner is looked up after the race (REQUIRES_NEW) and deep-linkable")
                .isEqualTo(winnerId);
        if (expectedName != null) {
            assertThat(duplicate.getServiceName())
                    .as("the flush-time 409 names the colliding copy like the pre-check 409 does")
                    .isEqualTo(expectedName);
        }
    }

    private void insertRawCopy(Connection connection, UUID masterId, String name, int duration) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, category, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, ?, ?, ?, ?, 'FIXED', 500.00, 0, true, NOW(), NOW())")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, masterId);
            insert.setString(3, name);
            insert.setString(4, CATEGORY);
            insert.setObject(5, typeId);
            insert.setInt(6, duration);
            insert.executeUpdate();
        }
    }

    /**
     * Checks out {@code max - 1} pooled connections — every one the blocked loser does not hold.
     * {@code getConnection} blocks until a background borrower hands its connection back, so on
     * return nobody but this test and the loser holds a connection and none can become idle.
     */
    private static List<Connection> drainPool(HikariDataSource hikari) throws Exception {
        int othersThanLoser = hikari.getMaximumPoolSize() - 1;
        List<Connection> held = new ArrayList<>(othersThanLoser);
        while (held.size() < othersThanLoser) {
            held.add(hikari.getConnection());
        }
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        assertThat(pool.getIdleConnections()).as("drained: no idle connection").isZero();
        assertThat(pool.getActiveConnections()).as("drained: test + loser hold the whole pool")
                .isEqualTo(hikari.getMaximumPoolSize());
        return held;
    }

    private int lockWaiters() {
        Integer waiting = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                        + "AND datname = current_database()", Integer.class);
        return waiting == null ? 0 : waiting;
    }

    /** Runs {@code first} in a held-open transaction, then {@code second}; returns {@code second}'s failure. */
    private Throwable raceTwo(Runnable first, Runnable second) throws Exception {
        Throwable failure = raceTwoOutcome(first, second).loserFailure();
        return failure != null ? failure : fail("write #2 succeeded — a second identical copy was committed");
    }

    private RaceOutcome raceTwoOutcome(Runnable first, Runnable second) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<Void> winner = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            first.run();
            firstWritten.countDown();
            awaitQuietly(releaseFirst);
        }));
        if (!firstWritten.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
            winner.get(POLL_MILLIS, TimeUnit.MILLISECONDS); // surfaces write #1's own failure
            fail("write #1 never flushed");
        }

        CompletableFuture<Void> loser = CompletableFuture.runAsync(second);
        awaitBlockedOrDone(loser);
        releaseFirst.countDown();

        winner.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        try {
            loser.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            return new RaceOutcome(null);
        } catch (ExecutionException ex) {
            return new RaceOutcome(ex.getCause());
        }
    }

    /** Returns once {@code second} finished or a backend waits on a row/index lock. */
    private void awaitBlockedOrDone(CompletableFuture<?> second) throws Exception {
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
                                + "AND datname = current_database()", Integer.class);
                if (waiting != null && waiting > 0) {
                    return;
                }
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("write #1 was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
