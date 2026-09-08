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
}
