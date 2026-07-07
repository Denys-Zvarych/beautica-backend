package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SalonService#removeAdmin} (Phase 21.2).
 *
 * <p>Salon-scoping and "is this user actually a SALON_ADMIN of this salon" are already enforced
 * by {@code @PreAuthorize} on {@code SalonController.removeAdmin} ({@code canManageSalon} +
 * {@code adminBelongsToSalon}). These tests cover the service-layer behaviour that is NOT
 * expressible in SpEL: the defense-in-depth re-check, the self-removal guard, and the exact
 * mutation applied to the target user.
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
    private MasterRepository masterRepository;

    @Mock
    private LocalityWriteValidator localityWriteValidator;

    @Mock
    private MasterService masterService;

    @Mock
    private CacheManager cacheManager;

    @InjectMocks
    private SalonService salonService;

    @Test
    @DisplayName("clears salonId while leaving role and isActive unchanged for a valid admin removal")
    void should_clearSalonIdOnly_when_removalIsValid() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User admin = buildSalonAdmin(adminId, salonId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));

        salonService.removeAdmin(ownerId, salonId, adminId);

        assertThat(admin.getSalonId())
                .as("removeAdmin must null out salon_id")
                .isNull();
        assertThat(admin.getRole())
                .as("role must remain SALON_ADMIN — this is an unassignment, not a deactivation")
                .isEqualTo(Role.SALON_ADMIN);
        assertThat(admin.isActive())
                .as("isActive must remain true — the account itself is not deactivated")
                .isTrue();
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
    }

    @Test
    @DisplayName("throws ForbiddenException with a clear message when the actor targets themselves")
    void should_throwForbidden_when_actorAttemptsSelfRemoval() {
        UUID salonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        assertThatThrownBy(() -> salonService.removeAdmin(adminId, salonId, adminId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Cannot remove yourself — ask another admin or the owner");

        verifyNoInteractions(userRepository);
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
