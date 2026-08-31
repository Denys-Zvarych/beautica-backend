package com.beautica.master;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.service.LocationQueryService;
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
import com.beautica.common.cache.UserProfileCacheEvictor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        // Audit-fix cycle 2: the REAL UserProfileCacheEvictor, never a @MockBean. It owns the
        // `user-profile` cache name and the afterCommit registration for the CROSS-AGGREGATE
        // eviction (a `masters` write invalidating a `users` cache) — mocking it would make every
        // assertion in the user-profile section below vacuous, which is exactly the coverage
        // theater the audit called out.
        classes = {MasterService.class, CacheConfig.class, com.beautica.common.cache.MasterCachePrefixEvictor.class,
                com.beautica.common.cache.UserProfileCacheEvictor.class,
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
    @MockBean LocationQueryService locationQueryService;
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
        Cache masterDetailByUserCache = cacheManager.getCache("master-detail-by-user");
        if (masterDetailByUserCache != null) masterDetailByUserCache.clear();
        Cache userProfileCache = cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE);
        if (userProfileCache != null) userProfileCache.clear();
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

    // ── master-detail-by-user eviction (Phase 265) ────────────────────────────

    @Test
    @DisplayName("deactivateOwnerMaster evicts the owner's master-detail-by-user entry (GET /masters/me) after commit")
    void should_evictMasterDetailByUserCacheForOwner_when_deactivateOwnerMasterCommits() {
        // Arrange — this cache backs GET /masters/me, which Phase 265 widened to SALON_OWNER.
        // Without the eviction an owner who toggles «Я також працюю як майстер» OFF keeps being
        // served their own master profile for the rest of the 10-minute TTL, while GET /users/me's
        // hasMasterProfile (derived on read, never cached) already reads false — the two disagree
        // inside a single screen.
        Cache detailByUserCache = cacheManager.getCache("master-detail-by-user");
        assertThat(detailByUserCache)
                .as("master-detail-by-user must be registered by the real CacheConfig")
                .isNotNull();

        UUID otherUserId = UUID.randomUUID();
        detailByUserCache.put(ACTOR_USER_ID, "cached-owner-master-detail");
        detailByUserCache.put(otherUserId, "unrelated-master-detail");

        stubOwnerMasterForDeactivation();

        // Act — the TransactionTemplate commits synchronously, so afterCommit() runs inline.
        transactionTemplate.execute(status -> {
            masterService.deactivateOwnerMaster(ACTOR_USER_ID, SALON_ID);
            return null;
        });

        // Assert
        assertThat(detailByUserCache.get(ACTOR_USER_ID))
                .as("the deactivated owner's GET /masters/me entry must be gone after commit")
                .isNull();
        assertThat(detailByUserCache.get(otherUserId))
                .as("eviction is per-key — an unrelated provider's entry must survive (§F-6)")
                .isNotNull();
    }

    /**
     * Audit fix, finding 3 — {@code deactivateMaster} is the SECOND reachable deactivation path
     * for an owner-master row: {@code AuthorizationService#canManageMaster} authorizes
     * {@code MasterType.SALON_OWNER} rows, so {@code DELETE /api/v1/masters/&#123;masterId&#125;}
     * deactivates the same row the «Я також працюю як майстер» toggle owns. Before the fix only
     * {@code deactivateOwnerMaster} evicted {@code master-detail-by-user}, so the Phase 265
     * invariant ("no stale GET /masters/me entry can survive a deactivation") held on one of two
     * paths — and the no-counterpart claim documented on the reactivation branch of
     * {@code createMasterForOwner} rested on it.
     */
    @Test
    @DisplayName("deactivateMaster evicts the master's master-detail-by-user entry (GET /masters/me) after commit")
    void should_evictMasterDetailByUserCache_when_deactivateMasterCommits() {
        // Arrange
        Cache detailByUserCache = cacheManager.getCache("master-detail-by-user");
        assertThat(detailByUserCache)
                .as("master-detail-by-user must be registered by the real CacheConfig")
                .isNotNull();

        UUID otherUserId = UUID.randomUUID();
        detailByUserCache.put(ACTOR_USER_ID, "cached-owner-master-detail");
        detailByUserCache.put(otherUserId, "unrelated-master-detail");

        stubMasterForStaffDeactivation();

        // Act — the TransactionTemplate commits synchronously, so afterCommit() runs inline.
        transactionTemplate.execute(status -> {
            masterService.deactivateMaster(UUID.randomUUID(), MASTER_ID);
            return null;
        });

        // Assert
        assertThat(detailByUserCache.get(ACTOR_USER_ID))
                .as("the deactivated master's GET /masters/me entry must be gone after commit — "
                        + "otherwise an owner who deactivated their own master row through "
                        + "DELETE /masters/{masterId} keeps being served it for the 10-minute TTL")
                .isNull();
        assertThat(detailByUserCache.get(otherUserId))
                .as("eviction is per-key — an unrelated provider's entry must survive (§F-6)")
                .isNotNull();
    }

    // ── NEGATIVE CACHING + create/reactivate eviction (audit-fix cycle 2) ─────
    //
    // These four tests guard the invariant rewritten in MasterService#deactivateOwnerMaster:
    // master-detail-by-user memoises the MISS as well as the hit, so eviction is bidirectional and
    // every CREATE and REACTIVATE path must evict — not just the deactivate paths cycle 1 covered.
    // The create side is the half most likely to rot: its failure mode is a 404 on a profile that
    // demonstrably exists, self-healing after 10 minutes, with nothing logged.

    /**
     * The premise every other test in this section rests on. If the empty Optional were NOT stored,
     * there would be no negative entry to go stale, the create/reactivate evictions below would be
     * unfalsifiable, and the MEDIUM finding would be unfixed while looking fixed.
     *
     * <p>Asserted by repository call count rather than by reading the cache, because a stored
     * {@code NullValue} is exactly what {@code cache.get(key)} reports as a non-null wrapper around
     * null — an assertion that is easy to write and easy to get backwards. "The second call did not
     * touch the database" cannot be satisfied by anything except a real cache hit.
     */
    @Test
    @DisplayName("findMyMasterDetail caches the EMPTY result — a second call for an opted-out user does not re-query")
    void should_cacheTheEmptyResult_when_findMyMasterDetailFindsNoActiveRow() {
        // Arrange — an opted-out owner: no active master row.
        when(masterRepository.findActiveByUserIdWithUserAndSalon(ACTOR_USER_ID))
                .thenReturn(Optional.empty());

        // Act — two reads through the @Cacheable proxy.
        var first = transactionTemplate.execute(status -> masterService.findMyMasterDetail(ACTOR_USER_ID));
        var second = transactionTemplate.execute(status -> masterService.findMyMasterDetail(ACTOR_USER_ID));

        // Assert
        assertThat(first).as("an opted-out owner must resolve to absent").isEmpty();
        assertThat(second).as("and stay absent on the cached read").isEmpty();
        verify(masterRepository, times(1))
                .findActiveByUserIdWithUserAndSalon(ACTOR_USER_ID);
    }

    /**
     * THE new invariant, and the one most likely to rot: a REACTIVATION must drop the cached miss.
     *
     * <p>This is the highest-probability key in the whole cache to be holding {@code Optional
     * .empty()} — the matching toggle-OFF evicted it moments earlier, and any read in the interval
     * re-cached the miss. Without the evict, an owner who turns «Я також працюю як майстер» back ON
     * gets 404 on their own reactivated profile for the rest of the 10-minute TTL while GET
     * /users/me's hasMasterProfile already reads true.
     */
    @Test
    @DisplayName("createMasterForOwner REACTIVATION evicts the cached NEGATIVE master-detail-by-user entry")
    void should_evictNegativeMasterDetailByUserEntry_when_createMasterForOwnerReactivatesRow() {
        // Arrange — warm a genuine negative entry through the real @Cacheable proxy, exactly as an
        // opted-out owner's GET /masters/me would.
        when(masterRepository.findActiveByUserIdWithUserAndSalon(ACTOR_USER_ID))
                .thenReturn(Optional.empty());
        transactionTemplate.execute(status -> masterService.findMyMasterDetail(ACTOR_USER_ID));
        verify(masterRepository, times(1)).findActiveByUserIdWithUserAndSalon(ACTOR_USER_ID);

        stubInactiveOwnerMasterForReactivation();

        // Act — toggle back ON.
        transactionTemplate.execute(status ->
                masterService.createMasterForOwner(ownerUser(), activeOwnedSalon()));

        // Assert — the next read must reach the DB again, not serve the memoised 404.
        transactionTemplate.execute(status -> masterService.findMyMasterDetail(ACTOR_USER_ID));
        verify(masterRepository, times(2))
                .findActiveByUserIdWithUserAndSalon(ACTOR_USER_ID);
    }

    /**
     * The CREATE branch of the same method — reached by POST /salons/{salonId}/master when no row
     * exists at all. Unlike the two registration-time create paths this one is NOT provably a
     * no-op: it is driven by an existing, long-lived owner account whose userId can already hold a
     * cached miss from any earlier GET /masters/me.
     */
    @Test
    @DisplayName("createMasterForOwner CREATE evicts the cached NEGATIVE master-detail-by-user entry")
    void should_evictNegativeMasterDetailByUserEntry_when_createMasterForOwnerCreatesRow() {
        // Arrange — warm the negative entry, then present "no row exists at all".
        when(masterRepository.findActiveByUserIdWithUserAndSalon(ACTOR_USER_ID))
                .thenReturn(Optional.empty());
        transactionTemplate.execute(status -> masterService.findMyMasterDetail(ACTOR_USER_ID));
        verify(masterRepository, times(1)).findActiveByUserIdWithUserAndSalon(ACTOR_USER_ID);

        when(masterRepository.findByUserIdWithSalon(ACTOR_USER_ID)).thenReturn(Optional.empty());
        Master created = mock(Master.class);
        when(created.getId()).thenReturn(MASTER_ID);
        when(masterRepository.save(any(Master.class))).thenReturn(created);

        // Act
        transactionTemplate.execute(status ->
                masterService.createMasterForOwner(ownerUser(), activeOwnedSalon()));

        // Assert
        transactionTemplate.execute(status -> masterService.findMyMasterDetail(ACTOR_USER_ID));
        verify(masterRepository, times(2))
                .findActiveByUserIdWithUserAndSalon(ACTOR_USER_ID);
    }

    /**
     * Per-key discipline (§F-6): the create/reactivate evictions must not become a blanket
     * {@code clear()}. A cache-wide clear would make both tests above pass while throwing away
     * every other provider's entry on every owner toggle.
     */
    @Test
    @DisplayName("createMasterForOwner REACTIVATION evicts only that owner's entry, leaving other users' intact")
    void should_evictOnlyOwnerEntry_when_createMasterForOwnerReactivatesRow() {
        // Arrange
        Cache detailByUserCache = cacheManager.getCache("master-detail-by-user");
        assertThat(detailByUserCache).isNotNull();
        UUID otherUserId = UUID.randomUUID();
        detailByUserCache.put(ACTOR_USER_ID, "cached-owner-master-detail");
        detailByUserCache.put(otherUserId, "unrelated-master-detail");

        stubInactiveOwnerMasterForReactivation();

        // Act
        transactionTemplate.execute(status ->
                masterService.createMasterForOwner(ownerUser(), activeOwnedSalon()));

        // Assert
        assertThat(detailByUserCache.get(ACTOR_USER_ID))
                .as("the reactivated owner's entry must be gone after commit")
                .isNull();
        assertThat(detailByUserCache.get(otherUserId))
                .as("eviction is per-key — an unrelated provider's entry must survive (§F-6)")
                .isNotNull();
    }

    // ── user-profile eviction: the CROSS-AGGREGATE guard (audit-fix cycle 2) ──
    //
    // UserProfileResponse.hasMasterProfile is derived from an active `masters` row, so a write in
    // MasterService stales a cache owned by the `user` package with nothing in the type system to
    // say so. That coupling — not the users-side writes UserCacheEvictionIT already covers — is
    // the whole risk of caching GET /users/me. These three tests are that guard.

    @Test
    @DisplayName("deactivateOwnerMaster evicts the owner's user-profile entry (GET /users/me) after commit")
    void should_evictUserProfileCache_when_deactivateOwnerMasterCommits() {
        // Arrange
        Cache userProfileCache = cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE);
        assertThat(userProfileCache)
                .as("user-profile must be registered by the real CacheConfig")
                .isNotNull();
        UUID otherUserId = UUID.randomUUID();
        userProfileCache.put(ACTOR_USER_ID, "cached-profile-with-hasMasterProfile-true");
        userProfileCache.put(otherUserId, "unrelated-profile");

        stubOwnerMasterForDeactivation();

        // Act
        transactionTemplate.execute(status -> {
            masterService.deactivateOwnerMaster(ACTOR_USER_ID, SALON_ID);
            return null;
        });

        // Assert
        assertThat(userProfileCache.get(ACTOR_USER_ID))
                .as("toggling «Я також працюю як майстер» OFF flips hasMasterProfile to false; a "
                        + "surviving entry means GET /users/me keeps claiming the profile exists "
                        + "for the full 5-minute TTL")
                .isNull();
        assertThat(userProfileCache.get(otherUserId))
                .as("per-key eviction — an unrelated user's profile must survive (§F-6)")
                .isNotNull();
    }

    @Test
    @DisplayName("deactivateMaster evicts the master's user-profile entry (GET /users/me) after commit")
    void should_evictUserProfileCache_when_deactivateMasterCommits() {
        // Arrange — DELETE /masters/{masterId} reaches owner-master rows too, since
        // AuthorizationService#canManageMaster authorizes MasterType.SALON_OWNER.
        Cache userProfileCache = cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE);
        assertThat(userProfileCache).isNotNull();
        userProfileCache.put(ACTOR_USER_ID, "cached-profile-with-hasMasterProfile-true");

        stubMasterForStaffDeactivation();

        // Act
        transactionTemplate.execute(status -> {
            masterService.deactivateMaster(UUID.randomUUID(), MASTER_ID);
            return null;
        });

        // Assert
        assertThat(userProfileCache.get(ACTOR_USER_ID))
                .as("the second deactivation path must evict user-profile too, or the "
                        + "cross-aggregate contract holds on only one of the two paths")
                .isNull();
    }

    @Test
    @DisplayName("createMasterForOwner REACTIVATION evicts the owner's user-profile entry (GET /users/me)")
    void should_evictUserProfileCache_when_createMasterForOwnerReactivatesRow() {
        // Arrange — the inverse direction: hasMasterProfile flips false → true.
        Cache userProfileCache = cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE);
        assertThat(userProfileCache).isNotNull();
        userProfileCache.put(ACTOR_USER_ID, "cached-profile-with-hasMasterProfile-false");

        stubInactiveOwnerMasterForReactivation();

        // Act
        transactionTemplate.execute(status ->
                masterService.createMasterForOwner(ownerUser(), activeOwnedSalon()));

        // Assert
        assertThat(userProfileCache.get(ACTOR_USER_ID))
                .as("reactivation flips hasMasterProfile to true; a surviving entry means the app "
                        + "keeps hiding the master section the owner just enabled")
                .isNull();
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
    /**
     * Stubs the {@link MasterRepository#findByIdWithUserAndSalon} call that the STAFF path
     * {@code deactivateMaster} uses. The row is typed {@code SALON_OWNER} on purpose: that is
     * exactly the row {@code AuthorizationService#canManageMaster} lets an owner deactivate
     * through {@code DELETE /masters/&#123;masterId&#125;}, which is what makes this a second
     * deactivation path for the Phase 265 toggle rather than a staff-only concern.
     * {@code getUser().getId()} must resolve, since that is the cache key the service evicts by.
     */
    private void stubMasterForStaffDeactivation() {
        var salon = mock(com.beautica.salon.entity.Salon.class);
        when(salon.getId()).thenReturn(SALON_ID);

        var user = mock(com.beautica.user.User.class);
        when(user.getId()).thenReturn(ACTOR_USER_ID);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);
        when(master.getUser()).thenReturn(user);

        when(masterRepository.findByIdWithUserAndSalon(MASTER_ID)).thenReturn(Optional.of(master));
    }

    /**
     * An active salon owned by {@link #ACTOR_USER_ID}, as {@code SalonService.createSalon} and the
     * Phase 12.4 re-enable endpoint pass it: {@code createMasterForOwner} checks
     * {@code isActive()} and {@code getOwner().getId()} before touching the row.
     */
    private com.beautica.salon.entity.Salon activeOwnedSalon() {
        var owner = mock(com.beautica.user.User.class);
        when(owner.getId()).thenReturn(ACTOR_USER_ID);

        var salon = mock(com.beautica.salon.entity.Salon.class);
        when(salon.getId()).thenReturn(SALON_ID);
        when(salon.isActive()).thenReturn(true);
        when(salon.getOwner()).thenReturn(owner);
        return salon;
    }

    /** The acting owner — {@code createMasterForOwner} rejects any role but {@code SALON_OWNER}. */
    private com.beautica.user.User ownerUser() {
        var owner = mock(com.beautica.user.User.class);
        when(owner.getId()).thenReturn(ACTOR_USER_ID);
        when(owner.getRole()).thenReturn(com.beautica.auth.Role.SALON_OWNER);
        return owner;
    }

    /**
     * Stubs the lookup {@code createMasterForOwner} uses to decide create / return-existing /
     * REACTIVATE. The row is typed {@code SALON_OWNER}, pinned to {@link #SALON_ID} and
     * {@code isActive() == false}, which is the exact shape that drives the reactivation branch —
     * the branch cycle 1 documented as needing no {@code master-detail-by-user} evict and that
     * negative caching turned into the one that most needs it.
     */
    private void stubInactiveOwnerMasterForReactivation() {
        var salon = mock(com.beautica.salon.entity.Salon.class);
        when(salon.getId()).thenReturn(SALON_ID);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);
        when(master.isActive()).thenReturn(false);

        when(masterRepository.findByUserIdWithSalon(ACTOR_USER_ID)).thenReturn(Optional.of(master));
    }

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
