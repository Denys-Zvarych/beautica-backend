package com.beautica.master;

import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.master.dto.WorkingHoursResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.entity.WorkingHours;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.WorkingHoursRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MasterService — unit")
class MasterServiceTest {

    @Mock private MasterRepository masterRepository;
    @Mock private UserRepository userRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private WorkingHoursRepository workingHoursRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private CacheManager cacheManager;
    // Phase 240 perf MEDIUM fix: resolveOblastId now delegates to the shared cached resolver
    // (LocationQueryService#resolveCityOblastId) instead of calling CityRepository directly —
    // must be declared so @InjectMocks can satisfy the constructor parameter (mirrors
    // SalonServiceTest). MasterService no longer depends on CityRepository at all.
    @Mock private com.beautica.location.service.LocationQueryService locationQueryService;
    // Phase 13.1: declared so @InjectMocks satisfies the BookingSlugService constructor
    // parameter. The creation paths call getOrCreateSlug(...) after save — a no-op stub
    // (default mock) is sufficient; its return value is ignored by MasterService.
    @Mock private com.beautica.booking.service.BookingSlugService bookingSlugService;
    // Bookability-eviction fix: MasterService now constructor-depends on these two collaborators.
    // Declared so @InjectMocks wires real mocks instead of leaving the fields null — otherwise the
    // afterCommit eviction callback NPEs the instant any deactivate/reactivate path is replayed
    // under an active transaction synchronization (see should_evict* guard tests below).
    @Mock private com.beautica.booking.service.SlotCalculationService slotCalculationService;
    @Mock private com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    // Prefix-eviction fix: the master-calendar / available-slots afterCommit callbacks now delegate to
    // the shared evictor, so @InjectMocks must have one to wire or every deactivate/reactivate path
    // NPEs the moment its synchronization replays. A mock is right HERE — this tier asserts that the
    // write path REQUESTS eviction. Whether the request actually matches a real cache key is a
    // different question, and one a mock can never answer: it is proven against the live @Cacheable
    // proxies in CachePrefixEvictionKeyShapeTest.
    @Mock private com.beautica.common.cache.MasterCachePrefixEvictor cachePrefixEvictor;

    // Mobile Phase 111: MasterService publishes a SalonStaffChangedEvent from every staff-set
    // mutation so the salon's derived rating is recomputed. @InjectMocks must carry this or the
    // publish call NPEs — a mock is correct here, the listener's own behaviour is unit-tested in
    // com.beautica.review.event.SalonStaffRatingListenerTest.
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    // Audit-fix cycle 2: every create/reactivate/deactivate path now also evicts the
    // user-profile cache through the shared evictor (a `masters` write stales GET /users/me
    // via hasMasterProfile). @InjectMocks must have one to wire or those paths NPE. Left as a
    // bare mock here on purpose — the eviction itself is proved against the REAL evictor and
    // the REAL CacheConfig in OwnerMasterCacheTest and UserCacheEvictionIT, where a cache
    // actually exists to observe; verifying a mock call here would only restate the source.
    @Mock private com.beautica.common.cache.UserProfileCacheEvictor userProfileCacheEvictor;
    // Phase 29.2 fallout: getMasterCalendar now resolves an absolute-instant "now" for
    // BookingResponse.awaitingClosure. A real fixed-value Clock (not a bare @Mock, which would
    // return null from #instant() and NPE) — the exact instant is irrelevant to every test in
    // this class, none of which assert on awaitingClosure.
    @Spy private Clock clock = Clock.systemUTC();

    @InjectMocks
    private MasterService masterService;

    /**
     * Runs {@code action} with Spring transaction synchronization active, then replays every
     * registered {@code afterCommit()} callback exactly as a real commit would, and clears the
     * synchronization. The bookability-cache eviction is registered as an {@code afterCommit}
     * callback, so without this replay it never runs in a non-transactional unit test.
     */
    private void runAndReplayAfterCommit(Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            action.run();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ── createMasterForIndependentUser ─────────────────────────────────────────

    @Test
    @DisplayName("should_createMasterForIndependentUser_when_userExists")
    void should_createMasterForIndependentUser_when_userExists() {
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);

        Master saved = Master.builder()
                .user(user)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(masterRepository.save(any(Master.class))).thenReturn(saved);

        Master result = masterService.createMasterForIndependentUser(userId);

        assertThat(result.getMasterType()).isEqualTo(MasterType.INDEPENDENT_MASTER);
        assertThat(result.isActive()).isTrue();
        assertThat(result.getSalon()).isNull();
        verify(masterRepository).save(any(Master.class));
    }

    @Test
    @DisplayName("should_allocateBookingSlugForSavedMaster_when_createMasterForIndependentUser")
    void should_allocateBookingSlugForSavedMaster_when_createMasterForIndependentUser() {
        UUID userId = UUID.randomUUID();
        UUID savedMasterId = UUID.randomUUID();
        User user = mock(User.class);

        Master saved = Master.builder()
                .id(savedMasterId)
                .user(user)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(masterRepository.save(any(Master.class))).thenReturn(saved);

        masterService.createMasterForIndependentUser(userId);

        // Phase 13.1 side-effect contract: the public booking slug MUST be allocated for
        // the just-saved master id. Without this verify, dropping the getOrCreateSlug call
        // would leave the master with a null slug (no booking page) yet keep tests green.
        verify(bookingSlugService).getOrCreateSlug(savedMasterId);
    }

