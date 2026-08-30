package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.dto.PendingInviteResponse;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 23.1 — integration tests for {@code GET /api/v1/salons/{salonId}/invites/pending} and
 * {@code DELETE /api/v1/salons/{salonId}/invites/{inviteId}}.
 *
 * <p>Mirrors {@code SalonAdminRemovalIntegrationTest}: real HTTP through {@link TestRestTemplate}
 * against a Testcontainers PostgreSQL instance, fixtures inserted directly via JDBC. Cleanup is
 * handled by {@link AbstractIntegrationTest#cleanDb()} (already deletes {@code invite_tokens}).
 */
@Import(TestSecurityConfig.class)
@DisplayName("SalonController — pending invites endpoints (Phase 23.1)")
class PendingInvitesIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PendingInvitesIntegrationTest.class);

    private static final String PENDING_URL = "/api/v1/salons/%s/invites/pending";
    private static final String CANCEL_URL = "/api/v1/salons/%s/invites/%s";
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

    // ── GET /invites/pending ──────────────────────────────────────────────────

    @Test
    @DisplayName("200 with pending invites when SALON_OWNER lists their own salon")
    void should_return200WithPendingInvites_when_ownerListsOwnSalon() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-list-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Owner Lists Invites Salon");
        insertInviteToken("invitee-" + System.nanoTime() + "@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        // Act
        log.debug("Act: GET {} as SALON_OWNER — must return the pending invite",
                String.format(PENDING_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(PENDING_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("owner listing pending invites for their own salon must return 200")
                .isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<PendingInviteResponse>>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data())
                .as("the one pending invite must be listed, never exposing a token field")
                .hasSize(1);
        assertThat(body.data().get(0).role()).isEqualTo("SALON_MASTER");
    }

    @Test
    @DisplayName("200 with pending invites when SALON_ADMIN lists their own salon")
    void should_return200WithPendingInvites_when_adminListsOwnSalon() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-admin-list-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Admin Lists Invites Salon");
        UUID adminId = insertSalonAdminUser("admin-list-" + System.nanoTime() + "@beautica.test", salonId);
        insertInviteToken("invitee-admin-" + System.nanoTime() + "@beautica.test", salonId, "SALON_ADMIN",
                Instant.now().plus(7, ChronoUnit.DAYS), false);
        String adminToken = loginAndGetToken(emailOf(adminId));

        // Act
        log.debug("Act: GET {} as SALON_ADMIN — must return the pending invite",
                String.format(PENDING_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(PENDING_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(adminToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("admin listing pending invites for their own salon must return 200")
                .isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<PendingInviteResponse>>>() {});
        assertThat(body.data()).hasSize(1);
    }

    @Test
    @DisplayName("403 when SALON_ADMIN from a DIFFERENT salon lists invites (cross-salon IDOR)")
    void should_return403_when_adminFromDifferentSalonListsInvites() throws Exception {
        // Arrange
        UUID ownerAId = insertUser("owner-a-list-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerAId, "Salon A List");
        insertInviteToken("invitee-a-" + System.nanoTime() + "@beautica.test", salonAId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false);

        UUID ownerBId = insertUser("owner-b-list-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = insertSalon(ownerBId, "Salon B List");
        UUID adminBId = insertSalonAdminUser("admin-b-list-" + System.nanoTime() + "@beautica.test", salonBId);
        String adminBToken = loginAndGetToken(emailOf(adminBId));

        // Act — Salon B's admin targets Salon A's invites via Salon A's id in the path
        log.debug("Act: GET {} as Salon B's admin targeting Salon A — must be denied",
                String.format(PENDING_URL, salonAId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(PENDING_URL, salonAId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(adminBToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("cross-salon invite listing must be denied with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("expired and already-used invites are excluded from the pending list")
    void should_excludeExpiredAndUsedTokens_when_listingPendingInvites() throws Exception {
        // Arrange — one genuinely pending invite, one expired, one already used
        UUID ownerId = insertUser("owner-filter-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Filter Invites Salon");
        insertInviteToken("pending-" + System.nanoTime() + "@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), false);
        insertInviteToken("expired-" + System.nanoTime() + "@beautica.test", salonId, "SALON_MASTER",
                Instant.now().minus(1, ChronoUnit.DAYS), false);
        insertInviteToken("used-" + System.nanoTime() + "@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), true);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        // Act
        log.debug("Act: GET {} — expired and used tokens must be excluded",
                String.format(PENDING_URL, salonId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(PENDING_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<PendingInviteResponse>>>() {});
        assertThat(body.data())
                .as("only the non-expired, unused invite must be listed")
                .hasSize(1)
                .allSatisfy(invite -> assertThat(invite.recipientEmail()).startsWith("pending-"));
    }

    @Test
    @DisplayName("403 when a CLIENT attempts to list pending invites")
    void should_return403_when_clientListsPendingInvites() throws Exception {
        UUID ownerId = insertUser("owner-client-list-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Client Denied Salon");
        UUID clientId = insertUser("client-list-" + System.nanoTime() + "@beautica.test", "CLIENT");
        String clientToken = loginAndGetToken(emailOf(clientId));

        ResponseEntity<String> response = restTemplate.exchange(
                String.format(PENDING_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(clientToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT must be denied access to the pending-invites list")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── DELETE /invites/{inviteId} ────────────────────────────────────────────

    @Test
    @DisplayName("204 and marks used when SALON_OWNER cancels a pending invite; second cancel 404s")
    void should_return204AndMarkUsed_when_ownerCancelsPendingInvite_and_404sOnSecondCancel() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-cancel-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Cancel Invite Salon");
        UUID inviteId = insertInviteToken("cancel-target-" + System.nanoTime() + "@beautica.test", salonId,
                "SALON_MASTER", Instant.now().plus(7, ChronoUnit.DAYS), false);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        // Act — first cancel
        log.debug("Act: DELETE {} as SALON_OWNER — must succeed", String.format(CANCEL_URL, salonId, inviteId));
        ResponseEntity<Void> firstResponse = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);

        // Assert — first cancel succeeds and flips is_used
        assertThat(firstResponse.getStatusCode())
                .as("cancelling a pending invite must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readIsUsed(inviteId))
                .as("cancelled invite must be marked used=true, never deleted")
                .isTrue();

        // Act — second cancel of the same (now-used) invite
        log.debug("Act: DELETE {} a second time — must 404", String.format(CANCEL_URL, salonId, inviteId));
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                String.class);

        // Assert — second cancel 404s
        assertThat(secondResponse.getStatusCode())
                .as("cancelling an already-cancelled invite must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("204 when SALON_ADMIN cancels a pending invite for their own salon")
    void should_return204_when_adminCancelsOwnSalonInvite() throws Exception {
        UUID ownerId = insertUser("owner-admin-cancel-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Admin Cancel Salon");
        UUID adminId = insertSalonAdminUser("admin-cancel-" + System.nanoTime() + "@beautica.test", salonId);
        UUID inviteId = insertInviteToken("admin-cancel-target-" + System.nanoTime() + "@beautica.test", salonId,
                "SALON_MASTER", Instant.now().plus(7, ChronoUnit.DAYS), false);
        String adminToken = loginAndGetToken(emailOf(adminId));

        ResponseEntity<Void> response = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(adminToken)),
                Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readIsUsed(inviteId)).isTrue();
    }

    @Test
    @DisplayName("403 when SALON_ADMIN from a DIFFERENT salon cancels an invite (cross-salon IDOR, @PreAuthorize gate)")
    void should_return403_when_adminFromDifferentSalonCancelsInvite() throws Exception {
        // Arrange — salon A's invite, salon B's admin
        UUID ownerAId = insertUser("owner-a-cancel-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerAId, "Salon A Cancel");
        UUID inviteAId = insertInviteToken("invite-a-cancel-" + System.nanoTime() + "@beautica.test", salonAId,
                "SALON_MASTER", Instant.now().plus(7, ChronoUnit.DAYS), false);

        UUID ownerBId = insertUser("owner-b-cancel-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = insertSalon(ownerBId, "Salon B Cancel");
        UUID adminBId = insertSalonAdminUser("admin-b-cancel-" + System.nanoTime() + "@beautica.test", salonBId);
        String adminBToken = loginAndGetToken(emailOf(adminBId));

        // Act — Salon B's admin targets Salon A's invite via Salon A's id in the path
        log.debug("Act: DELETE {} as Salon B's admin targeting Salon A — must be denied",
                String.format(CANCEL_URL, salonAId, inviteAId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(CANCEL_URL, salonAId, inviteAId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(adminBToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("cross-salon invite cancellation must be denied with 403 at the @PreAuthorize gate")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readIsUsed(inviteAId))
                .as("Salon A's invite must be untouched")
                .isFalse();
    }

    @Test
    @DisplayName("404 when cancelling an invite that belongs to a DIFFERENT salon than the path salonId "
            + "(defense-in-depth service-layer check, distinct from the @PreAuthorize gate)")
    void should_return404_when_cancellingInviteFromDifferentSalonViaOwnAuthorizedSalon() throws Exception {
        // Arrange — the actor legitimately manages Salon B (passes @PreAuthorize), but the
        // inviteId in the path belongs to Salon A. The @PreAuthorize gate alone cannot catch
        // this — it only checks the actor's access to the PATH salonId, not whether the invite
        // itself belongs there. SalonService.cancelInvite's defense-in-depth check must 404.
        UUID ownerAId = insertUser("owner-a-mismatch-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonAId = insertSalon(ownerAId, "Salon A Mismatch");
        UUID inviteAId = insertInviteToken("invite-a-mismatch-" + System.nanoTime() + "@beautica.test", salonAId,
                "SALON_MASTER", Instant.now().plus(7, ChronoUnit.DAYS), false);

        UUID ownerBId = insertUser("owner-b-mismatch-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonBId = insertSalon(ownerBId, "Salon B Mismatch");
        String ownerBToken = loginAndGetToken(emailOf(ownerBId));

        // Act — Owner B supplies THEIR OWN salonId (authorized) but Salon A's inviteId
        log.debug("Act: DELETE {} — path salonId is Owner B's own salon, but inviteId belongs to Salon A",
                String.format(CANCEL_URL, salonBId, inviteAId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(CANCEL_URL, salonBId, inviteAId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerBToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("an invite belonging to a different salon than the path salonId must 404, "
                        + "never cancel across salons")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(readIsUsed(inviteAId))
                .as("Salon A's invite must be untouched")
                .isFalse();
    }

    @Test
    @DisplayName("403 when a CLIENT attempts to cancel a pending invite (gap: only the LIST endpoint "
            + "had a CLIENT-denial test before this)")
    void should_return403_when_clientCancelsPendingInvite() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-client-cancel-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Client Cancel Denied Salon");
        UUID inviteId = insertInviteToken("cancel-denied-" + System.nanoTime() + "@beautica.test", salonId,
                "SALON_MASTER", Instant.now().plus(7, ChronoUnit.DAYS), false);
        UUID clientId = insertUser("client-cancel-" + System.nanoTime() + "@beautica.test", "CLIENT");
        String clientToken = loginAndGetToken(emailOf(clientId));

        // Act
        log.debug("Act: DELETE {} as CLIENT — must be denied", String.format(CANCEL_URL, salonId, inviteId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(clientToken)),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("CLIENT must be denied access to the invite-cancellation endpoint")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readIsUsed(inviteId))
                .as("a denied CLIENT must never be able to mutate the invite")
                .isFalse();
    }

    @Test
    @DisplayName("a cancelled invite can no longer be accepted via POST /auth/invite/accept "
            + "(end-to-end, not merely implied by the used-token unit/integration coverage)")
    void should_rejectAcceptOfInvite_when_previouslyCancelledByOwner() throws Exception {
        // Arrange — a genuine pending invite, cancelled by the owner through the real endpoint
        UUID ownerId = insertUser("owner-cancel-then-accept-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Cancel Then Accept Salon");
        String rawToken = "raw-cancel-then-accept-" + UUID.randomUUID();
        UUID inviteId = insertInviteTokenWithRawToken(
                "cancel-then-accept-" + System.nanoTime() + "@beautica.test", salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), rawToken);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        log.debug("Act 1: DELETE {} as SALON_OWNER — cancels the invite before it is ever accepted",
                String.format(CANCEL_URL, salonId, inviteId));
        ResponseEntity<Void> cancelResponse = restTemplate.exchange(
                String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);
        assertThat(cancelResponse.getStatusCode())
                .as("the owner's cancellation must succeed before the accept attempt below")
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Act 2 — the invitee, who never saw the cancellation, tries to accept the now-dead link
        var acceptRequest = new com.beautica.auth.dto.InviteAcceptRequest(
                rawToken, "Str0ngP@ss1!", "Jane", "Doe", "+380501234567");
        log.debug("Act 2: POST /auth/invite/accept with the CANCELLED invite's raw token — must be rejected");
        ResponseEntity<String> acceptResponse = restTemplate.postForEntity(
                "/api/v1/auth/invite/accept", acceptRequest, String.class);

        // Assert — the cancellation is a real security boundary, not merely a hidden list entry
        assertThat(acceptResponse.getStatusCode())
                .as("accepting a cancelled invite must be rejected, never provision an account")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(
                acceptResponse.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE email = (SELECT email FROM invite_tokens WHERE id = ?)",
                        Integer.class, inviteId))
                .as("no account must ever be provisioned from a cancelled invite")
                .isZero();
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
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, name, testCityId());
        return salonId;
    }

    private UUID insertInviteToken(String email, UUID salonId, String role, Instant expiresAt, boolean isUsed) {
        UUID id = UUID.randomUUID();
        String token = "test-invite-token-" + id;
        jdbcTemplate.update(
                "INSERT INTO invite_tokens (id, token, email, salon_id, role, expires_at, is_used, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                id, token, email, salonId, role, java.sql.Timestamp.from(expiresAt), isUsed);
        return id;
    }

    /**
     * Like {@link #insertInviteToken}, but stores the SHA-256 hex digest of {@code rawToken} —
     * matching {@code SecureTokenGenerator#hash} — so the row is genuinely acceptable via
     * {@code POST /auth/invite/accept} (which hashes the caller-supplied raw token before looking
     * it up). {@link #insertInviteToken}'s literal token value is never accepted by that endpoint;
     * this helper exists specifically for tests that exercise the accept flow end-to-end.
     */
    private UUID insertInviteTokenWithRawToken(String email, UUID salonId, String role, Instant expiresAt,
            String rawToken) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO invite_tokens (id, token, email, salon_id, role, expires_at, is_used, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, false, NOW(), NOW())",
                id, sha256Hex(rawToken), email, salonId, role, java.sql.Timestamp.from(expiresAt));
        return id;
    }

    private String sha256Hex(String input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private Boolean readIsUsed(UUID inviteId) {
        return jdbcTemplate.queryForObject("SELECT is_used FROM invite_tokens WHERE id = ?", Boolean.class, inviteId);
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
