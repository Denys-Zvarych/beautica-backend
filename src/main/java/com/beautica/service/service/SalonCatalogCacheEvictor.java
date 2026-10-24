package com.beautica.service.service;

import com.beautica.common.cache.SalonBookingInfoCacheEvictor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Dedicated single-key eviction for the {@code salon-service-catalog} cache backing
 * {@link ServiceCatalogService#getSalonServiceCatalog}, keyed by {@code salonId}.
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
 *
 * <p><b>Phase 377 — the public salon booking page rides along.</b> Every write that can flip the
 * salon's bookable-service set (a master's schedule, service assignments, definitions, master
 * (de)activation) can also flip its bookable-MASTER list, so {@code salon-booking-slug-info} is
 * evicted here too ({@link SalonBookingInfoCacheEvictor#evictSalon}, an off-thread value scan) instead
 * of wiring a parallel call into each writer.
 *
 * <p><b>Booking writes use {@link #evictCatalogOnly(UUID)}</b> (phase 377 audit, perf F2): a booking never
 * changes the page's {@code masters} roster (roster membership is structural — schedule + assigned service — not
 * occupancy), so the booking-package call sites evict the catalogue + catalogue snapshot only and never trigger
 * the booking-page value scan on the hot booking path. Schedule, service and master writers keep
 * {@link #evict(UUID)}.
 *
 * <p><b>Accepted staleness since phase 391</b> (phase 391 audit, perf LOW-1). The page now embeds
 * {@code serviceCoverage} and {@code serviceCategories}, whose free-slot verdict IS occupancy-derived: a booking
 * that consumes a master's last free slot flips that master (or a whole service) out of both. The booking path
 * still does NOT evict {@code salon-booking-slug-info} — adding the value scan to every booking commit would
 * put an O(cache) sweep on the hottest write path for a cosmetic gain — so a cached page can list such a
 * master/service for at most {@code CacheConfig.SALON_BOOKING_SLUG_INFO_TTL_SECONDS} (30 s). That is safe: the
 * page is only a menu; the next call the guest makes — availability / bookable-days for that master — is
 * uncached-by-page and authoritative (it reads the slot caches this same booking evicted), so it shows no free
 * time, and the booking POST re-checks the slot under the advisory lock. Pinned by
 * {@code SalonCatalogCacheEvictorOrderingTest}.
 */
@Component
public class SalonCatalogCacheEvictor {

    private final SalonBookingInfoCacheEvictor salonBookingInfoCacheEvictor;

    public SalonCatalogCacheEvictor(SalonBookingInfoCacheEvictor salonBookingInfoCacheEvictor) {
        this.salonBookingInfoCacheEvictor = salonBookingInfoCacheEvictor;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    // beforeInvocation = true (phase 384 audit): the CATALOGUE entry must be gone BEFORE the booking page is
    // evicted. With the default (after the body) the page went first, and a reader arriving in between rebuilt the
    // page from the STALE cached catalogue and re-cached it for its TTL. Phase 391 audit: the page now embeds
    // getSalonCatalogSnapshot (catalogue + serviceCoverage from one batch), evicted here with the catalogue, same key,
    // also before the page.
    @CacheEvict(value = {"salon-service-catalog", ServiceCatalogService.SALON_CATALOG_SNAPSHOT_CACHE},
            key = "#salonId", condition = "#salonId != null", beforeInvocation = true)
    public void evict(UUID salonId) {
        // @CacheEvict (before this body) performs the catalogue eviction. A null salonId is skipped by the
        // condition (independent masters own no salon catalogue entry) and is a no-op for the booking page too.
        salonBookingInfoCacheEvictor.evictSalon(salonId);
    }

    /**
     * The catalogue (+ catalogue snapshot) eviction ONLY — what {@link #evict(UUID)} did before phase 377. For
     * booking writers, which change the occupancy-derived catalogue verdict but never the public salon booking
     * page's roster; the cached page's embedded coverage may lag by ≤ 30 s (see the class javadoc — accepted,
     * the next availability call is authoritative). Same afterCommit contract and null-condition as {@link #evict}.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CacheEvict(value = {"salon-service-catalog", ServiceCatalogService.SALON_CATALOG_SNAPSHOT_CACHE},
            key = "#salonId", condition = "#salonId != null")
    public void evictCatalogOnly(UUID salonId) {
        // Intentionally empty: @CacheEvict performs the eviction. A null salonId is skipped
        // by the condition (independent masters own no salon catalogue entry).
    }
}
