package com.beautica.master.repository;

import java.util.UUID;

/**
 * The two cache keys a master's detail DTO is stored under — {@code master-detail} by
 * {@code masterId} and {@code master-detail-by-user} by {@code userId} — loaded as an id-only
 * projection so an eviction sweep never hydrates {@code Master}/{@code User} entities.
 *
 * @param masterId the master row's id
 * @param userId   the linked user's id, or {@code null} for a detached master (phase 294)
 */
public record MasterCacheKeys(UUID masterId, UUID userId) {
}
