package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.dto.RotateAdminRequest;
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
 * Phase 21.3 — integration tests for {@code PATCH /api/v1/salons/{salonId}/admins/{userId}/salon}
 * (rotate a SALON_ADMIN between two salons owned by the SAME owner).
 *
 * <p>Uses real HTTP through {@link TestRestTemplate} backed by a Testcontainers PostgreSQL
 * instance. All fixture data is inserted directly via JDBC. Cleanup is handled by
 * {@link AbstractIntegrationTest#cleanDb()}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("SalonController.rotateAdmin — rotate-admin endpoint (Phase 21.3)")
class SalonAdminRotationIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SalonAdminRotationIntegrationTest.class);

    private static final String ROTATE_ADMIN_URL = "/api/v1/salons/%s/admins/%s/salon";
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
    @DisplayName("200 when SALON_OWNER rotates an admin between two salons they own")
    void should_return200AndMoveSalonId_when_ownerRotatesAdminBetweenOwnedSalons() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-rotate-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerId, "Owner Rotate Salon A");
        UUID salonBId = insertSalon(ownerId, "Owner Rotate Salon B");
        UUID adminId = insertSalonAdminUser("admin-rotated-" + System.nanoTime() + "@beautica.test", salonAId);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        // Act
        log.debug("Act: PATCH {} as SALON_OWNER — must succeed",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonBId), bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("owner rotating an admin between owned salons must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(readSalonId(adminId))
                .as("rotated admin's salon_id must now point at Salon B")
                .isEqualTo(salonBId);
    }

    @Test
    @DisplayName("200 when SALON_ADMIN rotates themselves from their own salon into another salon of the SAME owner")
    void should_return200_when_adminRotatesOwnSalonToAnotherSalonOfSameOwner() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-admin-rotate-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerId, "Admin Rotate Salon A");
        UUID salonBId = insertSalon(ownerId, "Admin Rotate Salon B");
        UUID adminId = insertSalonAdminUser("admin-self-rotate-" + System.nanoTime() + "@beautica.test", salonAId);
        String adminToken = loginAndGetToken(emailOf(adminId));

        // Act
        log.debug("Act: PATCH {} as SALON_ADMIN rotating their own assignment — must succeed",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonBId), bearerHeaders(adminToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("admin rotating their own salon assignment within the same owner's portfolio must succeed")
                .isEqualTo(HttpStatus.OK);
        assertThat(readSalonId(adminId)).isEqualTo(salonBId);
    }

    @Test
    @DisplayName("403 when destination salon belongs to a DIFFERENT owner (cross-owner rotation blocked)")
    void should_return403_when_destinationSalonHasDifferentOwner() throws Exception {
        // Arrange
        UUID ownerAId = insertUser("owner-a-xown-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerAId, "Salon A Cross-Owner");
        UUID adminId = insertSalonAdminUser("admin-xown-" + System.nanoTime() + "@beautica.test", salonAId);
        String ownerAToken = loginAndGetToken(emailOf(ownerAId));

        UUID ownerBId = insertUser("owner-b-xown-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = insertSalon(ownerBId, "Salon B Cross-Owner");

        // Act — Owner A attempts to rotate their own admin into Owner B's salon
        log.debug("Act: PATCH {} targeting a salon owned by a DIFFERENT owner — must be denied",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonBId), bearerHeaders(ownerAToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("cross-owner rotation must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readSalonId(adminId))
                .as("admin must be untouched after a rejected cross-owner rotation")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("403 when destinationSalonId resolves to NO salon row at all (nonexistent UUID, distinct from cross-owner-existing and inactive-existing)")
    void should_return403_when_destinationSalonDoesNotExist() throws Exception {
        // Arrange — destSalonId is a fresh random UUID that was never inserted into `salons`,
        // unlike the cross-owner and inactive tests above which target a REAL row. Two security
        // audit passes flagged that only "existing but foreign" and "existing but inactive" were
        // integration-tested, leaving "no row at all" unverified end-to-end even though code
        // inspection shows salonsShareOwner collapses it into the same branch.
        UUID ownerId = insertUser("owner-nonexistent-dest-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerId, "Nonexistent-Dest Source Salon");
        UUID adminId = insertSalonAdminUser("admin-nonexistent-dest-" + System.nanoTime() + "@beautica.test", salonAId);
        String ownerToken = loginAndGetToken(emailOf(ownerId));
        UUID nonexistentSalonId = UUID.randomUUID();

        // Act
        log.debug("Act: PATCH {} targeting a destinationSalonId with NO backing salon row — must be denied",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(nonexistentSalonId), bearerHeaders(ownerToken)),
                String.class);

        // Assert — identical 403 to cross-owner/inactive (Sec LOW-1): a nonexistent destination
        // must not be distinguishable from an existing-but-foreign or existing-but-inactive one.
        assertThat(response.getStatusCode())
                .as("rotation into a destinationSalonId with no backing row must be rejected with "
                        + "403, identical to the cross-owner and inactive-destination denials")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readSalonId(adminId))
                .as("admin must be untouched after a rejected nonexistent-destination rotation")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("403 when destination salon is soft-deleted/inactive (not a distinct 400 — Sec LOW-1 status-code oracle fix)")
    void should_return403_when_destinationSalonIsInactive() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-inactive-dest-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerId, "Active Source Salon");
        UUID inactiveSalonId = insertInactiveSalon(ownerId, "Inactive Destination Salon");
        UUID adminId = insertSalonAdminUser("admin-inactive-dest-" + System.nanoTime() + "@beautica.test", salonAId);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        // Act
        log.debug("Act: PATCH {} targeting an INACTIVE destination salon — must be rejected",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(inactiveSalonId), bearerHeaders(ownerToken)),
                String.class);

        // Assert — collapsed to the SAME 403 as cross-owner/not-found (Sec LOW-1): distinguishing
        // "inactive" via a distinct status would let an actor with source-salon authority probe an
        // owner's full salon portfolio, including inactive salons a public endpoint would 404 on.
        assertThat(response.getStatusCode())
                .as("rotation into an inactive/soft-deleted destination must be rejected with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readSalonId(adminId))
                .as("admin must be untouched after a rejected inactive-destination rotation")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("400 when destination equals the admin's current salon (no-op rotation)")
    void should_return400_when_destinationEqualsCurrentSalon() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-noop-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "No-Op Rotation Salon");
        UUID adminId = insertSalonAdminUser("admin-noop-" + System.nanoTime() + "@beautica.test", salonId);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        // Act
        log.debug("Act: PATCH {} with destinationSalonId == current salon — must be rejected",
                String.format(ROTATE_ADMIN_URL, salonId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonId), bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("a same-salon no-op rotation must be rejected with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("400 when request body omits destinationSalonId (bean-validation @NotNull)")
    void should_return400_when_requestBodyMissingDestinationSalonId() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-missingbody-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerId, "Missing-Body Salon A");
        UUID adminId = insertSalonAdminUser("admin-missingbody-" + System.nanoTime() + "@beautica.test", salonAId);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        // Act — send an empty JSON object so destinationSalonId binds to null and the
        // controller's @Valid @NotNull constraint rejects it before any service logic runs.
        log.debug("Act: PATCH {} with an empty JSON body (destinationSalonId omitted) — must fail validation",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>("{}", bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("a request body missing destinationSalonId must fail bean validation with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readSalonId(adminId))
                .as("admin must be untouched when the request body fails validation")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("403 when SALON_ADMIN attempts to rotate an admin from a DIFFERENT salon (cross-salon IDOR)")
    void should_return403_when_adminRotatesAdminFromDifferentSalon() throws Exception {
        // Arrange
        UUID ownerAId = insertUser("owner-a-idor-rot-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerAId, "Salon A IDOR Rotate");
        UUID adminAId = insertSalonAdminUser("admin-a-idor-rot-" + System.nanoTime() + "@beautica.test", salonAId);
        String adminAToken = loginAndGetToken(emailOf(adminAId));

        UUID ownerBId = insertUser("owner-b-idor-rot-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = insertSalon(ownerBId, "Salon B IDOR Rotate");
        UUID salonCId = insertSalon(ownerBId, "Salon C IDOR Rotate");
        UUID adminBId = insertSalonAdminUser("admin-b-idor-rot-" + System.nanoTime() + "@beautica.test", salonBId);

        // Act — Salon A's admin targets Salon B's admin via Salon B's id in the path
        log.debug("Act: PATCH {} as Salon A's admin targeting Salon B's admin — must be denied",
                String.format(ROTATE_ADMIN_URL, salonBId, adminBId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonBId, adminBId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonCId), bearerHeaders(adminAToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("cross-salon admin rotation must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readSalonId(adminBId))
                .as("Salon B's admin must be untouched")
                .isEqualTo(salonBId);
    }

    @Test
    @DisplayName("401 when no Authorization token is provided")
    void should_return401_when_noTokenProvided() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-notoken-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerId, "No-Token Salon A");
        UUID salonBId = insertSalon(ownerId, "No-Token Salon B");
        UUID adminId = insertSalonAdminUser("admin-notoken-" + System.nanoTime() + "@beautica.test", salonAId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Act
        log.debug("Act: PATCH {} with no Authorization header — must be rejected before reaching the controller",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonBId), headers),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("an unauthenticated request must be rejected with 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(readSalonId(adminId))
                .as("admin must be untouched when the request is unauthenticated")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("403 when a CLIENT attempts to rotate an admin")
    void should_return403_when_clientAttemptsToRotateAdmin() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-client-rot-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerId, "Client Denied Salon A");
        UUID salonBId = insertSalon(ownerId, "Client Denied Salon B");
        UUID adminId = insertSalonAdminUser("admin-client-rot-" + System.nanoTime() + "@beautica.test", salonAId);
        UUID clientId = insertUser("client-rotate-" + System.nanoTime() + "@beautica.test", "CLIENT");
        String clientToken = loginAndGetToken(emailOf(clientId));

        // Act
        log.debug("Act: PATCH {} as CLIENT — role guard must deny with 403",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonBId), bearerHeaders(clientToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("CLIENT must be denied access to rotate-admin")
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

    private UUID insertInactiveSalon(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, false, NOW(), NOW())",
                salonId, ownerId, name);
        return salonId;
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private UUID readSalonId(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT salon_id FROM users WHERE id = ?", UUID.class, userId);
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
