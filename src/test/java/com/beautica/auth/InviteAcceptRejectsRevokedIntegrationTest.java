package com.beautica.auth;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InviteErrorResponse;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.InviteTokenException;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the accept-path revocation checks (B6) in {@link InviteService#previewInvite} and
 * {@link InviteService#acceptInvite}.
 *
 * <p><strong>Why the fixtures carry a FUTURE {@code expires_at}.</strong> Both methods already
 * reject a token that is {@code is_used} or past its expiry, and those two checks run BEFORE the
 * new {@code revokedAt != null} guard. A SUPERSEDED row produced by the ordinary re-invite flow is
 * always ALSO expired, so it would be rejected by the pre-existing expiry check and this class
 * would pass with the B6 guard deleted — coverage theatre. Seeding a revoked-but-unexpired row is
 * the only shape in which the new guard is the ONLY thing standing between the caller and a
 * provisioned account, which is exactly the defence-in-depth property B6 claims: a clock skew, or
 * any future path that retires a still-live token, must not resurrect it.
 *
 * <p>{@code SUPERSEDED} is the load-bearing case ({@code is_used} stays false, so nothing else
 * catches it). {@code CANCELLED} is included as the belt-and-braces half: it is caught by the
 * {@code isUsed()} check first, and the assertion here is that adding the revocation marker did not
 * weaken that older boundary.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Invite preview/accept — a revoked token is dead regardless of what retired it")
class InviteAcceptRejectsRevokedIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(InviteAcceptRejectsRevokedIntegrationTest.class);

    private static final String ACCEPT_URL = "/api/v1/auth/invite/accept";
    private static final String VALIDATE_URL = "/api/v1/auth/invite/validate?token=%s";
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
    @DisplayName("POST /auth/invite/accept — 400 and NO account for a SUPERSEDED token whose "
            + "expires_at is still in the future (only the revokedAt guard can reject this)")
    void should_return400AndProvisionNothing_when_acceptingASupersededButUnexpiredToken() throws Exception {
        // Arrange
        Fixture fixture = seedRevokedInvite("SUPERSEDED", false);

        // Act
        log.debug("Act: accept a SUPERSEDED invite that has NOT yet expired and is not is_used");
        ResponseEntity<String> response = restTemplate.postForEntity(
                ACCEPT_URL,
                new InviteAcceptRequest(fixture.rawToken(), TEST_PASSWORD, "Sup", "Erseded", "+380501234567"),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("a retired invite must never provision an account — body: %s", response.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteErrorResponse>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.data().code())
                .as("phase 285: a SUPERSEDED token is revoked regardless of what retired it — "
                        + "INVITE_REVOKED, not INVITE_USED or INVITE_EXPIRED")
                .isEqualTo(InviteTokenException.Code.INVITE_REVOKED.name());
        assertThat(countUsers(fixture.email()))
                .as("THE decisive assertion: no account may exist for a superseded invite's address")
                .isZero();
        assertThat(readIsUsed(fixture.inviteId()))
                .as("the rejected token must be left exactly as it was found")
                .isFalse();
    }

    @Test
    @DisplayName("GET /auth/invite/validate — 400 + data.code=INVITE_REVOKED for a SUPERSEDED token "
            + "whose expires_at is still in the future, so the invitee never sees a live-looking "
            + "preview of a dead link")
    void should_return400_when_previewingASupersededButUnexpiredToken() throws Exception {
        Fixture fixture = seedRevokedInvite("SUPERSEDED", false);

        log.debug("Act: preview a SUPERSEDED invite that has NOT yet expired");
        ResponseEntity<String> response = restTemplate.getForEntity(
                String.format(VALIDATE_URL, fixture.rawToken()), String.class);

        assertThat(response.getStatusCode())
                .as("body: %s", response.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteErrorResponse>>() {});
        assertThat(body.success()).isFalse();
        // Phase 285 REVERSES the old "must not distinguish revoked from never-existed" posture —
        // data.code now legitimately says INVITE_REVOKED (see InviteService#previewInvite's
        // rewritten javadoc for the ruling). What must still never leak is the DB-internal
        // revoked_reason enum constant ("SUPERSEDED"/"CANCELLED" — distinct from the wire-level
        // INVITE_REVOKED code) and any PII (the invitee's email).
        assertThat(body.data().code()).isEqualTo(InviteTokenException.Code.INVITE_REVOKED.name());
        assertThat(response.getBody())
                .as("the response must never leak the internal revoked_reason enum constant or PII")
                .doesNotContain("SUPERSEDED")
                .doesNotContain(fixture.email());
    }

    @Test
    @DisplayName("POST /auth/invite/accept — 400 + data.code=INVITE_REVOKED and NO account for a "
            + "CANCELLED token (phase 285: the revoked check now runs BEFORE the used check, so "
            + "a CANCELLED token — which sets both — reports the more specific code)")
    void should_return400AndProvisionNothing_when_acceptingACancelledToken() throws Exception {
        Fixture fixture = seedRevokedInvite("CANCELLED", true);

        log.debug("Act: accept an invite the salon owner had already cancelled");
        ResponseEntity<String> response = restTemplate.postForEntity(
                ACCEPT_URL,
                new InviteAcceptRequest(fixture.rawToken(), TEST_PASSWORD, "Can", "Celled", "+380501234567"),
                String.class);

        assertThat(response.getStatusCode())
                .as("body: %s", response.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteErrorResponse>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.data().code()).isEqualTo(InviteTokenException.Code.INVITE_REVOKED.name());
        assertThat(countUsers(fixture.email()))
                .as("no account may be provisioned from a cancelled invite")
                .isZero();
    }

    @Test
    @DisplayName("GET /auth/invite/validate — 400 + data.code=INVITE_REVOKED for a CANCELLED token")
    void should_return400_when_previewingACancelledToken() throws Exception {
        Fixture fixture = seedRevokedInvite("CANCELLED", true);

        ResponseEntity<String> response = restTemplate.getForEntity(
                String.format(VALIDATE_URL, fixture.rawToken()), String.class);

        assertThat(response.getStatusCode())
                .as("body: %s", response.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteErrorResponse>>() {});
        assertThat(body.data().code()).isEqualTo(InviteTokenException.Code.INVITE_REVOKED.name());
    }

    /**
     * Discriminating control. Without it, the four tests above could be passing because the
     * fixture is broken (an unusable raw token, a mis-hashed digest, a salon the accept path
     * rejects) rather than because the revocation guard fired. The SAME seeding helper, with
     * revocation left off, must produce an invite that accepts successfully.
     */
    @Test
    @DisplayName("control — the identical fixture with NO revocation accepts successfully (201), "
            + "proving the 400s above come from the revocation guard and not a broken fixture")
    void should_return201_when_acceptingTheSameFixtureWithoutRevocation() {
        Fixture fixture = seedRevokedInvite(null, false);

        log.debug("Act: accept the control invite — same fixture shape, revocation columns left null");
        ResponseEntity<String> response = restTemplate.postForEntity(
                ACCEPT_URL,
                new InviteAcceptRequest(fixture.rawToken(), TEST_PASSWORD, "Con", "Trol", "+380501234567"),
                String.class);

        assertThat(response.getStatusCode())
                .as("if this is not 201 the fixture itself is unusable and the guard tests above "
                        + "prove nothing — body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(countUsers(fixture.email())).isEqualTo(1);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private record Fixture(UUID inviteId, String rawToken, String email) { }

    /**
     * Seeds an owner, a salon and one genuinely acceptable invite (SHA-256 digest of
     * {@code rawToken}, matching {@code SecureTokenGenerator#hash}) with {@code expires_at} two
     * days in the FUTURE.
     *
     * @param revokedReason {@code SUPERSEDED} / {@code CANCELLED}, or {@code null} for the control
     * @param isUsed        mirrors what the production path writes for that reason — cancellation
     *                      also sets {@code is_used}, supersession does not
     */
    private Fixture seedRevokedInvite(String revokedReason, boolean isUsed) {
        long nonce = System.nanoTime();
        UUID ownerId = insertUser("owner-revoked-" + nonce + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Revoked Invite Salon " + nonce);

        String email = "revoked-invitee-" + nonce + "@beautica.test";
        String rawToken = "raw-revoked-" + UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO invite_tokens "
                        + "(id, token, email, salon_id, role, expires_at, is_used, revoked_at, revoked_reason, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'SALON_MASTER', ?, ?, ?, ?, NOW(), NOW())",
                inviteId, sha256Hex(rawToken), email, salonId,
                Timestamp.from(now.plus(2, ChronoUnit.DAYS)), isUsed,
                revokedReason == null ? null : Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
                revokedReason);
        return new Fixture(inviteId, rawToken, email);
    }

    private Integer countUsers(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
    }

    private Boolean readIsUsed(UUID inviteId) {
        return jdbcTemplate.queryForObject(
                "SELECT is_used FROM invite_tokens WHERE id = ?", Boolean.class, inviteId);
    }

    private UUID insertUser(String email, String role) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, true, true)",
                id, email, hash, role);
        return id;
    }

    private UUID insertSalon(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, name, testCityId());
        return salonId;
    }

    private String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
