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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
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

}
