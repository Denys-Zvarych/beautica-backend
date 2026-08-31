package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.dto.RotateAdminRequest;
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

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private SalonItFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new SalonItFixtures(
                restTemplate, jdbcTemplate, objectMapper, passwordEncoder, this::testCityId);
    }

    @Test
    @DisplayName("200 when SALON_OWNER rotates an admin between two salons they own")
    void should_return200AndMoveSalonId_when_ownerRotatesAdminBetweenOwnedSalons() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-rotate-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerId, "Owner Rotate Salon A");
        UUID salonBId = fixtures.insertSalon(ownerId, "Owner Rotate Salon B");
        UUID adminId = fixtures.insertAdmin("admin-rotated-" + System.nanoTime() + "@beautica.test", salonAId);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: PATCH {} as SALON_OWNER — must succeed",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonBId), fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("owner rotating an admin between owned salons must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(fixtures.readSalonId(adminId))
                .as("rotated admin's salon_id must now point at Salon B")
                .isEqualTo(salonBId);
    }

    @Test
    @DisplayName("200 when SALON_ADMIN rotates themselves from their own salon into another salon of the SAME owner")
    void should_return200_when_adminRotatesOwnSalonToAnotherSalonOfSameOwner() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-admin-rotate-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerId, "Admin Rotate Salon A");
        UUID salonBId = fixtures.insertSalon(ownerId, "Admin Rotate Salon B");
        UUID adminId = fixtures.insertAdmin("admin-self-rotate-" + System.nanoTime() + "@beautica.test", salonAId);
        String adminToken = fixtures.loginAndGetToken(fixtures.emailOf(adminId));

        // Act
        log.debug("Act: PATCH {} as SALON_ADMIN rotating their own assignment — must succeed",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonBId), fixtures.bearerHeaders(adminToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("admin rotating their own salon assignment within the same owner's portfolio must succeed")
                .isEqualTo(HttpStatus.OK);
        assertThat(fixtures.readSalonId(adminId)).isEqualTo(salonBId);
    }

    @Test
    @DisplayName("403 when destination salon belongs to a DIFFERENT owner (cross-owner rotation blocked)")
    void should_return403_when_destinationSalonHasDifferentOwner() throws Exception {
        // Arrange
        UUID ownerAId = fixtures.insertUser("owner-a-xown-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerAId, "Salon A Cross-Owner");
        UUID adminId = fixtures.insertAdmin("admin-xown-" + System.nanoTime() + "@beautica.test", salonAId);
        String ownerAToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerAId));

        UUID ownerBId = fixtures.insertUser("owner-b-xown-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = fixtures.insertSalon(ownerBId, "Salon B Cross-Owner");

        // Act — Owner A attempts to rotate their own admin into Owner B's salon
        log.debug("Act: PATCH {} targeting a salon owned by a DIFFERENT owner — must be denied",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonBId), fixtures.bearerHeaders(ownerAToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("cross-owner rotation must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fixtures.readSalonId(adminId))
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
        UUID ownerId = fixtures.insertUser("owner-nonexistent-dest-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerId, "Nonexistent-Dest Source Salon");
        UUID adminId = fixtures.insertAdmin("admin-nonexistent-dest-" + System.nanoTime() + "@beautica.test", salonAId);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));
        UUID nonexistentSalonId = UUID.randomUUID();

        // Act
        log.debug("Act: PATCH {} targeting a destinationSalonId with NO backing salon row — must be denied",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(nonexistentSalonId), fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert — identical 403 to cross-owner/inactive (Sec LOW-1): a nonexistent destination
        // must not be distinguishable from an existing-but-foreign or existing-but-inactive one.
        assertThat(response.getStatusCode())
                .as("rotation into a destinationSalonId with no backing row must be rejected with "
                        + "403, identical to the cross-owner and inactive-destination denials")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fixtures.readSalonId(adminId))
                .as("admin must be untouched after a rejected nonexistent-destination rotation")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("403 when destination salon is soft-deleted/inactive (not a distinct 400 — Sec LOW-1 status-code oracle fix)")
    void should_return403_when_destinationSalonIsInactive() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-inactive-dest-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerId, "Active Source Salon");
        UUID inactiveSalonId = fixtures.insertSalon(ownerId, "Inactive Destination Salon", false);
        UUID adminId = fixtures.insertAdmin("admin-inactive-dest-" + System.nanoTime() + "@beautica.test", salonAId);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: PATCH {} targeting an INACTIVE destination salon — must be rejected",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(inactiveSalonId), fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert — collapsed to the SAME 403 as cross-owner/not-found (Sec LOW-1): distinguishing
        // "inactive" via a distinct status would let an actor with source-salon authority probe an
        // owner's full salon portfolio, including inactive salons a public endpoint would 404 on.
        assertThat(response.getStatusCode())
                .as("rotation into an inactive/soft-deleted destination must be rejected with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fixtures.readSalonId(adminId))
                .as("admin must be untouched after a rejected inactive-destination rotation")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("400 when destination equals the admin's current salon (no-op rotation)")
    void should_return400_when_destinationEqualsCurrentSalon() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-noop-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "No-Op Rotation Salon");
        UUID adminId = fixtures.insertAdmin("admin-noop-" + System.nanoTime() + "@beautica.test", salonId);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: PATCH {} with destinationSalonId == current salon — must be rejected",
                String.format(ROTATE_ADMIN_URL, salonId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonId), fixtures.bearerHeaders(ownerToken)),
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
        UUID ownerId = fixtures.insertUser("owner-missingbody-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerId, "Missing-Body Salon A");
        UUID adminId = fixtures.insertAdmin("admin-missingbody-" + System.nanoTime() + "@beautica.test", salonAId);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act — send an empty JSON object so destinationSalonId binds to null and the
        // controller's @Valid @NotNull constraint rejects it before any service logic runs.
        log.debug("Act: PATCH {} with an empty JSON body (destinationSalonId omitted) — must fail validation",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>("{}", fixtures.bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("a request body missing destinationSalonId must fail bean validation with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fixtures.readSalonId(adminId))
                .as("admin must be untouched when the request body fails validation")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("403 when SALON_ADMIN attempts to rotate an admin from a DIFFERENT salon (cross-salon IDOR)")
    void should_return403_when_adminRotatesAdminFromDifferentSalon() throws Exception {
        // Arrange
        UUID ownerAId = fixtures.insertUser("owner-a-idor-rot-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerAId, "Salon A IDOR Rotate");
        UUID adminAId = fixtures.insertAdmin("admin-a-idor-rot-" + System.nanoTime() + "@beautica.test", salonAId);
        String adminAToken = fixtures.loginAndGetToken(fixtures.emailOf(adminAId));

        UUID ownerBId = fixtures.insertUser("owner-b-idor-rot-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = fixtures.insertSalon(ownerBId, "Salon B IDOR Rotate");
        UUID salonCId = fixtures.insertSalon(ownerBId, "Salon C IDOR Rotate");
        UUID adminBId = fixtures.insertAdmin("admin-b-idor-rot-" + System.nanoTime() + "@beautica.test", salonBId);

        // Act — Salon A's admin targets Salon B's admin via Salon B's id in the path
        log.debug("Act: PATCH {} as Salon A's admin targeting Salon B's admin — must be denied",
                String.format(ROTATE_ADMIN_URL, salonBId, adminBId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonBId, adminBId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonCId), fixtures.bearerHeaders(adminAToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("cross-salon admin rotation must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fixtures.readSalonId(adminBId))
                .as("Salon B's admin must be untouched")
                .isEqualTo(salonBId);
    }

    @Test
    @DisplayName("401 when no Authorization token is provided")
    void should_return401_when_noTokenProvided() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-notoken-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerId, "No-Token Salon A");
        UUID salonBId = fixtures.insertSalon(ownerId, "No-Token Salon B");
        UUID adminId = fixtures.insertAdmin("admin-notoken-" + System.nanoTime() + "@beautica.test", salonAId);
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
        assertThat(fixtures.readSalonId(adminId))
                .as("admin must be untouched when the request is unauthenticated")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("403 when a CLIENT attempts to rotate an admin")
    void should_return403_when_clientAttemptsToRotateAdmin() throws Exception {
        // Arrange
        UUID ownerId = fixtures.insertUser("owner-client-rot-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = fixtures.insertSalon(ownerId, "Client Denied Salon A");
        UUID salonBId = fixtures.insertSalon(ownerId, "Client Denied Salon B");
        UUID adminId = fixtures.insertAdmin("admin-client-rot-" + System.nanoTime() + "@beautica.test", salonAId);
        UUID clientId = fixtures.insertUser("client-rotate-" + System.nanoTime() + "@beautica.test", "CLIENT");
        String clientToken = fixtures.loginAndGetToken(fixtures.emailOf(clientId));

        // Act
        log.debug("Act: PATCH {} as CLIENT — role guard must deny with 403",
                String.format(ROTATE_ADMIN_URL, salonAId, adminId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonAId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(salonBId), fixtures.bearerHeaders(clientToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("CLIENT must be denied access to rotate-admin")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

}
