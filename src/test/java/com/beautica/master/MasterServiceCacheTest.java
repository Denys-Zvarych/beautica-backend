package com.beautica.master;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.repository.CityRepository;
import com.beautica.config.CacheConfig;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.WorkingHours;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.WorkingHoursRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.eq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        // The REAL prefix evictor, never a @MockBean: it owns the cache-key-shape predicate whose
        // silent mismatch made five evictions no-ops, so these tests must execute it.
        // com.beautica.config.ClockConfig (Phase 29.2 fallout): getMasterCalendar now needs a
        // Clock bean to resolve BookingResponse.awaitingClosure's "now" — none of this class's
        // tests assert on that flag, so the real systemUTC() clock ClockConfig provides is fine.
        classes = {MasterService.class, CacheConfig.class, com.beautica.common.cache.MasterCachePrefixEvictor.class,
                com.beautica.config.ClockConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(MasterServiceCacheTest.TransactionConfig.class)
@DisplayName("MasterService — @CacheEvict afterCommit behaviour")
class MasterServiceCacheTest {

    @TestConfiguration
    static class TransactionConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() { return new Object(); }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {}

                @Override
                protected void doCommit(DefaultTransactionStatus status) {}

                @Override
                protected void doRollback(DefaultTransactionStatus status) {}
            };
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
            return new TransactionTemplate(tm);
        }
    }

    @MockBean MasterRepository masterRepository;
    @MockBean UserRepository userRepository;
    @MockBean SalonRepository salonRepository;
    @MockBean WorkingHoursRepository workingHoursRepository;
    @MockBean BookingRepository bookingRepository;
    @MockBean CityRepository cityRepository;
    // Phase 13.1: MasterService now constructor-depends on BookingSlugService.
    // This slice does not exercise the creation paths, so a mock satisfies the wiring.
    @MockBean com.beautica.booking.service.BookingSlugService bookingSlugService;
    // Phase 21.3: MasterService now constructor-depends on AuthorizationService (rotateMasterToSalon).
    // This slice does not exercise that path, so a mock satisfies the wiring.
    @MockBean AuthorizationService authorizationService;
    // Bookability-eviction fix: MasterService now constructor-depends on these two collaborators.
    // This slice only asserts master-calendar / master-by-user eviction, so mocks satisfy the wiring
    // (their bookability-cache eviction is guarded at unit tier in MasterServiceTest and end-to-end
    // in SalonPublicProfileIntegrationTest).
    @MockBean com.beautica.booking.service.SlotCalculationService slotCalculationService;
    @MockBean com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;

    @Autowired MasterService masterService;
    @Autowired CacheManager cacheManager;
    @Autowired TransactionTemplate transactionTemplate;

    private static final UUID ACTOR_ID  = UUID.randomUUID();
    private static final UUID MASTER_ID = UUID.randomUUID();

    @BeforeEach
    void clearCache() {
        Cache calendarCache = cacheManager.getCache("master-calendar");
        if (calendarCache != null) calendarCache.clear();
        Cache masterByUserCache = cacheManager.getCache("master-by-user");
        if (masterByUserCache != null) masterByUserCache.clear();
    }

    @Test
    @DisplayName("upsertWorkingHours evicts the master-calendar cache after commit")
    void should_evictMasterCalendarCache_when_upsertWorkingHours() {
        Cache cache = cacheManager.getCache("master-calendar");
        assertThat(cache).isNotNull();
        // Populated through the REAL @Cacheable proxy on MasterService#getMasterCalendar so the key
        // asserted below is the one Spring computed, not one this test invented. The previous
        // `new SimpleKey(...)` sentinel matched the equally-wrong production predicate, which is how
        // a permanently no-op eviction stayed green here.
        Object cacheKey = populateRealCalendarEntry(MASTER_ID);

        Master master = mock(Master.class);
        when(masterRepository.findByIdWithUserAndSalon(MASTER_ID)).thenReturn(Optional.of(master));
        // upsert merge map is built from the all-rows finder (incl. inactive), not the
        // active-only finder — matching production after the 23505 duplicate-INSERT fix.
        when(workingHoursRepository.findByMasterId(MASTER_ID)).thenReturn(List.of());
        WorkingHours savedWh = mock(WorkingHours.class);
        when(workingHoursRepository.saveAll(any())).thenReturn(List.of(savedWh));
        when(savedWh.getDayOfWeek()).thenReturn(1);
        when(savedWh.getStartTime()).thenReturn(LocalTime.of(9, 0));
        when(savedWh.getEndTime()).thenReturn(LocalTime.of(18, 0));
        when(savedWh.isActive()).thenReturn(true);

        WorkingHoursRequest request = new WorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(18, 0), true);

        // Act — wrap in transaction so afterCommit() fires
        transactionTemplate.execute(status -> {
            masterService.upsertWorkingHours(ACTOR_ID, MASTER_ID, List.of(request));
            return null;
        });

        assertThat(cache.get(cacheKey))
                .as("master-calendar cache must be evicted after upsertWorkingHours commits")
                .isNull();
    }

    // V83 removed MasterService.addScheduleException / removeScheduleException. Per-date override
    // cache eviction now lives in MasterScheduleService and is covered by MasterScheduleServiceIT
    // (real-Postgres) — there is no longer a unit-level cache-evict path to assert here.

    @Test
    @DisplayName("deactivateMaster evicts the master-calendar cache after commit")
    void should_evictMasterCalendarCache_when_deactivateMaster() {
        Cache cache = cacheManager.getCache("master-calendar");
        assertThat(cache).isNotNull();
        // Real proxy again — see should_evictMasterCalendarCache_when_upsertWorkingHours.
        Object cacheKey = populateRealCalendarEntry(MASTER_ID);

        User user = mock(User.class);
        when(user.getId()).thenReturn(UUID.randomUUID());

        Master master = mock(Master.class);
        when(master.getUser()).thenReturn(user);
        when(masterRepository.findByIdWithUserAndSalon(MASTER_ID)).thenReturn(Optional.of(master));
        when(masterRepository.save(master)).thenReturn(master);

        // Act — wrap in transaction so afterCommit() fires
        transactionTemplate.execute(status -> {
            masterService.deactivateMaster(ACTOR_ID, MASTER_ID);
            return null;
        });

        assertThat(cache.get(cacheKey))
                .as("master-calendar cache must be evicted after deactivateMaster commits")
                .isNull();
    }

    @Test
    @DisplayName("deactivateMaster evicts only the specific user's master-by-user cache entry")
    void should_evictMasterByUserCache_forSpecificUserId_when_deactivateMaster() {
        Cache masterByUserCache = cacheManager.getCache("master-by-user");
        assertThat(masterByUserCache).isNotNull();

        UUID userAId = UUID.randomUUID();
        UUID userBId = UUID.randomUUID();

        // Prime both entries
        masterByUserCache.put(userAId, "master-a");
        masterByUserCache.put(userBId, "master-b");
        assertThat(masterByUserCache.get(userAId)).isNotNull();
        assertThat(masterByUserCache.get(userBId)).isNotNull();

        User userA = mock(User.class);
        when(userA.getId()).thenReturn(userAId);

        Master master = mock(Master.class);
        when(master.getUser()).thenReturn(userA);
        when(masterRepository.findByIdWithUserAndSalon(MASTER_ID)).thenReturn(Optional.of(master));

        // Act — wrap in transaction so afterCommit() fires
        transactionTemplate.execute(status -> {
            masterService.deactivateMaster(ACTOR_ID, MASTER_ID);
            return null;
        });

        // Only user A's entry must be evicted; user B must survive
        assertThat(masterByUserCache.get(userAId))
                .as("master-by-user cache entry for userA must be evicted after deactivateMaster commits")
                .isNull();
        assertThat(masterByUserCache.get(userBId))
                .as("master-by-user cache entry for userB must NOT be evicted")
                .isNotNull();
    }

    /**
     * Populates one {@code master-calendar} entry via the REAL {@code @Cacheable} method and returns
     * the key Spring stored, read back off the native Caffeine map rather than constructed here.
     *
     * <p>Driving the proxy is the whole point: an explicit SpEL {@code key = "{...}"} evaluates to a
     * {@code List}, never the {@code SimpleKey} these tests used to seed, and asserting against a
     * self-invented key is what let five silently-unsatisfiable eviction predicates ship. The
     * booking id-page is stubbed empty so the method returns a cacheable non-null {@code Page}
     * without reaching hydration.
     */
    private Object populateRealCalendarEntry(UUID masterId) {
        when(bookingRepository.findActiveIdsByMasterIdAndStartsAtBetween(
                eq(masterId), any(OffsetDateTime.class), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        masterService.getMasterCalendar(masterId, LocalDate.now(), LocalDate.now().plusDays(7),
                PageRequest.of(0, 20));

        Cache calendarCache = cacheManager.getCache("master-calendar");
        assertThat(calendarCache).isNotNull();
        Object nativeCache = calendarCache.getNativeCache();
        assertThat(nativeCache).isInstanceOf(com.github.benmanes.caffeine.cache.Cache.class);
        var keys = ((com.github.benmanes.caffeine.cache.Cache<?, ?>) nativeCache).asMap().keySet();
        assertThat(keys)
                .as("the real @Cacheable proxy must have stored exactly one master-calendar entry")
                .hasSize(1);
        return keys.iterator().next();
    }
}
