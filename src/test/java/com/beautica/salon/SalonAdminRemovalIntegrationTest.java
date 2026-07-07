package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 21.2 — integration tests for {@code DELETE /api/v1/salons/{salonId}/admins/{userId}}.
 *
 * <p>Uses real HTTP through {@link TestRestTemplate} backed by a Testcontainers PostgreSQL
 * instance. All fixture data is inserted directly via JDBC (fast, avoids exercising unrelated
 * registration/invite flows). Cleanup is handled by {@link AbstractIntegrationTest#cleanDb()}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("SalonController.removeAdmin — remove-admin endpoint (Phase 21.2)")
class SalonAdminRemovalIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SalonAdminRemovalIntegrationTest.class);

    private static final String REMOVE_ADMIN_URL = "/api/v1/salons/%s/admins/%s";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("204 when SALON_OWNER removes an admin from a salon they own; salon_id nulled, role/is_active unchanged")
    void should_return204AndClearSalonId_when_ownerRemovesAdminFromOwnedSalon() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-remove-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Owner Removes Admin Salon");
        UUID adminId = insertSalonAdminUser("admin-removed-" + System.nanoTime() + "@beautica.test", salonId);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        // Act
        log.debug("Act: DELETE {} as SALON_OWNER — must succeed", String.format(REMOVE_ADMIN_URL, salonId, adminId));
        ResponseEntity<Void> response = restTemplate.exchange(
                String.format(REMOVE_ADMIN_URL, salonId, adminId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("owner removing an admin from their own salon must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(readSalonId(adminId))
                .as("removed admin's salon_id must be null after removal")
                .isNull();
        assertThat(readRole(adminId))
                .as("removed admin's role must remain SALON_ADMIN — this is an unassignment, not a deactivation")
                .isEqualTo("SALON_ADMIN");
        assertThat(readIsActive(adminId))
                .as("removed admin's is_active must remain true")
                .isTrue();
    }

    @Test
    @DisplayName("204 when SALON_ADMIN removes another admin from their own salon (peer removal, one admin remains)")
    void should_return204_when_adminRemovesAnotherAdminFromOwnSalon() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-peer-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Peer Admin Removal Salon");
        UUID actingAdminId = insertSalonAdminUser("admin-acting-" + System.nanoTime() + "@beautica.test", salonId);
        UUID targetAdminId = insertSalonAdminUser("admin-target-" + System.nanoTime() + "@beautica.test", salonId);
        String actingAdminToken = loginAndGetToken(emailOf(actingAdminId));

        // Act
        log.debug("Act: DELETE {} as SALON_ADMIN removing a peer admin at the same salon — must succeed",
                String.format(REMOVE_ADMIN_URL, salonId, targetAdminId));
        ResponseEntity<Void> response = restTemplate.exchange(
                String.format(REMOVE_ADMIN_URL, salonId, targetAdminId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(actingAdminToken)),
                Void.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("admin removing a peer admin at their own salon must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readSalonId(targetAdminId))
                .as("removed admin's salon_id must be null after removal")
                .isNull();
    }

    @Test
    @DisplayName("204 when SALON_OWNER removes the LAST remaining admin, leaving the salon with zero admins (deliberate no-guard design)")
    void should_return204AndLeaveZeroAdmins_when_ownerRemovesTheOnlyRemainingAdmin() throws Exception {
        // Arrange — exactly ONE admin at this salon, no second admin/peer in the fixture.
        // This is the literal scenario the Javadoc on SalonService.removeAdmin calls out:
        // "Removing the last admin from a salon is intentionally unguarded." Self-removal is
        // blocked (see should_return403_when_adminAttemptsSelfRemoval), so the only actor who can
        // legally push a salon's admin count from 1 to 0 is the owner — never the sole admin
        // themselves.
        UUID ownerId = insertUser("owner-last-admin-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Last Admin Salon");
        UUID onlyAdminId = insertSalonAdminUser("admin-only-" + System.nanoTime() + "@beautica.test", salonId);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        assertThat(countActiveAdminsForSalon(salonId))
                .as("fixture precondition — exactly one admin must be assigned before removal")
                .isEqualTo(1);

        // Act
        log.debug("Act: DELETE {} as SALON_OWNER removing the ONLY admin — must succeed, no admin-count guard",
                String.format(REMOVE_ADMIN_URL, salonId, onlyAdminId));
        ResponseEntity<Void> response = restTemplate.exchange(
                String.format(REMOVE_ADMIN_URL, salonId, onlyAdminId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("removing the last admin must be ALLOWED — there is no minimum-admin-count guard")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readSalonId(onlyAdminId))
                .as("the removed admin's salon_id must be null")
                .isNull();
        assertThat(countActiveAdminsForSalon(salonId))
                .as("the salon must be left with ZERO admins — this is the deliberate, documented behaviour, "
                        + "not a bug; a future regression that adds a last-admin guard must fail this assertion")
                .isZero();
        assertThat(isSalonActive(salonId))
                .as("the salon itself must remain active/untouched — losing its last admin does not "
                        + "deactivate or otherwise cascade onto the salon row")
                .isTrue();
    }

    @Test
    @DisplayName("403 when SALON_ADMIN attempts to remove an admin from a DIFFERENT salon (cross-salon IDOR)")
    void should_return403_when_adminRemovesAdminFromDifferentSalon() throws Exception {
        // Arrange — salon A with its own admin, salon B with an unrelated admin
        UUID ownerAId = insertUser("owner-a-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerAId, "Salon A");
        UUID adminAId = insertSalonAdminUser("admin-a-" + System.nanoTime() + "@beautica.test", salonAId);
        String adminAToken = loginAndGetToken(emailOf(adminAId));

        UUID ownerBId = insertUser("owner-b-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = insertSalon(ownerBId, "Salon B");
        UUID adminBId = insertSalonAdminUser("admin-b-" + System.nanoTime() + "@beautica.test", salonBId);

        // Act — Salon A's admin targets Salon B's admin via Salon B's id in the path
        log.debug("Act: DELETE {} as Salon A's admin targeting Salon B — must be denied",
                String.format(REMOVE_ADMIN_URL, salonBId, adminBId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(REMOVE_ADMIN_URL, salonBId, adminBId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(adminAToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("cross-salon admin removal must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readSalonId(adminBId))
                .as("Salon B's admin must be untouched")
                .isEqualTo(salonBId);
    }

    @Test
    @DisplayName("403 when SALON_OWNER attempts to remove an admin from a salon they do NOT own (cross-owner IDOR)")
    void should_return403_when_ownerRemovesAdminFromSalonTheyDoNotOwn() throws Exception {
        // Arrange
        UUID ownerAId = insertUser("owner-a-idor-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerAId, "Salon A IDOR");
        UUID adminAId = insertSalonAdminUser("admin-a-idor-" + System.nanoTime() + "@beautica.test", salonAId);

        UUID ownerBId = insertUser("owner-b-idor-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        insertSalon(ownerBId, "Salon B IDOR");
        String ownerBToken = loginAndGetToken(emailOf(ownerBId));

        // Act — Owner B targets Salon A's admin
        log.debug("Act: DELETE {} as Owner B targeting Salon A's admin — must be denied",
                String.format(REMOVE_ADMIN_URL, salonAId, adminAId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(REMOVE_ADMIN_URL, salonAId, adminAId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerBToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("owner acting on a salon they do not own must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readSalonId(adminAId))
                .as("Salon A's admin must be untouched")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("403 when a SALON_ADMIN attempts to remove themselves")
    void should_return403_when_adminAttemptsSelfRemoval() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-self-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Self Removal Salon");
        UUID adminId = insertSalonAdminUser("admin-self-" + System.nanoTime() + "@beautica.test", salonId);
        String adminToken = loginAndGetToken(emailOf(adminId));

        // Act
        log.debug("Act: DELETE {} — admin targeting themselves — must be denied",
                String.format(REMOVE_ADMIN_URL, salonId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(REMOVE_ADMIN_URL, salonId, adminId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(adminToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("self-removal must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readSalonId(adminId))
                .as("admin attempting self-removal must be untouched")
                .isEqualTo(salonId);
    }

    @Test
    @DisplayName("403 when a CLIENT attempts to remove an admin")
    void should_return403_when_clientAttemptsToRemoveAdmin() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-client-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Client Denied Salon");
        UUID adminId = insertSalonAdminUser("admin-client-" + System.nanoTime() + "@beautica.test", salonId);
        UUID clientId = insertUser("client-remove-" + System.nanoTime() + "@beautica.test", "CLIENT");
        String clientToken = loginAndGetToken(emailOf(clientId));

        // Act
        log.debug("Act: DELETE {} as CLIENT — role guard must deny with 403",
                String.format(REMOVE_ADMIN_URL, salonId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(REMOVE_ADMIN_URL, salonId, adminId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(clientToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("CLIENT must be denied access to remove-admin")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID insertUser(String email, String role) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, ?, true, true)",
                id, email, hash, role);
        return id;
    }

    private UUID insertSalonAdminUser(String email, UUID salonId) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, 'SALON_ADMIN', ?, true, true)",
                id, email, hash, salonId);
        return id;
    }

    private UUID insertSalon(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, name);
        return salonId;
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private UUID readSalonId(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT salon_id FROM users WHERE id = ?", UUID.class, userId);
    }

    private String readRole(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId);
    }

    private Boolean readIsActive(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, userId);
    }

    private Integer countActiveAdminsForSalon(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE salon_id = ? AND role = 'SALON_ADMIN'", Integer.class, salonId);
    }

    private Boolean isSalonActive(UUID salonId) {
        return jdbcTemplate.queryForObject("SELECT is_active FROM salons WHERE id = ?", Boolean.class, salonId);
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
