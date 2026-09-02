package com.beautica.auth;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InviteErrorResponse;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.InviteTokenException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.service.SalonService;
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
 * Phase 286 — {@link InviteService#acceptInvite} must reject redemption of a salon-bound invite
 * once its salon has been soft-deactivated ({@code SalonService.deactivateSalon}), and it must do
 * so WITHOUT provisioning any state and WITHOUT burning the single-use token.
 *
 * <p><strong>Why "no side effect" is the decisive assertion, not the status code.</strong> A fix
 * that rejected the request but still called {@code token.markUsed()} before throwing (guard
 * placed AFTER {@code markUsed()} instead of before) would still return 409 and would still pass a
 * status-only test — the token would then be unrecoverable even if the salon were reactivated.
 * {@link #should_leaveTokenUnusedAndProvisionNothing_when_salonIsInactive()} pins that ordering by
 * asserting {@code is_used = false} survives the rejected call, on top of the zero-master and
 * zero-user assertions.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Invite accept — a salon-bound invite is dead once its salon is deactivated")
class InviteAcceptRejectsInactiveSalonIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(InviteAcceptRejectsInactiveSalonIntegrationTest.class);

    private static final String ACCEPT_URL = "/api/v1/auth/invite/accept";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SalonService salonService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("POST /auth/invite/accept — 409, zero masters, zero users, and the token is "
            + "left UNUSED when the invite's salon has been deactivated")
    void should_leaveTokenUnusedAndProvisionNothing_when_salonIsInactive() throws Exception {
        // Arrange
        Fixture fixture = seedInvite(Role.SALON_MASTER, false);

        // Act
        log.debug("Act: accept a SALON_MASTER invite whose salon is deactivated");
        ResponseEntity<String> response = restTemplate.postForEntity(
                ACCEPT_URL,
                new InviteAcceptRequest(fixture.rawToken(), TEST_PASSWORD, "Dead", "Salon", "+380501234567"),
                String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("a redemption into a dead salon must be rejected — body: %s", response.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteErrorResponse>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.data().code())
                .as("phase 285: phase 286's throw is re-pointed at InviteTokenException.Code.INVITE_SALON_INACTIVE")
                .isEqualTo(InviteTokenException.Code.INVITE_SALON_INACTIVE.name());

        assertThat(countMasters(fixture.salonId()))
                .as("no master row may ever be bound to a deactivated salon")
                .isZero();
        assertThat(countUsers(fixture.email()))
                .as("no account may be provisioned for a rejected redemption")
                .isZero();
        assertThat(readIsUsed(fixture.inviteId()))
                .as("THE decisive assertion pinning the guard's placement BEFORE markUsed(): a "
                        + "rejected redemption must not burn the single-use token")
                .isFalse();
    }

    @Test
    @DisplayName("control — the identical fixture against an ACTIVE salon accepts (201) and "
            + "creates the master row, proving the guard above does not over-reject")
    void should_return201AndCreateMaster_when_salonIsActive() {
        Fixture fixture = seedInvite(Role.SALON_MASTER, true);

        log.debug("Act: accept the same SALON_MASTER fixture shape against an active salon");
        ResponseEntity<String> response = restTemplate.postForEntity(
                ACCEPT_URL,
                new InviteAcceptRequest(fixture.rawToken(), TEST_PASSWORD, "Live", "Salon", "+380501234567"),
                String.class);

        assertThat(response.getStatusCode())
                .as("if this is not 201 the fixture itself is unusable and the 409 test above "
                        + "proves nothing — body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(countUsers(fixture.email())).isEqualTo(1);
        assertThat(countMasters(fixture.salonId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a SALON_ADMIN invite with a NULL salonId is salon-bound corrupt state — 409, "
            + "not 201")
    void should_return409_when_inviteSalonIdIsNull() throws Exception {
        // SALON_ADMIN is salon-bound: InviteService#sendInvite only ever mints a SALON_ADMIN
        // token after resolving a live Salon (InviteRequest.salonId() is @NotNull), so a live
        // token with this role NEVER legitimately has a null salonId. A null here can only mean
        // invite_tokens.salon_id's ON DELETE SET NULL FK fired because the referenced salon was
        // hard-deleted (V5__Fix_invite_tokens_cascade.sql) — corrupt state that must fail
        // closed, not silently accept. This replaces the old
        // should_return201_when_inviteSalonIdIsNull, whose premise (SALON_ADMIN is a
        // "genuinely salon-less role") was the incomplete assumption Fix 1 corrects.
        Fixture fixture = seedInviteWithNullSalon(Role.SALON_ADMIN);

        log.debug("Act: accept a SALON_ADMIN invite with a corrupt null salonId");
        ResponseEntity<String> response = restTemplate.postForEntity(
                ACCEPT_URL,
                new InviteAcceptRequest(fixture.rawToken(), TEST_PASSWORD, "No", "Salon", "+380501234567"),
                String.class);

        assertThat(response.getStatusCode())
                .as("a salon-bound role with a null salonId must fail closed, not skip the "
                        + "guard — body: %s", response.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteErrorResponse>>() {});
        assertThat(body.data().code()).isEqualTo(InviteTokenException.Code.INVITE_SALON_INACTIVE.name());
        assertThat(countUsers(fixture.email()))
                .as("no account may be provisioned for a rejected redemption")
                .isZero();
        assertThat(readIsUsed(fixture.inviteId()))
                .as("a rejected redemption must not burn the single-use token")
                .isFalse();
    }

    @Test
    @DisplayName("a SALON_MASTER invite with a NULL salonId is salon-bound corrupt state — 409, "
            + "zero masters, zero users, token left unused")
    void should_leaveTokenUnusedAndProvisionNothing_when_salonBoundInviteHasNullSalonId() throws Exception {
        // Same corrupt-state shape as the SALON_ADMIN case above, but for SALON_MASTER — the
        // other salon-bound role — and additionally pins that
        // MasterService#createMasterFromInvite is never reached (which would otherwise call
        // salonRepository.findById(null) and surface as an uncaught 500, not this clean 409).
        Fixture fixture = seedInviteWithNullSalon(Role.SALON_MASTER);

        log.debug("Act: accept a SALON_MASTER invite with a corrupt null salonId");
        ResponseEntity<String> response = restTemplate.postForEntity(
                ACCEPT_URL,
                new InviteAcceptRequest(fixture.rawToken(), TEST_PASSWORD, "No", "Salon", "+380501234567"),
                String.class);

        assertThat(response.getStatusCode())
                .as("a salon-bound role with a null salonId must fail closed with a clean 409, "
                        + "never a 500 from MasterService#createMasterFromInvite — body: %s",
                        response.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteErrorResponse>>() {});
        assertThat(body.data().code()).isEqualTo(InviteTokenException.Code.INVITE_SALON_INACTIVE.name());
        assertThat(countUsers(fixture.email()))
                .as("no account may be provisioned for a rejected redemption")
                .isZero();
        assertThat(countMasters(fixture.salonId()))
                .as("no master row may ever be created from a corrupt null-salon token")
                .isZero();
        assertThat(readIsUsed(fixture.inviteId()))
                .as("a rejected redemption must not burn the single-use token")
                .isFalse();
    }

    @Test
    @DisplayName("the realistic interleave — invite minted while the salon is ACTIVE, then the "
            + "salon is deactivated through the real SalonService before redemption — still 409, "
            + "still zero side effects, still unused token")
    void should_rejectRedemption_when_salonIsDeactivatedAfterInviteWasIssued() throws Exception {
        // Unlike seedInvite(role, false) above (which seeds the salon already inactive at INSERT
        // time), this drives the actual production sequence phase 286's Javadoc calls out as the
        // scenario a phase-267 cascade cannot win: the invite is minted for a genuinely LIVE
        // salon, and ONLY THEN does the owner delete it — through SalonService.deactivateSalon
        // itself (real dirty-checking commit + cache eviction), not a raw UPDATE that merely
        // matches its end state. If acceptInvite's guard ever became stale-read (e.g. re-added a
        // @Cacheable salon lookup instead of the current uncached salonRepository.findById),
        // this is the test that would catch it — the seed-inactive tests above cannot, because
        // they never populate any cache with a since-invalidated "active" entry.
        long nonce = System.nanoTime();
        UUID ownerId = insertUser("owner-interleave-" + nonce + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Interleave Test Salon " + nonce, true);
        String email = "interleave-invitee-" + nonce + "@beautica.test";
        String rawToken = "raw-interleave-" + UUID.randomUUID();
        UUID inviteId = insertInvite(email, salonId, Role.SALON_MASTER, rawToken);

        log.debug("Arrange: deactivate the salon via the real SalonService AFTER the invite exists");
        salonService.deactivateSalon(ownerId, salonId);

        log.debug("Act: redeem an invite minted before, but deactivated after, salon deletion");
        ResponseEntity<String> response = restTemplate.postForEntity(
                ACCEPT_URL,
                new InviteAcceptRequest(rawToken, TEST_PASSWORD, "Late", "Comer", "+380501234567"),
                String.class);

        assertThat(response.getStatusCode())
                .as("a real deactivate-then-redeem interleave must still be rejected — body: %s",
                        response.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteErrorResponse>>() {});
        assertThat(body.data().code()).isEqualTo(InviteTokenException.Code.INVITE_SALON_INACTIVE.name());
        assertThat(countMasters(salonId)).isZero();
        assertThat(countUsers(email)).isZero();
        assertThat(readIsUsed(inviteId))
                .as("the token must survive the rejected redemption unburned")
                .isFalse();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private record Fixture(UUID inviteId, String rawToken, String email, UUID salonId) { }

    /**
     * Seeds an owner, a salon (active per {@code salonActive}) and one genuinely acceptable
     * pending invite (SHA-256 digest of {@code rawToken}, matching
     * {@code SecureTokenGenerator#hash}) with {@code expires_at} two days in the future,
     * {@code is_used = false} and {@code revoked_at IS NULL}.
     */
    private Fixture seedInvite(Role role, boolean salonActive) {
        long nonce = System.nanoTime();
        UUID ownerId = insertUser("owner-inactive-salon-" + nonce + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Guard Test Salon " + nonce, salonActive);

        String email = "inactive-salon-invitee-" + nonce + "@beautica.test";
        String rawToken = "raw-inactive-salon-" + UUID.randomUUID();
        UUID inviteId = insertInvite(email, salonId, role, rawToken);
        return new Fixture(inviteId, rawToken, email, salonId);
    }

    private Fixture seedInviteWithNullSalon(Role role) {
        long nonce = System.nanoTime();
        String email = "null-salon-invitee-" + nonce + "@beautica.test";
        String rawToken = "raw-null-salon-" + UUID.randomUUID();
        UUID inviteId = insertInvite(email, null, role, rawToken);
        return new Fixture(inviteId, rawToken, email, null);
    }

    private UUID insertInvite(String email, UUID salonId, Role role, String rawToken) {
        UUID inviteId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO invite_tokens "
                        + "(id, token, email, salon_id, role, expires_at, is_used, revoked_at, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, false, NULL, NOW(), NOW())",
                inviteId, sha256Hex(rawToken), email, salonId, role.name(),
                Timestamp.from(now.plus(2, ChronoUnit.DAYS)));
        return inviteId;
    }

    private Integer countUsers(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
    }

    private Integer countMasters(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM masters WHERE salon_id = ?", Integer.class, salonId);
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

    private UUID insertSalon(UUID ownerId, String name, boolean isActive) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW(), ?)",
                salonId, ownerId, name, isActive, testCityId());
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
