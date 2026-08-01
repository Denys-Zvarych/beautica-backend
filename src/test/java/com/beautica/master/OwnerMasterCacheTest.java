package com.beautica.master;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.repository.CityRepository;
import com.beautica.config.CacheConfig;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WorkingHoursRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.repository.SalonRepository;
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
import java.util.Optional;
import java.util.UUID;

import com.beautica.common.cache.CacheKeyFixtures;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 12.7 — unit-style cache test verifying that {@link MasterService#deactivateOwnerMaster}
 * evicts both the {@code master-by-user} and {@code master-calendar} caches after commit.
 *
 * <p>Modelled exactly after {@link MasterServiceCacheTest}: loads only
 * {@code MasterService} and {@code CacheConfig}, mocks every repository, and wraps each
 * service call in a {@link TransactionTemplate} so the
 * {@code afterCommit} synchronization fires synchronously inside the test.
 *
 * <p>The stub {@link PlatformTransactionManager} commits synchronously so
 * {@code TransactionSynchronizationManager.registerSynchronization(...).afterCommit()} runs
 * within the same thread as the test assertion.
 */
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
@Import(OwnerMasterCacheTest.TransactionConfig.class)
@DisplayName("MasterService#deactivateOwnerMaster — cache eviction after commit (Phase 12.7)")
class OwnerMasterCacheTest {

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
    @MockBean ScheduleExceptionRepository scheduleExceptionRepository;
    @MockBean BookingRepository bookingRepository;
    @MockBean CityRepository cityRepository;
    // Phase 13.1: MasterService now constructor-depends on BookingSlugService.
    @MockBean com.beautica.booking.service.BookingSlugService bookingSlugService;
    // Phase 21.3: MasterService now constructor-depends on AuthorizationService (rotateMasterToSalon).
    @MockBean AuthorizationService authorizationService;
    // Phase 21.x (62ec609): MasterService now constructor-depends on SlotCalculationService
    // (bookable-master gating) and SalonCatalogCacheEvictor (salon-catalogue cache eviction).
    @MockBean com.beautica.booking.service.SlotCalculationService slotCalculationService;
    @MockBean com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;

    @Autowired MasterService masterService;
    @Autowired CacheManager cacheManager;
    @Autowired TransactionTemplate transactionTemplate;

    private static final UUID ACTOR_USER_ID = UUID.randomUUID();
    private static final UUID SALON_ID      = UUID.randomUUID();
    private static final UUID MASTER_ID     = UUID.randomUUID();

    @BeforeEach
    void clearCaches() {
        Cache calendarCache = cacheManager.getCache("master-calendar");
        if (calendarCache != null) calendarCache.clear();
        Cache masterByUserCache = cacheManager.getCache("master-by-user");
        if (masterByUserCache != null) masterByUserCache.clear();
        Cache availableSlotsCache = cacheManager.getCache("available-slots");
        if (availableSlotsCache != null) availableSlotsCache.clear();
    }

    // ── master-by-user eviction ───────────────────────────────────────────────

    @Test
    @DisplayName("deactivateOwnerMaster evicts the owner's master-by-user cache entry after commit")
    void should_evictMasterByUserCacheForOwner_when_deactivateOwnerMasterCommits() {
        // Arrange
        Cache masterByUserCache = cacheManager.getCache("master-by-user");
        assertThat(masterByUserCache).isNotNull();

        masterByUserCache.put(ACTOR_USER_ID, "cached-master-value");
        assertThat(masterByUserCache.get(ACTOR_USER_ID))
                .as("cache entry must be present before deactivation")
                .isNotNull();

        stubOwnerMasterForDeactivation();

        // Act — wrap in transaction so afterCommit() fires synchronously
        transactionTemplate.execute(status -> {
            masterService.deactivateOwnerMaster(ACTOR_USER_ID, SALON_ID);
            return null;
        });

        // Assert
        assertThat(masterByUserCache.get(ACTOR_USER_ID))
                .as("master-by-user cache entry for the owner must be evicted after deactivateOwnerMaster commits")
                .isNull();
    }

    @Test
    @DisplayName("deactivateOwnerMaster evicts only the owner's entry, leaving other users' entries intact")
    void should_evictOnlyOwnerEntry_andLeaveOtherUserEntryIntact_when_deactivateOwnerMasterCommits() {
        // Arrange
        Cache masterByUserCache = cacheManager.getCache("master-by-user");
        assertThat(masterByUserCache).isNotNull();

        UUID otherUserId = UUID.randomUUID();
        masterByUserCache.put(ACTOR_USER_ID, "owner-master");
        masterByUserCache.put(otherUserId, "other-master");

        stubOwnerMasterForDeactivation();

        // Act
        transactionTemplate.execute(status -> {
            masterService.deactivateOwnerMaster(ACTOR_USER_ID, SALON_ID);
            return null;
        });

        // Assert
        assertThat(masterByUserCache.get(ACTOR_USER_ID))
                .as("owner's master-by-user entry must be evicted")
                .isNull();
        assertThat(masterByUserCache.get(otherUserId))
                .as("unrelated user's master-by-user entry must NOT be evicted")
                .isNotNull();
    }

    // ── master-calendar eviction ──────────────────────────────────────────────

    @Test
    @DisplayName("deactivateOwnerMaster clears the master-calendar cache after commit")
    void should_clearMasterCalendarCache_when_deactivateOwnerMasterCommits() {
        // Arrange
        Cache calendarCache = cacheManager.getCache("master-calendar");
        assertThat(calendarCache).isNotNull();

        // Populated through the REAL @Cacheable proxy on MasterService#getMasterCalendar, so the key
        // under test is the one Spring computes rather than one this test invented. The previous
        // version seeded `new SimpleKey(...)`, which an explicit-`key` @Cacheable never produces — it
        // matched the equally-wrong production predicate and so kept a broken eviction green.
        Object calendarKey = populateRealCalendarEntry(MASTER_ID);

        stubOwnerMasterForDeactivation();

        // Act — wrap in transaction so afterCommit() fires synchronously
        transactionTemplate.execute(status -> {
            masterService.deactivateOwnerMaster(ACTOR_USER_ID, SALON_ID);
            return null;
        });

        // Assert
        assertThat(calendarCache.get(calendarKey))
                .as("master-calendar cache must be fully cleared after deactivateOwnerMaster commits")
                .isNull();
    }

    @Test
    @DisplayName("deactivateOwnerMaster evicts both master-by-user and master-calendar caches in one commit")
    void should_evictBothCaches_when_deactivateOwnerMasterCommits() {
        // Arrange
        Cache masterByUserCache = cacheManager.getCache("master-by-user");
        Cache calendarCache = cacheManager.getCache("master-calendar");
        assertThat(masterByUserCache).isNotNull();
        assertThat(calendarCache).isNotNull();

        masterByUserCache.put(ACTOR_USER_ID, "cached-master");
        // Real proxy again — see should_clearMasterCalendarCache_when_deactivateOwnerMasterCommits.
        Object calendarKey = populateRealCalendarEntry(MASTER_ID);

        stubOwnerMasterForDeactivation();

        // Act
        transactionTemplate.execute(status -> {
            masterService.deactivateOwnerMaster(ACTOR_USER_ID, SALON_ID);
            return null;
        });

        // Assert — both caches must be in a clean state after a single deactivation
        assertThat(masterByUserCache.get(ACTOR_USER_ID))
                .as("master-by-user entry must be evicted after deactivateOwnerMaster")
                .isNull();
        assertThat(calendarCache.get(calendarKey))
                .as("master-calendar must be cleared after deactivateOwnerMaster")
                .isNull();
    }

    // ── available-slots eviction ──────────────────────────────────────────────

    @Test
    @DisplayName("deactivateOwnerMaster clears the available-slots cache after commit")
    void should_clearAvailableSlotsCacheAfterCommit_when_deactivateOwnerMasterCommits() {
        // Arrange
        Cache availableSlotsCache = cacheManager.getCache("available-slots");
        assertThat(availableSlotsCache).isNotNull();

        // SlotCalculationService is a @MockBean here (MasterService only calls it, it is not the
        // subject), so its real @Cacheable proxy is not reachable from this context — the key is
        // seeded via CacheKeyFixtures.spelKey instead of by hand. That helper is not a guess: it is
        // pinned against the real SlotCalculationService#getAvailableSlots proxy by
        // CachePrefixEvictionKeyShapeTest, which asserts Spring's actual key equals its output. This
        // test therefore covers the WRITE-PATH wiring (does deactivation register a slot eviction at
        // all), while the key shape itself is proven against ground truth over there.
        Object slotKey = CacheKeyFixtures.spelKey(MASTER_ID, LocalDate.now(), UUID.randomUUID());
        availableSlotsCache.put(slotKey, "sentinel-slots-value");
        assertThat(availableSlotsCache.get(slotKey))
                .as("sentinel must be present in available-slots cache before deactivation")
                .isNotNull();

        stubOwnerMasterForDeactivation();

        // Act — wrap in transaction so afterCommit() fires synchronously
        transactionTemplate.execute(status -> {
            masterService.deactivateOwnerMaster(ACTOR_USER_ID, SALON_ID);
            return null;
        });

        // Assert
        assertThat(availableSlotsCache.get(slotKey))
                .as("available-slots cache must be fully cleared after deactivateOwnerMaster commits")
                .isNull();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    /**
     * Populates one {@code master-calendar} entry by calling the REAL {@code @Cacheable} method on
     * {@link MasterService}, and returns the key Spring stored for it — read back off the native
     * Caffeine map, never constructed here.
     *
     * <p>This is the difference that matters: the assertion that follows compares against a key the
     * framework produced, so it cannot silently agree with a broken eviction predicate the way a
     * hand-seeded {@code SimpleKey} sentinel did. The booking id-page is stubbed empty purely so the
     * method returns a cacheable non-null {@code Page} without touching hydration.
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

    /**
     * Stubs the {@link MasterRepository#findByUserId} call that {@code deactivateOwnerMaster}
     * uses to locate the owner-master row. The returned mock has type {@code SALON_OWNER} and
     * is associated with {@link #SALON_ID} so the filter chain inside the service method passes.
     *
     * <p>{@code master.setActive(false)} is a no-op on the mock; Hibernate dirty-checking is not
     * exercised here because there is no real JPA session. The purpose of the test is exclusively
     * to verify the cache eviction callbacks fire after commit.
     */
    private void stubOwnerMasterForDeactivation() {
        var salon = mock(com.beautica.salon.entity.Salon.class);
        when(salon.getId()).thenReturn(SALON_ID);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        // deactivateOwnerMaster now calls findByUserIdWithSalon (MEDIUM F2 fix) —
        // stub the new graph method so the filter chain resolves correctly.
        when(masterRepository.findByUserIdWithSalon(ACTOR_USER_ID)).thenReturn(Optional.of(master));
    }
}
