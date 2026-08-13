package com.beautica.booking.service;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.cache.MasterCachePrefixEvictor;
import com.beautica.common.util.TimeSlotCalculator;
import com.beautica.config.CacheConfig;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.master.entity.Master;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.service.repository.MasterServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {SlotCalculationService.class, CacheConfig.class,
                   MasterCachePrefixEvictor.class, ScheduleDateMath.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(SlotCalculationServiceCacheTest.ClockConfig.class)
@DisplayName("SlotCalculationService — @Cacheable/@CacheEvict behaviour")
class SlotCalculationServiceCacheTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-05-07T00:00:00Z"), ZoneId.of("Europe/Kyiv"));
        }
    }

    @MockBean BookingRepository bookingRepository;
    @MockBean MasterServiceRepository masterServiceRepository;
    @MockBean MasterScheduleService masterScheduleService;
    @MockBean TimeSlotCalculator timeSlotCalculator;

    @Autowired SlotCalculationService slotCalculationService;
    @Autowired CacheManager cacheManager;
    @Autowired Clock clock;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(n -> {
            var cache = cacheManager.getCache(n);
            if (cache != null) {
                cache.clear();
            }
        });
        reset(masterServiceRepository, masterScheduleService, bookingRepository, timeSlotCalculator);
    }

    /**
     * Stubs a cacheable (non-throwing) {@code getAvailableSlots} for one (master, service, date).
     *
     * <p>With Caffeine, exceptions are NOT cached — a key whose loader throws re-invokes the method on
     * every call — so the stub must return successfully to exercise the {@code @Cacheable} hit/miss
     * path at all. NO_SCHEDULE yields an empty (but cached) slot list, which is enough: these tests
     * assert cache population/eviction, never slot content.
     */
    private void stubCacheableSlots(UUID masterId, UUID masterServiceId, LocalDate date) {
        var sd = com.beautica.service.entity.ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();
        Master activeMaster = Master.builder().isActive(true).build();
        var msa = com.beautica.service.entity.MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(sd)
                .master(activeMaster)
                .isActive(true)
                .build();
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveDay(masterId, date))
                .thenReturn(new EffectiveDayResponse(date, EffectiveDaySource.NO_SCHEDULE, List.of()));
    }

    @Test
    @DisplayName("evictMasterAvailabilityCaches — cache miss on subsequent getAvailableSlots call")
    void should_evictSlotCache_when_evictMasterAvailabilityCachesIsCalled() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate futureDate = LocalDate.now(clock).plusDays(7);
        stubCacheableSlots(masterId, masterServiceId, futureDate);

        // First call — populates cache
        slotCalculationService.getAvailableSlots(masterId, futureDate, masterServiceId);
        // Second call — cache hit, repository NOT called again
        slotCalculationService.getAvailableSlots(masterId, futureDate, masterServiceId);

        // Assumption: this must be called OUTSIDE an active transaction. @Transactional(NOT_SUPPORTED)
        // suspends the caller's transaction — calling from within a transaction body would suspend
        // eviction, causing this test to give a false green.
        slotCalculationService.evictMasterAvailabilityCaches(masterId);

        // Third call — cache was evicted, repository called again
        slotCalculationService.getAvailableSlots(masterId, futureDate, masterServiceId);

        // Calls 1 and 3 hit the repository (call 2 was a cache hit). Total = 2.
        verify(masterServiceRepository, times(2))
                .findByMasterIdAndIdWithGraph(masterId, masterServiceId);
    }

    /**
     * THE REGRESSION GUARD for the cache-staleness hole this eviction exists to close.
     *
     * <p>A master performs one service at a time, so a booking of service A consumes wall-clock time
     * that also bounds the slots offered for service B on the same date. The eviction used to be keyed
     * {@code {masterId, date, masterServiceId}} and dropped ONLY the booked service's entry, leaving
     * B's cached list advertising a time A had already taken for the rest of the 60-second TTL.
     *
     * <p>Asserted at the cache layer rather than through a booking write: a booking write's contribution
     * is the {@code evictMasterAvailabilityCaches} call itself (verified in {@code BookingServiceTest} /
     * {@code AppointmentTransitionServiceTest}), while what regressed here is which entries that call
     * removes. Reverting {@code BOOKING_WRITE_CACHES} to omit {@code available-slots}, or narrowing the
     * evictor back to a per-service key, turns this red.
     */
    @Test
    @DisplayName("evictMasterAvailabilityCaches — drops EVERY service's slot list for the master, "
            + "not just the booked one")
    void should_evictOtherServicesSlots_when_masterAvailabilityIsEvicted() {
        UUID masterId = UUID.randomUUID();
        UUID bookedServiceId = UUID.randomUUID();
        UUID otherServiceId = UUID.randomUUID();
        LocalDate date = LocalDate.now(clock).plusDays(7);
        stubCacheableSlots(masterId, bookedServiceId, date);
        stubCacheableSlots(masterId, otherServiceId, date);

        // Both services' slot lists are cached — a client browsing service B alongside the client
        // about to book service A.
        slotCalculationService.getAvailableSlots(masterId, date, bookedServiceId);
        slotCalculationService.getAvailableSlots(masterId, date, otherServiceId);

        slotCalculationService.evictMasterAvailabilityCaches(masterId);

        slotCalculationService.getAvailableSlots(masterId, date, otherServiceId);

        // The UNBOOKED service recomputed → its stale list was dropped too. Under the old per-service
        // eviction this stayed at 1 (served from cache) and offered the consumed time.
        verify(masterServiceRepository, times(2))
                .findByMasterIdAndIdWithGraph(masterId, otherServiceId);
    }

    /**
     * The eviction stays SCOPED TO ONE MASTER — it must never degrade into a blanket
     * {@code available-slots} clear (Anti-Bug §F-6), which would make every booking anywhere on the
     * platform a cache-wide thundering-herd trigger.
     */
    @Test
    @DisplayName("evictMasterAvailabilityCaches — leaves another master's cached slots intact")
    void should_keepOtherMastersSlots_when_oneMasterIsEvicted() {
        UUID bookedMasterId = UUID.randomUUID();
        UUID otherMasterId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        LocalDate date = LocalDate.now(clock).plusDays(7);
        stubCacheableSlots(bookedMasterId, serviceId, date);
        stubCacheableSlots(otherMasterId, serviceId, date);

        slotCalculationService.getAvailableSlots(bookedMasterId, date, serviceId);
        slotCalculationService.getAvailableSlots(otherMasterId, date, serviceId);

        slotCalculationService.evictMasterAvailabilityCaches(bookedMasterId);

        slotCalculationService.getAvailableSlots(otherMasterId, date, serviceId);

        // Still 1 — the uninvolved master was served from cache, so the sweep matched on the key's
        // FIRST element and did not clear the whole cache.
        verify(masterServiceRepository, times(1))
                .findByMasterIdAndIdWithGraph(otherMasterId, serviceId);
    }
}
