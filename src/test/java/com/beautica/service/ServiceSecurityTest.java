package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
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

@Import(TestSecurityConfig.class)
@DisplayName("Service — cross-owner IDOR security regression")
class ServiceSecurityTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ServiceSecurityTest.class);
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
    @DisplayName("POST /salons/{salonAId}/services — 403 when Owner B adds a service to Owner A's salon")
    void should_return403_when_ownerBAddsServiceToSalonOwnedByOwnerA() throws Exception {
        // Arrange
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "sec-owner-a-add-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = fixtures.createSalon(ownerAToken, "Owner A Salon");

        String ownerBToken = fixtures.createSalonOwnerAndGetToken(
                "sec-owner-b-add-" + System.nanoTime() + "@beautica.test");

        var request = new CreateServiceDefinitionRequest(
                "IDOR Service", null, null, 30, new BigDecimal("100.00"), 0, null);

        // Act
        log.debug("Act: POST /api/v1/salons/{}/services with Owner B token — IDOR must be blocked", salonAId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonAId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(ownerBToken)),
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
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "sec-owner-a-assign-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = fixtures.createSalon(ownerAToken, "Owner A Salon");
        UUID masterAId = fixtures.createSalonMaster(salonAId);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerAToken, salonAId, "Salon A Service");

        String ownerBToken = fixtures.createSalonOwnerAndGetToken(
                "sec-owner-b-assign-" + System.nanoTime() + "@beautica.test");

        var request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        // Act
        log.debug("Act: POST /api/v1/salons/{}/masters/{}/services with Owner B token — IDOR must be blocked",
                salonAId, masterAId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonAId + "/masters/" + masterAId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(ownerBToken)),
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
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "sec-owner-a-del-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = fixtures.createSalon(ownerAToken, "Owner A Salon");
        UUID salonAServiceId = fixtures.createServiceDefinition(ownerAToken, salonAId, "Owner A's Service");

        String ownerBToken = fixtures.createSalonOwnerAndGetToken(
                "sec-owner-b-del-" + System.nanoTime() + "@beautica.test");

        // Act
        log.debug("Act: DELETE /api/v1/services/{} with Owner B token — IDOR must be blocked", salonAServiceId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + salonAServiceId, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(ownerBToken)),
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
        String indepToken = fixtures.createIndependentMasterAndGetToken(
                "sec-indep-del-" + System.nanoTime() + "@beautica.test");
        UUID indepServiceId = fixtures.createIndependentMasterService(indepToken, "Lash Extensions");

        // Arrange — separate salon owner
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "sec-owner-del-indep-" + System.nanoTime() + "@beautica.test");

        // Act
        log.debug("Act: DELETE /api/v1/services/{} as SALON_OWNER — IDOR on INDEPENDENT_MASTER service must be blocked",
                indepServiceId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + indepServiceId, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)),
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
        String masterBToken = fixtures.createIndependentMasterAndGetToken(
                "sec-indep-b-" + System.nanoTime() + "@beautica.test");
        UUID masterBServiceId = fixtures.createIndependentMasterService(masterBToken, "Gel Nails");

        // Arrange — master A (different independent master)
        String masterAToken = fixtures.createIndependentMasterAndGetToken(
                "sec-indep-a-" + System.nanoTime() + "@beautica.test");

        // Act
        log.debug("Act: DELETE /api/v1/services/{} as INDEPENDENT_MASTER A — cross-master IDOR must be blocked",
                masterBServiceId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + masterBServiceId, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(masterAToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 403 when INDEPENDENT_MASTER A tries to delete INDEPENDENT_MASTER B's service, serviceDefId=%s",
                        masterBServiceId)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

}