    @Test
    @DisplayName("should_throwNotFound_when_createIndependentMasterWithUnknownUserId")
    void should_throwNotFound_when_createIndependentMasterWithUnknownUserId() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.createMasterForIndependentUser(userId))
                .isInstanceOf(NotFoundException.class);

        verify(masterRepository, never()).save(any());
    }

    // ── createMasterFromInvite ─────────────────────────────────────────────────

    @Test
    @DisplayName("should_createMasterFromInvite_when_userAndSalonExist")
    void should_createMasterFromInvite_when_userAndSalonExist() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User user = mock(User.class);
        Salon salon = mock(Salon.class);

        Master saved = Master.builder()
                .user(user)
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        // Phase 286: createMasterFromInvite now rejects a deactivated salon — stub active so
        // this happy-path test still exercises success.
        when(salon.isActive()).thenReturn(true);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(masterRepository.save(any(Master.class))).thenReturn(saved);

        Master result = masterService.createMasterFromInvite(userId, salonId);

        assertThat(result.getMasterType()).isEqualTo(MasterType.SALON_MASTER);
        assertThat(result.getSalon()).isEqualTo(salon);
        assertThat(result.isActive()).isTrue();
        verify(masterRepository).save(any(Master.class));
        // Mobile Phase 111 — the invite-accept path is a staff-set change. A no-op for the
        // average today (the new row has no reviews), fired for the uniform invariant.
        verify(eventPublisher).publishEvent(
                new com.beautica.master.event.SalonStaffChangedEvent(salonId));
    }

    @Test
    @DisplayName("should_allocateBookingSlugForSavedMaster_when_createMasterFromInvite")
    void should_allocateBookingSlugForSavedMaster_when_createMasterFromInvite() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID savedMasterId = UUID.randomUUID();
        User user = mock(User.class);
        Salon salon = mock(Salon.class);

        Master saved = Master.builder()
                .id(savedMasterId)
                .user(user)
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        // Phase 286: createMasterFromInvite now rejects a deactivated salon — stub active so
        // this happy-path test still exercises success.
        when(salon.isActive()).thenReturn(true);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(masterRepository.save(any(Master.class))).thenReturn(saved);

        masterService.createMasterFromInvite(userId, salonId);

        // Phase 13.1 side-effect contract: invited salon masters also get a booking slug.
        verify(bookingSlugService).getOrCreateSlug(savedMasterId);
    }

    @Test
    @DisplayName("should_throwNotFound_when_createMasterFromInviteWithUnknownUserId")
    void should_throwNotFound_when_createMasterFromInviteWithUnknownUserId() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.createMasterFromInvite(userId, salonId))
                .isInstanceOf(NotFoundException.class);

        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwNotFound_when_createMasterFromInviteWithUnknownSalonId")
    void should_throwNotFound_when_createMasterFromInviteWithUnknownSalonId() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User user = mock(User.class);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(salonRepository.findById(salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.createMasterFromInvite(userId, salonId))
                .isInstanceOf(NotFoundException.class);

        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwBusinessException_when_createMasterFromInviteWithInactiveSalon")
    void should_throwBusinessException_when_createMasterFromInviteWithInactiveSalon() {
        // Phase 286: defence in depth for InviteService.acceptInvite's own salon-liveness
        // guard — this method is public and @Transactional, so the invariant belongs here too,
        // not only on that one caller's discipline.
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User user = mock(User.class);
        Salon salon = mock(Salon.class);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(salon.isActive()).thenReturn(false);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        assertThatThrownBy(() -> masterService.createMasterFromInvite(userId, salonId))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Salon is not active");

        verify(masterRepository, never()).save(any());
    }

    // ── getMasterDetail ────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_throwNotFound_when_getMasterDetailWithUnknownId")
    void should_throwNotFound_when_getMasterDetailWithUnknownId() {
        UUID masterId = UUID.randomUUID();
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.getMasterDetail(masterId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should_returnWorkingHours_when_masterDetailRequested")
    void should_returnWorkingHours_when_masterDetailRequested() {
        UUID masterId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getFirstName()).thenReturn("Anna");
        when(user.getLastName()).thenReturn("Kovalenko");

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getSalon()).thenReturn(null);

        WorkingHours wh = WorkingHours.builder()
                .master(master)
                .dayOfWeek(1)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(wh, "id", UUID.randomUUID());

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)).thenReturn(List.of(wh));

        MasterDetailResponse response = masterService.getMasterDetail(masterId);

        assertThat(response.masterId()).isEqualTo(masterId);
        assertThat(response.workingHours()).hasSize(1);
        assertThat(response.workingHours().get(0).dayOfWeek()).isEqualTo(1);
        // HIGH-2: cityId must be null when user.getCityId() returns null (fast-path)
        assertThat(response.cityId()).isNull();
        // CRITICAL: locationQueryService must never be called when cityId is null
        verifyNoInteractions(locationQueryService);
    }

    // ── resolveOblastId paths ─────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnOblastId_when_cityIdIsPresent")
    void should_returnOblastId_when_cityIdIsPresent() {
        UUID masterId = UUID.randomUUID();
        UUID cityUuid = UUID.randomUUID();
        UUID oblastUuid = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getFirstName()).thenReturn("Anna");
        when(user.getLastName()).thenReturn("Kovalenko");
        when(user.getCityId()).thenReturn(cityUuid);
        when(user.getDistrictId()).thenReturn(null);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getSalon()).thenReturn(null);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)).thenReturn(List.of());
        when(locationQueryService.resolveCityOblastId(cityUuid)).thenReturn(oblastUuid);

        MasterDetailResponse response = masterService.getMasterDetail(masterId);

        assertThat(response.cityId()).isEqualTo(cityUuid);
        assertThat(response.oblastId()).isEqualTo(oblastUuid);
        verify(locationQueryService).resolveCityOblastId(cityUuid);
    }

    @Test
    @DisplayName("should_returnNullOblastId_when_cityIdIsNull")
    void should_returnNullOblastId_when_cityIdIsNull() {
        UUID masterId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getFirstName()).thenReturn("Anna");
        when(user.getLastName()).thenReturn("Kovalenko");
        when(user.getCityId()).thenReturn(null);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getSalon()).thenReturn(null);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)).thenReturn(List.of());

        MasterDetailResponse response = masterService.getMasterDetail(masterId);

        assertThat(response.cityId()).isNull();
        assertThat(response.oblastId()).isNull();
        verify(locationQueryService, never()).resolveCityOblastId(any());
    }

    @Test
    @DisplayName("should_returnNullOblastId_when_cityIdPresentButCityRowMissing")
    void should_returnNullOblastId_when_cityIdPresentButCityRowMissing() {
        UUID masterId = UUID.randomUUID();
        UUID cityUuid = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getFirstName()).thenReturn("Anna");
        when(user.getLastName()).thenReturn("Kovalenko");
        when(user.getCityId()).thenReturn(cityUuid);
        when(user.getDistrictId()).thenReturn(null);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getSalon()).thenReturn(null);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)).thenReturn(List.of());
        when(locationQueryService.resolveCityOblastId(cityUuid)).thenReturn(null);

        MasterDetailResponse response = masterService.getMasterDetail(masterId);

        assertThat(response.oblastId()).isNull();
        verify(locationQueryService).resolveCityOblastId(cityUuid);
    }

    // ── getMasterDetail — embedded salon's oblastId (follow-up to PublicSalonResponse#oblastId) ──
    // The salon-affiliated master's embedded PublicSalonResponse must resolve oblastId from the
    // SALON's cityId, not the master's own user.getCityId() — a fixture where the two cities (and
    // therefore the two oblasts) DIFFER is required, or a bug that swaps/collapses the two
    // resolutions would still pass (fixture-defang guard, per project memory).

    @Test
    @DisplayName("should_resolveSalonOblastId_independently_when_masterHasSalonInDifferentCity")
    void should_resolveSalonOblastId_independently_when_masterHasSalonInDifferentCity() {
        UUID masterId = UUID.randomUUID();
        UUID userCityUuid = UUID.randomUUID();
        UUID userOblastUuid = UUID.randomUUID();
        UUID salonCityUuid = UUID.randomUUID();
        UUID salonOblastUuid = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getFirstName()).thenReturn("Anna");
        when(user.getLastName()).thenReturn("Kovalenko");
        when(user.getCityId()).thenReturn(userCityUuid);
        when(user.getDistrictId()).thenReturn(null);

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(UUID.randomUUID());
        when(salon.getCityId()).thenReturn(salonCityUuid);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)).thenReturn(List.of());
        when(locationQueryService.resolveCityOblastId(userCityUuid)).thenReturn(userOblastUuid);
        when(locationQueryService.resolveCityOblastId(salonCityUuid)).thenReturn(salonOblastUuid);

        MasterDetailResponse response = masterService.getMasterDetail(masterId);

        assertThat(response.oblastId())
                .as("the master's own oblastId must come from the master's user cityId")
                .isEqualTo(userOblastUuid);
        assertThat(response.salon().oblastId())
                .as("the embedded salon's oblastId must come from the SALON's cityId, not the master's")
                .isEqualTo(salonOblastUuid);
        assertThat(response.salon().oblastId()).isNotEqualTo(response.oblastId());
        // Finding 3 (Phase 240 perf LOW) short-circuit only kicks in when the two cities are
        // EQUAL — different cities here, so both must still independently reach the resolver.
        verify(locationQueryService).resolveCityOblastId(userCityUuid);
        verify(locationQueryService).resolveCityOblastId(salonCityUuid);
    }

    @Test
    @DisplayName("should_resolveSalonOblastIdOnce_when_masterAndSalonShareTheSameCity")
    void should_resolveSalonOblastIdOnce_when_masterAndSalonShareTheSameCity() {
        // Phase 240 perf LOW (Finding 3): the common case — a salon-affiliated master whose own
        // city IS the salon's city — must reuse the already-resolved oblastId instead of running
        // a second resolveCityOblastId call for the SAME cityId.
        UUID masterId = UUID.randomUUID();
        UUID sharedCityUuid = UUID.randomUUID();
        UUID sharedOblastUuid = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getFirstName()).thenReturn("Anna");
        when(user.getLastName()).thenReturn("Kovalenko");
        when(user.getCityId()).thenReturn(sharedCityUuid);
        when(user.getDistrictId()).thenReturn(null);

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(UUID.randomUUID());
        when(salon.getCityId()).thenReturn(sharedCityUuid);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)).thenReturn(List.of());
        when(locationQueryService.resolveCityOblastId(sharedCityUuid)).thenReturn(sharedOblastUuid);

        MasterDetailResponse response = masterService.getMasterDetail(masterId);

        assertThat(response.oblastId()).isEqualTo(sharedOblastUuid);
        assertThat(response.salon().oblastId())
                .as("same-city short-circuit must still populate the salon's oblastId")
                .isEqualTo(sharedOblastUuid);
        // The short-circuit means only ONE resolver call total, not one per resolveOblastId site.
        verify(locationQueryService, org.mockito.Mockito.times(1)).resolveCityOblastId(sharedCityUuid);
    }

    @Test
    @DisplayName("should_leaveSalonOblastIdNull_when_masterHasNoSalon")
    void should_leaveSalonOblastIdNull_when_masterHasNoSalon() {
        UUID masterId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getFirstName()).thenReturn("Anna");
        when(user.getLastName()).thenReturn("Kovalenko");
        when(user.getCityId()).thenReturn(null);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(user);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getSalon()).thenReturn(null);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)).thenReturn(List.of());

        MasterDetailResponse response = masterService.getMasterDetail(masterId);

        assertThat(response.salon()).isNull();
        // No salon at all — resolveOblastId must never be invoked with a salon cityId.
        verify(locationQueryService, never()).resolveCityOblastId(any());
    }

    // ── deactivateMaster — cache eviction ─────────────────────────────────────

    @Test
    @DisplayName("should_evictMasterDetailCache_when_deactivateMasterCalled")
    void should_evictMasterDetailCache_when_deactivateMasterCalled() {
        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);

        // INDEPENDENT_MASTER + actorId == userId: self-deactivation, so the Phase 290 finding #5
        // ownership guard (assertCanManageMaster) is satisfied with no salon fixture or
        // salonRepository/userRepository stubbing needed — this test is about cache eviction,
        // not authorization.
        Master master = Master.builder()
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "id", masterId);
        ReflectionTestUtils.setField(master, "user", user);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        // Stub both caches so the afterCommit eviction path does not NPE.
        Cache masterDetailCache = mock(Cache.class);
        Cache masterByUserCache = mock(Cache.class);
        when(cacheManager.getCache("master-detail")).thenReturn(masterDetailCache);
        when(cacheManager.getCache("master-by-user")).thenReturn(masterByUserCache);
        // master-calendar is also evicted by deactivateMaster, but no longer through cacheManager:
        // that eviction is a prefix scan and now delegates to the injected MasterCachePrefixEvictor
        // mock, so stubbing cacheManager.getCache("master-calendar") here would be an unused stub.

        // deactivateMaster guards eviction registration with isSynchronizationActive().
        // Manually initialise Spring transaction synchronization so the guard passes in this
        // non-transactional unit test, then capture and replay afterCommit().
        TransactionSynchronizationManager.initSynchronization();
        try {
            masterService.deactivateMaster(userId, masterId);

            // Capture all registered synchronizations and invoke afterCommit() on each.
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            syncs.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // The master-detail cache must have been asked to evict the deactivated master's key.
        verify(masterDetailCache).evict(masterId);
        // The master-by-user cache must also have been evicted for the user.
        verify(masterByUserCache).evict(userId);
    }

    // ── bookability-cache eviction guard (deactivate/reactivate flips is_active) ──
    // Regression net for the fix: each is_active flip must, after commit, evict
    //   • master-service-bookable (via slotCalculationService.evictMasterAvailabilityCaches(masterId)), and
    //   • salon-service-catalog   (via salonCatalogCacheEvictor.evict(salonId), non-null salon only).
    // Without these, a deactivated sole-performer's SALON service lingered in
    // GET /salons/{id}/services + the booking master-list for up to the 60s TTL.

    @Test
    @DisplayName("deactivateMaster — after commit evicts master-service-bookable (by masterId) AND salon-service-catalog (by salonId)")
    void should_evictBothBookabilityCaches_when_deactivateMaster() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = Master.builder()
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "id", masterId);
        ReflectionTestUtils.setField(master, "user", user);
        ReflectionTestUtils.setField(master, "salon", salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        // Phase 290 finding #5 — assertCanManageMaster's SALON_MASTER branch defers to the same
        // owner-or-admin check the batch cascade uses; stub the owner half. The role gate
        // (security fix, Phase 290 audit) must see actorId resolve to SALON_OWNER or this actor
        // is rejected before existsByIdAndOwnerId is even consulted.
        when(userRepository.findRoleById(actorId)).thenReturn(Optional.of(Role.SALON_OWNER));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        runAndReplayAfterCommit(() -> masterService.deactivateMaster(actorId, masterId));

        verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
        verify(salonCatalogCacheEvictor).evict(salonId);
        // Mobile Phase 111 — the salon lost a contributor to its derived rating.
        verify(eventPublisher).publishEvent(
                new com.beautica.master.event.SalonStaffChangedEvent(salonId));
    }

    @Test
    @DisplayName("deactivateOwnerMaster — after commit evicts master-service-bookable (by masterId) AND salon-service-catalog (by salonId)")
    void should_evictBothBookabilityCaches_when_deactivateOwnerMaster() {
        UUID actorUserId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = Master.builder()
                .masterType(MasterType.SALON_OWNER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "id", masterId);
        ReflectionTestUtils.setField(master, "salon", salon);

        when(masterRepository.findByUserIdWithSalon(actorUserId)).thenReturn(Optional.of(master));

        runAndReplayAfterCommit(() -> masterService.deactivateOwnerMaster(actorUserId, salonId));

        verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
        verify(salonCatalogCacheEvictor).evict(salonId);
        verify(eventPublisher).publishEvent(
                new com.beautica.master.event.SalonStaffChangedEvent(salonId));
    }

    @Test
    @DisplayName("createMasterForOwner (reactivation branch) — after commit evicts master-service-bookable (by masterId) AND salon-service-catalog (by salonId)")
    void should_evictBothBookabilityCaches_when_reactivatingInactiveOwnerMaster() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        User owner = mock(User.class);
        when(owner.getRole()).thenReturn(Role.SALON_OWNER);
        when(owner.getId()).thenReturn(userId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(owner);
        when(salon.getId()).thenReturn(salonId);

        Master inactive = Master.builder()
                .masterType(MasterType.SALON_OWNER)
                .isActive(false)
                .build();
        ReflectionTestUtils.setField(inactive, "id", masterId);
        ReflectionTestUtils.setField(inactive, "salon", salon);

        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.of(inactive));

        runAndReplayAfterCommit(() -> masterService.createMasterForOwner(owner, salon));

        // Reactivation flips is_active TRUE — the sole-performer's SALON service can (re)appear,
        // so both bookability caches must be evicted, exactly as on deactivation.
        assertThat(inactive.isActive())
                .as("reactivation branch must flip the row active")
                .isTrue();
        verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
        verify(salonCatalogCacheEvictor).evict(salonId);
        // Mobile Phase 111 — unlike the create branches this genuinely moves the rating: the
        // reactivated owner-as-master's prior reviews at this salon start counting again.
        verify(eventPublisher).publishEvent(
                new com.beautica.master.event.SalonStaffChangedEvent(salonId));
    }

    @Test
    @DisplayName("deactivateMaster (INDEPENDENT_MASTER, no salon) — evicts master-service-bookable but is a no-op on the salon-catalog evictor")
    void should_notTouchSalonCatalogEvictor_when_deactivatedMasterHasNoSalon() {
        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);

        Master master = Master.builder()
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "id", masterId);
        ReflectionTestUtils.setField(master, "user", user);
        // salon intentionally left null — an independent master owns no salon catalogue entry.

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        // Phase 290 finding #5 — assertCanManageMaster's INDEPENDENT_MASTER branch requires
        // self-management (actorId == the master's own user id); an independent master's only
        // legitimate deactivation caller is themselves.
        runAndReplayAfterCommit(() -> masterService.deactivateMaster(userId, masterId));

        verify(slotCalculationService).evictMasterAvailabilityCaches(masterId);
        verify(salonCatalogCacheEvictor, never()).evict(any());
        // Mobile Phase 111 — publishSalonStaffChanged(null) is a documented no-op. Without this
        // assertion, dropping the null guard would push a SalonStaffChangedEvent whose constructor
        // rejects null and turn every independent-master deactivation into a 500, with the whole
        // suite still green.
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    // ── upsertWorkingHours ─────────────────────────────────────────────────────

    @Test
    @DisplayName("should_upsertWorkingHours_when_ownerRequestsUpdate")
    void should_upsertWorkingHours_when_ownerRequestsUpdate() {
        UUID ownerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = mock(Master.class);

        var request = new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true);

        WorkingHours saved = WorkingHours.builder()
                .master(master)
                .dayOfWeek(1)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        // upsert merge map is built from ALL rows (incl. inactive) via findByMasterId, not the
        // active-only finder — matching production after the 23505 duplicate-INSERT fix.
        when(workingHoursRepository.findByMasterId(masterId)).thenReturn(List.of());
        when(workingHoursRepository.saveAll(anyList())).thenReturn(List.of(saved));

        List<WorkingHoursResponse> result =
                masterService.upsertWorkingHours(ownerId, masterId, List.of(request));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dayOfWeek()).isEqualTo(1);
        verify(workingHoursRepository).saveAll(anyList());
    }

    // ── upsertWorkingHours — inactive-row reuse (regression: UNIQUE(master_id, day_of_week) 23505) ──
    // Original bug: the merge map was built with the active-only finder
    // (findByMasterIdAndIsActiveTrue), so a pre-existing INACTIVE (master_id, day_of_week) row was
    // invisible. The merge treated that weekday as new → built a WorkingHours with a null @Id →
    // saveAll emitted an INSERT that violated UNIQUE(master_id, day_of_week)
    // (DataIntegrityViolationException, SQLState 23505).
    // Fix: build the merge map from findByMasterId (ALL rows, incl. inactive) so the existing row
    // is matched and UPDATEd in place (same @Id, setActive(true)) instead of re-INSERTed.

    @Test
    @DisplayName("should_updateInactiveRowInPlace_when_upsertWorkingHoursForPreviouslyDeactivatedDay")
    void should_updateInactiveRowInPlace_when_upsertWorkingHoursForPreviouslyDeactivatedDay() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID existingRowId = UUID.randomUUID();
        Master master = mock(Master.class);

        // Pre-seed an EXISTING, INACTIVE working-hours row for day 2 with a non-null @Id.
        // This is the row the active-only finder would have hidden, causing the duplicate INSERT.
        WorkingHours existingInactiveRow = WorkingHours.builder()
                .master(master)
                .dayOfWeek(2)
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(12, 0))
                .isActive(false)
                .build();
        ReflectionTestUtils.setField(existingInactiveRow, "id", existingRowId);

        // Re-enable day 2 with new hours and isActive = true.
        var request = new WorkingHoursRequest(2, LocalTime.of(9, 0), LocalTime.of(17, 0), true);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        // The merge map MUST be built from the all-rows finder so the inactive row is visible.
        when(workingHoursRepository.findByMasterId(masterId))
                .thenReturn(List.of(existingInactiveRow));
        when(workingHoursRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        masterService.upsertWorkingHours(actorId, masterId, List.of(request));

        // Capture exactly what was handed to saveAll — the merge result.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkingHours>> captor = ArgumentCaptor.forClass(List.class);
        verify(workingHoursRepository).saveAll(captor.capture());
        List<WorkingHours> persisted = captor.getValue();

        // Exactly ONE row for day 2 — no second (duplicate) row was created.
        assertThat(persisted)
                .as("merge must produce a single row for day 2, not a duplicate INSERT")
                .hasSize(1);

        WorkingHours mergedRow = persisted.get(0);
        // Updated IN PLACE: the SAME @Id is retained (no new row → no UNIQUE violation).
        assertThat(mergedRow.getId())
                .as("the existing inactive row must be reused, retaining its @Id")
                .isEqualTo(existingRowId);
        // The previously-inactive row is now active with the requested hours.
        assertThat(mergedRow.isActive())
                .as("the reused row must be re-activated")
                .isTrue();
        assertThat(mergedRow.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(mergedRow.getEndTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(mergedRow.getDayOfWeek()).isEqualTo(2);

        // The merge map is built from the all-rows finder; the active-only finder must NOT be used
        // on the upsert path (that finder would hide the inactive row and reintroduce the bug).
        verify(workingHoursRepository).findByMasterId(masterId);
        verify(workingHoursRepository, never()).findByMasterIdAndIsActiveTrue(any());
    }

    @Test
    @DisplayName("should_throwNotFound_when_upsertWorkingHours_masterMissing")
    void should_throwNotFound_when_upsertWorkingHours_masterMissing_explicit() {
        // Authorization is exclusively enforced by @PreAuthorize on MasterController — not re-checked here.
        // This test verifies the service throws NotFoundException when master is absent.
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        var request = new WorkingHoursRequest(2, LocalTime.of(10, 0), LocalTime.of(18, 0), true);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                masterService.upsertWorkingHours(actorId, masterId, List.of(request)))
                .isInstanceOf(NotFoundException.class);

        verify(workingHoursRepository, never()).saveAll(any());
    }

    // ── upsertWorkingHours — duplicate-dayOfWeek guard (regression) ────────────
    // A payload with two entries sharing the same dayOfWeek must be rejected with a
    // BusinessException(BAD_REQUEST) BEFORE any DB work. Without this guard the
    // byDay collector would silently collapse duplicates to the last-wins entry.
    // The descriptive message stays server-side; GlobalExceptionHandler.handleBusiness
    // rewrites it to the generic "Invalid request" 400 on the wire (asserted at the
    // controller layer below).

    @Test
    @DisplayName("should_throwBadRequest_when_upsertWorkingHours_payloadHasDuplicateDay")
    void should_throwBadRequest_when_upsertWorkingHoursPayloadHasDuplicateDay() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        // Two entries both targeting dayOfWeek=1 — the guard must trip on the second.
        var first = new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(13, 0), true);
        var duplicate = new WorkingHoursRequest(1, LocalTime.of(14, 0), LocalTime.of(18, 0), true);

        assertThatThrownBy(() ->
                masterService.upsertWorkingHours(actorId, masterId, List.of(first, duplicate)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Duplicate working-hours entry for the same day");

        // Guard runs before any repository access — no master lookup, no merge-map fetch, no save.
        verify(masterRepository, never()).findByIdWithUserAndSalon(any());
        verify(workingHoursRepository, never()).findByMasterId(any());
        verify(workingHoursRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("should_upsertWorkingHours_when_payloadHasTwoDistinctDays")
    void should_upsertWorkingHours_when_payloadHasTwoDistinctDays() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Master master = mock(Master.class);

        var monday = new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0), true);
        var tuesday = new WorkingHoursRequest(2, LocalTime.of(10, 0), LocalTime.of(18, 0), true);

        WorkingHours savedMon = WorkingHours.builder()
                .master(master).dayOfWeek(1)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).isActive(true).build();
        WorkingHours savedTue = WorkingHours.builder()
                .master(master).dayOfWeek(2)
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(18, 0)).isActive(true).build();
        ReflectionTestUtils.setField(savedMon, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(savedTue, "id", UUID.randomUUID());

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        // upsert merge map uses the all-rows finder (incl. inactive) — see production fix.
        when(workingHoursRepository.findByMasterId(masterId)).thenReturn(List.of());
        when(workingHoursRepository.saveAll(anyList())).thenReturn(List.of(savedMon, savedTue));

        List<WorkingHoursResponse> result =
                masterService.upsertWorkingHours(actorId, masterId, List.of(monday, tuesday));

        // Distinct days never trip the guard — both entries persisted.
        assertThat(result).hasSize(2);
        assertThat(result).extracting(WorkingHoursResponse::dayOfWeek).containsExactly(1, 2);
        verify(workingHoursRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("should_returnEmptyListAsNoOp_when_upsertWorkingHoursPayloadIsEmpty")
    void should_returnEmptyListAsNoOp_when_upsertWorkingHoursPayloadIsEmpty() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Master master = mock(Master.class);

        // Empty payload must never trip the duplicate guard; it stays a clean no-op
        // (master is loaded, saveAll receives an empty list, an empty list is returned).
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        // upsert merge map uses the all-rows finder (incl. inactive) — see production fix.
        when(workingHoursRepository.findByMasterId(masterId)).thenReturn(List.of());
        when(workingHoursRepository.saveAll(anyList())).thenReturn(List.of());

        List<WorkingHoursResponse> result =
                masterService.upsertWorkingHours(actorId, masterId, List.of());

        assertThat(result).isEmpty();
        verify(workingHoursRepository).saveAll(anyList());
    }

    // ── deactivateMaster ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_deactivateMaster_when_masterExists")
    void should_deactivateMaster_when_authorizedActorRequests() {
        // Controller-level authorization (@PreAuthorize) is not re-checked here; the Phase 290
        // finding #5 service-layer guard IS exercised — INDEPENDENT_MASTER + actorId == userId
        // satisfies it via self-management, with no salon fixture needed.
        // save() is no longer called — Hibernate dirty-checking flushes the mutation on commit.
        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);

        Master master = Master.builder()
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "user", user);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        masterService.deactivateMaster(userId, masterId);

        assertThat(master.isActive()).isFalse();
        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwNotFound_when_deactivateMaster_masterMissing")
    void should_throwNotFound_when_deactivateMaster_masterMissing() {
        // Authorization is exclusively enforced by @PreAuthorize on MasterController — not re-checked here.
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.deactivateMaster(actorId, masterId))
                .isInstanceOf(NotFoundException.class);

        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("deactivateMaster — Phase 290 finding #5: throws Forbidden when the actor neither "
            + "owns the master's salon nor is assigned to it as SALON_ADMIN")
    void should_throwForbidden_when_deactivateMaster_actorDoesNotOwnSalonAndIsNotAdmin() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User user = mock(User.class);
        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = Master.builder()
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "user", user);
        ReflectionTestUtils.setField(master, "salon", salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        // Actor holds a role that CAN manage salon staff (SALON_ADMIN) but is not assigned to
        // THIS salon — the role gate alone must not be sufficient, the assignment must also match.
        when(userRepository.findRoleById(actorId)).thenReturn(Optional.of(Role.SALON_ADMIN));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.deactivateMaster(actorId, masterId))
                .isInstanceOf(ForbiddenException.class);

        // The master must be left untouched — the guard runs BEFORE the mutation.
        assertThat(master.isActive()).isTrue();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    /**
     * QA audit (2026-09-03) gap fix — every existing {@code assertCanManageMaster}/
     * {@code assertCanManageSalonStaff} test exercised either the OWNER-positive branch or a
     * doubly-negative (neither owner nor admin) actor. Nothing proved the SALON_ADMIN-positive
     * branch of {@code canManageSalonStaff} — {@code userRepository.findSalonIdById(actorId)}
     * resolving to the master's own salon — actually authorizes. Without this test, inverting
     * that {@code .map(salonId::equals)} to something that always returns {@code false} would
     * still pass the whole suite (the owner-positive tests short-circuit past the admin branch
     * via {@code ||}, and the negative test never gives it a matching salon id to accept).
     */
    @Test
    @DisplayName("deactivateMaster — Phase 290 finding #5: a SALON_ADMIN who is NOT the owner but "
            + "IS assigned to this salon may deactivate its SALON_MASTER staff")
    void should_deactivateMaster_when_actorIsSalonAdminNotOwner() {
        UUID adminActorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User masterUser = mock(User.class);
        when(masterUser.getId()).thenReturn(masterUserId);
        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = Master.builder()
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "id", masterId);
        ReflectionTestUtils.setField(master, "user", masterUser);
        ReflectionTestUtils.setField(master, "salon", salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        // The admin's persisted role clears the gate...
        when(userRepository.findRoleById(adminActorId)).thenReturn(Optional.of(Role.SALON_ADMIN));
        // ...and IS assigned to this salon — the SALON_ADMIN branch of canManageSalonStaff.
        when(userRepository.findSalonIdById(adminActorId)).thenReturn(Optional.of(salonId));

        runAndReplayAfterCommit(() -> masterService.deactivateMaster(adminActorId, masterId));

        assertThat(master.isActive())
                .as("the admin-positive branch must actually authorize the mutation")
                .isFalse();
        verify(eventPublisher).publishEvent(
                new com.beautica.master.event.SalonStaffChangedEvent(salonId));
    }

    /**
     * Security fix regression test (Phase 290 audit, MEDIUM) — {@code canManageSalonStaff}
     * previously admitted ANY actor whose {@code users.salon_id} matched the target salon,
     * with no role check. {@code User.createFromInvite} populates {@code salon_id} for an invited
     * {@code SALON_MASTER} (a read-only role per the domain rules) exactly as it does for
     * {@code SALON_ADMIN}, so a master invited to THIS salon satisfied the old guard's second
     * OR-branch. This actor's {@code findSalonIdById} is stubbed to resolve to the target salon —
     * proving the role gate, not merely a missing assignment row, is what rejects them.
     */
    @Test
    @DisplayName("deactivateMaster — Phase 290 audit MEDIUM fix: a SALON_MASTER actor assigned to "
            + "this salon must NOT be treated as its staff manager")
    void should_throwForbidden_when_deactivateMaster_actorIsSalonMasterAssignedToSalon() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User user = mock(User.class);
        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = Master.builder()
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "user", user);
        ReflectionTestUtils.setField(master, "salon", salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(userRepository.findRoleById(actorId)).thenReturn(Optional.of(Role.SALON_MASTER));
        // lenient(): the fixed guard short-circuits on the role gate before ever reaching this
        // branch, so this stub is unused on correct code — kept anyway so the mutation check
        // (deleting the role gate) actually flips this test red instead of silently staying green,
        // since without it the mutated code would fall through to an unstubbed (empty) default.
        lenient().when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        assertThatThrownBy(() -> masterService.deactivateMaster(actorId, masterId))
                .isInstanceOf(ForbiddenException.class);

        assertThat(master.isActive()).isTrue();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    // ── deactivateMasters — Phase 290 batch cascade (findings #2, #3, #5) ────────

    @Test
    @DisplayName("deactivateMasters — deactivates every master using the ALREADY-LOADED entities "
            + "(no findByIdWithUserAndSalon call) and publishes exactly ONE SalonStaffChangedEvent "
            + "for the whole batch")
    void should_deactivateEveryMasterAndPublishExactlyOneEvent_when_deactivateMastersBatch() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        User userA = mock(User.class);
        when(userA.getId()).thenReturn(UUID.randomUUID());
        User userB = mock(User.class);
        when(userB.getId()).thenReturn(UUID.randomUUID());

        Master masterA = Master.builder().masterType(MasterType.SALON_MASTER).isActive(true).build();
        ReflectionTestUtils.setField(masterA, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(masterA, "user", userA);
        ReflectionTestUtils.setField(masterA, "salon", salon);

        Master masterB = Master.builder().masterType(MasterType.SALON_MASTER).isActive(true).build();
        ReflectionTestUtils.setField(masterB, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(masterB, "user", userB);
        ReflectionTestUtils.setField(masterB, "salon", salon);

        // Role gate (security fix, Phase 290 audit) — the batch guard resolves the actor's role
        // ONCE for the whole call, not once per master; see the negative test below for the count
        // assertion.
        when(userRepository.findRoleById(ownerId)).thenReturn(Optional.of(Role.SALON_OWNER));
        when(salonRepository.existsByIdAndOwnerId(salonId, ownerId)).thenReturn(true);

        masterService.deactivateMasters(ownerId, List.of(masterA, masterB), salonId);

        assertThat(masterA.isActive()).isFalse();
        assertThat(masterB.isActive()).isFalse();
        // Finding #3 — the batch never re-fetches a Master it was already handed.
        verify(masterRepository, never()).findByIdWithUserAndSalon(any());
        // Finding #2 — ONE event for N deactivations, not N.
        verify(eventPublisher, times(1)).publishEvent(
                new com.beautica.master.event.SalonStaffChangedEvent(salonId));
        // Role-gate perf invariant — resolved once for the batch, never once per master.
        verify(userRepository, times(1)).findRoleById(ownerId);
    }

    @Test
    @DisplayName("deactivateMasters — empty list is a no-op: no authorization query, no event")
    void should_doNothing_when_deactivateMastersCalledWithEmptyList() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        masterService.deactivateMasters(ownerId, List.of(), salonId);

        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("deactivateMasters — Phase 290 finding #5: throws Forbidden, before any master is "
            + "mutated or the event is published, when the actor neither owns the salon nor is its admin")
    void should_throwForbidden_when_deactivateMastersActorUnauthorized() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Master master = Master.builder().masterType(MasterType.SALON_MASTER).isActive(true).build();

        // Actor holds a role that CAN manage salon staff (SALON_ADMIN) but is not assigned to
        // THIS salon.
        when(userRepository.findRoleById(actorId)).thenReturn(Optional.of(Role.SALON_ADMIN));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.deactivateMasters(actorId, List.of(master), salonId))
                .isInstanceOf(ForbiddenException.class);

        assertThat(master.isActive()).isTrue();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    /**
     * Security fix regression test (Phase 290 audit, MEDIUM) — batch-cascade sibling of
     * {@code should_throwForbidden_when_deactivateMaster_actorIsSalonMasterAssignedToSalon}. The
     * cascade caller ({@code SalonService#deactivateSalon}) always requires {@code SALON_OWNER}
     * independently, but {@code assertCanManageSalonStaff} is the defense-in-depth layer BEHIND
     * that gate and must not itself admit a SALON_MASTER on an assignment-only match.
     */
    @Test
    @DisplayName("deactivateMasters — Phase 290 audit MEDIUM fix: a SALON_MASTER actor assigned to "
            + "this salon must NOT authorize the batch cascade")
    void should_throwForbidden_when_deactivateMasters_actorIsSalonMasterAssignedToSalon() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Master master = Master.builder().masterType(MasterType.SALON_MASTER).isActive(true).build();

        when(userRepository.findRoleById(actorId)).thenReturn(Optional.of(Role.SALON_MASTER));
        // lenient(): unreachable once the role gate short-circuits on correct code — see the
        // single-master sibling test for why it stays.
        lenient().when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        assertThatThrownBy(() -> masterService.deactivateMasters(actorId, List.of(master), salonId))
                .isInstanceOf(ForbiddenException.class);

        assertThat(master.isActive()).isTrue();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    // V83 removed the POST/DELETE /masters/{id}/schedule-exceptions legacy endpoints and the
    // MasterService.addScheduleException / removeScheduleException methods. Per-date overrides are
    // now owned exclusively by MasterScheduleService (PUT/DELETE /masters/{id}/schedule/overrides),
    // covered by MasterScheduleServiceIT + MasterScheduleControllerTest.

    // ── getMasterByUserId ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnMaster_when_getMasterByUserIdAndMasterExists")
    void should_returnMaster_when_getMasterByUserIdAndMasterExists() {
        UUID userId = UUID.randomUUID();
        Master master = Master.builder()
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();

        when(masterRepository.findActiveByUserIdWithUserAndSalon(userId)).thenReturn(Optional.of(master));

        Master result = masterService.getMasterByUserId(userId);

        assertThat(result).isEqualTo(master);
        assertThat(result.getMasterType()).isEqualTo(MasterType.INDEPENDENT_MASTER);
    }

    @Test
    @DisplayName("should_throwNotFound_when_getMasterByUserIdAndNoMasterRecord")
    void should_throwNotFound_when_getMasterByUserIdAndNoMasterRecord() {
        UUID userId = UUID.randomUUID();

        when(masterRepository.findActiveByUserIdWithUserAndSalon(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.getMasterByUserId(userId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should_throwNotFound_when_getMasterByUserId_andMasterIsDeactivated")
    void should_throwNotFound_when_getMasterByUserId_andMasterIsDeactivated() {
        UUID userId = UUID.randomUUID();

        // findActiveByUserIdWithUserAndSalon filters isActive=true at the DB level —
        // a deactivated master returns empty, which must surface as 404.
        when(masterRepository.findActiveByUserIdWithUserAndSalon(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.getMasterByUserId(userId))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * Audit-fix cycle 2 — the assertion flipped from "throws" to "returns empty" because
     * {@code findMyMasterDetail} must be able to NEGATIVELY CACHE this outcome, and
     * {@code @Cacheable} never stores an exception. The user-visible contract is unchanged and is
     * pinned one layer up: {@code MasterControllerTest
     * #should_return404_when_salonOwnerHasNoActiveOwnerMasterRow} feeds this same empty Optional
     * through the controller and asserts 404 (not 403). What is asserted HERE is that a
     * deactivated master still resolves to "absent" at the DB-filter level — a master holding a
     * valid JWT must not reach their profile after deactivation.
     */
    @Test
    @DisplayName("should_returnEmpty_when_findMyMasterDetail_andMasterIsDeactivated")
    void should_returnEmpty_when_findMyMasterDetail_andMasterIsDeactivated() {
        UUID userId = UUID.randomUUID();

        // DB-level isActive=true filter: deactivated master returns empty Optional,
        // preventing a master with a valid JWT from accessing GET /masters/me.
        when(masterRepository.findActiveByUserIdWithUserAndSalon(userId)).thenReturn(Optional.empty());

        assertThat(masterService.findMyMasterDetail(userId))
                .as("a deactivated master must resolve to absent; the controller turns this into 404")
                .isEmpty();
    }

    // ── createMasterForOwner (entity overload) ────────────────────────────────
    // Tests target the entity overload directly — no repo mocking required for
    // user/salon loading since both are passed in already (Findings 3 + 4 fix).

    @Test
    @DisplayName("should_createOwnerMaster_when_ownerHasNoExistingMasterRow")
    void should_createOwnerMaster_when_ownerHasNoExistingMasterRow() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);
        when(user.getId()).thenReturn(userId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(user);
        // Was declared and never used. Stubbed now because the Phase 111 publish assertion below
        // needs a real id — an unstubbed getId() returns null, publishSalonStaffChanged(null) is a
        // no-op, and the assertion would silently degrade into "nothing was published".
        when(salon.getId()).thenReturn(salonId);

        Master saved = Master.builder()
                .user(user)
                .salon(salon)
                .masterType(MasterType.SALON_OWNER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();

        // createMasterForOwner now calls findByUserIdWithSalon to avoid a lazy SELECT on
        // getSalon().getId() (MEDIUM F3 fix) — stub the new graph method.
        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.empty());
        when(masterRepository.save(any(Master.class))).thenReturn(saved);

        Master result = masterService.createMasterForOwner(user, salon);

        assertThat(result.getMasterType()).isEqualTo(MasterType.SALON_OWNER);
        assertThat(result.isActive()).isTrue();
        assertThat(result.getAvgRating()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(masterRepository).save(any(Master.class));
        verify(userRepository, never()).findById(any());
        verify(salonRepository, never()).findById(any());
        // Mobile Phase 111 — the CREATE branch of createMasterForOwner, the one publish site the
        // rest of the suite never reached (the reactivation branch and the invite path each have
        // their own test). An owner joining their own salon as a master is a staff-set change.
        verify(eventPublisher).publishEvent(
                new com.beautica.master.event.SalonStaffChangedEvent(salonId));
    }

    @Test
    @DisplayName("should_allocateBookingSlugForSavedMaster_when_createOwnerMaster")
    void should_allocateBookingSlugForSavedMaster_when_createOwnerMaster() {
        UUID userId = UUID.randomUUID();
        UUID savedMasterId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);
        when(user.getId()).thenReturn(userId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(user);

        Master saved = Master.builder()
                .id(savedMasterId)
                .user(user)
                .salon(salon)
                .masterType(MasterType.SALON_OWNER)
                .isActive(true)
                .build();

        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.empty());
        when(masterRepository.save(any(Master.class))).thenReturn(saved);

        masterService.createMasterForOwner(user, salon);

        // Phase 13.1 side-effect contract: a newly created owner master gets a booking slug.
        verify(bookingSlugService).getOrCreateSlug(savedMasterId);
    }

    @Test
    @DisplayName("should_notAllocateBookingSlug_when_ownerMasterAlreadyExistsIdempotent")
    void should_notAllocateBookingSlug_when_ownerMasterAlreadyExistsIdempotent() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);
        when(user.getId()).thenReturn(userId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(user);
        when(salon.getId()).thenReturn(salonId);

        Master existing = Master.builder()
                .user(user)
                .salon(salon)
                .masterType(MasterType.SALON_OWNER)
                .isActive(true)
                .build();

        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.of(existing));

        masterService.createMasterForOwner(user, salon);

        // Idempotent early-return path: no save, and crucially no slug allocation — the
        // existing master keeps its slug, the generator is never re-invoked.
        verify(masterRepository, never()).save(any());
        verifyNoInteractions(bookingSlugService);
    }

    @Test
    @DisplayName("should_returnExistingActiveRow_idempotent")
    void should_returnExistingActiveRow_idempotent() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);
        when(user.getId()).thenReturn(userId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(user);
        when(salon.getId()).thenReturn(salonId);

        Master existing = Master.builder()
                .user(user)
                .salon(salon)
                .masterType(MasterType.SALON_OWNER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();

        // createMasterForOwner now calls findByUserIdWithSalon (MEDIUM F3 fix).
        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.of(existing));

        Master result = masterService.createMasterForOwner(user, salon);

        assertThat(result).isSameAs(existing);
        verify(masterRepository, never()).save(any());
        // Mobile Phase 111 — the idempotent "already active" early return changes NO staff set, so
        // it must not publish. MasterService's own comment says so; nothing asserted it. Firing
        // here would recompute a salon's rating on every repeat owner-master call for no reason,
        // and would blur what the event means for anyone reading the listener.
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("should_reactivate_when_existingOwnerMasterIsInactive")
    void should_reactivate_when_existingOwnerMasterIsInactive() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);
        when(user.getId()).thenReturn(userId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(user);
        when(salon.getId()).thenReturn(salonId);

        Master inactive = Master.builder()
                .user(user)
                .salon(salon)
                .masterType(MasterType.SALON_OWNER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(false)
                .build();

        // createMasterForOwner now calls findByUserIdWithSalon (MEDIUM F3 fix).
        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.of(inactive));

        Master result = masterService.createMasterForOwner(user, salon);

        assertThat(result.isActive()).isTrue();
        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwConflict_when_userHasIndependentMasterRow")
    void should_throwConflict_when_userHasIndependentMasterRow() {
        UUID userId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);
        when(user.getId()).thenReturn(userId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(user);

        Master independentMaster = Master.builder()
                .user(user)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();

        // createMasterForOwner now calls findByUserIdWithSalon (MEDIUM F3 fix).
        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.of(independentMaster));

        assertThatThrownBy(() -> masterService.createMasterForOwner(user, salon))
                .isInstanceOf(ConflictException.class);

        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwConflict_when_ownerMasterExistsInDifferentSalon")
    void should_throwConflict_when_ownerMasterExistsInDifferentSalon() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID otherSalonId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);
        when(user.getId()).thenReturn(userId);

        Salon requestedSalon = mock(Salon.class);
        when(requestedSalon.isActive()).thenReturn(true);
        when(requestedSalon.getOwner()).thenReturn(user);
        when(requestedSalon.getId()).thenReturn(salonId);

        Salon otherSalon = mock(Salon.class);
        when(otherSalon.getId()).thenReturn(otherSalonId);

        Master ownerMasterInOtherSalon = Master.builder()
                .user(user)
                .salon(otherSalon)
                .masterType(MasterType.SALON_OWNER)
                .isActive(true)
                .build();

        // createMasterForOwner now calls findByUserIdWithSalon (MEDIUM F3 fix).
        when(masterRepository.findByUserIdWithSalon(userId)).thenReturn(Optional.of(ownerMasterInOtherSalon));

        assertThatThrownBy(() -> masterService.createMasterForOwner(user, requestedSalon))
                .isInstanceOf(ConflictException.class);

        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwForbidden_when_actorRoleIsNotSalonOwner")
    void should_throwForbidden_when_actorRoleIsNotSalonOwner() {
        User client = mock(User.class);
        when(client.getRole()).thenReturn(Role.CLIENT);

        Salon salon = mock(Salon.class);

        assertThatThrownBy(() -> masterService.createMasterForOwner(client, salon))
                .isInstanceOf(ForbiddenException.class);

        verify(salonRepository, never()).findById(any());
        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwForbidden_when_actorDoesNotOwnSalon")
    void should_throwForbidden_when_actorDoesNotOwnSalon() {
        UUID actorId = UUID.randomUUID();
        UUID realOwnerId = UUID.randomUUID();

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.SALON_OWNER);
        when(actor.getId()).thenReturn(actorId);

        User realOwner = mock(User.class);
        when(realOwner.getId()).thenReturn(realOwnerId);

        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(realOwner);

        assertThatThrownBy(() -> masterService.createMasterForOwner(actor, salon))
                .isInstanceOf(ForbiddenException.class);

        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwBusiness_when_salonIsInactive")
    void should_throwBusiness_when_salonIsInactive() {
        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SALON_OWNER);

        Salon inactiveSalon = mock(Salon.class);
        when(inactiveSalon.isActive()).thenReturn(false);

        assertThatThrownBy(() -> masterService.createMasterForOwner(user, inactiveSalon))
                .isInstanceOf(BusinessException.class);

        verify(masterRepository, never()).save(any());
    }

    // ── createMasterForOwner (UUID overload) — delegates to entity overload ──

    @Test
    @DisplayName("should_throwNotFound_when_salonMissing")
    void should_throwNotFound_when_salonMissing() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        // UUID overload loads user then salon — role is not checked until inside the entity
        // overload, but we never reach it because salonRepository returns empty first.
        User user = mock(User.class);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(salonRepository.findById(salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.createMasterForOwner(userId, salonId))
                .isInstanceOf(NotFoundException.class);

        verify(masterRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_throwNotFound_when_userMissing")
    void should_throwNotFound_when_userMissing() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.createMasterForOwner(userId, salonId))
                .isInstanceOf(NotFoundException.class);

        verify(salonRepository, never()).findById(any());
        verify(masterRepository, never()).save(any());
    }

    // ── getMastersByPage ───────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnPagedMasters_when_getMastersByPageCalled")
    void should_returnPagedMasters_when_getMastersByPageCalled() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Pageable pageable = Pageable.ofSize(10);

        User user = mock(User.class);
        when(user.getFirstName()).thenReturn("Olena");
        when(user.getLastName()).thenReturn("Bondar");

        Master master = Master.builder()
                .user(user)
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "id", masterId);

        // The service no longer forwards the caller's Pageable verbatim: SortWhitelist.apply
        // validates it against SORTABLE_MASTER_PROPERTIES and, because the incoming Pageable is
        // unsorted and the underlying query has no ORDER BY of its own, substitutes the default
        // sort plus the mandatory unique `id` tiebreaker. Stubbing/verifying with this exact
        // normalized instance (rather than any(Pageable.class)) is deliberate — it pins that
        // contract, so silently dropping the whitelist or the tiebreaker reddens this test.
        Pageable expectedNormalized = PageRequest.of(0, 10,
                Sort.by(Sort.Direction.DESC, "avgRating").and(Sort.by(Sort.Direction.ASC, "id")));

        Page<Master> masterPage = new PageImpl<>(List.of(master), expectedNormalized, 1);
        when(masterRepository.findBySalonIdAndIsActiveTrueWithUser(salonId, expectedNormalized))
                .thenReturn(masterPage);

        Page<MasterSummaryResponse> result = masterService.getMastersByPage(salonId, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).masterId()).isEqualTo(masterId);
        assertThat(result.getContent().get(0).masterType()).isEqualTo(MasterType.SALON_MASTER);
        verify(masterRepository).findBySalonIdAndIsActiveTrueWithUser(salonId, expectedNormalized);
    }

    @Test
    @DisplayName("getMastersByPage rejects a dotted sort path with a 400 before touching the repository")
    void should_throwBadRequest_when_getMastersByPageSortIsDottedPath() {
        UUID salonId = UUID.randomUUID();
        Pageable oracleAttempt = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "user.passwordHash"));

        assertThatThrownBy(() -> masterService.getMastersByPage(salonId, oracleAttempt))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        verifyNoInteractions(masterRepository);
    }
}
