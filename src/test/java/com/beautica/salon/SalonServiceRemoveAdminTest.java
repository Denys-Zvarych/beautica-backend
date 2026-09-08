package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.auth.TokensValidAfterCache;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.audit.AuditOutcome;
import com.beautica.salon.audit.StaffClientReferenceAuditResult;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.salon.service.StaffClientReferenceAuditService;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SalonService#removeAdmin} (Phase 21.2; hard-delete behaviour added by
 * Phase 299).
 *
 * <p>Salon-scoping and "is this user actually a SALON_ADMIN of this salon" are already enforced by
 * {@code @PreAuthorize} on {@code SalonController.removeAdmin} ({@code canManageSalon} +
 * {@code adminBelongsToSalon}). These tests cover the service-layer behaviour that is NOT
 * expressible in SpEL: the defense-in-depth re-check, the self-removal guard, the Phase 299
 * fail-closed client-reference audit precondition, and — for the happy path — that the disposal is
 * delegated to the shared {@code disposeStaffAccounts} seam rather than a parallel implementation
 * (mirrors {@link SalonServiceRemoveMasterTest}'s exact rationale for {@code removeMaster}'s own
 * guards, and the real end-to-end hard-delete/re-invite/token-refusal behaviour is covered by
 * {@code AdminRemovalHardDeleteIT} against a real Postgres).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService.removeAdmin — unit")
class SalonServiceRemoveAdminTest {

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private InviteService inviteService;

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private LocalityWriteValidator localityWriteValidator;

    @Mock
    private MasterService masterService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private StaffClientReferenceAuditService staffClientReferenceAuditService;

    @Mock
    private TokensValidAfterCache tokensValidAfterCache;

    @Mock
    private Clock clock;

    // Audit-fix cycle 2: SalonService evicts the affected user's cached profile after commit
    // (createSalon, removeAdmin, rotateAdmin all mutate a `users` row). @InjectMocks passes null
    // for an UNDECLARED collaborator silently, so compileTestJava stays green and the omission
    // only surfaces as an NPE at runtime — this field must exist even when no test here reaches
    // an evict call.
    @Mock
    private com.beautica.common.cache.UserProfileCacheEvictor userProfileCacheEvictor;

    @InjectMocks
    private SalonService salonService;

    @Test
    @DisplayName("Phase 299: hard-deletes the admin's account via the shared disposeStaffAccounts "
            + "seam once the client-reference audit is CLEAN and the admin owns no masters row")
    void should_hardDeleteAdminAccount_when_removalIsValidAndAuditIsClean() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User admin = buildSalonAdmin(adminId, salonId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(staffClientReferenceAuditService.runAuditForStaffUserIds(List.of(adminId)))
                .thenReturn(StaffClientReferenceAuditResult.of(List.of(), Instant.EPOCH));
        // Phase 299 D1 finding: a plain admin has NO masters row, so the shared seam's
        // findAllByUserIdInWithUser lookup resolves to an empty list for them — this is the exact
        // path AdminRemovalHardDeleteIT and SalonStaffHardDeleteIT case 5b already pin.
        when(masterRepository.findAllByUserIdInWithUser(List.of(adminId))).thenReturn(List.of());
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);

        salonService.removeAdmin(ownerId, salonId, adminId);

        verify(inviteTokenRepository).deleteBySalonIdAndStaffUserIds(salonId, List.of(adminId));
        // The afterCommit-vs-immediate fallback branch was promoted verbatim from a private
        // SalonService method into TokensValidAfterCache.invalidateAfterCommit(UUID) — assert on
        // that seam, not the mock's internal invalidate(...), which the promoted seam now owns.
        verify(tokensValidAfterCache).invalidateAfterCommit(adminId);
        verify(userProfileCacheEvictor).evictAfterCommit(adminId);
        verify(userRepository).deleteAllByIdInBatch(List.of(adminId));
        // No masters row existed to detach or delete — findIdsWithHistoricalReferences is only
        // reachable once staffMasters is non-empty.
        verify(masterRepository, never()).findIdsWithHistoricalReferences(any());
        verify(masterRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Phase 299 D2: throws 409 and writes nothing when the admin's user is also "
            + "referenced as a client elsewhere")
    void should_throwConflict_when_clientReferenceAuditFindsViolations() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User admin = buildSalonAdmin(adminId, salonId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(staffClientReferenceAuditService.runAuditForStaffUserIds(List.of(adminId)))
                .thenReturn(StaffClientReferenceAuditResult.of(
                        List.of(new com.beautica.salon.audit.StaffClientReferenceViolation(
                                adminId, Role.SALON_ADMIN,
                                com.beautica.salon.audit.StaffClientReferenceType.BOOKING_CLIENT, 1L)),
                        Instant.EPOCH));

        assertThatThrownBy(() -> salonService.removeAdmin(ownerId, salonId, adminId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(inviteTokenRepository, tokensValidAfterCache, userProfileCacheEvictor);
        verify(userRepository, never()).deleteAllByIdInBatch(any());
    }

    @Test
    @DisplayName("throws NotFoundException when the target user does not exist")
    void should_throwNotFound_when_targetUserDoesNotExist() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID missingUserId = UUID.randomUUID();

        when(userRepository.findById(missingUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.removeAdmin(ownerId, salonId, missingUserId))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(staffClientReferenceAuditService, inviteTokenRepository);
    }

    @Test
    @DisplayName("throws ForbiddenException (defense-in-depth) when the reloaded user is not a SALON_ADMIN")
    void should_throwForbidden_when_targetUserIsNotSalonAdmin() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User notAnAdmin = buildSalonMasterUser(targetId, salonId);

        when(userRepository.findById(targetId)).thenReturn(Optional.of(notAnAdmin));

        assertThatThrownBy(() -> salonService.removeAdmin(ownerId, salonId, targetId))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(staffClientReferenceAuditService, inviteTokenRepository);
    }

    @Test
    @DisplayName("throws ForbiddenException (defense-in-depth) when the admin belongs to a different salon")
    void should_throwForbidden_when_adminBelongsToDifferentSalon() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID otherSalonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User admin = buildSalonAdmin(adminId, otherSalonId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> salonService.removeAdmin(ownerId, salonId, adminId))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(staffClientReferenceAuditService, inviteTokenRepository);
    }

    @Test
    @DisplayName("throws ForbiddenException with a clear message when the actor targets themselves")
    void should_throwForbidden_when_actorAttemptsSelfRemoval() {
        UUID salonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        assertThatThrownBy(() -> salonService.removeAdmin(adminId, salonId, adminId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Cannot remove yourself — ask another admin or the owner");

        verifyNoInteractions(userRepository, staffClientReferenceAuditService, inviteTokenRepository);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User buildSalonAdmin(UUID id, UUID salonId) {
        var user = new User("admin@beautica.test", "hash", Role.SALON_ADMIN, null, null, null, salonId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private User buildSalonMasterUser(UUID id, UUID salonId) {
        var user = new User("master@beautica.test", "hash", Role.SALON_MASTER, null, null, null, salonId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
