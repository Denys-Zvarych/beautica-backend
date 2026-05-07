package com.beautica.service.controller;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.EmailService;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.ArgumentMatchers.anyString;

@Import(TestSecurityConfig.class)
@DisplayName("ServiceController — HTTP layer")
class ServiceControllerTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ServiceControllerTest.class);
    private static final String TEST_PASSWORD = "password123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @org.springframework.boot.test.mock.mockito.MockBean
    private EmailService emailService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        doNothing().when(emailService).sendInviteEmail(anyString(), anyString(), anyString());
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

    // ── POST /api/v1/salons/{salonId}/services ─────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — 201 when SALON_OWNER adds a service to their salon")
    void should_return201_when_ownerAddsServiceToSalon() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken(
                "svc-owner-add-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerToken, "Nails Salon");

        var request = new CreateServiceDefinitionRequest(
                "Classic Manicure", "Basic nail care", null, 60, new BigDecimal("350.00"), 10, null);

        log.debug("Act: POST /api/v1/salons/{}/services as SALON_OWNER", salonId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 201 when SALON_OWNER adds a service to their salon")
                .isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<ServiceDefinitionResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().id()).isNotNull();
        assertThat(body.data().name()).isEqualTo("Classic Manicure");
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 403 when a different owner adds a service")
    void should_return403_when_nonOwnerAddsServiceToSalon() throws Exception {
        String ownerAToken = createSalonOwnerAndGetToken(
                "svc-owner-a-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = createSalon(ownerAToken, "Owner A Salon");

        String ownerBToken = createSalonOwnerAndGetToken(
                "svc-owner-b-" + System.nanoTime() + "@beautica.test");

        var request = new CreateServiceDefinitionRequest(
                "Hijack Service", null, null, 30, new BigDecimal("100.00"), 0, null);

        log.debug("Act: POST /api/v1/salons/{}/services with Owner B token — cross-owner must be denied", salonAId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonAId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerBToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 403 when a different SALON_OWNER adds a service to another owner's salon")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /salons/{id}/services — 400 when baseDurationMinutes is zero")
    void should_return400_when_durationZeroOrNegative() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken(
                "svc-owner-val-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerToken, "Validation Salon");

        // baseDurationMinutes = 0 violates @Positive constraint
        String invalidBody = "{\"name\":\"Bad Service\",\"baseDurationMinutes\":0,\"bufferMinutesAfter\":0}";

        log.debug("Act: POST /api/v1/salons/{}/services with baseDurationMinutes=0 — must be rejected with 400", salonId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(invalidBody, bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when baseDurationMinutes violates @Positive constraint")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── POST /api/v1/salons/{salonId}/masters/{masterId}/services ──────────────

    @Test
    @DisplayName("POST /salons/{salonId}/masters/{masterId}/services — 201 when owner assigns service to master")
    void should_return201_when_ownerAssignsServiceToMaster() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken(
                "svc-assign-owner-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerToken, "Assignment Salon");
        UUID masterId = createSalonMaster(salonId);
        UUID serviceDefId = createServiceDefinition(ownerToken, salonId, "Pedicure");

        var request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        log.debug("Act: POST /api/v1/salons/{}/masters/{}/services — assign service to master", salonId, masterId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 201 when SALON_OWNER assigns a service to a master in their salon")
                .isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<MasterServiceResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().masterId()).isEqualTo(masterId);
    }

    @Test
    @DisplayName("POST /salons/{salonId}/masters/{masterId}/services — 409 when same service assigned twice")
    void should_return409_when_duplicateAssignment() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken(
                "svc-dup-owner-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerToken, "Duplicate Salon");
        UUID masterId = createSalonMaster(salonId);
        UUID serviceDefId = createServiceDefinition(ownerToken, salonId, "Eyebrows");

        var request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        // First assignment — must succeed
        ResponseEntity<String> firstResponse = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerToken)),
                String.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second assignment of the same service — must conflict
        log.debug("Act: POST same assignment twice — second call must return 409");
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerToken)),
                String.class);

        assertThat(secondResponse.getStatusCode())
                .as("status must be 409 when the same service is assigned to the same master twice")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ── GET /api/v1/masters/{masterId}/services ────────────────────────────────

    @Test
    @DisplayName("GET /masters/{id}/services — 200 without authentication (public endpoint)")
    void should_return200_when_publicGetMasterServices() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken(
                "svc-pub-owner-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerToken, "Public Services Salon");
        UUID masterId = createSalonMaster(salonId);
        UUID serviceDefId = createServiceDefinition(ownerToken, salonId, "Gel Nails");

        var assignRequest = new AssignServiceToMasterRequest(serviceDefId, null, null);
        restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(assignRequest, bearerHeaders(ownerToken)),
                String.class);

        log.debug("Act: GET /api/v1/masters/{}/services without credentials — public endpoint", masterId);
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/masters/" + masterId + "/services", String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 for public GET master services, masterId=%s", masterId)
                .isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data()).hasSize(1);
    }

    // ── POST /api/v1/independent-masters/me/services ───────────────────────────

    @Test
    @DisplayName("POST /independent-masters/me/services — 201 when INDEPENDENT_MASTER adds a service")
    void should_return201_when_independentMasterAddsOwnService() throws Exception {
        String indepToken = createIndependentMasterAndGetToken(
                "svc-indep-" + System.nanoTime() + "@beautica.test");

        var request = new CreateServiceDefinitionRequest(
                "Lash Extensions", "Volume set", null, 120, new BigDecimal("900.00"), 15, null);

        log.debug("Act: POST /api/v1/independent-masters/me/services as INDEPENDENT_MASTER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/independent-masters/me/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(indepToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 201 when INDEPENDENT_MASTER adds their own service")
                .isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<MasterServiceResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().serviceDefinition().name()).isEqualTo("Lash Extensions");
    }

    @Test
    @DisplayName("POST /independent-masters/me/services — 403 when CLIENT attempts the call")
    void should_return403_when_clientAddsIndependentMasterService() throws Exception {
        String clientToken = registerClientAndGetToken(
                "svc-client-" + System.nanoTime() + "@beautica.test");

        var request = new CreateServiceDefinitionRequest(
                "Sneaky Service", null, null, 30, new BigDecimal("100.00"), 0, null);

        log.debug("Act: POST /api/v1/independent-masters/me/services as CLIENT — must be denied");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/independent-masters/me/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 403 when CLIENT attempts to add an independent master service")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── DELETE /api/v1/services/{serviceDefId} ─────────────────────────────────

    @Test
    @DisplayName("DELETE /services/{id} — 204 when owner deactivates their service definition")
    void should_return204_when_ownerDeactivatesService() throws Exception {
        String ownerToken = createSalonOwnerAndGetToken(
                "svc-del-owner-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerToken, "Delete Service Salon");
        UUID serviceDefId = createServiceDefinition(ownerToken, salonId, "Threading");

        log.debug("Act: DELETE /api/v1/services/{} as SALON_OWNER — must return 204", serviceDefId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 204 when SALON_OWNER deactivates their service definition, id=%s", serviceDefId)
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("DELETE /services/{id} — 401 when no Authorization header is present")
    void should_return401_when_deleteServiceWithoutAuth() {
        UUID anyId = UUID.randomUUID();

        log.debug("Act: DELETE /api/v1/services/{} without Authorization header — must return 401", anyId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + anyId, HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders()),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 401 when DELETE /services/{id} is called without a Bearer token")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("DELETE /services/{id} — 403 when authenticated owner targets a non-existent service definition UUID")
    void should_return403_when_ownerDeletesNonExistentServiceDefinition() throws Exception {
        // canManageServiceDefinition calls serviceRepository.findOwnerUserId(serviceDefId).
        // When the service definition does not exist that query returns empty, so the
        // @PreAuthorize SpEL evaluates to false and Spring Security short-circuits with 403.
        // This means a non-existent ID is indistinguishable from an unauthorised one at the
        // HTTP boundary — intentional existence-oracle protection.
        String ownerToken = createSalonOwnerAndGetToken(
                "svc-del-missing-" + System.nanoTime() + "@beautica.test");
        UUID nonExistentId = UUID.randomUUID();

        log.debug("Act: DELETE /api/v1/services/{} with valid owner token but missing service def — must return 403", nonExistentId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + nonExistentId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 403 (not 404) when the service definition does not exist — @PreAuthorize denies before the service method runs")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String createSalonOwnerAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'SALON_OWNER', true)",
                UUID.randomUUID(), email, hash);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private String registerClientAndGetToken(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\",\"role\":\"CLIENT\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var parsed = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return parsed.data().accessToken();
    }

    private String createIndependentMasterAndGetToken(String email) throws Exception {
        var request = new RegisterIndependentMasterRequest(email, TEST_PASSWORD, "Anna", "Kovalenko", null);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/register/independent-master", request, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private UUID createSalon(String ownerToken, String name) throws Exception {
        String body = "{\"name\":\"" + name + "\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var parsed = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<com.beautica.salon.dto.SalonResponse>>() {});
        return parsed.data().id();
    }

    private UUID createSalonMaster(UUID salonId) {
        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "master-" + System.nanoTime() + "@beautica.test";
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active) VALUES (?, ?, ?, 'SALON_MASTER', ?, true)",
                masterUserId, masterEmail, hash, salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return masterId;
    }

    private UUID createServiceDefinition(String ownerToken, UUID salonId, String name) throws Exception {
        var request = new CreateServiceDefinitionRequest(name, null, null, 60, new BigDecimal("500.00"), 0, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var parsed = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<ServiceDefinitionResponse>>() {});
        return parsed.data().id();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
