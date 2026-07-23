package com.beautica.master;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.repository.CityRepository;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
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
 * Unit tests for {@link MasterService#rotateMasterToSalon} (Phase 21.3).
 *
 * <p>Management authority over the master's CURRENT salon is already enforced by
 * {@code @PreAuthorize} on {@code MasterController.rotateMasterSalon} ({@code canManageMaster}).
 * These tests cover the service-layer behaviour that is NOT expressible in SpEL: the
 * master-type restriction (SALON_MASTER only), the same-owner requirement (via
 * {@link AuthorizationService#salonsShareOwner}), the no-op guard, the exact mutation applied to
 * the master's salon, and the {@code master-detail} / {@code master-detail-by-user} /
 * {@code master-by-user} cache evictions (HIGH + MEDIUM fixes — both userId-keyed caches store a
 * {@code MasterDetailResponse}/{@code Master} that embed the mutated {@code salon} association).
 *
 * <p>All destination-related denial reasons (cross-owner, inactive, not-found) now collapse to
 * {@link ForbiddenException} (403) rather than distinct 404/400 statuses (Sec LOW-1 status-code
 * oracle fix) — see {@code should_throwForbidden_when_destinationSalonDoesNotExist} and
 * {@code should_throwForbidden_when_destinationSalonIsInactive} below.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MasterService.rotateMasterToSalon — unit")
class MasterServiceRotateTest {

    @Mock private MasterRepository masterRepository;
    @Mock private UserRepository userRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private WorkingHoursRepository workingHoursRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private CacheManager cacheManager;
    @Mock private CityRepository cityRepository;
    @Mock private com.beautica.booking.service.BookingSlugService bookingSlugService;
    @Mock private AuthorizationService authorizationService;
    // Prefix-eviction fix: rotation's afterCommit master-calendar eviction delegates to the shared
    // evictor, so @InjectMocks must have one or the replayed synchronization NPEs. Mock is correct at
    // this tier — key-shape correctness is proven in CachePrefixEvictionKeyShapeTest.
    @Mock private com.beautica.common.cache.MasterCachePrefixEvictor cachePrefixEvictor;

    @InjectMocks
    private MasterService masterService;

    @Test
    @DisplayName("moves the master to the destination salon and evicts master-detail / master-detail-by-user / master-by-user after commit")
    void should_moveMasterToDestinationSalonAndEvictCache_when_rotationIsValid() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();

        User user = mock(User.class);
        when(user.getId()).thenReturn(masterUserId);
        Salon sourceSalon = Salon.builder().id(sourceSalonId).isActive(true).build();
        Salon destSalon = Salon.builder().id(destSalonId).isActive(true).build();
        Master master = Master.builder()
                .id(masterId)
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "user", user);
        ReflectionTestUtils.setField(master, "salon", sourceSalon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(authorizationService.salonsShareOwner(sourceSalonId, destSalonId)).thenReturn(true);
        when(salonRepository.findById(destSalonId)).thenReturn(Optional.of(destSalon));

        Cache masterDetailCache = mock(Cache.class);
        Cache masterDetailByUserCache = mock(Cache.class);
        Cache masterByUserCache = mock(Cache.class);
        when(cacheManager.getCache("master-detail")).thenReturn(masterDetailCache);
        when(cacheManager.getCache("master-detail-by-user")).thenReturn(masterDetailByUserCache);
        when(cacheManager.getCache("master-by-user")).thenReturn(masterByUserCache);
        // No cacheManager stub for master-calendar: that eviction is a prefix scan and now goes
        // through the injected MasterCachePrefixEvictor mock, so stubbing it here would be unused.

        TransactionSynchronizationManager.initSynchronization();
        MasterSummaryResponse response;
        try {
            response = masterService.rotateMasterToSalon(actorId, masterId, destSalonId);

            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            syncs.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(master.getSalon())
                .as("rotateMasterToSalon must reassign the master's salon association")
                .isEqualTo(destSalon);
        assertThat(response.masterId()).isEqualTo(masterId);
        verify(masterDetailCache).evict(masterId);
        // HIGH fix: master-detail-by-user (backs GET /masters/me, 10-min TTL) must also be
        // evicted — it stores the same MasterDetailResponse embedding `salon`.
        verify(masterDetailByUserCache).evict(masterUserId);
        // MEDIUM fix: master-by-user must be evicted on every write path that mutates this row,
        // mirroring deactivateMaster/deactivateOwnerMaster/createMasterForOwner.
        verify(masterByUserCache).evict(masterUserId);
    }

    @Test
    @DisplayName("throws NotFoundException when the master does not exist")
    void should_throwNotFound_when_masterDoesNotExist() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.rotateMasterToSalon(actorId, masterId, destSalonId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("throws ForbiddenException when the master is INDEPENDENT_MASTER (no salon to rotate from)")
    void should_throwForbidden_when_masterIsIndependent() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        Master master = Master.builder()
                .id(masterId)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .isActive(true)
                .build();

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        assertThatThrownBy(() -> masterService.rotateMasterToSalon(actorId, masterId, destSalonId))
                .isInstanceOf(ForbiddenException.class);
        verify(authorizationService, never()).salonsShareOwner(any(), any());
    }

    @Test
    @DisplayName("throws ForbiddenException when the master is the owner's own SALON_OWNER-type master row")
    void should_throwForbidden_when_masterIsOwnerType() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        Master master = Master.builder()
                .id(masterId)
                .masterType(MasterType.SALON_OWNER)
                .isActive(true)
                .build();

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        assertThatThrownBy(() -> masterService.rotateMasterToSalon(actorId, masterId, destSalonId))
                .isInstanceOf(ForbiddenException.class);
        verify(authorizationService, never()).salonsShareOwner(any(), any());
    }

    @Test
    @DisplayName("throws BusinessException(400) when the destination equals the master's current salon (no-op)")
    void should_throwBadRequest_when_destinationEqualsCurrentSalon() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Salon salon = Salon.builder().id(salonId).isActive(true).build();
        Master master = Master.builder()
                .id(masterId)
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "salon", salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        assertThatThrownBy(() -> masterService.rotateMasterToSalon(actorId, masterId, salonId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(authorizationService, never()).salonsShareOwner(any(), any());
    }

    @Test
    @DisplayName("throws ForbiddenException when the destination salon does not share the same owner")
    void should_throwForbidden_when_destinationSalonHasDifferentOwner() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        Salon sourceSalon = Salon.builder().id(sourceSalonId).isActive(true).build();
        Master master = Master.builder()
                .id(masterId)
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "salon", sourceSalon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(authorizationService.salonsShareOwner(sourceSalonId, destSalonId)).thenReturn(false);
        when(salonRepository.findById(destSalonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.rotateMasterToSalon(actorId, masterId, destSalonId))
                .isInstanceOf(ForbiddenException.class);
        // Sec LOW-2: the destination-salon lookup now runs unconditionally alongside
        // salonsShareOwner so the cross-owner, not-found, and inactive denial branches all perform
        // identical DB work — this used to assert never() invoked, which was the very timing
        // asymmetry that was fixed.
        verify(salonRepository).findById(destSalonId);
    }

    @Test
    @DisplayName("throws ForbiddenException (not a distinct 404 — Sec LOW-1) when the destination salon does not exist")
    void should_throwForbidden_when_destinationSalonDoesNotExist() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        Salon sourceSalon = Salon.builder().id(sourceSalonId).isActive(true).build();
        Master master = Master.builder()
                .id(masterId)
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "salon", sourceSalon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(authorizationService.salonsShareOwner(sourceSalonId, destSalonId)).thenReturn(true);
        when(salonRepository.findById(destSalonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> masterService.rotateMasterToSalon(actorId, masterId, destSalonId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("throws ForbiddenException (not a distinct 400 — Sec LOW-1) when the destination salon is inactive (soft-deleted)")
    void should_throwForbidden_when_destinationSalonIsInactive() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();
        Salon sourceSalon = Salon.builder().id(sourceSalonId).isActive(true).build();
        Salon inactiveDest = Salon.builder().id(destSalonId).isActive(false).build();
        Master master = Master.builder()
                .id(masterId)
                .masterType(MasterType.SALON_MASTER)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "salon", sourceSalon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(authorizationService.salonsShareOwner(sourceSalonId, destSalonId)).thenReturn(true);
        when(salonRepository.findById(destSalonId)).thenReturn(Optional.of(inactiveDest));

        assertThatThrownBy(() -> masterService.rotateMasterToSalon(actorId, masterId, destSalonId))
                .isInstanceOf(ForbiddenException.class);
        assertThat(master.getSalon())
                .as("master's salon must be untouched when the destination is rejected")
                .isEqualTo(sourceSalon);
    }
}
