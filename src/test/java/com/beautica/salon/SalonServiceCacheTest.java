package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.CacheConfig;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.location.repository.CityRepository;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.audit.StaffClientReferenceAuditResult;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {SalonService.class, CacheConfig.class, SalonServiceCacheTest.TxConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("SalonService — @Cacheable/@CacheEvict behaviour")
class SalonServiceCacheTest {

    /**
     * Minimal no-op {@link PlatformTransactionManager} that activates Spring's
     * {@link org.springframework.transaction.support.TransactionSynchronizationManager}
     * during {@code @Transactional} method execution.
     *
     * <p>Without a transaction manager in the minimal {@code @SpringBootTest(classes = ...)}
     * context, {@code TransactionSynchronizationManager.isSynchronizationActive()} always
     * returns {@code false} and the {@code afterCommit} eviction hooks in
     * {@link SalonService} are never registered (anti-bug playbook §F rule 2).
     * This bean enables synchronization so that cache-eviction tests observe the
     * real production eviction path without standing up a full datasource or
     * Testcontainers PostgreSQL.
     */
    @TestConfiguration
    @EnableTransactionManagement
    static class TxConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() throws TransactionException {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition)
                        throws TransactionException {}

                @Override
                protected void doCommit(DefaultTransactionStatus status)
                        throws TransactionException {}

                @Override
                protected void doRollback(DefaultTransactionStatus status)
                        throws TransactionException {}
            };
        }

        // Phase 23.1: SalonService now constructor-depends on Clock (§G — no bare
        // Instant.now()/LocalDate.now()). This slice does not exercise the pending-invites
        // paths that read it, so a plain system clock satisfies the wiring only.
        @Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }

    @MockBean SalonRepository salonRepository;
    @MockBean UserRepository userRepository;
    @MockBean InviteService inviteService;
    @MockBean MasterRepository masterRepository;
    // Phase 21.5: SalonService now constructor-depends on MasterServiceRepository
    // (getSalonStaff's batch service-count query). This slice does not exercise that path,
    // so a mock satisfies the wiring only.
    @MockBean com.beautica.service.repository.MasterServiceRepository masterServiceRepository;
    @MockBean LocalityWriteValidator localityWriteValidator;
    @MockBean MasterService masterService;
    // Phase 21.3: SalonService now constructor-depends on AuthorizationService (rotateAdmin).
    // This slice does not exercise that path, so a mock satisfies the wiring.
    @MockBean AuthorizationService authorizationService;
    // oblastId surfacing on SalonResponse: SalonService now constructor-depends on
    // CityRepository (batch resolveOblastIdsByCityIds, used only by getOwnerSalons) and
    // LocationQueryService (single-row resolveOblastId — Phase 240 perf MEDIUM fix). This
    // slice's Salon mocks return a null cityId by default, so resolveOblastId short-circuits
    // and neither mock is exercised beyond satisfying Spring's bean graph.
    @MockBean CityRepository cityRepository;
    @MockBean com.beautica.location.SettlementDisplayNameResolver settlementDisplayNameResolver;
    @MockBean com.beautica.location.service.LocationQueryService locationQueryService;
    // Phase 23.1: SalonService now constructor-depends on InviteTokenRepository
    // (listSalonInvites/cancelInvite) and Clock (§G — no bare Instant.now()). This slice does
    // not exercise those paths, so a mock/fixed-clock bean satisfies the wiring only.
    @MockBean com.beautica.user.InviteTokenRepository inviteTokenRepository;
    // SalonService constructor-depends on UserProfileCacheEvictor: createSalon (owner locality
    // sync + auto-created owner-master row), removeAdmin and rotateAdmin all write a `users` row
    // and must stale the user-profile cache. This slice asserts the ownerSalons/salon-detail
    // caches only, so a mock satisfies the bean graph without changing any assertion below.
    // WITHOUT this bean the whole context fails to load with "No qualifying bean of type
    // UserProfileCacheEvictor ... constructor parameter 13", taking all 8 tests red.
    @MockBean com.beautica.common.cache.UserProfileCacheEvictor userProfileCacheEvictor;
    // Phase 290: SalonService now constructor-depends on the salon-deletion staff-deactivation
    // cascade's five collaborators. WITHOUT these the whole context fails to load with
    // "No qualifying bean of type ..." — the same failure mode userProfileCacheEvictor's own
    // comment above documents — taking every test in this file red, not just the three that
    // exercise deactivateSalon.
    @MockBean com.beautica.salon.service.StaffClientReferenceAuditService staffClientReferenceAuditService;
    @MockBean com.beautica.auth.TokensValidAfterCache tokensValidAfterCache;
    // Phase 301: the staff hard-delete cascade that phase 290 inlined in SalonService was
    // promoted verbatim to StaffAccountDisposalService so salon deletion, owner-initiated staff
    // removal and staff SELF-DELETE share one implementation. SalonService now constructor-
    // depends on it (parameter 22). WITHOUT this bean the whole context fails to load with
    // "No qualifying bean of type StaffAccountDisposalService" — the same failure mode the
    // phase 290/293 comments above document — taking all 8 tests red, not just the deletion ones.
    @MockBean com.beautica.salon.service.StaffAccountDisposalService staffAccountDisposalService;
    // Phase 293: SalonService now constructor-depends on BookingService for the salon-closure
    // booking cascade (deactivateSalon calls declineFutureConfirmedBookingsForSalonClosure).
    // WITHOUT this bean the whole context fails to load with "required a bean of type
    // BookingService" — the same failure mode userProfileCacheEvictor's comment above documents —
    // taking every test in this file red, not just the ones that exercise deactivateSalon.
    // Phase 297/298: this same BookingService mock also backs removeMaster's future-confirmed-
    // booking cascade (declineFutureConfirmedBookingsForMasterRemoval). Phase 297's dedicated
    // BookingRepository bean/field for a since-superseded 409-only guard was removed as dead code
    // by Phase 298, so no separate mock is needed here any more.
    @MockBean com.beautica.booking.service.BookingService bookingService;
    // Phase 268: SalonService now constructor-depends on the salon-deletion
    // catalogue/favourites/media cascade's five collaborators. WITHOUT these the whole context
    // fails to load with "No qualifying bean of type ..." — the same failure mode documented
    // above for the phase 290/293 additions. transactionManager itself is NOT mocked here — the
    // real synchronization-enabling bean from TxConfig above satisfies that constructor slot, so
    // purgeSalonMediaAfterCommit's afterCommit registration is exercised for real.
    @MockBean com.beautica.service.repository.ServiceRepository serviceRepository;
    @MockBean com.beautica.favorite.repository.FavoriteRepository favoriteRepository;
    @MockBean com.beautica.media.repository.MediaRepository mediaRepository;
    @MockBean com.beautica.media.service.MediaService mediaService;
    // Commit ac0a19e: SalonService now constructor-depends on MasterScheduleService (parameter 8)
    // and ScheduleDateMath (parameter 9) for the salon-staff schedule read-through. WITHOUT BOTH
    // the whole context fails to load with "No qualifying bean of type ..." — and supplying only
    // the first just moves the failure to parameter 9 — taking all 8 tests red. The sibling slice
    // SalonServiceInviteHistoryTest was updated in that commit; this one was missed.
    // Mocks, not real beans: the 8 tests below assert @Cacheable/@CacheEvict on the salon-read
    // path only, so neither collaborator is reached, and a real ScheduleDateMath would drag in
    // the Clock this slice already fakes in TxConfig above.
    @MockBean com.beautica.master.service.MasterScheduleService masterScheduleService;
    @MockBean com.beautica.master.service.ScheduleDateMath scheduleDateMath;
    @Autowired SalonService salonService;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("ownerSalons").clear();
        cacheManager.getCache("salon-detail").clear();
        // Isolate search:salons:browse from other tests — deactivateSalon clears the whole cache,
        // so a leftover entry from a prior test would give a false-positive eviction assertion.
        cacheManager.getCache("search:salons:browse").clear();
    }

    /**
     * A mock {@link Salon} whose {@code owner} resolves to {@code ownerId}.
     *
     * <p>Every {@code ownerSalons} eviction now keys on {@code SalonService#ownerIdOf(salon)}
     * (Phase 283) rather than on the actor parameter, so a bare {@code Mockito.mock(Salon.class)}
     * — which answers {@code null} for {@code getOwner()} — no longer models any row this service
     * can load: {@code salons.owner_id} is {@code NOT NULL REFERENCES users(id)} (V3). Extracted
     * the moment a second test needed the same three stub lines (§M-3).
     *
     * <p>{@code isActive()} is stubbed {@code true} (Phase 290): {@code deactivateSalon}'s new
     * idempotency guard returns early — before touching any cache — for an already-inactive
     * salon, and a bare {@code Mockito.mock(Salon.class)} otherwise answers the primitive
     * {@code boolean} default ({@code false}), which would make every {@code deactivateSalon}
     * test below a silent no-op.
     */
    private static Salon salonOwnedBy(UUID ownerId) {
        Salon salon = Mockito.mock(Salon.class);
        User owner = Mockito.mock(User.class);
        when(owner.getId()).thenReturn(ownerId);
        when(salon.getOwner()).thenReturn(owner);
        when(salon.isActive()).thenReturn(true);
        return salon;
    }

    /**
     * Wires the Phase 290 staff-deactivation cascade to a clean no-op for {@code salonId}: audit
     * CLEAN, no active masters, no staff user ids. Every {@code deactivateSalon} test in this file
     * asserts CACHE behaviour, not the cascade itself (that is
     * {@code SalonStaffDeactivationCascadeIT}'s job) — without these stubs, the new fail-closed
     * audit call and the masters/staff loops would NPE on Mockito's default {@code null} return
     * for the unstubbed {@link StaffClientReferenceAuditResult} and {@link Page} types.
     */
    private void stubCleanEmptyStaffCascade(UUID salonId) {
        when(staffClientReferenceAuditService.runAuditForSalon(salonId))
                .thenReturn(StaffClientReferenceAuditResult.of(List.of(), Instant.now()));
        when(masterRepository.findBySalonIdAndIsActiveTrueWithUser(salonId, Pageable.unpaged()))
                .thenReturn(Page.empty());
        when(staffClientReferenceAuditService.resolveSalonStaffUserIds(salonId)).thenReturn(List.of());
    }

    private static UpdateSalonRequest renameTo(String name) {
        return new UpdateSalonRequest(name, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("second call to getOwnerSalons returns cached result without hitting repository")
    void should_notHitRepository_when_getOwnerSalonsCalledTwice() {
        UUID ownerId = UUID.randomUUID();
        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(List.of());

        salonService.getOwnerSalons(ownerId);
        salonService.getOwnerSalons(ownerId);

        verify(salonRepository, times(1)).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }

    @Test
    @DisplayName("deactivateSalon evicts the cache so the next getOwnerSalons call re-queries the repository")
    void should_evictCache_when_deactivateSalonCalled() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User owner = new User("owner@example.com", "hash", Role.SALON_OWNER, "Test", "Owner", "+380501234567");
        Salon salon = salonOwnedBy(ownerId);

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(List.of());
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        stubCleanEmptyStaffCascade(salonId);
        // No save() stub needed: managed entity flushes via dirty-checking (PERF-LOW redundant-write drop).

        // Populate cache
        salonService.getOwnerSalons(ownerId);
        // Evict cache via deactivateSalon
        salonService.deactivateSalon(ownerId, salonId);
        // Cache was evicted — repository must be queried again
        salonService.getOwnerSalons(ownerId);

        verify(salonRepository, times(2)).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }

    // ── salon-detail cache tests ───────────────────────────────────────────────

    /**
     * Phase 240 CRITICAL fix — mandatory proof for the Finding 1 self-invocation bug.
     *
     * <p>Before the fix, {@code @Cacheable} sat on {@code getSalonEntity}, and
     * {@code getPublicSalon} called it as a plain in-class {@code this.getSalonEntity(...)}. That
     * self-invocation never crosses the CGLIB proxy under this codebase's proxy-based
     * {@code @EnableCaching} (no AspectJ mode), so the cache never fired on the ONLY path real
     * traffic takes — {@code GET /salons/{salonId}} via {@link SalonService#getPublicSalon}.
     *
     * <p>This test calls {@code salonService.getPublicSalon(salonId)} — through the REAL Spring
     * proxy this {@code @SpringBootTest(classes = ...)} context builds, exactly like
     * {@code SalonController} does — never {@code getSalonEntity} directly (that call shape is
     * exactly what let the bug hide behind a passing test suite before this fix: see
     * {@code SalonService#getSalonEntity}'s Javadoc). Confirmed RED against the pre-fix code
     * (see the PR description / commit history for the reverted-fix run) — before the fix, each
     * call re-executed {@code findByIdAndIsActiveTrueWithOwner}, so the repository was hit TWICE
     * and this assertion failed with "Wanted 1 time but was 2 times."
     */
    @Test
    @DisplayName("Phase 240 CRITICAL — second call to getPublicSalon (through the real proxy) is served from cache, not a self-invoked getSalonEntity")
    void should_notHitRepository_when_getPublicSalonCalledTwiceThroughTheProxy() {
        UUID salonId = UUID.randomUUID();
        Salon salon = Mockito.mock(Salon.class);
        when(salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)).thenReturn(Optional.of(salon));

        salonService.getPublicSalon(salonId);
        salonService.getPublicSalon(salonId);

        verify(salonRepository, times(1)).findByIdAndIsActiveTrueWithOwner(salonId);
    }

    @Test
    @DisplayName("updateSalon evicts salon-detail so the next getPublicSalon re-queries the repository")
    void should_evictSalonDetailCache_when_updateSalonCalled() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Salon salon = salonOwnedBy(UUID.randomUUID());

        when(salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)).thenReturn(Optional.of(salon));
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        // No save() stub needed: managed entity flushes via dirty-checking (PERF-LOW redundant-write drop).

        // Populate salon-detail cache — through the real proxy, mirroring SalonController.
        salonService.getPublicSalon(salonId);

        // Evict via updateSalon
        salonService.updateSalon(actorId, salonId, renameTo("Updated Name"));

        // Cache was evicted — repository must be queried again
        salonService.getPublicSalon(salonId);

        verify(salonRepository, times(2)).findByIdAndIsActiveTrueWithOwner(salonId);
    }

    @Test
    @DisplayName("updateSalon by the OWNER evicts ownerSalons so the next getOwnerSalons re-queries the repository")
    void should_evictOwnerSalonsCache_when_updateSalonCalled() {
        // The owner-is-the-actor case. Key derivation is still ownerIdOf(salon), not #actorId —
        // the two merely coincide here; should_...adminUpdatesSalon covers the case where they do not.
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Salon salon = salonOwnedBy(ownerId);

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(List.of());
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        // No save() stub needed: managed entity flushes via dirty-checking (PERF-LOW redundant-write drop).

        // Populate ownerSalons cache for this owner
        salonService.getOwnerSalons(ownerId);

        salonService.updateSalon(ownerId, salonId, renameTo("Updated Name"));

        // Cache was evicted — repository must be queried again
        salonService.getOwnerSalons(ownerId);

        verify(salonRepository, times(2)).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }

    /**
     * Phase 283 regression pin — the defect this whole fix exists for.
     *
     * <p>{@code getOwnerSalons} is {@code @Cacheable(value = "ownerSalons", key = "#ownerId")},
     * but {@code updateSalon} used to evict under its {@code actorId} parameter, on the assumption
     * (stated verbatim in this file's previous revision: <i>"the actor IS the owner"</i>) that only
     * the owner ever reaches it. That assumption is false: {@code updateSalon}'s gate is
     * {@code @PreAuthorize("... @authz.canManageSalon(...)")}, which also admits the
     * {@code SALON_ADMIN} assigned to the salon — see
     * {@code SalonServiceAdminTest#should_allowSalonAdminToUpdateSalonDetails}. An admin PATCH
     * therefore evicted a key nobody reads, and {@code GET /salons/mine} kept serving the owner the
     * pre-edit salon name for the full 5-minute {@code ownerSalons} TTL.
     *
     * <p>The actor id here is deliberately a THIRD, unrelated UUID, so the test can only pass if
     * the eviction key is derived from the salon row's owner and not from the caller.
     * Confirmed RED against the pre-fix line ({@code evictOwnerSalonsCacheAfterCommit(actorId)}):
     * the owner's entry survived, the second read was served from cache and the assertion failed
     * with "Wanted 2 times but was 1 time."
     */
    @Test
    @DisplayName("Phase 283 — a SALON_ADMIN's updateSalon evicts ownerSalons under the OWNER's key, not the actor's")
    void should_evictOwnerSalonsCacheUnderOwnerKey_when_adminUpdatesSalon() {
        UUID ownerId = UUID.randomUUID();
        UUID adminActorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Salon salon = salonOwnedBy(ownerId);

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(List.of());
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        // Populate the OWNER's ownerSalons entry — this is what GET /salons/mine reads.
        salonService.getOwnerSalons(ownerId);

        // The admin — not the owner — renames the salon.
        salonService.updateSalon(adminActorId, salonId, renameTo("Renamed By Admin"));

        salonService.getOwnerSalons(ownerId);

        verify(salonRepository, times(2)).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }

    @Test
    @DisplayName("deactivateSalon evicts salon-detail so the next getPublicSalon re-queries the repository")
    void should_evictSalonDetailCache_when_deactivateSalonCalled() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User owner = new User("owner2@example.com", "hash", Role.SALON_OWNER, "Test", "Owner", "+380501234568");
        Salon salon = salonOwnedBy(ownerId);

        when(salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)).thenReturn(Optional.of(salon));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        stubCleanEmptyStaffCascade(salonId);
        // No save() stub needed: managed entity flushes via dirty-checking (PERF-LOW redundant-write drop).

        // Populate salon-detail cache — through the real proxy, mirroring SalonController.
        salonService.getPublicSalon(salonId);

        // Evict cache via deactivateSalon
        salonService.deactivateSalon(ownerId, salonId);

        // Cache was evicted — repository must be queried again
        salonService.getPublicSalon(salonId);

        verify(salonRepository, times(2)).findByIdAndIsActiveTrueWithOwner(salonId);
    }

    @Test
    @DisplayName("deactivateSalon evicts search:salons:browse cache so a deactivated salon cannot be served from cache")
    void should_evictSearchSalonsCache_when_deactivateSalonCalled() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        // Arrange — wire mocks so deactivateSalon completes without errors
        User owner = new User("owner3@example.com", "hash", Role.SALON_OWNER, "Test", "Owner", "+380501234569");
        Salon salon = salonOwnedBy(ownerId);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        stubCleanEmptyStaffCascade(salonId);
        // No save() stub needed: managed entity flushes via dirty-checking (PERF-LOW redundant-write drop).

        // Seed the search:salons:browse cache with a sentinel entry so we can confirm it is cleared.
        // evictSearchSalonsCacheAfterCommit() calls cache.clear() — blanket eviction — so any
        // seeded key must return null after deactivateSalon returns.
        String sentinelKey = "q=nails&city=kyiv";
        Object sentinelValue = List.of("salon-result-stub");
        cacheManager.getCache("search:salons:browse").put(sentinelKey, sentinelValue);

        // Confirm the seed is present before the eviction under test
        assertThat(cacheManager.getCache("search:salons:browse").get(sentinelKey))
                .as("sentinel entry must be present in search:salons:browse before deactivateSalon")
                .isNotNull();

        // Act
        salonService.deactivateSalon(ownerId, salonId);

        // Assert — the entire search:salons:browse cache was cleared
        assertThat(cacheManager.getCache("search:salons:browse").get(sentinelKey))
                .as("search:salons:browse cache must be fully cleared after deactivateSalon (blanket eviction)")
                .isNull();
    }

    // ── PERF-LOW-2: a later salon create rewrites the owner's users.city/region/cityId ──────────
    // MasterDetailResponse reads those columns and is cached under masterId (master-detail) and
    // userId (master-detail-by-user); both must be evicted after commit or the owner's detail
    // keeps serving the previous city for the TTL.

    private User ownerForCreate(UUID ownerId) {
        User owner = new User("owner-" + ownerId + "@beautica.test", "hash", Role.SALON_OWNER, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(owner, "id", ownerId);
        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.save(org.mockito.ArgumentMatchers.any(Salon.class))).thenAnswer(inv -> {
            Salon saved = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "createdAt", Instant.now());
            return saved;
        });
        return owner;
    }

    private static com.beautica.salon.dto.CreateSalonRequest createRequest(UUID cityId) {
        return new com.beautica.salon.dto.CreateSalonRequest("Second Salon", null, null, null, null, null, null,
                cityId, null, "Shevchenka St", "5A", null);
    }

    @Test
    @DisplayName("createSalon (second salon) — leaves the owner's master-detail entries alone: a non-primary salon no longer rewrites the owner row")
    void should_notEvictOwnerMasterDetailCaches_when_secondSalonCreated() {
        // Replaces should_evictOwnerMasterDetailCaches_when_secondSalonCreatedWithCityId, which
        // pinned the old behaviour: a second salon used to overwrite the owner's users.city/…,
        // so the owner-master's cached detail had to go. Only the first (primary) salon syncs now.
        UUID ownerId = UUID.randomUUID();
        UUID ownerMasterId = UUID.randomUUID();
        ownerForCreate(ownerId);
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        cacheManager.getCache("master-detail").put(ownerMasterId, "owner-detail");
        cacheManager.getCache("master-detail-by-user").put(ownerId, "owner-me");

        salonService.createSalon(ownerId, createRequest(UUID.randomUUID()));

        verify(masterRepository, Mockito.never()).findIdByUserId(ownerId);
        assertThat(cacheManager.getCache("master-detail").get(ownerMasterId)).isNotNull();
        assertThat(cacheManager.getCache("master-detail-by-user").get(ownerId)).isNotNull();
    }

    @Test
    @DisplayName("createSalon (first salon) — does not look up an owner master that does not exist yet")
    void should_notLookUpOwnerMaster_when_firstSalonCreated() {
        UUID ownerId = UUID.randomUUID();
        ownerForCreate(ownerId);
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(false);

        salonService.createSalon(ownerId, createRequest(UUID.randomUUID()));

        verify(masterRepository, Mockito.never()).findIdByUserId(ownerId);
    }

    // ── updateSalon cityId change → affiliated masters' detail entries (fix cycle 1, perf LOW) ──
    // Every affiliated master's MasterDetailResponse embeds a PublicSalonResponse whose city /
    // region / citySettlementType / cityHromadaNameUk follow the salon's cityId.

    private static UpdateSalonRequest moveTo(UUID cityId) {
        return new UpdateSalonRequest(null, null, null, null, null,
                cityId, null, "Shevchenka St", "5A", null, null, null);
    }

    @Test
    @DisplayName("updateSalon changing cityId evicts every affiliated master's master-detail + master-detail-by-user entry, per key, after commit")
    void should_evictAffiliatedMasterDetailCaches_when_updateSalonChangesCityId() {
        UUID salonId = UUID.randomUUID();
        Salon salon = salonOwnedBy(UUID.randomUUID());
        when(salon.getCityId()).thenReturn(UUID.randomUUID());
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        UUID masterA = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID detachedMaster = UUID.randomUUID();
        UUID bystanderMaster = UUID.randomUUID();
        UUID bystanderUser = UUID.randomUUID();
        when(masterRepository.findCacheKeysBySalonId(salonId)).thenReturn(List.of(
                new com.beautica.master.repository.MasterCacheKeys(masterA, userA),
                new com.beautica.master.repository.MasterCacheKeys(detachedMaster, null)));
        cacheManager.getCache("master-detail").put(masterA, "stale-a");
        cacheManager.getCache("master-detail").put(detachedMaster, "stale-detached");
        cacheManager.getCache("master-detail").put(bystanderMaster, "other-salon");
        cacheManager.getCache("master-detail-by-user").put(userA, "stale-me-a");
        cacheManager.getCache("master-detail-by-user").put(bystanderUser, "other-salon-me");

        salonService.updateSalon(UUID.randomUUID(), salonId, moveTo(UUID.randomUUID()));

        assertThat(cacheManager.getCache("master-detail").get(masterA)).isNull();
        assertThat(cacheManager.getCache("master-detail").get(detachedMaster))
                .as("a detached master (null userId) still has its masterId entry evicted").isNull();
        assertThat(cacheManager.getCache("master-detail-by-user").get(userA)).isNull();
        assertThat(cacheManager.getCache("master-detail").get(bystanderMaster))
                .as("per-key evict, never clear() (§F-6)").isNotNull();
        assertThat(cacheManager.getCache("master-detail-by-user").get(bystanderUser))
                .as("per-key evict, never clear() (§F-6)").isNotNull();
    }

    @Test
    @DisplayName("updateSalon resending the SAME cityId, or omitting it, never loads the roster's cache keys")
    void should_notLoadMasterCacheKeys_when_cityIdUnchangedOrOmitted() {
        UUID salonId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        Salon salon = salonOwnedBy(UUID.randomUUID());
        when(salon.getCityId()).thenReturn(cityId);
        // The salon already carries the street/building every moveTo(...) resends — so this is a
        // true resend, not an (incidental) street edit, which would now rightly sweep the roster.
        when(salon.getStreet()).thenReturn("Shevchenka St");
        when(salon.getBuildingNo()).thenReturn("5A");
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        // master-detail is keyed by MASTER id. The roster lookup is stubbed to name this master, so
        // the entry would be evicted if either call ever reached the roster — the isNotNull below
        // is then a real signal, not a key that no eviction could ever target.
        UUID affiliatedMaster = UUID.randomUUID();
        when(masterRepository.findCacheKeysBySalonId(salonId)).thenReturn(List.of(
                new com.beautica.master.repository.MasterCacheKeys(affiliatedMaster, UUID.randomUUID())));
        cacheManager.getCache("master-detail").put(affiliatedMaster, "untouched");

        salonService.updateSalon(UUID.randomUUID(), salonId, moveTo(cityId));
        salonService.updateSalon(UUID.randomUUID(), salonId, renameTo("Renamed"));

        verify(masterRepository, Mockito.never()).findCacheKeysBySalonId(salonId);
        assertThat(cacheManager.getCache("master-detail").get(affiliatedMaster))
                .as("an affiliated master's entry survives an unchanged/omitted locality").isNotNull();
    }

    @Test
    @DisplayName("updateSalon with a cityId resolves the settlement ONCE and reuses it for the response")
    void should_resolveSettlementOnce_when_updateSalonCarriesCityId() {
        UUID salonId = UUID.randomUUID();
        UUID newCityId = UUID.randomUUID();
        Salon salon = salonOwnedBy(UUID.randomUUID());
        when(salon.getCityId()).thenReturn(newCityId);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        salonService.updateSalon(UUID.randomUUID(), salonId, moveTo(newCityId));

        verify(settlementDisplayNameResolver, times(1)).resolve(newCityId);
    }

    // ── updateSalon cityId change → discovery caches (fix cycle 2, LOW-1) ─────────────────────
    // A salon's city is both a discovery filter key and the cityLabel on its search card — and,
    // via COALESCE(sal.city_id, u.city_id), the city of every one of its masters in master search.

    private static final List<String> DISCOVERY_CACHES = List.of(
            "search:salons:browse", "search:salons:q", "search:masters:browse", "search:masters:q");

    private void seedDiscoveryCaches(Object key) {
        DISCOVERY_CACHES.forEach(name -> cacheManager.getCache(name).put(key, "stale-" + name));
    }

    @Test
    @DisplayName("updateSalon changing cityId clears all four discovery caches (search:salons:* and search:masters:*) after commit")
    void should_clearSalonAndMasterSearchCaches_when_updateSalonChangesCityId() {
        UUID salonId = UUID.randomUUID();
        Salon salon = salonOwnedBy(UUID.randomUUID());
        when(salon.getCityId()).thenReturn(UUID.randomUUID());
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(masterRepository.findCacheKeysBySalonId(salonId)).thenReturn(List.of());
        Object sentinelKey = "city-change-sentinel-" + salonId;
        seedDiscoveryCaches(sentinelKey);

        salonService.updateSalon(UUID.randomUUID(), salonId, moveTo(UUID.randomUUID()));

        for (String name : DISCOVERY_CACHES) {
            assertThat(cacheManager.getCache(name).get(sentinelKey))
                    .as("%s must be cleared when the salon's city changes", name)
                    .isNull();
        }
    }

    @Test
    @DisplayName("updateSalon with an unchanged or omitted cityId leaves every discovery cache intact")
    void should_keepSearchCaches_when_cityIdUnchangedOrOmitted() {
        UUID salonId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        Salon salon = salonOwnedBy(UUID.randomUUID());
        when(salon.getCityId()).thenReturn(cityId);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        Object sentinelKey = "city-kept-sentinel-" + salonId;
        seedDiscoveryCaches(sentinelKey);

        salonService.updateSalon(UUID.randomUUID(), salonId, moveTo(cityId));
        salonService.updateSalon(UUID.randomUUID(), salonId, renameTo("Renamed"));

        for (String name : DISCOVERY_CACHES) {
            assertThat(cacheManager.getCache(name).get(sentinelKey))
                    .as("%s must survive a PATCH that does not move the salon", name)
                    .isNotNull();
        }
    }

    // ── district-only locality change (fix cycle 3, LOW-2) ──────────────────────────────────
    // The locality is a (cityId, districtId) pair: the embedded salon block and the discovery
    // district bucket / districtLabel move with the district even when the city stays put.

    private static UpdateSalonRequest moveTo(UUID cityId, UUID districtId) {
        return new UpdateSalonRequest(null, null, null, null, null,
                cityId, districtId, "Shevchenka St", "5A", null, null, null);
    }

    @Test
    @DisplayName("updateSalon changing ONLY districtId (same city) evicts affiliated master-detail keys and every discovery cache")
    void should_evictMasterDetailAndSearchCaches_when_onlyDistrictChanges() {
        UUID salonId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        Salon salon = salonOwnedBy(UUID.randomUUID());
        when(salon.getCityId()).thenReturn(cityId);
        when(salon.getDistrictId()).thenReturn(UUID.randomUUID());
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(masterRepository.findCacheKeysBySalonId(salonId)).thenReturn(List.of(
                new com.beautica.master.repository.MasterCacheKeys(masterId, userId)));
        cacheManager.getCache("master-detail").put(masterId, "stale-district");
        cacheManager.getCache("master-detail-by-user").put(userId, "stale-district-me");
        Object sentinelKey = "district-change-sentinel-" + salonId;
        seedDiscoveryCaches(sentinelKey);

        salonService.updateSalon(UUID.randomUUID(), salonId, moveTo(cityId, UUID.randomUUID()));

        assertThat(cacheManager.getCache("master-detail").get(masterId)).isNull();
        assertThat(cacheManager.getCache("master-detail-by-user").get(userId)).isNull();
        for (String name : DISCOVERY_CACHES) {
            assertThat(cacheManager.getCache(name).get(sentinelKey))
                    .as("%s must be cleared when only the salon's district changes", name)
                    .isNull();
        }
    }

    @Test
    @DisplayName("updateSalon resending the SAME city AND the SAME district evicts nothing")
    void should_evictNothing_when_sameCityAndSameDistrictResent() {
        UUID salonId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        Salon salon = salonOwnedBy(UUID.randomUUID());
        when(salon.getCityId()).thenReturn(cityId);
        when(salon.getDistrictId()).thenReturn(districtId);
        // The salon already carries the street/building every moveTo(...) resends — so this is a
        // true resend, not an (incidental) street edit, which would now rightly sweep the roster.
        when(salon.getStreet()).thenReturn("Shevchenka St");
        when(salon.getBuildingNo()).thenReturn("5A");
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        Object sentinelKey = "same-locality-sentinel-" + salonId;
        seedDiscoveryCaches(sentinelKey);

        salonService.updateSalon(UUID.randomUUID(), salonId, moveTo(cityId, districtId));

        verify(masterRepository, Mockito.never()).findCacheKeysBySalonId(salonId);
        for (String name : DISCOVERY_CACHES) {
            assertThat(cacheManager.getCache(name).get(sentinelKey))
                    .as("%s must survive an identical locality resend", name)
                    .isNotNull();
        }
    }

    // ── owner-address sync on a PRIMARY salon's street-only edit (extra pass, MEDIUM) ─────────

    @Test
    @DisplayName("updateSalon street-only change on the PRIMARY salon evicts the owner's user-profile and owner-master keys, but no discovery cache")
    void should_evictOwnerCachesButNotSearch_when_primarySalonStreetOnlyChanges() {
        UUID ownerId = UUID.randomUUID();
        UUID ownerMasterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        Salon salon = salonOwnedBy(ownerId);
        when(salon.isPrimary()).thenReturn(true);
        when(salon.getCityId()).thenReturn(cityId);
        when(salon.getStreet()).thenReturn("Old St");
        when(salon.getBuildingNo()).thenReturn("5A");
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        // The owner-master is affiliated with its primary salon, so the salon-wide sweep (now run
        // on ANY address change) is what evicts its keys — no separate owner-master path.
        when(masterRepository.findCacheKeysBySalonId(salonId)).thenReturn(List.of(
                new com.beautica.master.repository.MasterCacheKeys(ownerMasterId, ownerId)));
        cacheManager.getCache("master-detail").put(ownerMasterId, "stale-owner-street");
        cacheManager.getCache("master-detail-by-user").put(ownerId, "stale-owner-street-me");
        Object sentinelKey = "street-only-sentinel-" + salonId;
        seedDiscoveryCaches(sentinelKey);

        salonService.updateSalon(UUID.randomUUID(), salonId, new UpdateSalonRequest(
                null, null, null, null, null, cityId, null, "New St", "5A", null, null, null));

        verify(userProfileCacheEvictor).evictAfterCommit(ownerId);
        assertThat(cacheManager.getCache("master-detail").get(ownerMasterId)).isNull();
        assertThat(cacheManager.getCache("master-detail-by-user").get(ownerId)).isNull();
        for (String name : DISCOVERY_CACHES) {
            assertThat(cacheManager.getCache(name).get(sentinelKey))
                    .as("%s stays tied to a city/district change, not a street edit", name)
                    .isNotNull();
        }
    }

    // ── street / building / note edits → affiliated master-detail (last fix, pre-existing LOW) ──
    // Every affiliated master's cached detail embeds the salon's street/buildingNo/locationNote,
    // not only its locality. Run on a NON-primary salon so the owner-sync path is not involved.

    @Test
    @DisplayName("updateSalon changing ONLY the street of a NON-primary salon evicts every affiliated master's detail keys, but no discovery cache")
    void should_evictAffiliatedMasterDetail_when_salonStreetOnlyChanges() {
        UUID salonId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        Salon salon = salonOwnedBy(UUID.randomUUID());
        when(salon.isPrimary()).thenReturn(false);
        when(salon.getCityId()).thenReturn(cityId);
        when(salon.getStreet()).thenReturn("Old St");
        when(salon.getBuildingNo()).thenReturn("5A");
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(masterRepository.findCacheKeysBySalonId(salonId)).thenReturn(List.of(
                new com.beautica.master.repository.MasterCacheKeys(masterId, userId)));
        cacheManager.getCache("master-detail").put(masterId, "stale-street");
        cacheManager.getCache("master-detail-by-user").put(userId, "stale-street-me");
        Object sentinelKey = "salon-street-sentinel-" + salonId;
        seedDiscoveryCaches(sentinelKey);

        salonService.updateSalon(UUID.randomUUID(), salonId, new UpdateSalonRequest(
                null, null, null, null, null, cityId, null, "New St", "5A", null, null, null));

        assertThat(cacheManager.getCache("master-detail").get(masterId)).isNull();
        assertThat(cacheManager.getCache("master-detail-by-user").get(userId)).isNull();
        verify(userProfileCacheEvictor, Mockito.never()).evictAfterCommit(org.mockito.ArgumentMatchers.any());
        for (String name : DISCOVERY_CACHES) {
            assertThat(cacheManager.getCache(name).get(sentinelKey))
                    .as("%s does not carry the street — a street edit must not clear it", name)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("updateSalon re-sending an UNCHANGED full address (city, district, street, building, note) evicts no master-detail key")
    void should_evictNoMasterDetail_when_unchangedAddressResent() {
        UUID salonId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        Salon salon = salonOwnedBy(UUID.randomUUID());
        when(salon.getCityId()).thenReturn(cityId);
        when(salon.getDistrictId()).thenReturn(districtId);
        when(salon.getStreet()).thenReturn("Same St");
        when(salon.getBuildingNo()).thenReturn("5A");
        when(salon.getLocationNote()).thenReturn("same note");
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        salonService.updateSalon(UUID.randomUUID(), salonId, new UpdateSalonRequest(
                null, null, null, null, null, cityId, districtId, "Same St", "5A", "same note",
                null, null));

        verify(masterRepository, Mockito.never()).findCacheKeysBySalonId(salonId);
    }
}
