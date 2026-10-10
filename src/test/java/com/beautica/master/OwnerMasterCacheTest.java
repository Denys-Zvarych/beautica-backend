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
 * Phase 12.7 — unit-style cache test verifying that owner-master lifecycle writes evict the
 * user-keyed and calendar/slot caches after commit. Phase 346 removed
 * {@code deactivateOwnerMaster} and (audit-fix cycle 1) made {@link MasterService#deactivateMaster}
 * refuse a SALON_OWNER row with 409, so the ONLY remaining legal deactivation of an owner-master
 * row is the salon-deletion cascade {@link MasterService#deactivateMasters}; the deactivation
 * cases drive that path. The refused path is pinned too: a 409 must evict nothing.
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
@DisplayName("MasterService owner-master lifecycle — cache eviction after commit (Phase 12.7)")
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
    // MasterService ctor dependency (saved-settlement label parts). A mock is right here: these
    // tests observe the master caches, not the resolver's own cache, and its default
    // Optional.empty() answer simply yields null label parts.
    @MockBean com.beautica.location.SettlementDisplayNameResolver settlementDisplayNameResolver;
    // Phase 13.1: MasterService now constructor-depends on BookingSlugService.
    @MockBean com.beautica.booking.service.BookingSlugService bookingSlugService;
    // Phase 21.3: MasterService now constructor-depends on AuthorizationService (rotateMasterToSalon).
    @MockBean AuthorizationService authorizationService;
    // Phase 21.x (62ec609): MasterService now constructor-depends on SlotCalculationService
    // (bookable-master gating) and SalonCatalogCacheEvictor (salon-catalogue cache eviction).
    @MockBean com.beautica.booking.service.SlotCalculationService slotCalculationService;
    @MockBean com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    // MasterService ctor dependency: public-roster gate's :bookableToday (getMastersByPage).
    @MockBean com.beautica.master.service.ScheduleDateMath scheduleDateMath;

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
    @DisplayName("salon-deletion deactivateMasters on the owner-master row evicts the owner's master-by-user cache entry after commit")
    void should_evictMasterByUserCacheForOwner_when_ownerMasterDeactivationCommits() {
        // Arrange
        Cache masterByUserCache = cacheManager.getCache("master-by-user");
        assertThat(masterByUserCache).isNotNull();

        masterByUserCache.put(ACTOR_USER_ID, "cached-master-value");
        assertThat(masterByUserCache.get(ACTOR_USER_ID))
                .as("cache entry must be present before deactivation")
                .isNotNull();

        Master ownerMaster = stubOwnerMasterForSalonDeletion();

        // Act — wrap in transaction so afterCommit() fires synchronously
        transactionTemplate.execute(status -> {
            masterService.deactivateMasters(ACTOR_USER_ID, List.of(ownerMaster), SALON_ID);
            return null;
        });

        // Assert
        assertThat(masterByUserCache.get(ACTOR_USER_ID))
                .as("master-by-user cache entry for the owner must be evicted after deactivateMaster commits")
                .isNull();
    }

    @Test
    @DisplayName("salon-deletion deactivateMasters on the owner-master row evicts only the owner's entry, leaving other users' entries intact")
    void should_evictOnlyOwnerEntry_andLeaveOtherUserEntryIntact_when_ownerMasterDeactivationCommits() {
        // Arrange
        Cache masterByUserCache = cacheManager.getCache("master-by-user");
        assertThat(masterByUserCache).isNotNull();

        UUID otherUserId = UUID.randomUUID();
        masterByUserCache.put(ACTOR_USER_ID, "owner-master");
        masterByUserCache.put(otherUserId, "other-master");

        Master ownerMaster = stubOwnerMasterForSalonDeletion();

        // Act
        transactionTemplate.execute(status -> {
            masterService.deactivateMasters(ACTOR_USER_ID, List.of(ownerMaster), SALON_ID);
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

    /**
     * Phase 265 invariant: no stale GET /masters/me entry can survive a deactivation. Since Phase
     * 346 (audit-fix cycle 1) the salon-deletion cascade is the only path that still deactivates an
     * owner-master row, so this is where the invariant must hold.
     */
    @Test
    @DisplayName("salon-deletion deactivateMasters evicts the owner's master-detail-by-user entry (GET /masters/me) after commit")
    void should_evictMasterDetailByUserCache_when_deactivateMasterCommits() {
        // Arrange
        Cache detailByUserCache = cacheManager.getCache("master-detail-by-user");
        assertThat(detailByUserCache)
                .as("master-detail-by-user must be registered by the real CacheConfig")
                .isNotNull();

        UUID otherUserId = UUID.randomUUID();
        detailByUserCache.put(ACTOR_USER_ID, "cached-owner-master-detail");
        detailByUserCache.put(otherUserId, "unrelated-master-detail");

        Master ownerMaster = stubOwnerMasterForSalonDeletion();

        // Act — the TransactionTemplate commits synchronously, so afterCommit() runs inline.
        transactionTemplate.execute(status -> {
            masterService.deactivateMasters(ACTOR_USER_ID, List.of(ownerMaster), SALON_ID);
            return null;
        });

        // Assert
        assertThat(detailByUserCache.get(ACTOR_USER_ID))
                .as("the deactivated master's GET /masters/me entry must be gone after commit — "
                        + "otherwise an owner whose salon was deleted keeps being served their "
                        + "master profile for the 10-minute TTL")
                .isNull();
        assertThat(detailByUserCache.get(otherUserId))
                .as("eviction is per-key — an unrelated provider's entry must survive (§F-6)")
                .isNotNull();
    }

    // ── NEGATIVE CACHING + create/reactivate eviction (audit-fix cycle 2) ─────
    //
    // These four tests guard the invariant above MasterService#evictUserKeyedMasterCachesAfterCommit:
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
     * .empty()} — the deactivation evicted it, and any read in the interval re-cached the miss.
     * Without the evict, an owner whose row is reactivated (a new salon after a deleted one)
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
     * The CREATE branch of the same method — reached by SalonService.createSalon when no row
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
    @DisplayName("salon-deletion deactivateMasters evicts the owner's user-profile entry (GET /users/me) after commit")
    void should_evictUserProfileCache_when_deactivateMasterCommits() {
        // Arrange — salon deletion deactivates the owner-master row, flipping hasMasterProfile.
        Cache userProfileCache = cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE);
        assertThat(userProfileCache).isNotNull();
        userProfileCache.put(ACTOR_USER_ID, "cached-profile-with-hasMasterProfile-true");

        Master ownerMaster = stubOwnerMasterForSalonDeletion();

        // Act
        transactionTemplate.execute(status -> {
            masterService.deactivateMasters(ACTOR_USER_ID, List.of(ownerMaster), SALON_ID);
            return null;
        });

        // Assert
        assertThat(userProfileCache.get(ACTOR_USER_ID))
                .as("the salon-deletion deactivation must evict user-profile too, or GET /users/me "
                        + "keeps reporting hasMasterProfile=true for the TTL")
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
    @DisplayName("salon-deletion deactivateMasters on the owner-master row clears the master-calendar cache after commit")
    void should_clearMasterCalendarCache_when_ownerMasterDeactivationCommits() {
        // Arrange
        Cache calendarCache = cacheManager.getCache("master-calendar");
        assertThat(calendarCache).isNotNull();

        // Populated through the REAL @Cacheable proxy on MasterService#getMasterCalendar, so the key
        // under test is the one Spring computes rather than one this test invented. The previous
        // version seeded `new SimpleKey(...)`, which an explicit-`key` @Cacheable never produces — it
        // matched the equally-wrong production predicate and so kept a broken eviction green.
        Object calendarKey = populateRealCalendarEntry(MASTER_ID);

        Master ownerMaster = stubOwnerMasterForSalonDeletion();

        // Act — wrap in transaction so afterCommit() fires synchronously
        transactionTemplate.execute(status -> {
            masterService.deactivateMasters(ACTOR_USER_ID, List.of(ownerMaster), SALON_ID);
            return null;
        });

        // Assert
        assertThat(calendarCache.get(calendarKey))
                .as("master-calendar cache must be fully cleared after deactivateMaster commits")
                .isNull();
    }

    @Test
    @DisplayName("salon-deletion deactivateMasters on the owner-master row evicts both master-by-user and master-calendar caches in one commit")
    void should_evictBothCaches_when_ownerMasterDeactivationCommits() {
        // Arrange
        Cache masterByUserCache = cacheManager.getCache("master-by-user");
        Cache calendarCache = cacheManager.getCache("master-calendar");
        assertThat(masterByUserCache).isNotNull();
        assertThat(calendarCache).isNotNull();

        masterByUserCache.put(ACTOR_USER_ID, "cached-master");
        // Real proxy again — see should_clearMasterCalendarCache_when_ownerMasterDeactivationCommits.
        Object calendarKey = populateRealCalendarEntry(MASTER_ID);

        Master ownerMaster = stubOwnerMasterForSalonDeletion();

        // Act
        transactionTemplate.execute(status -> {
            masterService.deactivateMasters(ACTOR_USER_ID, List.of(ownerMaster), SALON_ID);
            return null;
        });

        // Assert — both caches must be in a clean state after a single deactivation
        assertThat(masterByUserCache.get(ACTOR_USER_ID))
                .as("master-by-user entry must be evicted after deactivateMaster")
                .isNull();
        assertThat(calendarCache.get(calendarKey))
                .as("master-calendar must be cleared after deactivateMaster")
                .isNull();
    }

    // ── available-slots eviction ──────────────────────────────────────────────

    @Test
    @DisplayName("salon-deletion deactivateMasters on the owner-master row sweeps the master's availability caches after commit")
    void should_evictMasterAvailabilityCachesAfterCommit_when_ownerMasterDeactivationCommits() {
        // Arrange — deactivateMasters delegates the available-slots sweep to
        // SlotCalculationService#evictMasterAvailabilityCaches (by master, §F-2), a @MockBean here,
        // so the wiring is asserted on the delegate; the sweep itself is SlotCalculationService's own
        // test subject. (The removed deactivateOwnerMaster swept the cache inline instead.)
        Master ownerMaster = stubOwnerMasterForSalonDeletion();

        // Act — wrap in transaction so afterCommit() fires synchronously
        transactionTemplate.execute(status -> {
            masterService.deactivateMasters(ACTOR_USER_ID, List.of(ownerMaster), SALON_ID);
            return null;
        });

        // Assert
        verify(slotCalculationService).evictMasterAvailabilityCaches(MASTER_ID);
    }

    // ── refused path: DELETE /masters/{masterId} on the owner row (Phase 346 audit-fix cycle 1) ──

    @Test
    @DisplayName("deactivateMaster on the owner-master row is refused (409) and evicts nothing")
    void should_throwConflictAndEvictNothing_when_deactivateMasterTargetsOwnerRow() {
        // Arrange
        Cache masterByUserCache = cacheManager.getCache("master-by-user");
        Cache detailByUserCache = cacheManager.getCache("master-detail-by-user");
        Cache userProfileCache = cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE);
        assertThat(masterByUserCache).isNotNull();
        assertThat(detailByUserCache).isNotNull();
        assertThat(userProfileCache).isNotNull();
        masterByUserCache.put(ACTOR_USER_ID, "cached-master");
        detailByUserCache.put(ACTOR_USER_ID, "cached-owner-master-detail");
        userProfileCache.put(ACTOR_USER_ID, "cached-profile");
        Master ownerMaster = stubOwnerMasterForSalonDeletion();

        // Act
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                transactionTemplate.execute(status -> {
                    masterService.deactivateMaster(ACTOR_USER_ID, MASTER_ID);
                    return null;
                }));

        // Assert
        assertThat(thrown)
                .isInstanceOf(com.beautica.common.exception.BusinessException.class)
                .hasMessage(MasterService.OWNER_MASTER_NOT_REMOVABLE);
        verify(ownerMaster, never()).setActive(false);
        assertThat(masterByUserCache.get(ACTOR_USER_ID)).as("nothing changed — nothing evicted").isNotNull();
        assertThat(detailByUserCache.get(ACTOR_USER_ID)).isNotNull();
        assertThat(userProfileCache.get(ACTOR_USER_ID)).isNotNull();
        verify(slotCalculationService, never()).evictMasterAvailabilityCaches(MASTER_ID);
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
     * The owner's own {@code SALON_OWNER} master row, JOIN-FETCHed with {@code user} exactly as
     * {@code SalonService}'s salon-deletion cascade hands it to {@link MasterService#deactivateMasters},
     * plus the two lookups that path's {@code assertCanManageSalonStaff} makes for an owner actor.
     * {@code getUser().getId()} must resolve, since that is the cache key the service evicts by.
     * Also stubs {@code findByIdWithUserAndSalon} so the refused single-master path can load it.
     */
    private Master stubOwnerMasterForSalonDeletion() {
        var user = mock(com.beautica.user.User.class);
        when(user.getId()).thenReturn(ACTOR_USER_ID);

        var salon = mock(com.beautica.salon.entity.Salon.class);
        when(salon.getId()).thenReturn(SALON_ID);
        // A SALON_OWNER-type master row's own user IS the salon's owner by construction.
        when(salon.getOwner()).thenReturn(user);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);
        when(master.getUser()).thenReturn(user);

        when(userRepository.findRoleById(ACTOR_USER_ID))
                .thenReturn(Optional.of(com.beautica.auth.Role.SALON_OWNER));
        when(salonRepository.existsByIdAndOwnerId(SALON_ID, ACTOR_USER_ID)).thenReturn(true);
        when(masterRepository.findByIdWithUserAndSalon(MASTER_ID)).thenReturn(Optional.of(master));
        return master;
    }

    /**
     * An active salon owned by {@link #ACTOR_USER_ID}, as {@code SalonService.createSalon} passes
     * it: {@code createMasterForOwner} checks
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
}
