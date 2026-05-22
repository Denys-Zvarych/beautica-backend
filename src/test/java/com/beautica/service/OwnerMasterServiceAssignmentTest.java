package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
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
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("Owner-as-Master — service assignment integration (Phase 12.5)")
class OwnerMasterServiceAssignmentTest extends AbstractIntegrationTest {

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

    // No @AfterEach — AbstractIntegrationTest.cleanDb() handles all table cleanup in FK order.

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Calls the Phase 12.4 endpoint to enable the owner-as-master row for the given salon
     * and returns the resulting master UUID.
     */
    private UUID enableOwnerMaster(String ownerToken, UUID salonId) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/master", HttpMethod.POST,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<MasterDetailResponse>>() {});
        return body.data().masterId();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("201 when SALON_OWNER assigns a service to their own owner-operated master row")
    void should_assignService_when_ownerAssignsToOwnMasterRow() throws Exception {
        // Arrange
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-master-assign-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Owner Master Salon");
        UUID masterId = enableOwnerMaster(ownerToken, salonId);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, "Haircut");

        var assignRequest = new AssignServiceToMasterRequest(serviceDefId, null, null);

        // Act
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services",
                HttpMethod.POST,
                new HttpEntity<>(assignRequest, fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(resp.getStatusCode())
                .as("assigning a service to the owner's own master row must return 201")
                .isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<MasterServiceResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().masterId())
                .as("response masterId must match the owner-master row")
                .isEqualTo(masterId);
        assertThat(body.data().isActive()).isTrue();

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ?",
                Integer.class, masterId);
        assertThat(rowCount)
                .as("exactly one master_services row must be persisted for this master")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("403 when a SALON_OWNER attempts to assign a service to a master belonging to another salon")
    void should_return403_when_ownerAssignsToMasterInUnownedSalon() throws Exception {
        // Arrange — owner A
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "owner-a-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = fixtures.createSalon(ownerAToken, "Salon A");

        // Arrange — owner B with their own master and service definition
        String ownerBToken = fixtures.createSalonOwnerAndGetToken(
                "owner-b-" + System.nanoTime() + "@beautica.test");
        UUID salonBId = fixtures.createSalon(ownerBToken, "Salon B");
        UUID masterIdInSalonB = enableOwnerMaster(ownerBToken, salonBId);
        UUID serviceDefInSalonB = fixtures.createServiceDefinition(ownerBToken, salonBId, "Pedicure");

        var assignRequest = new AssignServiceToMasterRequest(serviceDefInSalonB, null, null);

        // Act — owner A tries to assign into salon B's master (which owner A does not own)
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonBId + "/masters/" + masterIdInSalonB + "/services",
                HttpMethod.POST,
                new HttpEntity<>(assignRequest, fixtures.bearerHeaders(ownerAToken)),
                String.class);

        // Assert
        assertThat(resp.getStatusCode())
                .as("owner A must not be allowed to manage master rows in salon B (expected 403)")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("200 public GET returns the owner-master's assigned service without authentication")
    void should_listOwnerMasterServices_publicly() throws Exception {
        // Arrange
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-public-svc-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Public Services Salon");
        UUID masterId = enableOwnerMaster(ownerToken, salonId);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, "Threading");

        var assignRequest = new AssignServiceToMasterRequest(serviceDefId, null, null);
        ResponseEntity<String> assignResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services",
                HttpMethod.POST,
                new HttpEntity<>(assignRequest, fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(assignResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Act — unauthenticated public read
        ResponseEntity<String> getResp = restTemplate.getForEntity(
                "/api/v1/masters/" + masterId + "/services", String.class);

        // Assert
        assertThat(getResp.getStatusCode())
                .as("public GET /masters/{id}/services must return 200 without any auth header")
                .isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                getResp.getBody(), new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data())
                .as("exactly one service must be returned for the owner-master")
                .hasSize(1);
        assertThat(body.data().get(0).masterId())
                .as("returned service entry must carry the correct masterId")
                .isEqualTo(masterId);
    }

    @Test
    @DisplayName("409 when the same service is assigned twice to the owner-master row")
    void should_rejectDuplicateAssignment_forOwnerMaster() throws Exception {
        // Arrange
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-dup-assign-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Duplicate Assign Salon");
        UUID masterId = enableOwnerMaster(ownerToken, salonId);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, "Waxing");

        var assignRequest = new AssignServiceToMasterRequest(serviceDefId, null, null);

        // First assignment — must succeed
        ResponseEntity<String> firstResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services",
                HttpMethod.POST,
                new HttpEntity<>(assignRequest, fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(firstResp.getStatusCode())
                .as("first assignment to owner-master must succeed with 201")
                .isEqualTo(HttpStatus.CREATED);

        // Act — duplicate assignment
        ResponseEntity<String> secondResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services",
                HttpMethod.POST,
                new HttpEntity<>(assignRequest, fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(secondResp.getStatusCode())
                .as("second identical assignment to the same owner-master row must return 409 CONFLICT")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("403 when SALON_OWNER assigns a service definition owned by another salon to their own master row")
    void should_return403_when_ownerAssignsCrossServiceDef() throws Exception {
        // Arrange — owner A has their own salon, master row
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "owner-a-svcdef-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = fixtures.createSalon(ownerAToken, "Salon A SvcDef");
        UUID masterAId = enableOwnerMaster(ownerAToken, salonAId);

        // Arrange — owner B creates a service definition in their own salon
        String ownerBToken = fixtures.createSalonOwnerAndGetToken(
                "owner-b-svcdef-" + System.nanoTime() + "@beautica.test");
        UUID salonBId = fixtures.createSalon(ownerBToken, "Salon B SvcDef");
        UUID serviceDefInSalonB = fixtures.createServiceDefinition(ownerBToken, salonBId, "BotoxInject");

        // Act — owner A uses their own valid salonAId + masterAId but injects salonB's serviceDefId
        var assignRequest = new AssignServiceToMasterRequest(serviceDefInSalonB, null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonAId + "/masters/" + masterAId + "/services",
                HttpMethod.POST,
                new HttpEntity<>(assignRequest, fixtures.bearerHeaders(ownerAToken)),
                String.class);

        // Assert
        assertThat(resp.getStatusCode())
                .as("injecting another salon's service-def into own master must return 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
