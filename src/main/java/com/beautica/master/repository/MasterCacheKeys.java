package com.beautica.master.repository;

import java.util.UUID;

/**
 * The cache keys a master's profile DTOs are stored under — {@code master-detail} by
 * {@code masterId}, {@code master-detail-by-user} by {@code userId} and {@code booking-slug-info}
 * by {@code bookingSlug} — loaded as an id-only projection so an eviction sweep never hydrates
 * {@code Master}/{@code User} entities.
 *
 * @param masterId    the master row's id
 * @param userId      the linked user's id, or {@code null} for a detached master (phase 294)
 * @param bookingSlug the public booking slug ({@code BookingSlugService#findBySlug} key), or
 *                    {@code null} for a row without one (Phase 344 c1)
 */
public record MasterCacheKeys(UUID masterId, UUID userId, String bookingSlug) {

    /**
     * Slug-less keys — for callers that never evict {@code booking-slug-info}. Used by the JPQL
     * constructor expression of {@code MasterRepository#findCacheKeysBySalonId}, whose only caller
     * ({@code SalonService#updateSalon}) evicts by {@code masterId}/{@code userId} alone.
     */
    public MasterCacheKeys(UUID masterId, UUID userId) {
        this(masterId, userId, null);
    }
}
