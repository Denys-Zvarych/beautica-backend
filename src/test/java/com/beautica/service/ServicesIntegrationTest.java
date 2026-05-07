package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

@Import(TestSecurityConfig.class)
@DisplayName("Services — full-flow integration")
class ServicesIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ServicesIntegrationTest.class);
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

    @Test
    @DisplayName("Full flow: create salon service, assign to master, GET returns effective price and duration")
    void should_createSalonServiceAndAssignToMaster_when_fullFlow() throws Exception {
        // Arrange: salon owner, salon, master, service definition
        String ownerToken = createSalonOwnerAndGetToken(
                "integ-owner-full-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerToken, "Full Flow Salon");
        UUID masterId = createSalonMaster(salonId);

        var createRequest = new CreateServiceDefinitionRequest(
                "Shellac Manicure",
                "Long-lasting shellac",
                null,
                75,
                new BigDecimal("600.00"),
                10,
                null
        );

        log.debug("Act step 1: POST /api/v1/salons/{}/services to create service definition", salonId);
        UUID serviceDefId = createServiceDefinition(ownerToken, salonId, createRequest);

        // Assign with a price override to verify effectivePrice resolution
        var assignRequest = new AssignServiceToMasterRequest(serviceDefId, new BigDecimal("550.00"), null);

        log.debug("Act step 2: POST /api/v1/salons/{}/masters/{}/services to assign service", salonId, masterId);
        ResponseEntity<String> assignResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(assignRequest, bearerHeaders(ownerToken)),
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
        String ownerToken = createSalonOwnerAndGetToken(
                "integ-owner-dup-" + System.nanoTime() + "@beautica.test");
        UUID salonId = createSalon(ownerToken, "Duplicate Assignment Salon");
        UUID masterId = createSalonMaster(salonId);

        var createRequest = new CreateServiceDefinitionRequest(
                "Pedicure", null, null, 90, new BigDecimal("450.00"), 15, null);
        UUID serviceDefId = createServiceDefinition(ownerToken, salonId, createRequest);

        var assignRequest = new AssignServiceToMasterRequest(serviceDefId, null, null);

        // First assignment — must succeed
        log.debug("Act step 1: first assignment of service {} to master {} — must return 201", serviceDefId, masterId);
        ResponseEntity<String> firstResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(assignRequest, bearerHeaders(ownerToken)),
                String.class);
        assertThat(firstResp.getStatusCode())
                .as("first assignment must succeed with 201")
                .isEqualTo(HttpStatus.CREATED);

        // Second identical assignment — must conflict
        log.debug("Act step 2: duplicate assignment of same service — must return 409");
        ResponseEntity<String> secondResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(assignRequest, bearerHeaders(ownerToken)),
                String.class);

        assertThat(secondResp.getStatusCode())
                .as("second identical assignment must return 409 CONFLICT, serviceDefId=%s, masterId=%s",
                        serviceDefId, masterId)
                .isEqualTo(HttpStatus.CONFLICT);
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

    private UUID createSalon(String ownerToken, String name) throws Exception {
        String body = "{\"name\":\"" + name + "\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var parsed = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<com.beautica.salon.dto.SalonResponse>>() {});
        return parsed.data().id();
    }

    private UUID createSalonMaster(UUID salonId) {
        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "integ-master-" + System.nanoTime() + "@beautica.test";
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

    private UUID createServiceDefinition(
            String ownerToken,
            UUID salonId,
            CreateServiceDefinitionRequest request
    ) throws Exception {
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
}
