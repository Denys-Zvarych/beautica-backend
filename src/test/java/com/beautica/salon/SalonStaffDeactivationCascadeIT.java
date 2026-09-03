package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.SalonDeletionBlockedException;
import com.beautica.notification.repository.DeviceTokenRepository;
import com.beautica.review.service.RatingRecalculationService;
import com.beautica.salon.service.SalonService;
import com.beautica.user.PasswordResetTicketRepository;
import com.beautica.user.RefreshTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Real-DB coverage for {@code SalonService#deactivateSalon}'s salon-deletion staff-deactivation
 * cascade (Phase 290) — see
 * {@code docs/backend-phases/phase-290-salon-deletion-staff-deactivation.md}.
 *
 * <p>Every fixture is inserted with raw SQL (mirrors {@code StaffClientReferenceAuditServiceIT}'s
 * local house convention) rather than driven through the invite/registration flow — this class
 * cares about the STATE {@code deactivateSalon} leaves behind, not about how staff came to exist.
 */
@DisplayName("SalonService.deactivateSalon — Phase 290 salon-deletion staff-deactivation cascade")
class SalonStaffDeactivationCascadeIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private SalonService salonService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Phase 290 perf-fix pinning (SpyBean wraps the REAL bean — end-state assertions elsewhere
    // in this class stay meaningful, these add statement-COUNT assertions on top).
    @SpyBean
    private RefreshTokenRepository refreshTokenRepository;

    @SpyBean
    private DeviceTokenRepository deviceTokenRepository;

    @SpyBean
    private PasswordResetTicketRepository passwordResetTicketRepository;

    @SpyBean
    private RatingRecalculationService ratingRecalculationService;

    /**
     * Pins Phase 290 finding #1: the 3N single-row session/notification-purge round trips must
     * be exactly 3 bulk statements, not N per-user statements — a regression to the per-user loop
     * passes every OTHER test in this class (they assert end state, e.g. {@code countRefreshTokens
     * == 0}), which is exactly why this needs its own statement-count assertion.
     */
    @Test
    @DisplayName("N staff members — token/ticket purge is exactly 3 bulk statements (deleteByUserIdIn "
            + "x2 + markAllUsedByUserIdIn), NEVER the per-user singular methods")
    void should_purgeTokensWithExactlyThreeBulkStatements_when_multipleStaffDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        UUID masterAId = createStaffUser("cascade-bulk-a-", "SALON_MASTER", salonId);
        createMasterRow(masterAId, salonId, "SALON_MASTER");
        UUID masterBId = createStaffUser("cascade-bulk-b-", "SALON_MASTER", salonId);
        createMasterRow(masterBId, salonId, "SALON_MASTER");
        UUID adminId = createStaffUser("cascade-bulk-admin-", "SALON_ADMIN", salonId);

        insertRefreshToken(masterAId);
        insertRefreshToken(masterBId);
        insertRefreshToken(adminId);
        insertDeviceToken(masterAId);
        insertDeviceToken(masterBId);
        insertPasswordResetTicket(adminId);

        salonService.deactivateSalon(ownerId, salonId);

        verify(refreshTokenRepository, times(1)).deleteByUserIdIn(anyCollection());
        verify(refreshTokenRepository, never()).deleteByUserId(any());
        verify(deviceTokenRepository, times(1)).deleteByUserIdIn(anyCollection());
        verify(deviceTokenRepository, never()).deleteByUserId(any());
        verify(passwordResetTicketRepository, times(1)).markAllUsedByUserIdIn(anyCollection());
        verify(passwordResetTicketRepository, never()).markAllUsedByUserId(any());

        // End-state sanity — the bulk statement actually did the work, not just ran zero-row.
        assertThat(countRefreshTokens(masterAId)).isZero();
        assertThat(countRefreshTokens(masterBId)).isZero();
        assertThat(countRefreshTokens(adminId)).isZero();
        assertThat(countDeviceTokens(masterAId)).isZero();
        assertThat(countDeviceTokens(masterBId)).isZero();
        assertThat(isPasswordResetTicketUsed(adminId)).isTrue();
    }

    /**
     * Pins Phase 290 finding #2 (and, by construction, finding #4 — the {@code salon-detail} /
     * {@code reviews-by-salon} evictions live inside the SAME {@code AFTER_COMMIT} listener
     * invocation): N masters deactivated in one cascade must trigger exactly ONE
     * {@code recalculateSalonRating} call for the salon, not N. A regression to the per-master
     * {@code publishSalonStaffChanged} call passes every end-state test in this class (the rating
     * recompute is idempotent — running it N times leaves the same number as running it once) —
     * which is exactly why this needs an invocation-COUNT assertion, not another value assertion.
     */
    @Test
    @DisplayName("N masters deactivated in one cascade — recalculateSalonRating fires exactly ONCE for the salon")
    void should_recalculateSalonRatingExactlyOnce_when_multipleMastersDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        for (int i = 0; i < 3; i++) {
            UUID masterUserId = createStaffUser("cascade-recalc-" + i + "-", "SALON_MASTER", salonId);
            createMasterRow(masterUserId, salonId, "SALON_MASTER");
        }

        salonService.deactivateSalon(ownerId, salonId);

        verify(ratingRecalculationService, times(1)).recalculateSalonRating(salonId);
    }

    @Test
    @DisplayName("one master + one admin — masters.is_active and users.is_active both flip false, "
            + "tokens_valid_after is stamped, refresh/device tokens purged, outstanding "
            + "password-reset tickets invalidated")
    void should_deactivateMastersAndAdmins_when_salonDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        UUID masterUserId = createStaffUser("cascade-master-", "SALON_MASTER", salonId);
        UUID masterRowId = createMasterRow(masterUserId, salonId, "SALON_MASTER");
        UUID adminId = createStaffUser("cascade-admin-", "SALON_ADMIN", salonId);

        insertRefreshToken(masterUserId);
        insertRefreshToken(adminId);
        insertDeviceToken(masterUserId);
        insertDeviceToken(adminId);
        insertPasswordResetTicket(masterUserId);

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(isSalonActive(salonId)).isFalse();

        assertThat(isMasterActive(masterRowId)).isFalse();
        assertThat(isUserActive(masterUserId)).isFalse();
        assertThat(hasTokensValidAfter(masterUserId)).isTrue();
        assertThat(countRefreshTokens(masterUserId)).isZero();
        assertThat(countDeviceTokens(masterUserId)).isZero();
        assertThat(isPasswordResetTicketUsed(masterUserId)).isTrue();

        assertThat(isUserActive(adminId)).isFalse();
        assertThat(hasTokensValidAfter(adminId)).isTrue();
        assertThat(countRefreshTokens(adminId)).isZero();
        assertThat(countDeviceTokens(adminId)).isZero();
    }

    @Test
    @DisplayName("masters.salon_id is RETAINED on deactivation, never nulled — nulling would read "
            + "as promotion to INDEPENDENT_MASTER")
    void should_retainMasterSalonId_when_salonDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        UUID masterUserId = createStaffUser("cascade-retain-", "SALON_MASTER", salonId);
        UUID masterRowId = createMasterRow(masterUserId, salonId, "SALON_MASTER");

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(isMasterActive(masterRowId)).isFalse();
        assertThat(masterSalonId(masterRowId))
                .as("salon_id must survive deactivation unchanged")
                .isEqualTo(salonId);
    }

    @Test
    @DisplayName("owner-as-master: the owner's OWN SALON_OWNER-type master row in the deleted "
            + "salon deactivates, but their users row — including its refresh tokens — is never "
            + "touched")
    void should_deactivateOwnerMasterRow_butNeverTheOwnerUsersRow_when_salonDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        UUID ownerMasterRowId = createMasterRow(ownerId, salonId, "SALON_OWNER");
        insertRefreshToken(ownerId);

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(isMasterActive(ownerMasterRowId))
                .as("the owner's own master row in THIS salon must still deactivate")
                .isFalse();
        assertThat(isUserActive(ownerId))
                .as("the owner's account is NEVER deactivated (Phase 290 D3, locked)")
                .isTrue();
        assertThat(userRole(ownerId)).isEqualTo("SALON_OWNER");
        assertThat(hasTokensValidAfter(ownerId))
                .as("tokensValidAfter must never be stamped for the owner by this cascade")
                .isFalse();
        assertThat(countRefreshTokens(ownerId))
                .as("the owner's refresh token must survive — their session is untouched")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a master belonging to a DIFFERENT salon is untouched — the negative that proves "
            + "the cascade is scoped, not platform-wide")
    void should_leaveOtherSalonsMasterUntouched_when_salonDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);

        UUID otherOwnerId = createOwner();
        UUID otherSalonId = createSalon(otherOwnerId);
        UUID otherMasterUserId = createStaffUser("cascade-other-master-", "SALON_MASTER", otherSalonId);
        UUID otherMasterRowId = createMasterRow(otherMasterUserId, otherSalonId, "SALON_MASTER");
        UUID otherAdminId = createStaffUser("cascade-other-admin-", "SALON_ADMIN", otherSalonId);

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(isMasterActive(otherMasterRowId)).isTrue();
        assertThat(isUserActive(otherMasterUserId)).isTrue();
        assertThat(isUserActive(otherAdminId)).isTrue();
        assertThat(isSalonActive(otherSalonId)).isTrue();
    }

    @Test
    @DisplayName("audit VIOLATIONS_FOUND aborts the WHOLE deletion before any mutation — salon "
            + "stays active, no staff row is touched")
    void should_abortWithoutMutating_when_auditFindsViolation() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        Provider provider = createProviderWithMaster(salonId);
        // The provider's own master user is referenced as a booking client — bypassing the
        // service layer entirely, exactly as StaffClientReferenceAuditServiceIT's fixtures do
        // (every application write path rejects a non-CLIENT actor here; this can only exist via
        // a seed/fixture script, which is precisely what this simulates).
        insertBooking(provider.masterUserId(), provider);

        assertThatThrownBy(() -> salonService.deactivateSalon(ownerId, salonId))
                .isInstanceOf(SalonDeletionBlockedException.class);

        assertThat(isSalonActive(salonId))
                .as("the salon must remain active — the abort happens before salon.setActive(false)")
                .isTrue();
        assertThat(isMasterActive(provider.masterRowId())).isTrue();
        assertThat(isUserActive(provider.masterUserId())).isTrue();
    }

    @Test
    @DisplayName("a second DELETE on an already-inactive salon is a no-op — tokensValidAfter is "
            + "not re-stamped and the master row is not re-mutated")
    void should_noOp_when_salonAlreadyInactive() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        UUID masterUserId = createStaffUser("cascade-idempotent-master-", "SALON_MASTER", salonId);
        UUID masterRowId = createMasterRow(masterUserId, salonId, "SALON_MASTER");

        salonService.deactivateSalon(ownerId, salonId);
        Timestamp firstTokensValidAfter = tokensValidAfterTimestamp(masterUserId);

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(tokensValidAfterTimestamp(masterUserId))
                .as("a repeat DELETE must not re-stamp tokensValidAfter — that would prove a re-scrub ran")
                .isEqualTo(firstTokensValidAfter);
        assertThat(isMasterActive(masterRowId)).isFalse();
        assertThat(isSalonActive(salonId)).isFalse();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private record Provider(UUID salonId, UUID masterUserId, UUID masterRowId, UUID masterServiceId) {}

    private UUID createOwner() {
        return createUser("cascade-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER", null);
    }

    private UUID createSalon(UUID ownerId) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());
        return salonId;
    }

    private UUID createStaffUser(String emailPrefix, String role, UUID salonId) {
        return createUser(emailPrefix + System.nanoTime() + "@beautica.test", role, salonId);
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private UUID createMasterRow(UUID userId, UUID salonId, String masterType) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, NOW(), NOW())",
                masterId, userId, salonId, masterType);
        return masterId;
    }

    private Provider createProviderWithMaster(UUID salonId) {
        UUID masterUserId = createStaffUser("cascade-audit-master-", "SALON_MASTER", salonId);
        UUID masterRowId = createMasterRow(masterUserId, salonId, "SALON_MASTER");

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterRowId, serviceDefId);

        return new Provider(salonId, masterUserId, masterRowId, masterServiceId);
    }

    private void insertBooking(UUID clientId, Provider provider) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, provider.masterRowId(), provider.masterServiceId(), provider.salonId());
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

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    // ── assertions ──────────────────────────────────────────────────────────────────────────

    private boolean isSalonActive(UUID salonId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM salons WHERE id = ?", Boolean.class, salonId));
    }

    private boolean isMasterActive(UUID masterId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM masters WHERE id = ?", Boolean.class, masterId));
    }

    private UUID masterSalonId(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT salon_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private boolean isUserActive(UUID userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM users WHERE id = ?", Boolean.class, userId));
    }

    private String userRole(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId);
    }

    private boolean hasTokensValidAfter(UUID userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT tokens_valid_after IS NOT NULL FROM users WHERE id = ?", Boolean.class, userId));
    }

    private Timestamp tokensValidAfterTimestamp(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT tokens_valid_after FROM users WHERE id = ?", Timestamp.class, userId);
    }

    private int countRefreshTokens(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
    }

    private int countDeviceTokens(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", Integer.class, userId);
    }

    private boolean isPasswordResetTicketUsed(UUID userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_used FROM password_reset_tickets WHERE user_id = ? LIMIT 1",
                Boolean.class, userId));
    }
}
