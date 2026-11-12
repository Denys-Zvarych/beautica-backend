package com.beautica.salon;

import com.beautica.TestConstants;
import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.location.repository.CityRepository;
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
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 12.1 — unit tests for the isPrimary assignment logic in SalonService.createSalon.
 *
 * <p>The existing SalonServiceTest covers multi-salon creation but does not stub
 * the active-salon count or assert isPrimary. These tests close that gap by isolating the
 * two branching paths: first salon (no ACTIVE salon → isPrimary=true) and subsequent
 * salon (an active salon exists → isPrimary=false).
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

    // CRITICAL: must be declared so @InjectMocks can satisfy the CityRepository constructor
    // parameter — without it the field receives null and resolveOblastId throws NPE whenever
    // getCityId() returns a non-null value (mirrors MasterServiceTest). CityRepository backs
    // ONLY the batch resolveOblastIdsByCityIds sibling now (getOwnerSalons) — the single-row
    // resolveOblastId delegates to the shared LocationQueryService below (Phase 240 perf fix).
    @Mock
    private CityRepository cityRepository;

    // Shared city/region label lookup (salon + owner denorm). Declared so @InjectMocks does not
    // pass null; the default Optional.empty() stub is a no-op resolution.
    @Mock
    private com.beautica.location.SettlementDisplayNameResolver settlementDisplayNameResolver;

    @Mock
    private com.beautica.location.service.LocationQueryService locationQueryService;

    // Audit-fix cycle 2: SalonService evicts the affected user's cached profile after commit
    // (createSalon, removeAdmin, rotateAdmin all mutate a `users` row). @InjectMocks passes null
    // for an UNDECLARED collaborator silently, so compileTestJava stays green and the omission
    // only surfaces as an NPE at runtime — this field must exist even when no test here reaches
    // an evict call.
    @Mock
    private com.beautica.common.cache.UserProfileCacheEvictor userProfileCacheEvictor;

    // Phase 301: SalonService now constructor-depends on the promoted staff-account
    // hard-delete seam. None of these tests exercise a path that dereferences it, so a plain
    // mock satisfies the constructor without any stubbing.
    @Mock
    private com.beautica.salon.service.StaffAccountDisposalService staffAccountDisposalService;

    // Phase 376: createSalon generates the new salon's booking slug via BookingSlugService.
    // @InjectMocks passes null for an UNDECLARED collaborator, so createSalon would NPE.
    @Mock
    private com.beautica.booking.service.BookingSlugService bookingSlugService;

    @InjectMocks
    private SalonService salonService;

    // ── isPrimary = true when this is the owner's first salon ─────────────────

    @Test
    @DisplayName("createSalon — sets isPrimary=true when the owner has no prior salons")
    void should_setPrimaryTrue_when_ownerHasNoPriorSalons() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("First Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.countByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(0L);
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

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.countByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(1L);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.createSalon(ownerId, request);

        ArgumentCaptor<Salon> captor = ArgumentCaptor.forClass(Salon.class);
        verify(salonRepository).save(captor.capture());
        assertThat(captor.getValue().isPrimary())
                .as("subsequent salon for owner must be marked isPrimary=false")
                .isFalse();
    }

    // ── first-salon path releases is_primary held by deleted salons (SAL-04) ─

    @Test
    @DisplayName("createSalon — clears is_primary on inactive salons when owner has no active salon")
    void should_clearInactivePrimaries_when_ownerHasNoActiveSalon() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("Any Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.countByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(0L);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.createSalon(ownerId, request);

        verify(salonRepository).clearPrimaryOnInactiveSalons(ownerId);
        verify(salonRepository, never()).existsByOwnerId(any());
    }

    @Test
    @DisplayName("createSalon — releases deleted salons' is_primary BEFORE saving the new primary salon (V56 unique index)")
    void should_clearInactivePrimariesBeforeSave_when_firstSalonPath() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("Recreated Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.countByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(0L);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.createSalon(ownerId, request);

        InOrder order = inOrder(salonRepository, masterService);
        order.verify(salonRepository).clearPrimaryOnInactiveSalons(ownerId);
        order.verify(salonRepository).save(any(Salon.class));
        order.verify(masterService).createMasterForOwner(any(User.class), any(Salon.class));
    }

    @Test
    @DisplayName("createSalon — leaves other salons' is_primary untouched when owner has an active salon")
    void should_notClearPrimaries_when_ownerHasActiveSalon() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("Any Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.countByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(1L);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.createSalon(ownerId, request);

        verify(salonRepository, never()).clearPrimaryOnInactiveSalons(any());
    }

    // ── stranded owner-master while another salon is active (SAL-04, audit finding b) ─

    @Test
    @DisplayName("createSalon — stranded owner-master + no active primary → new salon primary, inactive primaries cleared first, owner-master re-homed")
    void should_makeNewSalonPrimaryAndRehomeOwnerMaster_when_ownerMasterStrandedAndNoActivePrimary() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("Third Salon", null, null, null, null, null, null, null, null, null, null, null);
        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.countByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(1L);
        when(masterService.isOwnerMasterStranded(ownerId)).thenReturn(true);
        when(salonRepository.existsByOwnerIdAndIsActiveTrueAndIsPrimaryTrue(ownerId)).thenReturn(false);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.createSalon(ownerId, request);

        ArgumentCaptor<Salon> captor = ArgumentCaptor.forClass(Salon.class);
        InOrder order = inOrder(salonRepository, masterService);
        order.verify(salonRepository).clearPrimaryOnInactiveSalons(ownerId);
        order.verify(salonRepository).save(captor.capture());
        order.verify(masterService).createMasterForOwner(owner, captor.getValue());
        assertThat(captor.getValue().isPrimary()).as("no active primary left → new salon is primary").isTrue();
    }

    @Test
    @DisplayName("createSalon — stranded owner-master but an active primary exists → new salon not primary, owner-master still re-homed")
    void should_rehomeOwnerMasterWithoutPrimary_when_ownerMasterStrandedAndActivePrimaryExists() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("Third Salon", null, null, null, null, null, null, null, null, null, null, null);
        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.countByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(1L);
        when(masterService.isOwnerMasterStranded(ownerId)).thenReturn(true);
        when(salonRepository.existsByOwnerIdAndIsActiveTrueAndIsPrimaryTrue(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.createSalon(ownerId, request);

        ArgumentCaptor<Salon> captor = ArgumentCaptor.forClass(Salon.class);
        verify(salonRepository).save(captor.capture());
        assertThat(captor.getValue().isPrimary()).as("an active primary already exists").isFalse();
        verify(salonRepository, never()).clearPrimaryOnInactiveSalons(any());
        verify(masterService).createMasterForOwner(owner, captor.getValue());
    }

    @Test
    @DisplayName("createSalon — owner-master active on an active salon → no primary lookup, no re-home (unchanged path)")
    void should_notTouchOwnerMasterOrPrimary_when_ownerMasterNotStranded() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildOwner(ownerId);
        var request = new CreateSalonRequest("Second Salon", null, null, null, null, null, null, null, null, null, null, null);
        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.countByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(1L);
        when(masterService.isOwnerMasterStranded(ownerId)).thenReturn(false);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> inv.getArgument(0));

        salonService.createSalon(ownerId, request);

        verify(salonRepository, never()).existsByOwnerIdAndIsActiveTrueAndIsPrimaryTrue(any());
        verify(masterService, never()).createMasterForOwner(any(User.class), any(Salon.class));
    }

    // ── non-owner role still rejected before the active-salon count is reached ─

    @Test
    @DisplayName("createSalon — throws ForbiddenException before the active-salon count when caller is CLIENT")
    void should_throwForbidden_before_activeSalonCount_when_callerIsClient() {
        UUID userId = UUID.randomUUID();
        User client = buildUser(userId, Role.CLIENT);
        var request = new CreateSalonRequest("Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(client));

        assertThatThrownBy(() -> salonService.createSalon(userId, request))
                .isInstanceOf(ForbiddenException.class)
                .as("CLIENT must be rejected before the primary-check or save");

        verify(salonRepository, never()).countByOwnerIdAndIsActiveTrue(any());
        verify(salonRepository, never()).save(any());
    }

    // ── isPrimary defaults to false on a new Salon entity ────────────────────

    @Test
    @DisplayName("Salon entity — isPrimary defaults to false when built without explicit value")
    void should_defaultIsPrimaryToFalse_when_salonBuiltWithoutExplicitValue() {
        User owner = buildOwner(UUID.randomUUID());

        Salon salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
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

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.countByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(0L);
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

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.countByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(1L);
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

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.countByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(0L);
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

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.countByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(1L);
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
