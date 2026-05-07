package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.EmailService;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

@Import(TestSecurityConfig.class)
@DisplayName("Service — cross-owner IDOR security regression")
class ServiceSecurityTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ServiceSecurityTest.class);
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
    @DisplayName("POST /salons/{salonAId}/services — 403 when Owner B adds a service to Owner A's salon")
    void should_return403_when_ownerBAddsServiceToSalonOwnedByOwnerA() throws Exception {
        // Arrange
        String ownerAToken = createSalonOwnerAndGetToken(
                "sec-owner-a-add-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = createSalon(ownerAToken, "Owner A Salon");

        String ownerBToken = createSalonOwnerAndGetToken(
                "sec-owner-b-add-" + System.nanoTime() + "@beautica.test");

        var request = new CreateServiceDefinitionRequest(
                "IDOR Service", null, null, 30, new BigDecimal("100.00"), 0, null);

        // Act
        log.debug("Act: POST /api/v1/salons/{}/services with Owner B token — IDOR must be blocked", salonAId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonAId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerBToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 403 when Owner B adds a service to Owner A's salon, salonId=%s", salonAId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /salons/{salonAId}/masters/{masterAId}/services — 403 when Owner B assigns service to Master in Owner A's salon")
    void should_return403_when_ownerBAssignsServiceToMasterInSalonOwnedByOwnerA() throws Exception {
        // Arrange
        String ownerAToken = createSalonOwnerAndGetToken(
                "sec-owner-a-assign-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = createSalon(ownerAToken, "Owner A Salon");
        UUID masterAId = createSalonMaster(salonAId);
        UUID serviceDefId = createServiceDefinition(ownerAToken, salonAId, "Salon A Service");

        String ownerBToken = createSalonOwnerAndGetToken(
                "sec-owner-b-assign-" + System.nanoTime() + "@beautica.test");

        var request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        // Act
        log.debug("Act: POST /api/v1/salons/{}/masters/{}/services with Owner B token — IDOR must be blocked",
                salonAId, masterAId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonAId + "/masters/" + masterAId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerBToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 403 when Owner B assigns a service to a master in Owner A's salon, salonId=%s, masterId=%s",
                        salonAId, masterAId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("DELETE /services/{salonAServiceId} — 403 when Owner B deactivates Owner A's service definition")
    void should_return403_when_ownerBDeactivatesServiceOwnedBySalonA() throws Exception {
        // Arrange
        String ownerAToken = createSalonOwnerAndGetToken(
                "sec-owner-a-del-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = createSalon(ownerAToken, "Owner A Salon");
        UUID salonAServiceId = createServiceDefinition(ownerAToken, salonAId, "Owner A's Service");

        String ownerBToken = createSalonOwnerAndGetToken(
                "sec-owner-b-del-" + System.nanoTime() + "@beautica.test");

        // Act
        log.debug("Act: DELETE /api/v1/services/{} with Owner B token — IDOR must be blocked", salonAServiceId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + salonAServiceId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerBToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 403 when Owner B deactivates Owner A's service definition, serviceDefId=%s", salonAServiceId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("DELETE /services/{serviceDefId} — 403 when SALON_OWNER deletes an INDEPENDENT_MASTER's service")
    void should_return403_when_salonOwnerDeletesIndependentMasterService() throws Exception {
        // Arrange — independent master creates a service
        String indepToken = createIndependentMasterAndGetToken(
                "sec-indep-del-" + System.nanoTime() + "@beautica.test");
        UUID indepServiceId = createIndependentMasterService(indepToken, "Lash Extensions");

        // Arrange — separate salon owner
        String ownerToken = createSalonOwnerAndGetToken(
                "sec-owner-del-indep-" + System.nanoTime() + "@beautica.test");

        // Act
        log.debug("Act: DELETE /api/v1/services/{} as SALON_OWNER — IDOR on INDEPENDENT_MASTER service must be blocked",
                indepServiceId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + indepServiceId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 403 when SALON_OWNER tries to delete INDEPENDENT_MASTER's service, serviceDefId=%s",
                        indepServiceId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("DELETE /services/{serviceDefId} — 403 when INDEPENDENT_MASTER A deletes INDEPENDENT_MASTER B's service")
    void should_return403_when_independentMasterADeletesIndependentMasterBService() throws Exception {
        // Arrange — master B creates a service
        String masterBToken = createIndependentMasterAndGetToken(
                "sec-indep-b-" + System.nanoTime() + "@beautica.test");
        UUID masterBServiceId = createIndependentMasterService(masterBToken, "Gel Nails");

        // Arrange — master A (different independent master)
        String masterAToken = createIndependentMasterAndGetToken(
                "sec-indep-a-" + System.nanoTime() + "@beautica.test");

        // Act
        log.debug("Act: DELETE /api/v1/services/{} as INDEPENDENT_MASTER A — cross-master IDOR must be blocked",
                masterBServiceId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + masterBServiceId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(masterAToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 403 when INDEPENDENT_MASTER A tries to delete INDEPENDENT_MASTER B's service, serviceDefId=%s",
                        masterBServiceId)
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
        String masterEmail = "sec-master-" + System.nanoTime() + "@beautica.test";
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

    private String createIndependentMasterAndGetToken(String email) throws Exception {
        var request = new RegisterIndependentMasterRequest(email, TEST_PASSWORD, "Anna", "Kovalenko", null);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/register/independent-master", request, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private UUID createIndependentMasterService(String indepToken, String name) throws Exception {
        var request = new CreateServiceDefinitionRequest(name, null, null, 60, new BigDecimal("500.00"), 0, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/independent-masters/me/services", HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(indepToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var parsed = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<com.beautica.service.dto.MasterServiceResponse>>() {});
        return parsed.data().serviceDefinition().id();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
