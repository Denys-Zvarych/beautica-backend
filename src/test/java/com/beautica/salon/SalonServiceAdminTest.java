package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.entity.Salon;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SalonService permission boundaries around SALON_ADMIN (Phase 2.9).
 *
 * Covers:
 * - SALON_ADMIN may update salon details (management access gate)
 * - SALON_ADMIN may not deactivate a salon (owner-only destructive operation)
 * - SALON_ADMIN may send an invite to a new master (management access gate)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService — SALON_ADMIN permission boundaries")
class SalonServiceAdminTest {

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private InviteService inviteService;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private AuthorizationService authorizationService;

    @InjectMocks
    private SalonService salonService;

    // ── updateSalon ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateSalon — succeeds when actor is SALON_ADMIN with management access to the salon")
    void should_allowSalonAdminToUpdateSalonDetails() {
        UUID adminId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildOwner(UUID.randomUUID(), salonId);
        Salon salon = buildSalon(salonId, owner, "Original Name");

        var request = new UpdateSalonRequest("Updated Name", null, null, null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));
        // authorizationService.enforceCanManageSalon does nothing — access granted for SALON_ADMIN

        SalonResponse response = salonService.updateSalon(adminId, salonId, request);

        assertThat(response.name()).isEqualTo("Updated Name");
        verify(salonRepository).save(salon);
    }

    // ── deactivateSalon ───────────────────────────────────────────────────────

    @Test
    @DisplayName("deactivateSalon — throws ForbiddenException when actor is SALON_ADMIN")
    void should_denyDeletion_when_actorIsSalonAdmin() {
        UUID adminId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User admin = buildSalonAdmin(adminId, salonId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> salonService.deactivateSalon(adminId, salonId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only SALON_OWNER may deactivate a salon");

        verify(salonRepository, never()).save(any());
    }

    // ── inviteMaster ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("inviteMaster — succeeds when actor is SALON_ADMIN with management access to the salon")
    void should_allowSalonAdminToInviteMaster() {
        UUID adminId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildOwner(UUID.randomUUID(), salonId);
        Salon salon = buildSalon(salonId, owner, "Glamour Studio");
        var expectedResponse = new InviteResponse("newmaster@example.com",
                Instant.now().plusSeconds(259200));

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(inviteService.sendInvite(any(), any())).thenReturn(expectedResponse);
        // authorizationService.enforceCanManageSalon does nothing — access granted for SALON_ADMIN

        assertThatCode(() -> salonService.inviteMaster(adminId, salonId, "newmaster@example.com", Role.SALON_MASTER))
                .doesNotThrowAnyException();

        verify(inviteService).sendInvite(any(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User buildOwner(UUID id, UUID salonId) {
        var user = new User("owner@beautica.test", "hash", Role.SALON_OWNER, null, null, null, salonId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private User buildSalonAdmin(UUID id, UUID salonId) {
        var user = new User("admin@beautica.test", "hash", Role.SALON_ADMIN, null, null, null, salonId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Salon buildSalon(UUID id, User owner, String name) {
        var salon = Salon.builder()
                .owner(owner)
                .name(name)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(salon, "id", id);
        ReflectionTestUtils.setField(salon, "createdAt", Instant.now());
        return salon;
    }
}
