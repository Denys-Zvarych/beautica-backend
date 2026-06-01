package com.beautica.master;

import com.beautica.booking.repository.BookingRepository;
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

import org.springframework.cache.interceptor.SimpleKey;

import static org.assertj.core.api.Assertions.assertThat;
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
        classes = {MasterService.class, CacheConfig.class},
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

        // PERF-MEDIUM-4: eviction filters by SimpleKey whose toString() starts with "[masterId,".
        // A plain String sentinel is not a SimpleKey and would survive the removeIf predicate.
        SimpleKey calendarKey = new SimpleKey(MASTER_ID, LocalDate.now(), LocalDate.now().plusDays(7), 0, 20);
        calendarCache.put(calendarKey, "sentinel-value");
        assertThat(calendarCache.get(calendarKey))
                .as("sentinel must be in master-calendar cache before deactivation")
                .isNotNull();

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
        // PERF-MEDIUM-4: use a SimpleKey sentinel so the removeIf predicate recognises it.
        SimpleKey calendarKey = new SimpleKey(MASTER_ID, LocalDate.now(), LocalDate.now().plusDays(7), 0, 20);
        calendarCache.put(calendarKey, "calendar-data");

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

        // PERF-MEDIUM-3: available-slots eviction filters by SimpleKey whose toString() starts
        // with "[masterId," — the key shape is SimpleKey[masterId, date, masterServiceId].
        SimpleKey slotKey = new SimpleKey(MASTER_ID, LocalDate.now(), UUID.randomUUID());
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
