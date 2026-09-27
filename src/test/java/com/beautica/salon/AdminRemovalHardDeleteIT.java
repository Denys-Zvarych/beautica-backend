package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.InvitePersistenceService;
import com.beautica.auth.Role;
import com.beautica.auth.TokensValidAfterCache;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.service.SalonService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Real-DB coverage for {@code SalonService#removeAdmin}'s Phase 299 hard-delete behaviour — see
 * {@code docs/backend-phases/phase-299-remove-admin-orphan-account.md}.
 *
 * <p><b>Split by what each case actually exercises</b>, mirroring {@code MasterRemovalIT}'s exact
 * convention for the sibling {@code removeMaster} endpoint. Most cases call
 * {@code salonService.removeAdmin(...)} directly — they pin the disposal and its guards, not the
 * HTTP/authorization layer, and a direct call lets the negative cases assert the precise exception
 * type. {@link SalonAdminRemovalIntegrationTest} keeps the pre-existing HTTP-level authorization
 * matrix (owner/admin/cross-salon/cross-owner/client, all unaffected by this phase); this class does
 * not re-duplicate that matrix, only the guards this phase's hard-delete makes newly consequential
 * (self-removal and cross-salon now abort a real delete, not just a field write).
 *
 * <p>An admin fixture here carries NO master row and NO booking history by construction — Phase
 * 299 D1's finding is that a plain admin never has one (see {@code SalonService#removeAdmin}'s
 * javadoc), so {@code disposeStaffAccounts}'s masters-row fork resolves empty and the DELETE-not-
 * DETACH branch always fires for these fixtures, unlike {@code MasterRemovalIT}'s split between a
 * bookingless master (deleted) and a master with history (detached).
 */
@Import(TestSecurityConfig.class)
@DisplayName("SalonService.removeAdmin — Phase 299 hard-delete")
class AdminRemovalHardDeleteIT extends AbstractIntegrationTest {

    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

    @Autowired
    private SalonService salonService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The only way to observe the RAW invite token for case 5's re-invite — see
     * {@code SalonStaffReinviteAfterDeletionIT}'s identical rationale.
     */
    @SpyBean
    private InvitePersistenceService invitePersistenceService;

    /**
     * Spied (not mocked) so the true no-op assertions in cases 13-14 can prove a DENIED removal
     * never reaches the disposal seam at all — {@code verify(..., never())} on the real bean,
     * delegating to real behaviour for every call this class does not assert on.
     */
    @SpyBean
    private TokensValidAfterCache tokensValidAfterCache;

