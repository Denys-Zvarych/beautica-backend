package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.RotateMasterRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 21.3 — integration tests for {@code PATCH /api/v1/masters/{masterId}/salon} (rotate a
 * SALON_MASTER between two salons owned by the SAME owner).
 *
 * <p>Uses real HTTP through {@link TestRestTemplate} backed by a Testcontainers PostgreSQL
 * instance. Tokens are minted directly via {@link JwtTokenProvider} (no password/login
 * round-trip needed — mirrors {@code MasterScheduleSecurityIT}). All fixture data is inserted
 * directly via JDBC. Cleanup is handled by {@link AbstractIntegrationTest#cleanDb()}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("MasterController.rotateMasterSalon — rotate-master endpoint (Phase 21.3)")
class MasterRotationIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MasterRotationIntegrationTest.class);

    private static final String ROTATE_MASTER_URL = "/api/v1/masters/%s/salon";
    private static final String MASTER_DETAIL_URL = "/api/v1/masters/%s";
    private static final String MASTER_ME_URL = "/api/v1/masters/me";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetRestTemplate() {
        // The Spring test context is cached across test classes with identical configuration
        // (this class and SalonAdminRotationIntegrationTest both @Import(TestSecurityConfig.class)),
        // so when both run in the same JVM the singleton TestRestTemplate bean is SHARED. Plain
        // JDK-backed SimpleClientHttpRequestFactory (the framework default when nothing else
        // configures the factory) does not support PATCH at all on this JDK (HttpURLConnection
        // rejects it with ProtocolException: "Invalid HTTP method: PATCH"), so this class cannot
        // just "reset to the default" — it must pin an explicit factory that (a) actually supports
        // PATCH and (b) is deterministic no matter what a sibling test class wired onto the shared
        // bean beforehand or afterward. Explicitly (re)configure the same Apache HttpClient5-backed
        // factory SalonAdminRotationIntegrationTest uses, in every @BeforeEach, so every test in
        // this class runs against a known, working, freshly-built HTTP client regardless of
        // execution order relative to sibling classes sharing the same context.
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("200 when SALON_OWNER rotates a SALON_MASTER between two salons they own; master-detail cache reflects the new salon immediately")
    void should_return200AndEvictCache_when_ownerRotatesMasterBetweenOwnedSalons() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-master-rotate-" + System.nanoTime() + "@beautica.test", Role.SALON_OWNER);
        UUID salonAId = insertSalon(ownerId, "Master Rotate Salon A");
        UUID salonBId = insertSalon(ownerId, "Master Rotate Salon B");
        UUID masterUserId = insertUser("staff-rotate-" + System.nanoTime() + "@beautica.test", Role.SALON_MASTER);
        UUID masterId = insertMaster(masterUserId, salonAId, "SALON_MASTER");
        String ownerToken = tokenFor(ownerId, "owner@doesnotmatter.test", Role.SALON_OWNER);

        // Warm the master-detail cache with Salon A before rotating.
        ResponseEntity<String> beforeResponse = restTemplate.exchange(
                String.format(MASTER_DETAIL_URL, masterId), HttpMethod.GET, null, String.class);
        assertThat(beforeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readSalonIdFromDetail(beforeResponse.getBody())).isEqualTo(salonAId);

        // Act
        log.debug("Act: PATCH {} as SALON_OWNER — must succeed", String.format(ROTATE_MASTER_URL, masterId));
        ResponseEntity<String> rotateResponse = restTemplate.exchange(
                String.format(ROTATE_MASTER_URL, masterId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateMasterRequest(salonBId), bearerHeaders(ownerToken)),
                String.class);

        // Assert — rotation succeeded and DB reflects the new salon
        assertThat(rotateResponse.getStatusCode())
                .as("owner rotating a master between owned salons must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(readMasterSalonId(masterId)).isEqualTo(salonBId);

        // Assert — the public master-detail cache must NOT serve the stale Salon A after commit;
        // it must reflect Salon B immediately, not after the cache TTL expires.
        ResponseEntity<String> afterResponse = restTemplate.exchange(
                String.format(MASTER_DETAIL_URL, masterId), HttpMethod.GET, null, String.class);
        assertThat(afterResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readSalonIdFromDetail(afterResponse.getBody()))
                .as("master-detail cache must be evicted post-rotation so GET reflects the new salon immediately")
                .isEqualTo(salonBId);
    }

    @Test
    @DisplayName("200 when SALON_OWNER rotates a SALON_MASTER; the rotated master's OWN master-detail-by-user cache (GET /masters/me) reflects the new salon immediately (HIGH fix)")
    void should_evictMasterDetailByUserCache_when_rotatedMasterReadsOwnProfile() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-userkeyed-rotate-" + System.nanoTime() + "@beautica.test", Role.SALON_OWNER);
        UUID salonAId = insertSalon(ownerId, "UserKeyed Rotate Salon A");
        UUID salonBId = insertSalon(ownerId, "UserKeyed Rotate Salon B");
        UUID masterUserId = insertUser("staff-userkeyed-rotate-" + System.nanoTime() + "@beautica.test", Role.SALON_MASTER);
        UUID masterId = insertMaster(masterUserId, salonAId, "SALON_MASTER");
        String ownerToken = tokenFor(ownerId, "owner@doesnotmatter.test", Role.SALON_OWNER);
        String masterToken = tokenFor(masterUserId, "staff@doesnotmatter.test", Role.SALON_MASTER);

        // Warm the master-detail-by-user cache (GET /masters/me, 10-min TTL) with Salon A before
        // rotating — this is the exact userId-keyed cache the HIGH finding says was never evicted.
        ResponseEntity<String> beforeResponse = restTemplate.exchange(
                MASTER_ME_URL, HttpMethod.GET, new HttpEntity<>(bearerHeaders(masterToken)), String.class);
        assertThat(beforeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readSalonIdFromDetail(beforeResponse.getBody())).isEqualTo(salonAId);

        // Act — owner rotates the master to Salon B
        log.debug("Act: PATCH {} as SALON_OWNER — must succeed", String.format(ROTATE_MASTER_URL, masterId));
        ResponseEntity<String> rotateResponse = restTemplate.exchange(
                String.format(ROTATE_MASTER_URL, masterId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateMasterRequest(salonBId), bearerHeaders(ownerToken)),
                String.class);
        assertThat(rotateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Assert — the rotated master's OWN GET /masters/me must NOT serve the stale Salon A for
        // up to the 10-minute TTL; master-detail-by-user must be evicted immediately after commit.
        ResponseEntity<String> afterResponse = restTemplate.exchange(
                MASTER_ME_URL, HttpMethod.GET, new HttpEntity<>(bearerHeaders(masterToken)), String.class);
        assertThat(afterResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readSalonIdFromDetail(afterResponse.getBody()))
                .as("master-detail-by-user cache must be evicted post-rotation so GET /masters/me "
                        + "reflects the new salon immediately, not after the 10-minute TTL expires")
                .isEqualTo(salonBId);
    }

    @Test
    @DisplayName("200 when SALON_ADMIN rotates a master from their own salon into another salon of the SAME owner")
    void should_return200_when_adminRotatesMasterToAnotherSalonOfSameOwner() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-admin-mrotate-" + System.nanoTime() + "@beautica.test", Role.SALON_OWNER);
        UUID salonAId = insertSalon(ownerId, "Admin Master Rotate Salon A");
        UUID salonBId = insertSalon(ownerId, "Admin Master Rotate Salon B");
        UUID adminId = insertSalonAdminUser("admin-mrotate-" + System.nanoTime() + "@beautica.test", salonAId);
        UUID masterUserId = insertUser("staff-admin-mrotate-" + System.nanoTime() + "@beautica.test", Role.SALON_MASTER);
        UUID masterId = insertMaster(masterUserId, salonAId, "SALON_MASTER");
        String adminToken = tokenFor(adminId, "admin@doesnotmatter.test", Role.SALON_ADMIN);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_MASTER_URL, masterId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateMasterRequest(salonBId), bearerHeaders(adminToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("admin rotating a master from their own salon into another salon of the same owner must succeed")
                .isEqualTo(HttpStatus.OK);
        assertThat(readMasterSalonId(masterId)).isEqualTo(salonBId);
    }

    @Test
    @DisplayName("403 when destination salon belongs to a DIFFERENT owner (cross-owner rotation blocked)")
    void should_return403_when_destinationSalonHasDifferentOwner() throws Exception {
        // Arrange
        UUID ownerAId = insertUser("owner-a-mxown-" + System.nanoTime() + "@beautica.test", Role.SALON_OWNER);
        UUID salonAId = insertSalon(ownerAId, "Salon A Master Cross-Owner");
        UUID masterUserId = insertUser("staff-mxown-" + System.nanoTime() + "@beautica.test", Role.SALON_MASTER);
        UUID masterId = insertMaster(masterUserId, salonAId, "SALON_MASTER");
        String ownerAToken = tokenFor(ownerAId, "ownera@doesnotmatter.test", Role.SALON_OWNER);

        UUID ownerBId = insertUser("owner-b-mxown-" + System.nanoTime() + "@beautica.test", Role.SALON_OWNER);
        UUID salonBId = insertSalon(ownerBId, "Salon B Master Cross-Owner");

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_MASTER_URL, masterId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateMasterRequest(salonBId), bearerHeaders(ownerAToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("cross-owner master rotation must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readMasterSalonId(masterId)).isEqualTo(salonAId);
    }

    @Test
    @DisplayName("403 when destinationSalonId resolves to NO salon row at all (nonexistent UUID, distinct from cross-owner-existing and inactive-existing)")
    void should_return403_when_destinationSalonDoesNotExist() throws Exception {
        // Arrange — destSalonId is a fresh random UUID that was never inserted into `salons`,
        // unlike the cross-owner and inactive tests above which target a REAL row. Two security
        // audit passes flagged that only "existing but foreign" and "existing but inactive" were
        // integration-tested, leaving "no row at all" unverified end-to-end even though code
        // inspection shows salonsShareOwner / findById(...).filter(isActive) collapse it into the
        // same branch.
        UUID ownerId = insertUser("owner-mnonexistent-dest-" + System.nanoTime() + "@beautica.test", Role.SALON_OWNER);
        UUID salonAId = insertSalon(ownerId, "Nonexistent-Dest Source Salon For Master");
        UUID masterUserId = insertUser("staff-mnonexistent-" + System.nanoTime() + "@beautica.test", Role.SALON_MASTER);
        UUID masterId = insertMaster(masterUserId, salonAId, "SALON_MASTER");
        String ownerToken = tokenFor(ownerId, "owner@doesnotmatter.test", Role.SALON_OWNER);
        UUID nonexistentSalonId = UUID.randomUUID();

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_MASTER_URL, masterId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateMasterRequest(nonexistentSalonId), bearerHeaders(ownerToken)),
                String.class);

        // Assert — identical 403 to cross-owner/inactive (Sec LOW-1): a nonexistent destination
        // must not be distinguishable from an existing-but-foreign or existing-but-inactive one.
        assertThat(response.getStatusCode())
                .as("rotation into a destinationSalonId with no backing row must be rejected with "
                        + "403, identical to the cross-owner and inactive-destination denials")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readMasterSalonId(masterId))
                .as("master must be untouched after a rejected nonexistent-destination rotation")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("403 when destination salon is soft-deleted/inactive (not a distinct 400 — Sec LOW-1 status-code oracle fix)")
    void should_return403_when_destinationSalonIsInactive() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-minactive-dest-" + System.nanoTime() + "@beautica.test", Role.SALON_OWNER);
        UUID salonAId = insertSalon(ownerId, "Active Source Salon For Master");
        UUID inactiveSalonId = insertInactiveSalon(ownerId, "Inactive Destination Salon For Master");
        UUID masterUserId = insertUser("staff-minactive-" + System.nanoTime() + "@beautica.test", Role.SALON_MASTER);
        UUID masterId = insertMaster(masterUserId, salonAId, "SALON_MASTER");
        String ownerToken = tokenFor(ownerId, "owner@doesnotmatter.test", Role.SALON_OWNER);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_MASTER_URL, masterId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateMasterRequest(inactiveSalonId), bearerHeaders(ownerToken)),
                String.class);

        // Assert — collapsed to the SAME 403 as cross-owner (Sec LOW-1): distinguishing "inactive"
        // via a distinct status would let an actor with source-salon authority probe an owner's
        // full salon portfolio, including inactive salons a public endpoint would 404 on.
        assertThat(response.getStatusCode())
                .as("rotation into an inactive/soft-deleted destination must be rejected with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readMasterSalonId(masterId)).isEqualTo(salonAId);
    }

    @Test
    @DisplayName("400 when destination equals the master's current salon (no-op rotation)")
    void should_return400_when_destinationEqualsCurrentSalon() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-mnoop-" + System.nanoTime() + "@beautica.test", Role.SALON_OWNER);
        UUID salonId = insertSalon(ownerId, "No-Op Master Rotation Salon");
        UUID masterUserId = insertUser("staff-mnoop-" + System.nanoTime() + "@beautica.test", Role.SALON_MASTER);
        UUID masterId = insertMaster(masterUserId, salonId, "SALON_MASTER");
        String ownerToken = tokenFor(ownerId, "owner@doesnotmatter.test", Role.SALON_OWNER);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_MASTER_URL, masterId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateMasterRequest(salonId), bearerHeaders(ownerToken)),
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
        UUID ownerId = insertUser("owner-mmissingbody-" + System.nanoTime() + "@beautica.test", Role.SALON_OWNER);
        UUID salonAId = insertSalon(ownerId, "Missing-Body Salon A For Master");
        UUID masterUserId = insertUser("staff-mmissingbody-" + System.nanoTime() + "@beautica.test", Role.SALON_MASTER);
        UUID masterId = insertMaster(masterUserId, salonAId, "SALON_MASTER");
        String ownerToken = tokenFor(ownerId, "owner@doesnotmatter.test", Role.SALON_OWNER);

        // Act — send an empty JSON object so destinationSalonId binds to null and the
        // controller's @Valid @NotNull constraint rejects it before any service logic runs.
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_MASTER_URL, masterId), HttpMethod.PATCH,
                new HttpEntity<>("{}", bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("a request body missing destinationSalonId must fail bean validation with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readMasterSalonId(masterId))
                .as("master must be untouched when the request body fails validation")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("403 when rotating an INDEPENDENT_MASTER (no salon to rotate from)")
    void should_return403_when_masterIsIndependent() throws Exception {
        // Arrange
        UUID masterUserId = insertUser("indep-mrotate-" + System.nanoTime() + "@beautica.test", Role.INDEPENDENT_MASTER);
        UUID masterId = insertMaster(masterUserId, null, "INDEPENDENT_MASTER");
        UUID ownerId = insertUser("owner-indep-dest-" + System.nanoTime() + "@beautica.test", Role.SALON_OWNER);
        UUID destSalonId = insertSalon(ownerId, "Destination For Independent Master");
        // The actor themselves is the independent master (self-management path in canManageMaster).
        String masterToken = tokenFor(masterUserId, "indep@doesnotmatter.test", Role.INDEPENDENT_MASTER);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_MASTER_URL, masterId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateMasterRequest(destSalonId), bearerHeaders(masterToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("an INDEPENDENT_MASTER has no salon to rotate from — must be rejected with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("401 when no Authorization token is provided")
    void should_return401_when_noTokenProvided() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-mnotoken-" + System.nanoTime() + "@beautica.test", Role.SALON_OWNER);
        UUID salonAId = insertSalon(ownerId, "No-Token Salon A For Master");
        UUID salonBId = insertSalon(ownerId, "No-Token Salon B For Master");
        UUID masterUserId = insertUser("staff-mnotoken-" + System.nanoTime() + "@beautica.test", Role.SALON_MASTER);
        UUID masterId = insertMaster(masterUserId, salonAId, "SALON_MASTER");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_MASTER_URL, masterId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateMasterRequest(salonBId), headers),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("an unauthenticated request must be rejected with 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(readMasterSalonId(masterId))
                .as("master must be untouched when the request is unauthenticated")
                .isEqualTo(salonAId);
    }

    @Test
    @DisplayName("403 when rotating the owner's own SALON_OWNER-type master row")
    void should_return403_when_masterIsOwnerType() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-ownertype-" + System.nanoTime() + "@beautica.test", Role.SALON_OWNER);
        UUID salonAId = insertSalon(ownerId, "Owner Master Salon A");
        UUID salonBId = insertSalon(ownerId, "Owner Master Salon B");
        UUID masterId = insertMaster(ownerId, salonAId, "SALON_OWNER");
        String ownerToken = tokenFor(ownerId, "owner@doesnotmatter.test", Role.SALON_OWNER);

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ROTATE_MASTER_URL, masterId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateMasterRequest(salonBId), bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("the owner's own SALON_OWNER-type master row must not be rotatable via this endpoint")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID insertUser(String email, Role role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', ?, 'Fn', 'Ln', true, true)",
                id, email, role.name());
        return id;
    }

    private UUID insertSalonAdminUser(String email, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'SALON_ADMIN', ?, 'Fn', 'Ln', true, true)",
                id, email, salonId);
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

    private UUID insertMaster(UUID userId, UUID salonId, String masterType) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, review_count, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, true, NOW(), NOW())",
                masterId, userId, salonId, masterType);
        return masterId;
    }

    private UUID readMasterSalonId(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT salon_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private UUID readSalonIdFromDetail(String body) throws Exception {
        var response = objectMapper.readValue(body, new TypeReference<ApiResponse<MasterDetailResponse>>() {
        });
        return response.data().salon() != null ? response.data().salon().id() : null;
    }

    private String tokenFor(UUID userId, String email, Role role) {
        return jwtTokenProvider.generateAccessToken(userId, email, role);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
