package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.CacheConfig;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.location.repository.CityRepository;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

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
    }

    @MockBean SalonRepository salonRepository;
    @MockBean UserRepository userRepository;
    @MockBean InviteService inviteService;
    @MockBean MasterRepository masterRepository;
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
        var salon = Mockito.mock(com.beautica.salon.entity.Salon.class);

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(List.of());
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
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
        Salon salon = Mockito.mock(Salon.class);

        when(salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)).thenReturn(Optional.of(salon));
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        // No save() stub needed: managed entity flushes via dirty-checking (PERF-LOW redundant-write drop).

        // Populate salon-detail cache — through the real proxy, mirroring SalonController.
        salonService.getPublicSalon(salonId);

        // Evict via updateSalon
        UpdateSalonRequest updateRequest = new UpdateSalonRequest(
                "Updated Name", null, null, null, null,
                null, null, null, null, null, null, null);
        salonService.updateSalon(actorId, salonId, updateRequest);

        // Cache was evicted — repository must be queried again
        salonService.getPublicSalon(salonId);

        verify(salonRepository, times(2)).findByIdAndIsActiveTrueWithOwner(salonId);
    }

    @Test
    @DisplayName("updateSalon evicts ownerSalons so the next getOwnerSalons re-queries the repository")
    void should_evictOwnerSalonsCache_when_updateSalonCalled() {
        // updateSalon's @Caching evicts ownerSalons under key=#actorId — the actor IS the owner
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Salon salon = Mockito.mock(Salon.class);

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of());
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        // No save() stub needed: managed entity flushes via dirty-checking (PERF-LOW redundant-write drop).

        // Populate ownerSalons cache for this owner
        salonService.getOwnerSalons(actorId);

        // Evict via updateSalon — second @CacheEvict in @Caching group targets ownerSalons
        UpdateSalonRequest updateRequest = new UpdateSalonRequest(
                "Updated Name", null, null, null, null,
                null, null, null, null, null, null, null);
        salonService.updateSalon(actorId, salonId, updateRequest);

        // Cache was evicted — repository must be queried again
        salonService.getOwnerSalons(actorId);

        verify(salonRepository, times(2)).findAllByOwnerIdAndIsActiveTrue(actorId);
    }

    @Test
    @DisplayName("deactivateSalon evicts salon-detail so the next getPublicSalon re-queries the repository")
    void should_evictSalonDetailCache_when_deactivateSalonCalled() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User owner = new User("owner2@example.com", "hash", Role.SALON_OWNER, "Test", "Owner", "+380501234568");
        Salon salon = Mockito.mock(Salon.class);

        when(salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)).thenReturn(Optional.of(salon));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
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
        Salon salon = Mockito.mock(Salon.class);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
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
