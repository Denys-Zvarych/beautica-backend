package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.config.CacheConfig;
import com.beautica.location.LocalityWriteValidator;
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

    @Autowired SalonService salonService;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("ownerSalons").clear();
        cacheManager.getCache("salon-detail").clear();
        // Isolate search:salons from other tests — deactivateSalon clears the whole cache,
        // so a leftover entry from a prior test would give a false-positive eviction assertion.
        cacheManager.getCache("search:salons").clear();
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
        when(salonRepository.save(salon)).thenReturn(salon);

        // Populate cache
        salonService.getOwnerSalons(ownerId);
        // Evict cache via deactivateSalon
        salonService.deactivateSalon(ownerId, salonId);
        // Cache was evicted — repository must be queried again
        salonService.getOwnerSalons(ownerId);

        verify(salonRepository, times(2)).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }

    // ── salon-detail cache tests ───────────────────────────────────────────────

    @Test
    @DisplayName("second call to getSalonEntity returns cached result without hitting repository")
    void should_notHitRepository_when_getSalonEntityCalledTwice() {
        UUID salonId = UUID.randomUUID();
        Salon salon = Mockito.mock(Salon.class);
        when(salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)).thenReturn(Optional.of(salon));

        salonService.getSalonEntity(salonId);
        salonService.getSalonEntity(salonId);

        verify(salonRepository, times(1)).findByIdAndIsActiveTrueWithOwner(salonId);
    }

    @Test
    @DisplayName("updateSalon evicts salon-detail so the next getSalonEntity re-queries the repository")
    void should_evictSalonDetailCache_when_updateSalonCalled() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Salon salon = Mockito.mock(Salon.class);

        when(salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)).thenReturn(Optional.of(salon));
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(salonRepository.save(salon)).thenReturn(salon);

        // Populate salon-detail cache
        salonService.getSalonEntity(salonId);

        // Evict via updateSalon
        UpdateSalonRequest updateRequest = new UpdateSalonRequest(
                "Updated Name", null, null, null, null,
                null, null, null, null, null, null, null);
        salonService.updateSalon(actorId, salonId, updateRequest);

        // Cache was evicted — repository must be queried again
        salonService.getSalonEntity(salonId);

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
        when(salonRepository.save(salon)).thenReturn(salon);

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
    @DisplayName("deactivateSalon evicts salon-detail so the next getSalonEntity re-queries the repository")
    void should_evictSalonDetailCache_when_deactivateSalonCalled() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User owner = new User("owner2@example.com", "hash", Role.SALON_OWNER, "Test", "Owner", "+380501234568");
        Salon salon = Mockito.mock(Salon.class);

        when(salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)).thenReturn(Optional.of(salon));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        when(salonRepository.save(salon)).thenReturn(salon);

        // Populate salon-detail cache
        salonService.getSalonEntity(salonId);

        // Evict cache via deactivateSalon
        salonService.deactivateSalon(ownerId, salonId);

        // Cache was evicted — repository must be queried again
        salonService.getSalonEntity(salonId);

        verify(salonRepository, times(2)).findByIdAndIsActiveTrueWithOwner(salonId);
    }

    @Test
    @DisplayName("deactivateSalon evicts search:salons cache so a deactivated salon cannot be served from cache")
    void should_evictSearchSalonsCache_when_deactivateSalonCalled() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        // Arrange — wire mocks so deactivateSalon completes without errors
        User owner = new User("owner3@example.com", "hash", Role.SALON_OWNER, "Test", "Owner", "+380501234569");
        Salon salon = Mockito.mock(Salon.class);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        when(salonRepository.save(salon)).thenReturn(salon);

        // Seed the search:salons cache with a sentinel entry so we can confirm it is cleared.
        // evictSearchSalonsCacheAfterCommit() calls cache.clear() — blanket eviction — so any
        // seeded key must return null after deactivateSalon returns.
        String sentinelKey = "q=nails&city=kyiv";
        Object sentinelValue = List.of("salon-result-stub");
        cacheManager.getCache("search:salons").put(sentinelKey, sentinelValue);

        // Confirm the seed is present before the eviction under test
        assertThat(cacheManager.getCache("search:salons").get(sentinelKey))
                .as("sentinel entry must be present in search:salons before deactivateSalon")
                .isNotNull();

        // Act
        salonService.deactivateSalon(ownerId, salonId);

        // Assert — the entire search:salons cache was cleared
        assertThat(cacheManager.getCache("search:salons").get(sentinelKey))
                .as("search:salons cache must be fully cleared after deactivateSalon (blanket eviction)")
                .isNull();
    }
}
