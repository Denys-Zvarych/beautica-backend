package com.beautica.booking.service;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.util.TimeSlotCalculator;
import com.beautica.config.CacheConfig;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WorkingHoursRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {SlotCalculationService.class, CacheConfig.class},
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

    @MockBean WorkingHoursRepository workingHoursRepository;
    @MockBean BookingRepository bookingRepository;
    @MockBean MasterServiceRepository masterServiceRepository;
    @MockBean ScheduleExceptionRepository scheduleExceptionRepository;
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
        reset(masterServiceRepository, workingHoursRepository,
                scheduleExceptionRepository, bookingRepository, timeSlotCalculator);
    }

    @Test
    @DisplayName("evictAvailableSlots — cache miss on subsequent getAvailableSlots call")
    void should_evictSlotCache_when_evictAvailableSlotsIsCalled() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate futureDate = LocalDate.now(clock).plusDays(7);

        // Stub returns empty so getAvailableSlots completes without NPE
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.empty());

        // First call — cache miss, hits repository
        // Empty optional → NotFoundException, but @Cacheable caches the exception result
        // Actually with Caffeine, exceptions are NOT cached — each call with the same key
        // that throws will re-invoke the method. We therefore stub a valid MSA to get
        // a cacheable (non-throwing) result.
        // Re-stub to use a valid response so the result is cacheable.
        var sd = com.beautica.service.entity.ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();
        var msa = com.beautica.service.entity.MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(sd)
                .isActive(true)
                .build();
        reset(masterServiceRepository);
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(workingHoursRepository.findByMasterIdAndDayOfWeek(masterId, futureDate.getDayOfWeek().getValue()))
                .thenReturn(Optional.empty());

        // First call — populates cache
        slotCalculationService.getAvailableSlots(masterId, futureDate, masterServiceId);
        // Second call — cache hit, repository NOT called again
        slotCalculationService.getAvailableSlots(masterId, futureDate, masterServiceId);

        // Assumption: evictAvailableSlots must be called OUTSIDE an active transaction.
        // @Transactional(NOT_SUPPORTED) suspends the caller's transaction — calling from within
        // a transaction body would suspend eviction, causing this test to give a false green.
        // Evict the cache entry
        slotCalculationService.evictAvailableSlots(masterId, futureDate, masterServiceId);

        // Third call — cache was evicted, repository called again
        slotCalculationService.getAvailableSlots(masterId, futureDate, masterServiceId);

        // Calls 1 and 3 hit the repository (call 2 was a cache hit). Total = 2.
        verify(masterServiceRepository, times(2))
                .findByMasterIdAndIdWithGraph(masterId, masterServiceId);
    }
}
