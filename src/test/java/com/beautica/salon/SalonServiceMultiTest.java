package com.beautica.salon;

import com.beautica.auth.Role;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import com.beautica.auth.InviteService;
import com.beautica.common.security.AuthorizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService — multi-salon unit")
class SalonServiceMultiTest {

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

    // -------------------------------------------------------------------------
    // createSalon — second salon for owner who already has one
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createSalon — saves second salon when owner already has one")
    void should_createSecondSalon_when_ownerAlreadyHasOne() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.test", Role.SALON_OWNER);
        var request = new CreateSalonRequest("Second Salon", null, "Lviv", null, null, null, null);
        var savedSalon = buildSalon(UUID.randomUUID(), owner, "Second Salon");

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);

        SalonResponse response = salonService.createSalon(ownerId, request);

        assertThat(response.name()).isEqualTo("Second Salon");
        assertThat(response.ownerId()).isEqualTo(ownerId);
        verify(salonRepository).save(any(Salon.class));
    }

    // -------------------------------------------------------------------------
    // getOwnerSalons — multiple salons returned
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getOwnerSalons — returns all salons when owner has multiple")
    void should_returnAllSalons_when_ownerHasMultiple() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.test", Role.SALON_OWNER);
        var salon1 = buildSalon(UUID.randomUUID(), owner, "Salon Alpha");
        var salon2 = buildSalon(UUID.randomUUID(), owner, "Salon Beta");
        var salon3 = buildSalon(UUID.randomUUID(), owner, "Salon Gamma");

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId))
                .thenReturn(List.of(salon1, salon2, salon3));

        List<SalonResponse> responses = salonService.getOwnerSalons(ownerId);

        assertThat(responses).hasSize(3);
        assertThat(responses).extracting(SalonResponse::name)
                .containsExactlyInAnyOrder("Salon Alpha", "Salon Beta", "Salon Gamma");
        assertThat(responses).allMatch(r -> ownerId.equals(r.ownerId()));
        verify(salonRepository).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }

    // -------------------------------------------------------------------------
    // getOwnerSalons — empty list when owner has no salons
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getOwnerSalons — returns empty list when owner has no salons")
    void should_returnEmptyList_when_ownerHasNoSalons() {
        UUID ownerId = UUID.randomUUID();

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId))
                .thenReturn(Collections.emptyList());

        List<SalonResponse> responses = salonService.getOwnerSalons(ownerId);

        assertThat(responses).isEmpty();
        verify(salonRepository).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }

    // -------------------------------------------------------------------------
    // createSalon — owner.setSalonId() must NOT be called
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createSalon — does not call setSalonId on owner after salon is created")
    void should_notSetSalonIdOnOwner_when_salonCreated() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.test", Role.SALON_OWNER);
        var request = new CreateSalonRequest("My Salon", null, "Kyiv", null, null, null, null);
        var savedSalon = buildSalon(UUID.randomUUID(), owner, "My Salon");

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);

        salonService.createSalon(ownerId, request);

        // owner.setSalonId() was removed in multi-salon refactor; salonId on User must remain null
        assertThat(owner.getSalonId())
                .as("owner.salonId must not be set by createSalon in multi-salon model")
                .isNull();
        // userRepository.save must never be called just to update the owner's salonId
        verify(userRepository, never()).save(any(User.class));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
