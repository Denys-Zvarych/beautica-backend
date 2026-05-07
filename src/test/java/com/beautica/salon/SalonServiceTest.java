package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
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

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(List.of(salon1, salon2));

        List<SalonResponse> responses = salonService.getOwnerSalons(ownerId);

        assertThat(responses).hasSize(2);
        assertThat(responses).extracting(SalonResponse::name)
                .containsExactlyInAnyOrder("Salon Alpha", "Salon Beta");
        assertThat(responses).allMatch(r -> ownerId.equals(r.ownerId()));
        verify(salonRepository).findAllByOwnerIdAndIsActiveTrue(ownerId);
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
    @DisplayName("getSalonEntity — throws NotFoundException when salonId does not exist")
    void should_throwNotFound_when_getSalonEntityWithUnknownId() {
        UUID salonId = UUID.randomUUID();

        when(salonRepository.findById(salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.getSalonEntity(salonId))
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
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(salon.isActive()).isFalse();
        verify(salonRepository).save(salon);
        verify(userRepository).findById(ownerId);
    }

    @Test
    @DisplayName("deactivateSalon — throws NotFoundException when salonId and ownerId do not match")
    void should_throwNotFound_when_salonNotOwnedByRequester() {
        UUID attackerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User attacker = buildUser(attackerId, "attacker@beautica.com", Role.SALON_OWNER);

        when(userRepository.findById(attackerId)).thenReturn(Optional.of(attacker));
        when(salonRepository.findByIdAndOwnerId(salonId, attackerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.deactivateSalon(attackerId, salonId))
                .isInstanceOf(NotFoundException.class);

        verify(salonRepository, never()).save(any());
        verify(userRepository).findById(attackerId);
    }

    @Test
    @DisplayName("deactivateSalon — makes exactly one repository call (findByIdAndOwnerId only)")
    void should_makeExactlyOneRepositoryCall_when_deactivateSalon() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Active Salon");

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.deactivateSalon(ownerId, salonId);

        verify(userRepository).findById(ownerId);
        verify(salonRepository).findByIdAndOwnerId(salonId, ownerId);
        verify(salonRepository, never()).findById(any());
        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
    }

    @Test
    @DisplayName("inviteMaster — delegates to inviteService when actor can manage salon")
    void should_delegateToInviteService_when_inviteMaster() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Salon salon = buildSalon(salonId, buildUser(actorId, "owner@test.com", Role.SALON_OWNER), "Test");
        var expected = new InviteResponse("master@test.com", Instant.now().plusSeconds(3600));

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        doNothing().when(authorizationService).enforceCanManageSalon(eq(actorId), eq(salon));
        when(inviteService.sendInvite(any(InviteRequest.class), eq(actorId))).thenReturn(expected);

        InviteResponse result = salonService.inviteMaster(actorId, salonId, "master@test.com", Role.SALON_MASTER);

        assertThat(result.invitedEmail()).isEqualTo("master@test.com");
        verify(inviteService).sendInvite(any(InviteRequest.class), eq(actorId));
    }

    @Test
    @DisplayName("getMastersBySalon — maps Page<Master> to Page<MasterSummaryResponse> via from() factory")
    void should_returnMasterSummaries_when_getMastersBySalon() {
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.ofSize(10);

        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master@beautica.test", Role.SALON_MASTER);
        Master master = Master.builder()
                .masterType(MasterType.SALON_MASTER)
                .user(user)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "id", masterId);

        Page<Master> pageOfMasters = new PageImpl<>(List.of(master), pageable, 1);
        when(masterRepository.findBySalonIdAndIsActiveTrue(salonId, pageable)).thenReturn(pageOfMasters);

        var result = salonService.getMastersBySalon(salonId, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).masterId()).isEqualTo(masterId);
        assertThat(result.getContent().get(0).userId()).isEqualTo(userId);
        verify(masterRepository).findBySalonIdAndIsActiveTrue(salonId, pageable);
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
