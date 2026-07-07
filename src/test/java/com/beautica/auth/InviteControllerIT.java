package com.beautica.auth;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.TestConstants;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("Invite endpoints — integration")
class InviteControllerIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(InviteControllerIT.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private InviteTokenRepository inviteTokenRepository;

    @Autowired
    private NotificationOutboxRepository notificationOutboxRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> createdEmails = new ArrayList<>();
    private final List<UUID> createdSalonIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // masters.user_id → users.id blocks user deletion; delete masters first
        for (String email : createdEmails) {
            jdbcTemplate.update(
                    "DELETE FROM masters WHERE user_id = (SELECT id FROM users WHERE email = ?)", email);
        }
        // users.salon_id → salons.id (ON DELETE SET NULL) — nullify first, then delete salons
        jdbcTemplate.execute("UPDATE users SET salon_id = NULL WHERE salon_id IS NOT NULL");
        for (UUID sid : createdSalonIds) {
            jdbcTemplate.update("DELETE FROM salons WHERE id = ?", sid);
        }
        createdSalonIds.clear();
        transactionTemplate.executeWithoutResult(status -> {
            for (String email : createdEmails) {
                userRepository.findByEmail(email).ifPresent(user -> {
                    refreshTokenRepository.deleteByUserId(user.getId());
                    userRepository.delete(user);
                });
                inviteTokenRepository.findByEmailAndIsUsedFalse(email)
                        .ifPresent(inviteTokenRepository::delete);
            }
        });
        createdEmails.clear();
        // Cross-test residue: invite flows write notification_outbox rows that other ITs
        // sharing this JVM may otherwise observe. Wipe them last so FK order is preserved.
        notificationOutboxRepository.deleteAll();
    }

    @Test
    @DisplayName("SALON_OWNER sends invite → 201 with invitedEmail")
    void should_return201_when_salonOwnerSendsInvite() throws Exception {
        String ownerEmail = uniqueEmail("owner");
        String masterEmail = uniqueEmail("master");
        createdEmails.add(ownerEmail);
        createdEmails.add(masterEmail);
        UUID salonId = UUID.randomUUID();
        log.debug("Arrange: register SALON_OWNER email={}", ownerEmail);

        String registrationToken = registerAndGetToken(ownerEmail, Role.CLIENT);
        String ownerAccessToken = promoteToSalonOwnerWithSalon(ownerEmail, registrationToken, salonId);

        HttpHeaders headers = bearerHeaders(ownerAccessToken);
        var request = new InviteRequest(masterEmail, salonId, null);

        log.debug("Act: POST /auth/invite as SALON_OWNER for email={}", masterEmail);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/invite",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class
        );

        assertThat(response.getStatusCode())
                .as("status must be 201 when SALON_OWNER sends a valid invite")
                .isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().invitedEmail()).isEqualTo(masterEmail);
        assertThat(body.data().expiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("CLIENT sends invite → 403")
    void should_return403_when_clientSendsInvite() throws Exception {
        String clientEmail = uniqueEmail("client");
        createdEmails.add(clientEmail);
        log.debug("Arrange: CLIENT email={}", clientEmail);

        String clientToken = registerAndGetToken(clientEmail, Role.CLIENT);

        HttpHeaders headers = bearerHeaders(clientToken);
        var request = new InviteRequest(uniqueEmail("target"), UUID.randomUUID(), null);

        log.debug("Act: POST /auth/invite as CLIENT role — must be rejected with 403");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/invite",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class
        );

        assertThat(response.getStatusCode())
                .as("status must be 403 when CLIENT sends invite")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("SALON_ADMIN sends invite directly via /auth/invite → 403 (split-gate regression net)")
    void should_return403_when_salonAdminSendsInviteViaAuthInvite() throws Exception {
        // REGRESSION NET (Phase 21.1 multi-admin relaxation): AuthController.sendInvite is gated
        // @PreAuthorize("hasRole('SALON_OWNER')") and is INTENTIONALLY untouched by this phase — a
        // SALON_ADMIN's only authorized invite surface is POST /api/v1/salons/{salonId}/invite (see
        // should_return201_when_salonAdminInvitesNewAdminIntoOwnSalon below). Nothing previously
        // pinned that hasRole('SALON_OWNER') still rejects a SALON_ADMIN caller on THIS path; a
        // future refactor could silently widen it to hasAnyRole('SALON_OWNER','SALON_ADMIN') and
        // reopen the split-gate risk (a SALON_ADMIN issuing invites for a salon it does not manage)
        // with no CI signal.
        String ownerEmail = uniqueEmail("owner-split-gate");
        String adminEmail = uniqueEmail("admin-split-gate");
        createdEmails.add(ownerEmail);
        createdEmails.add(adminEmail);
        UUID salonId = UUID.randomUUID();
        log.debug("Arrange: SALON_ADMIN ({}) of salonId={} attempts POST /auth/invite directly", adminEmail, salonId);

        String ownerRegistrationToken = registerAndGetToken(ownerEmail, Role.CLIENT);
        promoteToSalonOwnerWithSalon(ownerEmail, ownerRegistrationToken, salonId);
        registerAndGetToken(adminEmail, Role.CLIENT);
        String adminAccessToken = promoteToSalonAdmin(adminEmail, salonId);

        HttpHeaders headers = bearerHeaders(adminAccessToken);
        var request = new InviteRequest(uniqueEmail("target-split-gate"), salonId, null);

        log.debug("Act: POST /auth/invite as SALON_ADMIN — must still be rejected with 403");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/invite",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class
        );

        assertThat(response.getStatusCode())
                .as("SALON_ADMIN must still receive 403 on POST /auth/invite — only SALON_OWNER may use this path")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Unauthenticated request to send invite → 401")
    void should_return401_when_unauthenticatedSendsInvite() {
        log.debug("Arrange: no Authorization header");

        var request = new InviteRequest(uniqueEmail("unauth"), UUID.randomUUID(), null);

        log.debug("Act: POST /auth/invite without credentials — unauthenticated request must be rejected");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/invite", request, String.class);

        assertThat(response.getStatusCode())
                .as("status must be 401 when no Authorization header is present")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SALON_OWNER invites already-registered email → 201 generic success (no enumeration oracle)")
    void should_return201GenericSuccess_when_invitedEmailAlreadyRegistered() throws Exception {
        String ownerEmail = uniqueEmail("owner2");
        String alreadyRegistered = uniqueEmail("existing");
        createdEmails.add(ownerEmail);
        createdEmails.add(alreadyRegistered);
        UUID salonId = UUID.randomUUID();
        log.debug("Arrange: register both owner={} and target={}", ownerEmail, alreadyRegistered);

        String registrationToken = registerAndGetToken(ownerEmail, Role.CLIENT);
        String ownerAccessToken = promoteToSalonOwnerWithSalon(ownerEmail, registrationToken, salonId);
        registerAndGetToken(alreadyRegistered, Role.CLIENT);

        HttpHeaders headers = bearerHeaders(ownerAccessToken);
        var request = new InviteRequest(alreadyRegistered, salonId, null);

        log.debug("Act: POST /auth/invite targeting already-registered email={}", alreadyRegistered);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/invite",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class
        );

        // Enumeration hardening: an already-registered target must return the SAME generic 201
        // and body shape as a normal invite — never a distinguishing 409 — so an authenticated
        // caller cannot probe registration status.
        assertThat(response.getStatusCode())
                .as("already-registered must return the same generic 201 as a normal invite")
                .isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().invitedEmail()).isEqualTo(alreadyRegistered);
        assertThat(body.data().expiresAt()).isAfter(Instant.now());

        // No invite token is created and no e-mail enqueued for an already-registered target —
        // the distinguishing side effect is absent, not merely hidden.
        assertThat(inviteTokenRepository.findByEmailAndIsUsedFalse(alreadyRegistered))
                .as("no invite token must be persisted for an already-registered email")
                .isEmpty();
    }

    @Test
    @DisplayName("Repeated invite for a pending (not-yet-registered) email is idempotent → 201 both times (no residual 409 oracle)")
    void should_return201Idempotently_when_inviteSentTwiceForPendingEmail() throws Exception {
        String ownerEmail = uniqueEmail("owner3");
        String pendingEmail = uniqueEmail("pending");
        createdEmails.add(ownerEmail);
        createdEmails.add(pendingEmail);
        UUID salonId = UUID.randomUUID();
        log.debug("Arrange: register SALON_OWNER email={}", ownerEmail);

        String registrationToken = registerAndGetToken(ownerEmail, Role.CLIENT);
        String ownerAccessToken = promoteToSalonOwnerWithSalon(ownerEmail, registrationToken, salonId);

        HttpHeaders headers = bearerHeaders(ownerAccessToken);
        var request = new InviteRequest(pendingEmail, salonId, null);
        HttpEntity<InviteRequest> entity = new HttpEntity<>(request, headers);

        log.debug("Act: POST /auth/invite twice for the same pending email={}", pendingEmail);
        ResponseEntity<String> first = restTemplate.exchange(
                "/api/v1/auth/invite", HttpMethod.POST, entity, String.class);
        ResponseEntity<String> second = restTemplate.exchange(
                "/api/v1/auth/invite", HttpMethod.POST, entity, String.class);

        // The residual oracle being closed: previously the SECOND call returned 409 for a
        // pending (not-registered) email while a registered email kept returning 200 — that
        // 200-vs-409 split revealed registration status. Both calls must now return 201.
        assertThat(first.getStatusCode())
                .as("first invite must return 201")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode())
                .as("second (idempotent) invite must also return 201 — no residual 409 oracle")
                .isEqualTo(HttpStatus.CREATED);

        var firstBody = objectMapper.readValue(
                first.getBody(), new TypeReference<ApiResponse<InviteResponse>>() {});
        var secondBody = objectMapper.readValue(
                second.getBody(), new TypeReference<ApiResponse<InviteResponse>>() {});
        assertThat(secondBody.success()).isTrue();
        assertThat(secondBody.data().invitedEmail()).isEqualTo(firstBody.data().invitedEmail());
    }

    // ── Phase 21.1 — multi-admin relaxation ───────────────────────────────────

    @Test
    @DisplayName("Phase 21.1: SALON_OWNER invites a second SALON_ADMIN into a salon that already has one → 201 (uniqueness relaxed)")
    void should_return201_when_secondAdminInviteSentToSalonWithExistingAdmin() throws Exception {
        String ownerEmail = uniqueEmail("owner-multi-admin");
        String firstAdminEmail = uniqueEmail("first-admin");
        String secondAdminEmail = uniqueEmail("second-admin");
        createdEmails.add(ownerEmail);
        createdEmails.add(firstAdminEmail);
        createdEmails.add(secondAdminEmail);
        UUID salonId = UUID.randomUUID();
        log.debug("Arrange: salon={} already has one SALON_ADMIN ({}); inviting a second", salonId, firstAdminEmail);

        String registrationToken = registerAndGetToken(ownerEmail, Role.CLIENT);
        String ownerAccessToken = promoteToSalonOwnerWithSalon(ownerEmail, registrationToken, salonId);
        registerAndGetToken(firstAdminEmail, Role.CLIENT);
        promoteToSalonAdmin(firstAdminEmail, salonId);

        HttpHeaders headers = bearerHeaders(ownerAccessToken);
        var request = new InviteRequest(secondAdminEmail, salonId, Role.SALON_ADMIN);

        log.debug("Act: POST /auth/invite for a second SALON_ADMIN in salonId={}", salonId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/invite",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class
        );

        assertThat(response.getStatusCode())
                .as("a second SALON_ADMIN invite to the same salon must succeed with 201 (no more uniqueness conflict)")
                .isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().invitedEmail()).isEqualTo(secondAdminEmail);
    }

    @Test
    @DisplayName("Phase 21.1: a SALON_ADMIN caller invites a new SALON_ADMIN into their own salon via POST /salons/{salonId}/invite → 201")
    void should_return201_when_salonAdminInvitesNewAdminIntoOwnSalon() throws Exception {
        // NOTE: /api/v1/auth/invite is gated `@PreAuthorize("hasRole('SALON_OWNER')")` at the
        // controller layer and is unrelated to this phase (untouched, pre-existing SALON_OWNER-
        // only onboarding surface). The reachable, already-authorized HTTP surface for a
        // SALON_ADMIN caller is POST /api/v1/salons/{salonId}/invite (SalonController — controller
        // gate hasAnyRole('SALON_OWNER','SALON_ADMIN') + @authz.canManageSalon), which delegates
        // to the same InviteService.sendInvite this phase relaxes.
        String ownerEmail = uniqueEmail("owner-admin-invites-admin");
        String firstAdminEmail = uniqueEmail("first-admin-caller");
        String secondAdminEmail = uniqueEmail("second-admin-target");
        createdEmails.add(ownerEmail);
        createdEmails.add(firstAdminEmail);
        createdEmails.add(secondAdminEmail);
        UUID salonId = UUID.randomUUID();
        log.debug("Arrange: SALON_ADMIN ({}) of salonId={} invites a new SALON_ADMIN", firstAdminEmail, salonId);

        String ownerRegistrationToken = registerAndGetToken(ownerEmail, Role.CLIENT);
        promoteToSalonOwnerWithSalon(ownerEmail, ownerRegistrationToken, salonId);
        registerAndGetToken(firstAdminEmail, Role.CLIENT);
        String adminAccessToken = promoteToSalonAdmin(firstAdminEmail, salonId);

        HttpHeaders headers = bearerHeaders(adminAccessToken);
        var request = new com.beautica.salon.dto.InviteRequest(secondAdminEmail, Role.SALON_ADMIN);

        log.debug("Act: POST /salons/{}/invite as SALON_ADMIN caller for a new SALON_ADMIN target={}", salonId, secondAdminEmail);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/invite",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class
        );

        assertThat(response.getStatusCode())
                .as("a SALON_ADMIN caller must be able to invite a new SALON_ADMIN into their own salon")
                .isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InviteResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().invitedEmail()).isEqualTo(secondAdminEmail);
    }

    @Test
    @DisplayName("Phase 21.1: a SALON_ADMIN caller inviting a SALON_ADMIN into a different salon via POST /salons/{salonId}/invite still → 403")
    void should_return403_when_salonAdminInvitesAdminIntoDifferentSalon() throws Exception {
        String ownerEmail = uniqueEmail("owner-cross-salon");
        String adminEmail = uniqueEmail("admin-cross-salon");
        String targetEmail = uniqueEmail("target-cross-salon");
        createdEmails.add(ownerEmail);
        createdEmails.add(adminEmail);
        createdEmails.add(targetEmail);
        UUID adminSalonId = UUID.randomUUID();
        UUID otherSalonId = UUID.randomUUID();
        log.debug("Arrange: SALON_ADMIN of salonId={} attempts to invite into unrelated salonId={}", adminSalonId, otherSalonId);

        String ownerRegistrationToken = registerAndGetToken(ownerEmail, Role.CLIENT);
        promoteToSalonOwnerWithSalon(ownerEmail, ownerRegistrationToken, adminSalonId);
        registerAndGetToken(adminEmail, Role.CLIENT);
        String adminAccessToken = promoteToSalonAdmin(adminEmail, adminSalonId);

        // otherSalonId must exist as a real salon row for FK/lookups further down the flow —
        // registered under a second throwaway owner so cleanUp() can tear it down too.
        String otherOwnerEmail = uniqueEmail("owner-other-salon");
        createdEmails.add(otherOwnerEmail);
        String otherOwnerRegistrationToken = registerAndGetToken(otherOwnerEmail, Role.CLIENT);
        promoteToSalonOwnerWithSalon(otherOwnerEmail, otherOwnerRegistrationToken, otherSalonId);

        HttpHeaders headers = bearerHeaders(adminAccessToken);
        var request = new com.beautica.salon.dto.InviteRequest(targetEmail, Role.SALON_ADMIN);

        log.debug("Act: POST /salons/{}/invite as SALON_ADMIN caller targeting a different salonId — must be rejected with 403", otherSalonId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + otherSalonId + "/invite",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class
        );

        assertThat(response.getStatusCode())
                .as("a SALON_ADMIN caller must never be able to invite into a salon other than their own")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Phase 21.1: accepting a SALON_ADMIN invite when the salon already has an admin → 201, both admin rows persisted")
    void should_return201AndCreateSecondSalonAdmin_when_acceptingInviteForSalonThatAlreadyHasAnAdmin() throws Exception {
        // GAP CLOSED: every other Phase 21.1 test exercises invite CREATION only — the second
        // admin's `users` row never actually gets persisted in those flows (an invite_tokens row
        // is created, but nothing accepts it). This test drives the full invite→accept round trip
        // through the real HTTP + service + JPA path (not a raw JDBC bypass — see
        // V108DropSalonAdminUniquenessMigrationTest for that layer) so that the application code
        // itself, not just Postgres, is proven compatible with two SALON_ADMIN rows sharing a
        // salon_id now that uq_users_salon_admin (V8) is dropped by V108.
        String ownerEmail = uniqueEmail("owner-accept-second-admin");
        String firstAdminEmail = uniqueEmail("first-admin-accept");
        String secondAdminEmail = uniqueEmail("second-admin-accept");
        createdEmails.add(ownerEmail);
        createdEmails.add(firstAdminEmail);
        createdEmails.add(secondAdminEmail);
        UUID salonId = UUID.randomUUID();
        log.debug("Arrange: salon={} already has a persisted SALON_ADMIN ({}); accepting an invite for a second", salonId, firstAdminEmail);

        String ownerRegistrationToken = registerAndGetToken(ownerEmail, Role.CLIENT);
        promoteToSalonOwnerWithSalon(ownerEmail, ownerRegistrationToken, salonId);
        registerAndGetToken(firstAdminEmail, Role.CLIENT);
        promoteToSalonAdmin(firstAdminEmail, salonId);

        String rawToken = UUID.randomUUID().toString();
        saveValidAdminInviteToken(secondAdminEmail, salonId, rawToken);
        var request = new InviteAcceptRequest(rawToken, "Password12345", "Second", "Admin", "+380501234567");

        log.debug("Act: POST /auth/invite/accept for a SECOND SALON_ADMIN of an already-admin'd salonId={}", salonId);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/invite/accept", request, String.class);

        assertThat(response.getStatusCode())
                .as("accepting a second SALON_ADMIN invite for a salon that already has one must succeed with 201")
                .isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().role()).isEqualTo(Role.SALON_ADMIN);
        assertThat(body.data().email()).isEqualTo(secondAdminEmail);
        assertThat(body.data().salonId()).isEqualTo(salonId);

        var persistedSecondAdmin = userRepository.findByEmail(secondAdminEmail);
        assertThat(persistedSecondAdmin).isPresent();
        assertThat(persistedSecondAdmin.get().getRole()).isEqualTo(Role.SALON_ADMIN);
        assertThat(persistedSecondAdmin.get().getSalonId()).isEqualTo(salonId);

        // The decisive assertion: TWO real, application-persisted SALON_ADMIN rows now coexist
        // for the same salon_id — the exact row shape uq_users_salon_admin used to forbid.
        Integer adminCountForSalon = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE salon_id = ? AND role = 'SALON_ADMIN'",
                Integer.class, salonId);
        assertThat(adminCountForSalon)
                .as("salonId=%s must now have two persisted SALON_ADMIN rows after the second invite is accepted", salonId)
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Valid token accept → 201, user created with SALON_MASTER role")
    void should_return201AndCreateSalonMaster_when_validTokenAccepted() throws Exception {
        String masterEmail = uniqueEmail("newmaster");
        createdEmails.add(masterEmail);
        UUID salonId = UUID.randomUUID();
        log.debug("Arrange: insert valid invite token for email={}", masterEmail);

        String rawToken = UUID.randomUUID().toString();
        String salonOwnerEmail = uniqueEmail("salon-owner");
        createdEmails.add(salonOwnerEmail);
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, first_name, last_name, is_active, email_verified, created_at, updated_at) " +
                "VALUES (?, ?, 'SALON_OWNER', 'Owner', 'Test', true, true, now(), now())",
                salonOwnerEmail, TestConstants.HASHED_TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) " +
                "VALUES (?, (SELECT id FROM users WHERE email = ?), 'Test Salon', true, now(), now())",
                salonId, salonOwnerEmail);
        createdSalonIds.add(salonId);
        saveValidInviteToken(masterEmail, salonId, rawToken);

        var request = new InviteAcceptRequest(rawToken, "Password12345", "Jane", "Doe", "+380501234567");

        log.debug("Act: POST /auth/invite/accept with valid token for email={}", masterEmail);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/invite/accept", request, String.class);

        assertThat(response.getStatusCode())
                .as("status must be 201 when a valid invite token is accepted")
                .isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().role()).isEqualTo(Role.SALON_MASTER);
        assertThat(body.data().email()).isEqualTo(masterEmail);
        assertThat(body.data().salonId()).isEqualTo(salonId);

        var persistedUser = userRepository.findByEmail(masterEmail);
        assertThat(persistedUser).isPresent();
        assertThat(persistedUser.get().getRole()).isEqualTo(Role.SALON_MASTER);
        assertThat(persistedUser.get().getSalonId()).isEqualTo(salonId);
    }

    @Test
    @DisplayName("Token not found → 404")
    void should_return404_when_tokenNotFound() throws Exception {
        var request = new InviteAcceptRequest("nonexistent-token-xyz", "Password12345", "Jane", "Doe", "+380501234567");
        log.debug("Arrange: no matching token in DB");

        log.debug("Act: POST /auth/invite/accept with a token that does not exist in the DB");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/invite/accept", request, String.class);

        assertThat(response.getStatusCode())
                .as("status must be 404 when token does not exist")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Expired token → 400")
    void should_return400_when_tokenExpired() throws Exception {
        String masterEmail = uniqueEmail("expiredmaster");
        createdEmails.add(masterEmail);
        log.debug("Arrange: insert expired invite token for email={}", masterEmail);

        String rawToken = UUID.randomUUID().toString();
        saveExpiredInviteToken(masterEmail, rawToken);

        var request = new InviteAcceptRequest(rawToken, "Password12345", "Jane", "Doe", "+380501234567");

        log.debug("Act: POST /auth/invite/accept with an expired token for email={}", masterEmail);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/invite/accept", request, String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when invite token is expired")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo("Invalid request");
    }

    @Test
    @DisplayName("Already-used token → 400")
    void should_return400_when_tokenAlreadyUsed() throws Exception {
        String masterEmail = uniqueEmail("usedmaster");
        createdEmails.add(masterEmail);
        log.debug("Arrange: insert used invite token for email={}", masterEmail);

        String rawToken = UUID.randomUUID().toString();
        saveUsedInviteToken(masterEmail, rawToken);

        var request = new InviteAcceptRequest(rawToken, "Password12345", "Jane", "Doe", "+380501234567");

        log.debug("Act: POST /auth/invite/accept with a token already marked used for email={}", masterEmail);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/invite/accept", request, String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when invite token was already used")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo("Invalid request");
    }

    @Test
    @DisplayName("/auth/invite/accept is reachable without auth → not 401")
    void should_notReturn401_when_inviteAcceptCalledWithoutAuth() throws Exception {
        var request = new InviteAcceptRequest("some-token", "Str0ngP@ss1!", null, null, null);
        log.debug("Arrange: no Authorization header — endpoint must be public");

        log.debug("Act: POST /auth/invite/accept without credentials — endpoint must be public");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/invite/accept", request, String.class);

        assertThat(response.getStatusCode())
                .as("invite/accept endpoint must not return 401 — it is public")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /auth/invite/validate with valid token → 200 with invite details")
    void should_return200WithInviteDetails_when_validTokenValidated() throws Exception {
        String masterEmail = uniqueEmail("validatemaster");
        createdEmails.add(masterEmail);
        UUID salonId = UUID.randomUUID();
        String rawToken = UUID.randomUUID().toString();
        log.debug("Arrange: save valid invite token for email={}", masterEmail);

        saveValidInviteToken(masterEmail, null, rawToken);

        log.debug("Act: GET /auth/invite/validate with valid token for email={}", masterEmail);
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/auth/invite/validate?token=" + rawToken, String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 for a valid unexpired invite token")
                .isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<InvitePreviewResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().invitedEmail()).isEqualTo(masterEmail);
        assertThat(body.data().role()).isEqualTo(Role.SALON_MASTER);
        assertThat(body.data().expiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("GET /auth/invite/validate with unknown token → 400")
    void should_return404_when_validateWithUnknownToken() {
        log.debug("Arrange: no token stored — using random UUID");

        log.debug("Act: GET /auth/invite/validate with a random UUID that has no matching token in the DB");
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/auth/invite/validate?token=" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when token is unknown")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /auth/invite/validate with expired token → 400")
    void should_return400_when_validateWithExpiredToken() throws Exception {
        String masterEmail = uniqueEmail("expiredvalidate");
        createdEmails.add(masterEmail);
        String rawToken = UUID.randomUUID().toString();
        log.debug("Arrange: save expired invite token for email={}", masterEmail);

        saveExpiredInviteToken(masterEmail, rawToken);

        log.debug("Act: GET /auth/invite/validate with expired token for email={}", masterEmail);
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/auth/invite/validate?token=" + rawToken, String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when invite token is expired")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo("Invalid request");
    }

    @Test
    @DisplayName("GET /auth/invite/validate with used token → 400")
    void should_return400_when_validateWithUsedToken() throws Exception {
        String masterEmail = uniqueEmail("usedvalidate");
        createdEmails.add(masterEmail);
        String rawToken = UUID.randomUUID().toString();
        log.debug("Arrange: save used invite token for email={}", masterEmail);

        saveUsedInviteToken(masterEmail, rawToken);

        log.debug("Act: GET /auth/invite/validate with a token already marked used for email={}", masterEmail);
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/auth/invite/validate?token=" + rawToken, String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when invite token was already used")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var body = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<Void>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo("Invalid request");
    }

    @Test
    @DisplayName("GET /auth/invite/validate without Authorization header → not 401")
    void should_notRequireAuth_when_validatingInviteToken() throws Exception {
        String masterEmail = uniqueEmail("noauthvalidate");
        createdEmails.add(masterEmail);
        String rawToken = UUID.randomUUID().toString();
        log.debug("Arrange: valid token, no auth header — endpoint must be public");

        saveValidInviteToken(masterEmail, null, rawToken);

        log.debug("Act: GET /auth/invite/validate without credentials — endpoint must be public");
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/auth/invite/validate?token=" + rawToken, String.class);

        assertThat(response.getStatusCode())
                .as("invite/validate endpoint must not return 401 — it is public")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("invite/accept with blank phoneNumber → 400")
    void should_return400_when_inviteAccept_has_blank_phoneNumber() throws Exception {
        // Arrange: a valid token is not needed — Bean Validation fires before service logic
        log.debug("Arrange: request with empty phoneNumber — validation must reject before reaching service");
        var request = new InviteAcceptRequest("any-token", "Password12345", "Jane", "Doe", "");

        // Act
        log.debug("Act: POST /auth/invite/accept with blank phoneNumber");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/invite/accept", request, String.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 400 when phoneNumber is blank")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("invite/accept omitting phoneNumber → 400")
    void should_return400_when_inviteAccept_omits_phoneNumber() throws Exception {
        // Arrange: send raw JSON without phoneNumber field — Jackson sets it to null, @NotBlank rejects null
        log.debug("Arrange: request body without phoneNumber field — @NotBlank must reject null");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = """
                {
                  "token": "any-token",
                  "password": "Password12345",
                  "firstName": "Jane",
                  "lastName": "Doe"
                }
                """;

        // Act
        log.debug("Act: POST /auth/invite/accept with phoneNumber field absent from JSON body");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/invite/accept",
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                String.class
        );

        // Assert
        assertThat(response.getStatusCode())
                .as("status must be 400 when phoneNumber is absent from the request body")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String uniqueEmail(String prefix) {
        return prefix + "-" + System.nanoTime() + "@beautica.test";
    }

    private HttpHeaders bearerHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Registers a user via the public registration endpoint, verifies their email directly in the
     * DB (Phase 1.7 gate: unverified users get 403 on login), then logs in and returns the access
     * token. The {@code ignoredRole} parameter is kept for call-site readability but has no effect
     * — self-registration always produces a CLIENT.
     */
    private String registerAndGetToken(String email, Role ignoredRole) throws Exception {
        restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email, "Str0ngP@ss1!", SelfRegistrationRole.CLIENT, "Test", "User", "+380501234567", null),
                String.class
        );
        // Phase 1.7: mark email as verified so login does not return 403 EMAIL_NOT_VERIFIED
        verifyEmailInDb(email);
        var loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, "Str0ngP@ss1!"),
                String.class
        );
        var body = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private void verifyEmailInDb(String email) {
        transactionTemplate.executeWithoutResult(status ->
            userRepository.findByEmail(email).ifPresent(user -> {
                user.setEmailVerified(true);
                userRepository.save(user);
            })
        );
    }

    /**
     * Directly manipulates the user's role in the DB so we can test the SALON_OWNER
     * permission check without a dedicated promote endpoint.
     */
    private String promoteToSalonOwner(String email, String existingToken) throws Exception {
        transactionTemplate.executeWithoutResult(status ->
                userRepository.findByEmail(email).ifPresent(user -> {
                    org.springframework.test.util.ReflectionTestUtils.setField(user, "role", Role.SALON_OWNER);
                    userRepository.save(user);
                })
        );
        var loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, "Str0ngP@ss1!"),
                String.class
        );
        var body = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private String promoteToSalonOwnerWithSalon(String email, String existingToken, UUID salonId) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, (SELECT id FROM users WHERE email = ?), 'Test Salon', true, now(), now())",
                salonId, email
        );
        createdSalonIds.add(salonId);
        transactionTemplate.executeWithoutResult(status ->
                userRepository.findByEmail(email).ifPresent(user -> {
                    org.springframework.test.util.ReflectionTestUtils.setField(user, "role", Role.SALON_OWNER);
                    org.springframework.test.util.ReflectionTestUtils.setField(user, "salonId", salonId);
                    userRepository.save(user);
                })
        );
        var loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, "Str0ngP@ss1!"),
                String.class
        );
        var body = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    /**
     * Directly manipulates an already-registered user's role/salon in the DB to become a
     * SALON_ADMIN of the given (already-existing) salon, without a dedicated promote endpoint —
     * mirrors {@link #promoteToSalonOwnerWithSalon}. The caller is responsible for ensuring the
     * salon row already exists (e.g. via {@link #promoteToSalonOwnerWithSalon}).
     */
    private String promoteToSalonAdmin(String email, UUID salonId) throws Exception {
        transactionTemplate.executeWithoutResult(status ->
                userRepository.findByEmail(email).ifPresent(user -> {
                    org.springframework.test.util.ReflectionTestUtils.setField(user, "role", Role.SALON_ADMIN);
                    org.springframework.test.util.ReflectionTestUtils.setField(user, "salonId", salonId);
                    userRepository.save(user);
                })
        );
        var loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, "Str0ngP@ss1!"),
                String.class
        );
        var body = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private InviteToken saveValidInviteToken(String email, UUID salonId, String rawToken) {
        var token = new InviteToken(
                sha256Hex(rawToken),
                email,
                salonId,
                Role.SALON_MASTER,
                Instant.now().plusSeconds(3600)
        );
        return inviteTokenRepository.save(token);
    }

    private InviteToken saveValidAdminInviteToken(String email, UUID salonId, String rawToken) {
        var token = new InviteToken(
                sha256Hex(rawToken),
                email,
                salonId,
                Role.SALON_ADMIN,
                Instant.now().plusSeconds(3600)
        );
        return inviteTokenRepository.save(token);
    }

    private InviteToken saveExpiredInviteToken(String email, String rawToken) {
        var token = new InviteToken(
                sha256Hex(rawToken),
                email,
                null,
                Role.SALON_MASTER,
                Instant.now().minusSeconds(1)
        );
        return inviteTokenRepository.save(token);
    }

    private InviteToken saveUsedInviteToken(String email, String rawToken) {
        var token = new InviteToken(
                sha256Hex(rawToken),
                email,
                null,
                Role.SALON_MASTER,
                Instant.now().plusSeconds(3600)
        );
        token.markUsed();
        return inviteTokenRepository.save(token);
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
