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

    @Test
    @DisplayName("evictAvailableSlots — cache miss on subsequent getAvailableSlots call")
    void should_evictSlotCache_when_evictAvailableSlotsIsCalled() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate futureDate = LocalDate.now(clock).plusDays(7);

        // A valid (non-throwing) MSA so the result is cacheable. With Caffeine, exceptions are NOT
        // cached — each call with the same key that throws re-invokes the method — so we must return
        // a successful result to exercise the @Cacheable hit/miss path.
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
        // No weekly template / override covers the date → NO_SCHEDULE, empty intervals, no slots.
        // This keeps the result cacheable while exercising the resolver as the underlying data source.
        when(masterScheduleService.resolveEffectiveDay(masterId, futureDate))
                .thenReturn(new EffectiveDayResponse(
                        futureDate, EffectiveDaySource.NO_SCHEDULE, List.of()));

        // First call — populates cache
        slotCalculationService.getAvailableSlots(masterId, futureDate, masterServiceId);
        // Second call — cache hit, repository NOT called again
        slotCalculationService.getAvailableSlots(masterId, futureDate, masterServiceId);

        // Assumption: evictAvailableSlots must be called OUTSIDE an active transaction.
        // @Transactional(NOT_SUPPORTED) suspends the caller's transaction — calling from within
        // a transaction body would suspend eviction, causing this test to give a false green.
        // Evict the cache entry for the (masterId, date, masterServiceId) key
        slotCalculationService.evictAvailableSlots(masterId, futureDate, masterServiceId);

        // Third call — cache was evicted, repository called again
        slotCalculationService.getAvailableSlots(masterId, futureDate, masterServiceId);

        // Calls 1 and 3 hit the repository (call 2 was a cache hit). Total = 2.
        verify(masterServiceRepository, times(2))
                .findByMasterIdAndIdWithGraph(masterId, masterServiceId);
    }
}
