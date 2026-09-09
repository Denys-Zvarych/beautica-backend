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
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.dto.UpdateServicePhotoRequest;
import com.beautica.service.entity.PriceType;
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
                "IDOR Service", null, "NAIL_SERVICE", 30, 0,
                PriceType.FIXED, new BigDecimal("100.00"), null, null,
                fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE"));

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

    // ── Phase 306 — SALON_ADMIN parity on service management (negative arms) ───

    @Test
    @DisplayName("Phase 306 case 5: POST /salons/{salonAId}/services — 403 when a SALON_ADMIN of a DIFFERENT salon adds a service; nothing written")
    void should_return403_when_salonAdminOfDifferentSalonAddsService() throws Exception {
        // Arrange
        UUID salonAId = fixtures.insertSalonWithOwner("Owner A Salon (306 admin cross)");
        String adminBToken = fixtures.createSalonAdminAndGetToken(
                fixtures.insertSalonWithOwner("Owner B Salon (306 admin cross)"),
                "p306-adminb-add-" + System.nanoTime() + "@beautica.test");

        var request = new CreateServiceDefinitionRequest(
                "IDOR Service", null, "NAIL_SERVICE", 30, 0,
                PriceType.FIXED, new BigDecimal("100.00"), null, null,
                fixtures.resolveServiceTypeIdForCategory("NAIL_SERVICE"));

        long countBefore = fixtures.countServiceDefinitionsForOwner("SALON", salonAId);

        // Act
        log.debug("Act: POST /api/v1/salons/{}/services as a SALON_ADMIN of a different salon — must be blocked", salonAId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonAId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(adminBToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fixtures.countServiceDefinitionsForOwner("SALON", salonAId))
                .as("cross-salon admin's rejected POST must not have written anything")
                .isEqualTo(countBefore);
    }

    @Test
    @DisplayName("Phase 306 case 5: POST /salons/{salonAId}/masters/{masterAId}/services — 403 when a SALON_ADMIN of a DIFFERENT salon assigns a service; nothing written")
    void should_return403_when_salonAdminOfDifferentSalonAssignsService() throws Exception {
        // Arrange
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "p306-ownera-assign-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = fixtures.createSalon(ownerAToken, "Owner A Salon (306 assign cross)");
        UUID masterAId = fixtures.createSalonMaster(salonAId);
        UUID serviceDefId = fixtures.createServiceDefinition(ownerAToken, salonAId, "Salon A Service (306)");

        String adminBToken = fixtures.createSalonAdminAndGetToken(
                fixtures.insertSalonWithOwner("Owner B Salon (306 assign cross)"),
                "p306-adminb-assign-" + System.nanoTime() + "@beautica.test");

        var request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        // Act
        log.debug("Act: POST /api/v1/salons/{}/masters/{}/services as a SALON_ADMIN of a different salon — must be blocked",
                salonAId, masterAId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonAId + "/masters/" + masterAId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(adminBToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Integer assignmentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND service_def_id = ?",
                Integer.class, masterAId, serviceDefId);
        assertThat(assignmentCount)
                .as("cross-salon admin's rejected assignment must not have written a master_services row")
                .isZero();
    }

    @Test
    @DisplayName("Phase 306 case 5: PATCH /services/{id} — 403 when a SALON_ADMIN of a DIFFERENT salon patches a service; row unchanged")
    void should_return403_when_salonAdminOfDifferentSalonPatchesService() throws Exception {
        // Arrange
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "p306-ownera-patch-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = fixtures.createSalon(ownerAToken, "Owner A Salon (306 patch cross)");
        UUID serviceDefId = fixtures.createServiceDefinition(ownerAToken, salonAId, "Salon A Service (306 patch)");

        String adminBToken = fixtures.createSalonAdminAndGetToken(
                fixtures.insertSalonWithOwner("Owner B Salon (306 patch cross)"),
                "p306-adminb-patch-" + System.nanoTime() + "@beautica.test");

        var patch = new UpdateServiceDefinitionRequest(
                "Hijacked Name", null, null, null, null, null, null, null, null, null);

        // Act
        log.debug("Act: PATCH /api/v1/services/{} as a SALON_ADMIN of a different salon — must be blocked", serviceDefId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.PATCH,
                new HttpEntity<>(patch, fixtures.bearerHeaders(adminBToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM service_definitions WHERE id = ?", String.class, serviceDefId))
                .as("the rejected cross-salon PATCH must not have changed the row")
                .isEqualTo("Salon A Service (306 patch)");
    }

    @Test
    @DisplayName("Phase 306 case 5: PATCH /services/{id}/photo — 403 when a SALON_ADMIN of a DIFFERENT salon sets the photo")
    void should_return403_when_salonAdminOfDifferentSalonSetsServicePhoto() throws Exception {
        // Arrange
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "p306-ownera-photo-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = fixtures.createSalon(ownerAToken, "Owner A Salon (306 photo cross)");
        UUID serviceDefId = fixtures.createServiceDefinition(ownerAToken, salonAId, "Salon A Service (306 photo)");

        String adminBToken = fixtures.createSalonAdminAndGetToken(
                fixtures.insertSalonWithOwner("Owner B Salon (306 photo cross)"),
                "p306-adminb-photo-" + System.nanoTime() + "@beautica.test");

        var photoRequest = new UpdateServicePhotoRequest("https://cdn.beautica.test/hijack.jpg");

        // Act
        log.debug("Act: PATCH /api/v1/services/{}/photo as a SALON_ADMIN of a different salon — must be blocked", serviceDefId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId + "/photo", HttpMethod.PATCH,
                new HttpEntity<>(photoRequest, fixtures.bearerHeaders(adminBToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT photo_url FROM service_definitions WHERE id = ?", String.class, serviceDefId))
                .as("the rejected cross-salon photo PATCH must not have changed the row")
                .isNull();
    }

    @Test
    @DisplayName("Phase 306 case 7 (negative arm): DELETE /services/{id} — 403 when a SALON_ADMIN of a DIFFERENT salon deletes a service; it stays active")
    void should_return403_when_salonAdminOfDifferentSalonDeletesService() throws Exception {
        // Arrange
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "p306-ownera-del-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = fixtures.createSalon(ownerAToken, "Owner A Salon (306 delete cross)");
        UUID serviceDefId = fixtures.createServiceDefinition(ownerAToken, salonAId, "Salon A Service (306 delete)");

        String adminBToken = fixtures.createSalonAdminAndGetToken(
                fixtures.insertSalonWithOwner("Owner B Salon (306 delete cross)"),
                "p306-adminb-del-" + System.nanoTime() + "@beautica.test");

        // Act
        log.debug("Act: DELETE /api/v1/services/{} as a SALON_ADMIN of a different salon — must be blocked", serviceDefId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(adminBToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM service_definitions WHERE id = ?", Boolean.class, serviceDefId))
                .as("the definition must stay active after the rejected cross-salon DELETE")
                .isTrue();
    }

    @Test
    @DisplayName("Phase 306 case 8: PATCH /services/{id} — 403 when SALON_MASTER (read-only role) patches a service (D6 — SALON_MASTER gains nothing)")
    void should_return403_when_salonMasterPatchesService() throws Exception {
        // Arrange
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "p306-owner-master-patch-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "P306 SALON_MASTER Patch Salon");
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, "Salon Service (306 master patch)");
        String masterToken = fixtures.createSalonMasterAndGetToken(
                salonId, "p306-master-patch-" + System.nanoTime() + "@beautica.test");

        var patch = new UpdateServiceDefinitionRequest(
                "Hijacked by master", null, null, null, null, null, null, null, null, null);

        // Act
        log.debug("Act: PATCH /api/v1/services/{} as SALON_MASTER (read-only) — must be blocked", serviceDefId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.PATCH,
                new HttpEntity<>(patch, fixtures.bearerHeaders(masterToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Phase 306 case 8: PATCH /services/{id}/photo — 403 when SALON_MASTER (read-only role) sets the photo (D6)")
    void should_return403_when_salonMasterSetsServicePhoto() throws Exception {
        // Arrange
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "p306-owner-master-photo-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "P306 SALON_MASTER Photo Salon");
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, "Salon Service (306 master photo)");
        String masterToken = fixtures.createSalonMasterAndGetToken(
                salonId, "p306-master-photo-" + System.nanoTime() + "@beautica.test");

        var photoRequest = new UpdateServicePhotoRequest("https://cdn.beautica.test/master-hijack.jpg");

        // Act
        log.debug("Act: PATCH /api/v1/services/{}/photo as SALON_MASTER (read-only) — must be blocked", serviceDefId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId + "/photo", HttpMethod.PATCH,
                new HttpEntity<>(photoRequest, fixtures.bearerHeaders(masterToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Phase 306 case 9: PATCH /services/{id} — 403 when CLIENT patches a service")
    void should_return403_when_clientPatchesService() throws Exception {
        // Arrange
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "p306-owner-client-patch-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "P306 CLIENT Patch Salon");
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, "Salon Service (306 client patch)");
        String clientToken = fixtures.createClientAndGetToken(
                "p306-client-patch-" + System.nanoTime() + "@beautica.test");

        var patch = new UpdateServiceDefinitionRequest(
                "Hijacked by client", null, null, null, null, null, null, null, null, null);

        // Act
        log.debug("Act: PATCH /api/v1/services/{} as CLIENT — must be blocked", serviceDefId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId, HttpMethod.PATCH,
                new HttpEntity<>(patch, fixtures.bearerHeaders(clientToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Phase 306 case 9: PATCH /services/{id}/photo — 403 when CLIENT sets the photo")
    void should_return403_when_clientSetsServicePhoto() throws Exception {
        // Arrange
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "p306-owner-client-photo-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "P306 CLIENT Photo Salon");
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, "Salon Service (306 client photo)");
        String clientToken = fixtures.createClientAndGetToken(
                "p306-client-photo-" + System.nanoTime() + "@beautica.test");

        var photoRequest = new UpdateServicePhotoRequest("https://cdn.beautica.test/client-hijack.jpg");

        // Act
        log.debug("Act: PATCH /api/v1/services/{}/photo as CLIENT — must be blocked", serviceDefId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + serviceDefId + "/photo", HttpMethod.PATCH,
                new HttpEntity<>(photoRequest, fixtures.bearerHeaders(clientToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Phase 306 case 10 (negative arm): PATCH /services/{id} — 403 when INDEPENDENT_MASTER A patches INDEPENDENT_MASTER B's service definition")
    void should_return403_when_independentMasterAPatchesIndependentMasterBService() throws Exception {
        // Arrange — master B creates a service
        String masterBToken = fixtures.createIndependentMasterAndGetToken(
                "p306-indep-b-patch-" + System.nanoTime() + "@beautica.test");
        UUID masterBServiceId = fixtures.createIndependentMasterService(masterBToken, "Master B Service (306)");

        // Arrange — master A (different independent master)
        String masterAToken = fixtures.createIndependentMasterAndGetToken(
                "p306-indep-a-patch-" + System.nanoTime() + "@beautica.test");

        var patch = new UpdateServiceDefinitionRequest(
                "Hijacked by master A", null, null, null, null, null, null, null, null, null);

        // Act
        log.debug("Act: PATCH /api/v1/services/{} as INDEPENDENT_MASTER A — cross-master IDOR must be blocked",
                masterBServiceId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + masterBServiceId, HttpMethod.PATCH,
                new HttpEntity<>(patch, fixtures.bearerHeaders(masterAToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Phase 306 case 11: PATCH /services/{id} — 403 when a SALON_ADMIN edits a definition owned by an INDEPENDENT_MASTER (the SALON and INDEPENDENT_MASTER arms do not leak into each other)")
    void should_return403_when_salonAdminEditsIndependentMasterOwnedService() throws Exception {
        // Arrange — an independent master creates a service
        String indepToken = fixtures.createIndependentMasterAndGetToken(
                "p306-indep-vs-admin-" + System.nanoTime() + "@beautica.test");
        UUID indepServiceId = fixtures.createIndependentMasterService(indepToken, "Independent Master Service (306)");

        // Arrange — an unrelated salon's admin
        String adminToken = fixtures.createSalonAdminAndGetToken(
                fixtures.insertSalonWithOwner("Unrelated Salon (306 admin-vs-indep)"),
                "p306-admin-vs-indep-" + System.nanoTime() + "@beautica.test");

        var patch = new UpdateServiceDefinitionRequest(
                "Hijacked by unrelated admin", null, null, null, null, null, null, null, null, null);

        // Act
        log.debug("Act: PATCH /api/v1/services/{} as an unrelated SALON_ADMIN — arms must not leak into each other",
                indepServiceId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/services/" + indepServiceId, HttpMethod.PATCH,
                new HttpEntity<>(patch, fixtures.bearerHeaders(adminToken)), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

}
