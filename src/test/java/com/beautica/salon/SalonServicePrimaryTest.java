package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 12.1 — unit tests for the isPrimary assignment logic in SalonService.createSalon.
 *
 * <p>The existing SalonServiceTest covers multi-salon creation but does not stub
 * existsByOwnerId or assert isPrimary. These tests close that gap by isolating the
 * two branching paths: first salon (no prior salons → isPrimary=true) and subsequent
 * salon (prior salons exist → isPrimary=false).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService — isPrimary assignment (Phase 12.1)")
class SalonServicePrimaryTest {

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

    // ── isPrimary = true when this is the owner's first salon ─────────────────

    @Test
    @DisplayName("createSalon — sets isPrimary=true when the owner has no prior salons")
    void should_setPrimaryTrue_when_ownerHasNoPriorSalons() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("First Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(false);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.createSalon(ownerId, request);

        ArgumentCaptor<Salon> captor = ArgumentCaptor.forClass(Salon.class);
        verify(salonRepository).save(captor.capture());
        assertThat(captor.getValue().isPrimary())
                .as("first salon for owner must be marked isPrimary=true")
                .isTrue();
    }

    // ── isPrimary = false when the owner already has at least one salon ───────

    @Test
    @DisplayName("createSalon — sets isPrimary=false when the owner already has at least one salon")
    void should_setPrimaryFalse_when_ownerAlreadyHasAtLeastOneSalon() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("Second Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.createSalon(ownerId, request);

        ArgumentCaptor<Salon> captor = ArgumentCaptor.forClass(Salon.class);
        verify(salonRepository).save(captor.capture());
        assertThat(captor.getValue().isPrimary())
                .as("subsequent salon for owner must be marked isPrimary=false")
                .isFalse();
    }

    // ── isPrimary check uses existsByOwnerId, not a manual count ─────────────

    @Test
    @DisplayName("createSalon — calls existsByOwnerId to determine primary status")
    void should_callExistsByOwnerId_when_decidingPrimaryStatus() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("Any Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(false);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.createSalon(ownerId, request);

        verify(salonRepository).existsByOwnerId(ownerId);
    }

    // ── non-owner role still rejected before existsByOwnerId is reached ───────

    @Test
    @DisplayName("createSalon — throws ForbiddenException before existsByOwnerId when caller is CLIENT")
    void should_throwForbidden_before_existsByOwnerIdCheck_when_callerIsClient() {
        UUID userId = UUID.randomUUID();
        User client = buildUser(userId, Role.CLIENT);
        var request = new CreateSalonRequest("Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(client));

        assertThatThrownBy(() -> salonService.createSalon(userId, request))
                .isInstanceOf(ForbiddenException.class)
                .as("CLIENT must be rejected before the primary-check or save");

        verify(salonRepository, never()).existsByOwnerId(any());
        verify(salonRepository, never()).save(any());
    }

    // ── isPrimary defaults to false on a new Salon entity ────────────────────

    @Test
    @DisplayName("Salon entity — isPrimary defaults to false when built without explicit value")
    void should_defaultIsPrimaryToFalse_when_salonBuiltWithoutExplicitValue() {
        User owner = buildOwner(UUID.randomUUID());

        Salon salon = Salon.builder()
                .owner(owner)
                .name("Test")
                .isActive(true)
                .build();

        assertThat(salon.isPrimary())
                .as("isPrimary must default to false per Phase 12.1 architecture decision")
                .isFalse();
    }

    // ── SalonResponse.from propagates isPrimary field ─────────────────────────

    @Test
    @DisplayName("createSalon — returned SalonResponse reflects isPrimary=true for first salon")
    void should_returnSalonResponseWithIsPrimaryTrue_when_firstSalon() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("Primary Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(false);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> {
            Salon s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
            return s;
        });

        SalonResponse response = salonService.createSalon(ownerId, request);

        assertThat(response.isPrimary())
                .as("SalonResponse.isPrimary must reflect the saved entity value")
                .isTrue();
    }

    @Test
    @DisplayName("createSalon — returned SalonResponse reflects isPrimary=false for subsequent salon")
    void should_returnSalonResponseWithIsPrimaryFalse_when_subsequentSalon() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("Second Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> {
            Salon s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
            return s;
        });

        SalonResponse response = salonService.createSalon(ownerId, request);

        assertThat(response.isPrimary())
                .as("SalonResponse.isPrimary must be false when owner already has a salon")
                .isFalse();
    }

    // ── auto-create master on first salon (Phase 12.2) ───────────────────────

    @Test
    @DisplayName("createSalon — sets isPrimary=true AND calls masterService.createMasterForOwner(User,Salon) when first salon")
    void should_setPrimary_andAutoCreateMaster_when_firstSalon() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("First Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(false);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> {
            Salon s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
            return s;
        });
        // Entity overload — SalonService now passes the already-loaded owner and savedSalon.
        when(masterService.createMasterForOwner(any(User.class), any(Salon.class)))
                .thenReturn(mock(Master.class));

        SalonResponse response = salonService.createSalon(ownerId, request);

        assertThat(response.isPrimary()).isTrue();

        ArgumentCaptor<Salon> salonCaptor = ArgumentCaptor.forClass(Salon.class);
        verify(masterService).createMasterForOwner(any(User.class), salonCaptor.capture());
        assertThat(salonCaptor.getValue()).isNotNull();
        assertThat(salonCaptor.getValue().isPrimary())
                .as("the saved salon passed to masterService must already be marked isPrimary")
                .isTrue();
    }

    @Test
    @DisplayName("createSalon — sets isPrimary=false AND does NOT call masterService.createMasterForOwner when additional salon")
    void should_notSetPrimary_andNotCreateMaster_when_additionalSalon() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("Second Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        SalonResponse response = salonService.createSalon(ownerId, request);

        assertThat(response.isPrimary()).isFalse();
        // Entity overload must never be called for subsequent salons.
        verify(masterService, never()).createMasterForOwner(any(User.class), any(Salon.class));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User buildOwner(UUID id) {
        return buildUser(id, Role.SALON_OWNER);
    }

    private User buildUser(UUID id, Role role) {
        var user = new User("user@beautica.test", "hashed", role, null, null, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
