package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.DuplicateServiceResponse;
import com.beautica.service.entity.PriceType;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.service.ServiceCatalogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("Services — full-flow integration")
class ServicesIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ServicesIntegrationTest.class);
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ServiceCatalogService serviceCatalogService;

    /** Source of the Hibernate {@link Statistics} the combined-PATCH statement gate counts on. */
    @Autowired
    private EntityManagerFactory emf;

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM master_services");
        jdbcTemplate.execute("DELETE FROM service_definitions");
        jdbcTemplate.execute("DELETE FROM invite_tokens");
        jdbcTemplate.execute("DELETE FROM masters");
        jdbcTemplate.execute("DELETE FROM salons");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @Test
    @DisplayName("Full flow: create salon service, assign to master, GET returns effective price and duration")
    void should_createSalonServiceAndAssignToMaster_when_fullFlow() throws Exception {
        // Arrange: salon owner, salon, master, service definition
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "integ-owner-full-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Full Flow Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);

        var createRequest = new CreateServiceDefinitionRequest(
                "Shellac Manicure",
                "Long-lasting shellac",
                "NAIL_SERVICE",
                75,
                10,
                PriceType.FIXED,
                new BigDecimal("600.00"),
                null,
                null,
                fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE")
        );

        log.debug("Act step 1: POST /api/v1/salons/{}/services to create service definition", salonId);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, createRequest);

        // Assign with a price override to verify effectivePrice resolution
        var assignRequest = new AssignServiceToMasterRequest(serviceDefId, new BigDecimal("550.00"), null);

        log.debug("Act step 2: POST /api/v1/salons/{}/masters/{}/services to assign service", salonId, masterId);
        ResponseEntity<String> assignResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(assignRequest, fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(assignResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Act: public GET to verify effective values
        log.debug("Act step 3: GET /api/v1/masters/{}/services (public) — verify effective price and duration", masterId);
        ResponseEntity<String> getResp = restTemplate.getForEntity(
                "/api/v1/masters/" + masterId + "/services", String.class);

        // Assert
        assertThat(getResp.getStatusCode())
                .as("public GET master services must return 200")
                .isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                getResp.getBody(), new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data()).hasSize(1);

        MasterServiceResponse svc = body.data().get(0);
        assertThat(svc.masterId())
                .as("masterId on response must match the assigned master")
                .isEqualTo(masterId);
        assertThat(svc.serviceDefinition().name())
                .as("service definition name must be persisted correctly")
                .isEqualTo("Shellac Manicure");
        assertThat(svc.effectivePrice())
                .as("effectivePrice must use priceOverride=550.00 rather than basePrice=600.00")
                .isEqualByComparingTo(new BigDecimal("550.00"));
        assertThat(svc.effectiveDurationMinutes())
                .as("effectiveDurationMinutes must fall back to baseDurationMinutes=75 when no durationOverride")
                .isEqualTo(75);
        assertThat(svc.isActive()).isTrue();
    }

    @Test
    @DisplayName("409 when the same service is assigned to the same master twice")
    void should_return409_when_sameServiceAssignedTwice() throws Exception {
        // Arrange
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "integ-owner-dup-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Duplicate Assignment Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);

        var createRequest = new CreateServiceDefinitionRequest(
                "Pedicure", null, "NAIL_SERVICE", 90, 15,
                PriceType.FIXED, new BigDecimal("450.00"), null, null,
                fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE"));
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, createRequest);

        var assignRequest = new AssignServiceToMasterRequest(serviceDefId, null, null);

        // First assignment — must succeed
        log.debug("Act step 1: first assignment of service {} to master {} — must return 201", serviceDefId, masterId);
        ResponseEntity<String> firstResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(assignRequest, fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(firstResp.getStatusCode())
                .as("first assignment must succeed with 201")
                .isEqualTo(HttpStatus.CREATED);

        // Second identical assignment — must conflict
        log.debug("Act step 2: duplicate assignment of same service — must return 409");
        ResponseEntity<String> secondResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(assignRequest, fixtures.bearerHeaders(ownerToken)),
                String.class);

        assertThat(secondResp.getStatusCode())
                .as("second identical assignment must return 409 CONFLICT, serviceDefId=%s, masterId=%s",
                        serviceDefId, masterId)
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ── V121 one-active-service-per-(owner, type) — full stack ─────────────────
    //
    // The @WebMvcTest slice pins the 409's JSON shape against a mocked service, and
    // ServiceDefinitionUniqueActiveTypeTest pins the partial index against real Postgres.
    // Neither proves the two meet: that a real HTTP create against a real database produces the
    // branchable body carrying a REAL, resolvable existingServiceDefId. That composition — the
    // exact call the mobile add-service screen makes — is only observable here.

    @Test
    @DisplayName("409 DUPLICATE_SERVICE with the existing service's id when the salon re-adds a service type it already offers")
    void should_return409WithExistingServiceDefId_when_salonReAddsSameServiceType() throws Exception {
        // Arrange
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "integ-owner-duptype-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Duplicate Type Salon");
        UUID serviceTypeId = fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE");

        UUID firstServiceDefId = fixtures.createServiceDefinition(ownerToken, salonId,
                new CreateServiceDefinitionRequest("Класичне нарощення", null, "NAIL_SERVICE", 60, 10,
                        PriceType.FIXED, new BigDecimal("500.00"), null, null, serviceTypeId));

        // Deliberately a different name, price and duration: the locked product rule keys on the
        // service type alone, so none of these make it a distinct service.
        var duplicate = new CreateServiceDefinitionRequest("Класичне нарощення VIP", null, "NAIL_SERVICE", 120, 10,
                PriceType.FIXED, new BigDecimal("900.00"), null, null, serviceTypeId);

        // Act
        log.debug("Act: POST /api/v1/salons/{}/services re-adding service type {} at a different price", salonId, serviceTypeId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(duplicate, fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(resp.getStatusCode())
                .as("re-adding an already-offered service type must conflict, salonId=%s", salonId)
                .isEqualTo(HttpStatus.CONFLICT);

        var body = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<DuplicateServiceResponse>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.data())
                .extracting(DuplicateServiceResponse::code, DuplicateServiceResponse::existingServiceDefId)
                .as("the client branches on code and deep-links via existingServiceDefId")
                .containsExactly("DUPLICATE_SERVICE", firstServiceDefId);

        // serviceName is the field the mobile error copy renders, so its WIRE value is part of the
        // contract — and the @Schema doc on DuplicateServiceResponse is only as good as an
        // assertion on the real response. On this (pre-check) path it is the service TYPE's label,
        // NOT the name the caller just submitted; asserting equality with the submitted string
        // would pass for either source and prove nothing.
        String expectedTypeName = jdbcTemplate.queryForObject(
                "SELECT name_uk FROM service_types WHERE id = ?", String.class, serviceTypeId);
        assertThat(body.data().serviceName())
                .as("the pre-check path names the conflicting service by its service-type label")
                .isNotNull()
                .isEqualTo(expectedTypeName)
                .isNotEqualTo("Класичне нарощення VIP");

        // Nothing was written: a 409 that still inserted would leave the owner with a phantom row.
        Long definitionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE owner_id = ?", Long.class, salonId);
        assertThat(definitionCount).as("the rejected create must persist nothing").isEqualTo(1L);
    }

    @Test
    @DisplayName("re-creating a service type after it was deleted succeeds — the index is partial on is_active")
    void should_allowRecreation_when_theServiceTypeWasPreviouslyDeleted() throws Exception {
        // Arrange
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "integ-owner-recreate-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Recreate Salon");
        UUID serviceTypeId = fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE");

        UUID firstServiceDefId = fixtures.createServiceDefinition(ownerToken, salonId,
                new CreateServiceDefinitionRequest("Класичне нарощення", null, "NAIL_SERVICE", 60, 10,
                        PriceType.FIXED, new BigDecimal("500.00"), null, null, serviceTypeId));

        log.debug("Act step 1: DELETE /api/v1/services/{} — soft delete flips is_active", firstServiceDefId);
        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/v1/services/" + firstServiceDefId, HttpMethod.DELETE,
                new HttpEntity<>(null, fixtures.bearerHeaders(ownerToken)), Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Act — the same service type again. Deletion is SOFT, so the losing row survives; an
        // unconditional unique constraint (or a pre-check that dropped its isActive predicate)
        // would make this service permanently uncreatable, with no reactivate endpoint to escape.
        log.debug("Act step 2: POST /api/v1/salons/{}/services re-creating the deleted service type", salonId);
        ResponseEntity<String> recreateResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(new CreateServiceDefinitionRequest(
                        "Класичне нарощення", null, "NAIL_SERVICE", 60, 10,
                        PriceType.FIXED, new BigDecimal("650.00"), null, null, serviceTypeId),
                        fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(recreateResp.getStatusCode())
                .as("re-creating a soft-deleted service type must succeed, not 409")
                .isEqualTo(HttpStatus.CREATED);

        var recreated = objectMapper.readValue(
                recreateResp.getBody(), new TypeReference<ApiResponse<ServiceDefinitionResponse>>() {});
        assertThat(recreated.data().id())
                .as("a fresh row is inserted — the soft-deleted definition is never resurrected")
                .isNotEqualTo(firstServiceDefId);

        Long activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE owner_id = ? AND is_active = TRUE",
                Long.class, salonId);
        assertThat(activeCount)
                .as("exactly one ACTIVE definition of this type remains — the invariant V121 enforces")
                .isEqualTo(1L);
    }

    // ── V121 on the OTHER two write paths ──────────────────────────────────────
    //
    // Before these, full-stack DUPLICATE_SERVICE coverage existed for the SALON single-create path
    // ONLY. The two paths below were proven at the POJO level alone, and each has a property a
    // POJO test structurally cannot reach:
    //   * PATCH — the two-phase resolvePatchServiceType design is a Hibernate AUTO-FLUSH ORDERING
    //     property. ServiceCatalogServiceUpdateTest:1092 asserts field state on a plain object;
    //     with no session there is no flush to order, so it cannot show that the duplicate guard
    //     runs while the entity is still CLEAN. Reverse the two phases and that unit test stays
    //     green while every real PATCH degrades to a generic 409 with no data.code.
    //   * independent-master create — addIndependentMasterService is the one create path that uses
    //     save() + a deferred flushTranslatingDuplicateViolation rather than saveAndFlush, so the
    //     classification depends on the deferred flush actually emitting the insert.

    @Test
    @DisplayName("PATCH /services/{id} — 409 DUPLICATE_SERVICE when the patch re-points a service "
            + "at a type the same owner already offers, and the row is left unchanged")
    void should_return409_when_patchRepointsAtATypeTheOwnerAlreadyOffers() throws Exception {
        // Arrange — one salon, two services of two different types.
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "integ-owner-patchdup-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Patch Duplicate Salon");
        UUID takenTypeId = fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE");
        UUID otherTypeId = fixtures.resolveServiceTypeIdForCategory("HAIRDRESSING");

        UUID incumbentId = fixtures.createServiceDefinition(ownerToken, salonId,
                new CreateServiceDefinitionRequest("Класичне нарощення", null, "NAIL_SERVICE", 60, 10,
                        PriceType.FIXED, new BigDecimal("500.00"), null, null, takenTypeId));
        UUID patchedId = fixtures.createServiceDefinition(ownerToken, salonId,
                new CreateServiceDefinitionRequest("Стрижка", null, "HAIRDRESSING", 45, 5,
                        PriceType.FIXED, new BigDecimal("400.00"), null, null, otherTypeId));

        // A COMBINED category + serviceTypeId patch — the exact shape whose flush ordering the
        // two-phase design exists for. Patching the type alone would leave the entity clean anyway
        // and could not distinguish the two orderings.
        var repoint = new UpdateServiceDefinitionRequest(
                null, null, "NAIL_SERVICE", null, null, null, null, null, null, takenTypeId);

        // Act
        log.debug("Act: PATCH /api/v1/services/{} re-pointing it at service type {}, which this "
                + "salon already offers through definition {}", patchedId, takenTypeId, incumbentId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/services/" + patchedId, HttpMethod.PATCH,
                new HttpEntity<>(repoint, fixtures.bearerHeaders(ownerToken)), String.class);

        // Assert
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var body = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<DuplicateServiceResponse>>() {});
        assertThat(body.data())
                .extracting(DuplicateServiceResponse::code, DuplicateServiceResponse::existingServiceDefId)
                .as("a populated existingServiceDefId proves the PRE-CHECK produced this 409 while "
                        + "the entity was still clean. If phase 2 ran first, Hibernate's AUTO flush "
                        + "would emit the UPDATE inside the finder call, the index would fire there, "
                        + "and the client would get a generic 409 with a null code instead.")
                .containsExactly("DUPLICATE_SERVICE", incumbentId);

        // The rejected PATCH must not have half-applied: category and type both unchanged.
        assertThat(jdbcTemplate.queryForMap(
                "SELECT category, service_type_id FROM service_definitions WHERE id = ?", patchedId))
                .as("a 409 that still committed the category would leave the row inconsistent with "
                        + "its own service type")
                .containsEntry("category", "HAIRDRESSING")
                .containsEntry("service_type_id", otherTypeId);
    }

    @Test
    @DisplayName("POST /independent-masters/me/services — 409 DUPLICATE_SERVICE when the master "
            + "re-adds a service type they already offer, and nothing is persisted")
    void should_return409_when_independentMasterReAddsSameServiceType() throws Exception {
        // Arrange
        String email = "integ-indep-duptype-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        UUID firstServiceDefId = fixtures.createIndependentMasterService(token, "Класичне нарощення");
        UUID serviceTypeId = fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE");

        // Different name, price and duration again — the rule keys on the service type alone.
        var duplicate = new CreateServiceDefinitionRequest("Класичне нарощення VIP", null,
                "NAIL_SERVICE", 120, 10, PriceType.FIXED, new BigDecimal("900.00"), null, null,
                serviceTypeId);

        // Act
        log.debug("Act: POST /api/v1/independent-masters/me/services re-adding service type {}", serviceTypeId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/independent-masters/me/services", HttpMethod.POST,
                new HttpEntity<>(duplicate, fixtures.bearerHeaders(token)), String.class);

        // Assert
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var body = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<DuplicateServiceResponse>>() {});
        assertThat(body.data())
                .extracting(DuplicateServiceResponse::code, DuplicateServiceResponse::existingServiceDefId)
                .as("this path defers its flush so the definition and the assignment insert leave "
                        + "together; the code must survive that deferral")
                .containsExactly("DUPLICATE_SERVICE", firstServiceDefId);

        // owner_id for an independent master's services is masters.id (not the user id).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE owner_id = ?", Long.class, masterId))
                .as("the rejected create must persist neither the definition nor its assignment")
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ?", Long.class, masterId))
                .as("the deferred flush means the assignment insert is queued too — it must roll "
                        + "back with the definition, which a definition-only count would miss")
                .isEqualTo(1L);
    }

    // ── PATCH flush-ordering perf gate ─────────────────────────────────────────

    /**
     * Statement gate for commit {@code 274f15e}'s perf claim: "a combined category + type patch
     * emits a single UPDATE".
     *
     * <p>That claim had NO gate, while the sibling Phase 26.9 commit established absolute
     * {@code getPrepareStatementCount()} gates as this repo's pattern for exactly this class of
     * regression. The regression it guards is subtle and entirely invisible to every other test:
     * re-interleaving {@code resolvePatchServiceType}'s two phases makes the category setter fire
     * before the duplicate guard's JPQL, Hibernate's AUTO flush pushes a category-only UPDATE
     * mid-method, the remaining setters re-dirty the entity, and {@code persistDefinition}'s
     * {@code saveAndFlush} emits a SECOND UPDATE — plus it takes the row's write lock earlier than
     * necessary. The patched row ends up correct either way, so correctness tests stay green.
     *
     * <p><b>Two metrics, deliberately.</b> {@code getEntityUpdateCount() == 1} is the direct,
     * self-documenting statement of the claim and is stable against unrelated churn.
     * {@code getPrepareStatementCount()} is the house pattern and additionally catches an extra
     * SELECT creeping into the mutate phase; its value is DERIVED FROM A RUN, never predicted, and
     * a change to it is a prompt to re-derive the arithmetic rather than to bump the number.
     *
     * <p>Driven through the service bean rather than HTTP so the count covers this transaction
     * alone — an HTTP request would fold in the JWT filter's user lookup and the authz guard's
     * ownership query, both unrelated to the flush ordering under test.
     */
    @Test
    @DisplayName("PATCH combining category + serviceTypeId emits exactly ONE entity UPDATE — "
            + "re-interleaving the resolve and mutate phases would silently make it two")
    void should_emitOneUpdate_when_patchCombinesCategoryAndServiceType() throws Exception {
        // Arrange
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "integ-owner-patchcount-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Patch Count Salon");
        UUID nailTypeId = fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE");
        UUID hairTypeId = fixtures.resolveServiceTypeIdForCategory("HAIRDRESSING");

        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId,
                new CreateServiceDefinitionRequest("Стрижка", null, "HAIRDRESSING", 45, 5,
                        PriceType.FIXED, new BigDecimal("400.00"), null, null, hairTypeId));

        // Both fields together: category alone never reaches the duplicate guard's query, and type
        // alone leaves the entity clean until the mutate phase anyway. Only the COMBINED patch can
        // produce the mid-method flush this gate exists to catch.
        var combined = new UpdateServiceDefinitionRequest(
                null, null, "NAIL_SERVICE", null, null, null, null, null, null, nailTypeId);

        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        // Act
        log.debug("Act: PATCH service {} with BOTH category=NAIL_SERVICE and serviceTypeId={} "
                + "through the service bean, counting statements", serviceDefId, nailTypeId);
        serviceCatalogService.updateServiceDefinition(serviceDefId, combined);

        long entityUpdates = statistics.getEntityUpdateCount();
        long statements = statistics.getPrepareStatementCount();
        log.debug("Observed entityUpdateCount={} prepareStatementCount={}", entityUpdates, statements);

        // Assert
        assertThat(entityUpdates)
                .as("ONE UPDATE. Two means the category setter dirtied the entity before the "
                        + "duplicate guard's query, so AUTO flush pushed a partial write and the "
                        + "final saveAndFlush wrote again — the exact regression 274f15e removed. "
                        + "Observed %s.", entityUpdates)
                .isEqualTo(1L);
        assertThat(statements)
                .as("absolute JDBC statement gate (house pattern): findByIdWithServiceType + the "
                        + "category-active check + the service-type resolve + the V121 duplicate "
                        + "finder + the single UPDATE + the affected-master lookup. A rise means a "
                        + "new query on this path — re-derive the arithmetic, do not bump the "
                        + "number. Observed %s.", statements)
                .isEqualTo(PATCH_COMBINED_STATEMENTS);

        // The patch itself must still have applied — otherwise a no-op PATCH would pass this gate.
        assertThat(jdbcTemplate.queryForMap(
                "SELECT category, service_type_id FROM service_definitions WHERE id = ?", serviceDefId))
                .containsEntry("category", "NAIL_SERVICE")
                .containsEntry("service_type_id", nailTypeId);
    }

    /** Derived from a run — see the gate's javadoc for the per-statement arithmetic. */
    private static final long PATCH_COMBINED_STATEMENTS = 6L;

}
