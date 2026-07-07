package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SalonService#rotateAdmin} (Phase 21.3).
 *
 * <p>Salon-scoping and "is this user actually a SALON_ADMIN of the source salon" are already
 * enforced by {@code @PreAuthorize} on {@code SalonController.rotateAdmin} ({@code canManageSalon}
 * + {@code adminBelongsToSalon}). These tests cover the service-layer behaviour that is NOT
 * expressible in SpEL: the defense-in-depth re-check, the same-owner requirement (via
 * {@link AuthorizationService#salonsShareOwner}), the no-op guard, and the exact mutation applied
 * to the target user.
 *
 * <p>Destination validation now uses the lightweight {@code SalonRepository.existsByIdAndIsActiveTrue}
 * projection instead of a full-entity {@code findById} (Perf MEDIUM-4) — no {@code Salon} entity
 * is loaded at all in this method any more. All destination-related denial reasons (cross-owner,
 * inactive, not-found) collapse to {@link ForbiddenException} (403) rather than distinct 404/400
 * statuses (Sec LOW-1 status-code oracle fix) — see
 * {@code should_throwForbidden_when_destinationSalonDoesNotExist} and
 * {@code should_throwForbidden_when_destinationSalonIsInactive} below.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService.rotateAdmin — unit")
class SalonServiceRotateAdminTest {

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private InviteService inviteService;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private LocalityWriteValidator localityWriteValidator;

    @Mock
    private MasterService masterService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private AuthorizationService authorizationService;

    @InjectMocks
    private SalonService salonService;

    @Test
    @DisplayName("moves salonId to the destination when the destination shares the same owner and is active")
    void should_moveSalonIdToDestination_when_rotationIsValid() {
        UUID actorId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User admin = buildSalonAdmin(adminId, sourceSalonId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(authorizationService.salonsShareOwner(sourceSalonId, destSalonId)).thenReturn(true);
        when(salonRepository.existsByIdAndIsActiveTrue(destSalonId)).thenReturn(true);

        var response = salonService.rotateAdmin(actorId, sourceSalonId, adminId, destSalonId);

        assertThat(admin.getSalonId())
                .as("rotateAdmin must move salon_id to the destination")
                .isEqualTo(destSalonId);
        assertThat(admin.getRole())
                .as("role must remain SALON_ADMIN")
                .isEqualTo(Role.SALON_ADMIN);
        assertThat(response.salonId()).isEqualTo(destSalonId);
        assertThat(response.userId()).isEqualTo(adminId);
    }

    @Test
    @DisplayName("throws NotFoundException when the target user does not exist")
    void should_throwNotFound_when_targetUserDoesNotExist() {
        UUID actorId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        UUID missingUserId = UUID.randomUUID();

        when(userRepository.findById(missingUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.rotateAdmin(actorId, sourceSalonId, missingUserId, destSalonId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("throws ForbiddenException (defense-in-depth) when the reloaded user is not a SALON_ADMIN")
    void should_throwForbidden_when_targetUserIsNotSalonAdmin() {
        UUID actorId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User notAnAdmin = buildSalonMasterUser(targetId, sourceSalonId);

        when(userRepository.findById(targetId)).thenReturn(Optional.of(notAnAdmin));

        assertThatThrownBy(() -> salonService.rotateAdmin(actorId, sourceSalonId, targetId, destSalonId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("throws ForbiddenException (defense-in-depth) when the admin belongs to a different salon")
    void should_throwForbidden_when_adminBelongsToDifferentSourceSalon() {
        UUID actorId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID otherSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User admin = buildSalonAdmin(adminId, otherSalonId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> salonService.rotateAdmin(actorId, sourceSalonId, adminId, destSalonId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("throws BusinessException(400) when the destination equals the source salon (no-op rotation)")
    void should_throwBadRequest_when_destinationEqualsSourceSalon() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User admin = buildSalonAdmin(adminId, salonId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> salonService.rotateAdmin(actorId, salonId, adminId, salonId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(authorizationService, never()).salonsShareOwner(any(), any());
    }

    @Test
    @DisplayName("throws ForbiddenException when the destination salon does not share the same owner")
    void should_throwForbidden_when_destinationSalonHasDifferentOwner() {
        UUID actorId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User admin = buildSalonAdmin(adminId, sourceSalonId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(authorizationService.salonsShareOwner(sourceSalonId, destSalonId)).thenReturn(false);

        assertThatThrownBy(() -> salonService.rotateAdmin(actorId, sourceSalonId, adminId, destSalonId))
                .isInstanceOf(ForbiddenException.class);
        // Sec LOW-2: the active-check query now runs unconditionally alongside salonsShareOwner so
        // the cross-owner, not-found, and inactive denial branches all perform identical DB work —
        // this used to assert never() invoked, which was the very timing asymmetry that was fixed.
        verify(salonRepository).existsByIdAndIsActiveTrue(destSalonId);
    }

    @Test
    @DisplayName("throws ForbiddenException (not a distinct 404 — Sec LOW-1) when the destination salon does not exist")
    void should_throwForbidden_when_destinationSalonDoesNotExist() {
        UUID actorId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User admin = buildSalonAdmin(adminId, sourceSalonId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(authorizationService.salonsShareOwner(sourceSalonId, destSalonId)).thenReturn(true);
        when(salonRepository.existsByIdAndIsActiveTrue(destSalonId)).thenReturn(false);

        assertThatThrownBy(() -> salonService.rotateAdmin(actorId, sourceSalonId, adminId, destSalonId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("throws ForbiddenException (not a distinct 400 — Sec LOW-1) when the destination salon is inactive (soft-deleted)")
    void should_throwForbidden_when_destinationSalonIsInactive() {
        UUID actorId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User admin = buildSalonAdmin(adminId, sourceSalonId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(authorizationService.salonsShareOwner(sourceSalonId, destSalonId)).thenReturn(true);
        when(salonRepository.existsByIdAndIsActiveTrue(destSalonId)).thenReturn(false);

        assertThatThrownBy(() -> salonService.rotateAdmin(actorId, sourceSalonId, adminId, destSalonId))
                .isInstanceOf(ForbiddenException.class);
        assertThat(admin.getSalonId())
                .as("admin must be untouched when the destination is rejected")
                .isEqualTo(sourceSalonId);
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
