package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.dto.CreateSalonRequest;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService — unit")
class SalonServiceTest {

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

    @Test
    @DisplayName("createSalon — saves and returns SalonResponse when owner already has one salon")
    void should_createSalon_when_ownerAlreadyHasOneSalon() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        var request = new CreateSalonRequest("Second Salon", null, "Kyiv", null, null, null, null);
        var savedSalon = buildSalon(UUID.randomUUID(), owner, "Second Salon");

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);

        SalonResponse response = salonService.createSalon(ownerId, request);

        assertThat(response.name()).isEqualTo("Second Salon");
        assertThat(response.ownerId()).isEqualTo(ownerId);
        verify(salonRepository).save(any(Salon.class));
    }

    @Test
    @DisplayName("getOwnerSalons — returns all salons mapped to SalonResponse when owner has multiple salons")
    void should_getOwnerSalons_when_ownerHasMultipleSalons() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        var salon1 = buildSalon(UUID.randomUUID(), owner, "Salon Alpha");
        var salon2 = buildSalon(UUID.randomUUID(), owner, "Salon Beta");

        when(salonRepository.findAllByOwnerIdFetchOwner(ownerId)).thenReturn(List.of(salon1, salon2));

        List<SalonResponse> responses = salonService.getOwnerSalons(ownerId);

        assertThat(responses).hasSize(2);
        assertThat(responses).extracting(SalonResponse::name)
                .containsExactlyInAnyOrder("Salon Alpha", "Salon Beta");
        assertThat(responses).allMatch(r -> ownerId.equals(r.ownerId()));
        verify(salonRepository).findAllByOwnerIdFetchOwner(ownerId);
    }

    @Test
    @DisplayName("createSalon — throws ForbiddenException when user is not SALON_OWNER")
    void should_throwForbidden_when_userIsNotSalonOwner() {
        UUID userId = UUID.randomUUID();
        User client = buildUser(userId, "client@beautica.com", Role.CLIENT);
        var request = new CreateSalonRequest("My Salon", null, null, null, null, null, null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(client));

        assertThatThrownBy(() -> salonService.createSalon(userId, request))
                .isInstanceOf(ForbiddenException.class);

        verify(salonRepository, never()).save(any());
    }

    @Test
    @DisplayName("getSalon — throws NotFoundException when salonId does not exist")
    void should_throwNotFound_when_getSalonWithUnknownId() {
        UUID salonId = UUID.randomUUID();

        when(salonRepository.findById(salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.getSalon(salonId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("updateSalon — applies non-null patch fields and saves when actor has management access")
    void should_updateSalon_when_ownerPatchesSalon() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Old Name");

        var request = new UpdateSalonRequest("New Name", null, "Lviv", null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));
        // authorizationService.enforceCanManageSalon is void — Mockito does nothing by default (access granted)

        SalonResponse response = salonService.updateSalon(ownerId, salonId, request);

        assertThat(response.name()).isEqualTo("New Name");
        assertThat(response.city()).isEqualTo("Lviv");
        verify(salonRepository).save(salon);
    }

    @Test
    @DisplayName("updateSalon — throws ForbiddenException when actor lacks management access")
    void should_throwForbidden_when_differentOwnerUpdatesSalon() {
        UUID realOwnerId = UUID.randomUUID();
        UUID attackerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User realOwner = buildUser(realOwnerId, "real@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, realOwner, "Salon");

        var request = new UpdateSalonRequest("Hijacked", null, null, null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        doThrow(new ForbiddenException("Access denied"))
                .when(authorizationService).enforceCanManageSalon(attackerId, salon);

        assertThatThrownBy(() -> salonService.updateSalon(attackerId, salonId, request))
                .isInstanceOf(ForbiddenException.class);

        verify(salonRepository, never()).save(any());
    }

    @Test
    @DisplayName("deactivateSalon — sets isActive to false and saves when owner requests")
    void should_deactivateSalon_when_ownerRequests() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Active Salon");

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(salon.isActive()).isFalse();
        verify(salonRepository).save(salon);
    }

    @Test
    @DisplayName("deactivateSalon — throws ForbiddenException when non-owner attempts deactivation")
    void should_throwForbidden_when_nonOwnerDeactivatesSalon() {
        UUID realOwnerId = UUID.randomUUID();
        UUID attackerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User realOwner = buildUser(realOwnerId, "real@beautica.com", Role.SALON_OWNER);
        User attacker = buildUser(attackerId, "attacker@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, realOwner, "Salon");

        when(userRepository.findById(attackerId)).thenReturn(Optional.of(attacker));
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        assertThatThrownBy(() -> salonService.deactivateSalon(attackerId, salonId))
                .isInstanceOf(ForbiddenException.class);

        verify(salonRepository, never()).save(any());
    }

    @Test
    @DisplayName("deactivateSalon — throws ForbiddenException when caller role is not SALON_OWNER (positive-guard coverage)")
    void should_throwForbiddenException_when_nonOwnerCallsDeactivateSalon() {
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User master = buildUser(masterId, "master@beautica.com", Role.SALON_MASTER);

        when(userRepository.findById(masterId)).thenReturn(Optional.of(master));

        assertThatThrownBy(() -> salonService.deactivateSalon(masterId, salonId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only SALON_OWNER may deactivate a salon");

        verify(salonRepository, never()).save(any());
    }

    private User buildUser(UUID id, String email, Role role) {
        var user = new User(email, "hashed", role, null, null, null);
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