    private SalonItFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SalonItFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder,
                this::testCityId);
    }

    // ── case 1 — the common case: the row is GONE, not merely detached from the salon ───────

    @Test
    @DisplayName("case 1 — remove an admin: 204, and the users row is GONE, not salon_id IS NULL")
    void should_hardDeleteUsersRow_when_adminRemoved() {
        Admin admin = createSalonWithAdmin();

        salonService.removeAdmin(admin.ownerId(), admin.salonId(), admin.adminUserId());

        assertThat(userExists(admin.adminUserId()))
                .as("Phase 299 — the account row is hard-deleted, not merely unassigned")
                .isFalse();
    }

    // ── case 2 — the ON DELETE CASCADE fan-out ───────────────────────────────────────────────

    @Test
    @DisplayName("case 2 — refresh tokens, device tokens and password-reset tickets of the "
            + "removed admin are gone (cascade)")
    void should_cascadeSessionsAndTickets_when_adminRemoved() {
        Admin admin = createSalonWithAdmin();
        insertRefreshToken(admin.adminUserId());
        insertDeviceToken(admin.adminUserId());
        insertPasswordResetTicket(admin.adminUserId());
        assertThat(countRefreshTokens(admin.adminUserId())).isEqualTo(1);
        assertThat(countDeviceTokens(admin.adminUserId())).isEqualTo(1);
        assertThat(countPasswordResetTickets(admin.adminUserId())).isEqualTo(1);

        salonService.removeAdmin(admin.ownerId(), admin.salonId(), admin.adminUserId());

        assertThat(countRefreshTokens(admin.adminUserId())).isZero();
        assertThat(countDeviceTokens(admin.adminUserId())).isZero();
        assertThat(countPasswordResetTickets(admin.adminUserId())).isZero();
    }

    // ── case 3 — the removed admin's access token stops working immediately ─────────────────

    @Test
    @DisplayName("case 3 — the removed admin's PRE-REMOVAL access token no longer authenticates "
            + "on the very next request (Phase 295 HIGH-1 contract, now on this path)")
    void should_rejectAccessToken_when_adminWasRemoved() throws Exception {
        Admin admin = createSalonWithAdmin();
        String adminToken = fx.loginAndGetToken(fx.emailOf(admin.adminUserId()));
        HttpEntity<Void> authed = new HttpEntity<>(fx.bearerHeaders(adminToken));
        assertThat(restTemplate.exchange("/api/v1/users/me", HttpMethod.GET, authed, String.class)
                .getStatusCode())
                .as("control: the very same token must be accepted BEFORE the removal")
                .isEqualTo(HttpStatus.OK);

        salonService.removeAdmin(admin.ownerId(), admin.salonId(), admin.adminUserId());

        assertThat(userExists(admin.adminUserId())).isFalse();
        assertThat(restTemplate.exchange("/api/v1/users/me", HttpMethod.GET, authed, String.class)
                .getStatusCode())
                .as("a removed admin's outstanding access token must NOT authenticate")
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    // ── case 4 — phase 294 D5: created_by_user_id RESTRICT -> SET NULL ───────────────────────

    @Test
    @DisplayName("case 4 — a walk-in booking the removed admin rang up survives with "
            + "created_by_user_id NULL")
    void should_keepWalkInBookingWithNullCreator_when_creatingAdminRemoved() {
        Admin admin = createSalonWithAdmin();
        UUID masterUserId = fx.insertUserWithSalon(
                "ar-c4-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", admin.salonId());
        UUID masterId = insertMaster(masterUserId, admin.salonId());
        UUID serviceDefId = insertServiceDefinition(admin.salonId());
        UUID masterServiceId = insertMasterService(masterId, serviceDefId);
        UUID clientId = fx.insertUser("ar-c4-client-" + System.nanoTime() + "@beautica.test", "CLIENT");
        UUID bookingId = insertStandaloneBooking(
                clientId, masterId, masterServiceId, admin.salonId(), "COMPLETED", PAST);
        jdbcTemplate.update("UPDATE bookings SET created_by_user_id = ? WHERE id = ?",
                admin.adminUserId(), bookingId);

        salonService.removeAdmin(admin.ownerId(), admin.salonId(), admin.adminUserId());

        assertThat(bookingExists(bookingId)).isTrue();
        assertThat(bookingCreatedByUserId(bookingId)).isNull();
        assertThat(userExists(admin.adminUserId())).isFalse();
    }

    // ── case 5 — the promised re-invite actually works (Phase 296 acceptance shape) ─────────

    @Test
    @DisplayName("case 5 — the removed admin's email can be re-invited to the SAME salon and the "
            + "invite is accepted end to end — the capability the old javadoc promised and did not "
            + "deliver")
    void should_allowReInviteToSameSalon_when_adminRemoved() {
        Admin admin = createSalonWithAdmin();
        String adminEmail = fx.emailOf(admin.adminUserId());

        salonService.removeAdmin(admin.ownerId(), admin.salonId(), admin.adminUserId());

        salonService.inviteMaster(admin.ownerId(), admin.salonId(), adminEmail, Role.SALON_ADMIN);
        String rawToken = lastCapturedRawToken();
        ResponseEntity<String> accepted = restTemplate.postForEntity(
                "/api/v1/auth/invite/accept",
                new InviteAcceptRequest(rawToken, "N0vaP@rol9x!", "Нова", "Особа", "+380501234567"),
                String.class);

        assertThat(accepted.getStatusCode())
                .as("acceptance criterion — the whole point of the hard delete: existsByEmail no "
                        + "longer blocks a fresh invite for this address")
                .isEqualTo(HttpStatus.CREATED);
        UUID newUserId = userIdOf(adminEmail);
        assertThat(newUserId).isNotEqualTo(admin.adminUserId());
        assertThat(userRole(newUserId)).isEqualTo("SALON_ADMIN");
        assertThat(userSalonId(newUserId)).isEqualTo(admin.salonId());
    }

    // ── case 6a — D2: bookings.client_id blocks the removal ─────────────────────────────────

    @Test
    @DisplayName("case 6a — 409 when the admin's user is referenced as a bookings.client_id "
            + "elsewhere; nothing is deleted (D2)")
    void should_refuseWith409_when_adminUserIsBookingClient() {
        Admin admin = createSalonWithAdmin();
        UUID masterUserId = fx.insertUserWithSalon(
                "ar-c6a-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", admin.salonId());
        UUID masterId = insertMaster(masterUserId, admin.salonId());
        UUID serviceDefId = insertServiceDefinition(admin.salonId());
        UUID masterServiceId = insertMasterService(masterId, serviceDefId);
        insertStandaloneBooking(
                admin.adminUserId(), masterId, masterServiceId, admin.salonId(), "COMPLETED", PAST);

        assertThatThrownBy(() -> salonService.removeAdmin(
                admin.ownerId(), admin.salonId(), admin.adminUserId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        assertThat(userExists(admin.adminUserId())).isTrue();
    }

    // ── case 6b — D2: appointments.client_id (no sibling bookings row) also blocks it ───────

    @Test
    @DisplayName("case 6b — 409 when the admin's user is referenced ONLY as an "
            + "appointments.client_id (no sibling bookings row); nothing is deleted (D2)")
    void should_refuseWith409_when_adminUserIsAppointmentClientOnly() {
        Admin admin = createSalonWithAdmin();
        insertAppointmentHeader(admin.adminUserId(), admin.salonId());
        assertThat(count("SELECT COUNT(*) FROM bookings WHERE client_id = ?", admin.adminUserId()))
                .isZero();

        assertThatThrownBy(() -> salonService.removeAdmin(
                admin.ownerId(), admin.salonId(), admin.adminUserId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        assertThat(userExists(admin.adminUserId())).isTrue();
    }

    // ── case 7 — 403 self-removal, nothing written ───────────────────────────────────────────

    @Test
    @DisplayName("case 7 — 403 when the actor removes themselves; nothing written")
    void should_refuseWithForbidden_when_actorRemovesThemselves() {
        Admin admin = createSalonWithAdmin();

        assertThatThrownBy(() -> salonService.removeAdmin(
                admin.adminUserId(), admin.salonId(), admin.adminUserId()))
                .isInstanceOf(ForbiddenException.class);

        assertThat(userExists(admin.adminUserId())).isTrue();
    }

    // ── case 8 — 403 when userId is a SALON_ADMIN of a DIFFERENT salon ──────────────────────

    @Test
    @DisplayName("case 8 — 403 when userId is a SALON_ADMIN of a different salon "
            + "(adminBelongsToSalon arm)")
    void should_refuseWithForbidden_when_targetAdminBelongsToDifferentSalon() {
        Admin admin = createSalonWithAdmin();
        Admin otherAdmin = createSalonWithAdmin();

        assertThatThrownBy(() -> salonService.removeAdmin(
                admin.ownerId(), admin.salonId(), otherAdmin.adminUserId()))
                .isInstanceOf(ForbiddenException.class);

        assertThat(userExists(otherAdmin.adminUserId())).isTrue();
    }

    // ── case 9 — 403 when userId is a SALON_MASTER of this salon ────────────────────────────

    @Test
    @DisplayName("case 9 — 403 when userId is a SALON_MASTER of this salon — masters go through "
            + "Phase 297's endpoint, not this one")
    void should_refuseWithForbidden_when_targetIsSalonMasterOfThisSalon() {
        Admin admin = createSalonWithAdmin();
        UUID masterUserId = fx.insertUserWithSalon(
                "ar-c9-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", admin.salonId());
        insertMaster(masterUserId, admin.salonId());

        assertThatThrownBy(() -> salonService.removeAdmin(
                admin.ownerId(), admin.salonId(), masterUserId))
                .isInstanceOf(ForbiddenException.class);

        assertThat(userExists(masterUserId))
                .as("the master's account must survive an admin-removal call aimed at it")
                .isTrue();
    }

    // ── case 10 — other admins of the same salon, and the salon itself, are untouched ───────

    @Test
    @DisplayName("case 10 — other admins of the same salon are untouched, and the salon row "
            + "itself is untouched")
    void should_leaveOtherAdminsAndSalonUntouched_when_oneAdminRemoved() {
        Admin admin = createSalonWithAdmin();
        UUID otherAdminUserId = fx.insertAdmin(
                "ar-c10-peer-" + System.nanoTime() + "@beautica.test", admin.salonId());

        salonService.removeAdmin(admin.ownerId(), admin.salonId(), admin.adminUserId());

        assertThat(userExists(otherAdminUserId)).isTrue();
        assertThat(userSalonId(otherAdminUserId)).isEqualTo(admin.salonId());
        assertThat(salonIsActive(admin.salonId())).isTrue();
    }

    // ── case 11 — removing the LAST admin still succeeds (ownership-based, not count-based) ──

    @Test
    @DisplayName("case 11 — removing the LAST admin still succeeds — owner access is "
            + "ownership-based, never admin-count-based")
    void should_succeed_when_removingTheOnlyRemainingAdmin() {
        Admin admin = createSalonWithAdmin();
        assertThat(countAdminsForSalon(admin.salonId())).isEqualTo(1);

        salonService.removeAdmin(admin.ownerId(), admin.salonId(), admin.adminUserId());

        assertThat(countAdminsForSalon(admin.salonId())).isZero();
        assertThat(salonIsActive(admin.salonId())).isTrue();
    }

    // ── case 12 — idempotency comes from row absence, not a flag ────────────────────────────

    @Test
    @DisplayName("case 12 — a second removal of the same userId is 404: the row is simply gone")
    void should_throwNotFound_when_removingTheSameAdminTwice() {
        Admin admin = createSalonWithAdmin();

        salonService.removeAdmin(admin.ownerId(), admin.salonId(), admin.adminUserId());

        assertThatThrownBy(() -> salonService.removeAdmin(
                admin.ownerId(), admin.salonId(), admin.adminUserId()))
                .isInstanceOf(NotFoundException.class);
    }

    // ── case 13 — Phase 299 D7: a SALON_ADMIN may no longer remove a co-admin ──────────────
    //
    // D7 narrowed the controller's @PreAuthorize to hasRole('SALON_OWNER'); everything above this
    // point calls salonService.removeAdmin(...) directly, which bypasses that Spring Security AOP
    // proxy entirely and so cannot exercise the role gate. Cases 13-15 go over real HTTP for
    // exactly that reason — closing the LOW that all 13 pre-existing cases in this class use the
    // OWNER as actor and never drive an admin actor through the endpoint the gate actually sits on.

    @Test
    @DisplayName("case 13 — 403 when a SALON_ADMIN attempts to remove a co-admin (Phase 299 D7); "
            + "a TRUE no-op: the row survives, the target's refresh token still authenticates, and "
            + "TokensValidAfterCache is not evicted")
    void should_refuseWithForbidden_when_actorIsSalonAdminRemovingCoAdmin() throws Exception {
        Admin admin = createSalonWithAdmin();
        UUID coAdminId = fx.insertAdmin(
                "ar-c13-co-" + System.nanoTime() + "@beautica.test", admin.salonId());
        AuthResponse actingAuth = loginFull(fx.emailOf(admin.adminUserId()));
        AuthResponse targetAuth = loginFull(fx.emailOf(coAdminId));

        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ADMIN_URL, admin.salonId(), coAdminId), HttpMethod.DELETE,
                new HttpEntity<>(fx.bearerHeaders(actingAuth.accessToken())),
                String.class);

        assertThat(response.getStatusCode())
                .as("Phase 299 D7 — a SALON_ADMIN may no longer remove a co-admin; only the owner can")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userExists(coAdminId))
                .as("a denied removal must be a true no-op — the target's account row survives")
                .isTrue();
        assertThat(refreshTokenStillAuthenticates(targetAuth.refreshToken()))
                .as("the target's refresh token must still authenticate — a 403 that killed the "
                        + "peer's session would be the real bug a status-only assertion would miss")
                .isTrue();
        verify(tokensValidAfterCache, never()).invalidate(coAdminId);
    }

    // ── case 14 — Phase 299 D7: a SALON_ADMIN may no longer remove themselves either ────────

    @Test
    @DisplayName("case 14 — 403 when a SALON_ADMIN attempts to remove themselves — the role gate "
            + "now fires before the service-layer self-removal guard ever runs; a TRUE no-op")
    void should_refuseWithForbidden_when_salonAdminRemovesThemselves() throws Exception {
        Admin admin = createSalonWithAdmin();
        AuthResponse adminAuth = loginFull(fx.emailOf(admin.adminUserId()));

        ResponseEntity<String> response = restTemplate.exchange(
                String.format(ADMIN_URL, admin.salonId(), admin.adminUserId()), HttpMethod.DELETE,
                new HttpEntity<>(fx.bearerHeaders(adminAuth.accessToken())),
                String.class);

        assertThat(response.getStatusCode())
                .as("Phase 299 D7 — SALON_ADMIN is no longer an allowed role for this endpoint at all")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userExists(admin.adminUserId()))
                .as("a denied self-removal must be a true no-op — the actor's own row survives")
                .isTrue();
        assertThat(refreshTokenStillAuthenticates(adminAuth.refreshToken()))
                .as("the actor's own refresh token must still authenticate after the denied call")
                .isTrue();
        verify(tokensValidAfterCache, never()).invalidate(admin.adminUserId());
    }

    // ── case 15 — actor-matrix positive control: OWNER still succeeds over HTTP ─────────────

    @Test
    @DisplayName("case 15 — 204 when the OWNER removes a co-admin over real HTTP with a second "
            + "admin present (actor-matrix positive control for D7's narrowed gate)")
    void should_hardDelete_when_ownerRemovesCoAdmin_withSecondAdminPresent() throws Exception {
        Admin admin = createSalonWithAdmin();
        UUID secondAdminId = fx.insertAdmin(
                "ar-c15-second-" + System.nanoTime() + "@beautica.test", admin.salonId());
        String ownerToken = fx.loginAndGetToken(fx.emailOf(admin.ownerId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                String.format(ADMIN_URL, admin.salonId(), admin.adminUserId()), HttpMethod.DELETE,
                new HttpEntity<>(fx.bearerHeaders(ownerToken)),
                Void.class);

        assertThat(response.getStatusCode())
                .as("D7 must not have collaterally broken the owner-allowed path")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userExists(admin.adminUserId()))
                .as("Phase 299 — the owner-removed admin's row is hard-deleted")
                .isFalse();
        assertThat(userExists(secondAdminId))
                .as("the untouched second admin must survive")
                .isTrue();
        verify(tokensValidAfterCache).invalidate(admin.adminUserId());
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private static final String ADMIN_URL = "/api/v1/salons/%s/admins/%s";

    private record Admin(UUID ownerId, UUID salonId, UUID adminUserId) {}

    private Admin createSalonWithAdmin() {
        UUID ownerId = fx.insertUser("ar-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fx.insertSalon(ownerId, "Admin Removal Salon " + System.nanoTime());
        UUID adminUserId = fx.insertAdmin("ar-admin-" + System.nanoTime() + "@beautica.test", salonId);
        return new Admin(ownerId, salonId, adminUserId);
    }

    private UUID insertMaster(UUID userId, UUID salonId) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
    }

    private UUID insertServiceDefinition(UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                id, salonId, resolveUnusedServiceTypeId("SALON", salonId));
        return id;
    }

    private UUID insertMasterService(UUID masterId, UUID serviceDefId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, "
                        + "updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                id, masterId, serviceDefId);
        return id;
    }

    private UUID insertStandaloneBooking(UUID clientId, UUID masterId, UUID masterServiceId,
                                         UUID salonId, String status, OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status,
                startsAt, startsAt.plusMinutes(60));
        return bookingId;
    }

    private UUID insertAppointmentHeader(UUID clientId, UUID salonId) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO appointments (id, client_id, salon_id, status, booking_source, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'CONFIRMED', 'APP', NOW(), NOW())",
                appointmentId, clientId, salonId);
        return appointmentId;
    }

    private void insertRefreshToken(UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens (id, token, user_id, expires_at, is_revoked, family_id, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, NOW() + interval '30 days', false, ?, NOW(), NOW())",
                UUID.randomUUID(), "rt-" + UUID.randomUUID(), userId, UUID.randomUUID());
    }

    private void insertDeviceToken(UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO device_tokens (id, user_id, token, platform, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'ANDROID', true, NOW(), NOW())",
                UUID.randomUUID(), userId, "dt-" + UUID.randomUUID());
    }

    private void insertPasswordResetTicket(UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO password_reset_tickets (id, ticket_hash, user_id, expires_at, is_used, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, NOW() + interval '1 hour', false, NOW(), NOW())",
                UUID.randomUUID(), "prt-" + UUID.randomUUID(), userId);
    }

    /**
     * The last raw invite token minted in this test — see
     * {@code SalonStaffReinviteAfterDeletionIT#lastCapturedRawToken}'s identical rationale.
     */
    private String lastCapturedRawToken() {
        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(invitePersistenceService, atLeastOnce()).persistInviteAndEnqueue(
                anyString(), any(UUID.class), any(Role.class), any(Instant.class),
                anyString(), linkCaptor.capture(), anyString());
        List<String> links = linkCaptor.getAllValues();
        String link = links.get(links.size() - 1);
        String encoded = link.substring(link.indexOf("token=") + "token=".length());
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    /**
     * Logs in and returns the FULL {@link AuthResponse} (access + refresh token) — unlike
     * {@link SalonItFixtures#loginAndGetToken}, which discards the refresh token, cases 13-14 need
     * it to prove a denied removal left the target's outstanding session fully intact.
     */
    private AuthResponse loginFull(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, SalonItFixtures.TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data();
    }

    /** True iff {@code refreshToken} still mints a new access token — i.e. it was never revoked. */
    private boolean refreshTokenStillAuthenticates(String refreshToken) {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshRequest(refreshToken), String.class);
        return resp.getStatusCode() == HttpStatus.OK;
    }

    // ── assertions ──────────────────────────────────────────────────────────────────────────

    private boolean userExists(UUID userId) {
        return count("SELECT COUNT(*) FROM users WHERE id = ?", userId) == 1;
    }

    private boolean bookingExists(UUID bookingId) {
        return count("SELECT COUNT(*) FROM bookings WHERE id = ?", bookingId) == 1;
    }

    private UUID bookingCreatedByUserId(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM bookings WHERE id = ?", UUID.class, bookingId);
    }

    private UUID userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private String userRole(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId);
    }

    private UUID userSalonId(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT salon_id FROM users WHERE id = ?", UUID.class, userId);
    }

    private boolean salonIsActive(UUID salonId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM salons WHERE id = ?", Boolean.class, salonId));
    }

    private int countAdminsForSalon(UUID salonId) {
        return count("SELECT COUNT(*) FROM users WHERE salon_id = ? AND role = 'SALON_ADMIN'", salonId);
    }

    private int countRefreshTokens(UUID userId) {
        return count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", userId);
    }

    private int countDeviceTokens(UUID userId) {
        return count("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", userId);
    }

    private int countPasswordResetTickets(UUID userId) {
        return count("SELECT COUNT(*) FROM password_reset_tickets WHERE user_id = ?", userId);
    }

    private int count(String sql, Object arg) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arg);
        return value == null ? -1 : value;
    }
}
