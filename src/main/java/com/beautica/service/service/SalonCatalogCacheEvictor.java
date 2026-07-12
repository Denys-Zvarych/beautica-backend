package com.beautica.service.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Dedicated single-key eviction for the {@code salon-service-catalog} cache backing
 * {@link ServiceCatalogService#getSalonServiceCatalog} (keyed by {@code salonId}).
 *
 * <p><b>Why a separate bean.</b> The catalogue verdict is invalidated from write paths in
 * three different packages — schedule writes ({@code MasterScheduleService}), booking writes
 * ({@code BookingService} / {@code GuestBookingService} / {@code BookingCancellationService}),
 * and service-definition mutations ({@code ServiceCatalogService}). Routing every one of those
 * through {@code ServiceCatalogService}'s own {@code @CacheEvict} would create a dependency cycle
 * ({@code MasterScheduleService} ← {@code SlotCalculationService} ← {@code ServiceCatalogService}).
 * This bean has no collaborators, so any writer can depend on it without a cycle, and the
 * {@code @CacheEvict} still fires through the Spring proxy (callers inject and invoke it — never
 * self-invoke).
 *
 * <p><b>afterCommit contract.</b> Callers MUST invoke {@link #evict(UUID)} from a
 * {@code TransactionSynchronization.afterCommit()} callback (anti-bug §F rule 2) so a parallel
 * reader cannot repopulate the cache with a pre-commit snapshot. {@code NOT_SUPPORTED} keeps the
 * eviction out of any surrounding transaction (§F rule 2), and {@code condition = "#salonId != null"}
 * makes it a safe no-op for salon-less (INDEPENDENT_MASTER) writers.
 */
@Component
public class SalonCatalogCacheEvictor {

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CacheEvict(value = "salon-service-catalog", key = "#salonId", condition = "#salonId != null")
    public void evict(UUID salonId) {
        // Intentionally empty: @CacheEvict performs the eviction. A null salonId is skipped
        // by the condition (independent masters own no salon catalogue entry).
    }
}
