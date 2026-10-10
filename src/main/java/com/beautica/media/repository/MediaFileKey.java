package com.beautica.media.repository;

import com.beautica.media.entity.EntityType;

import java.util.UUID;

/**
 * Scalar projection of one {@code media_files} row for the salon-deletion purge: the row id (for the batched
 * DB delete), the R2 key to delete, and the {@code (entityType, entityId)} pair the own-prefix check and the
 * portfolio-cache eviction need. No entity, no uploader proxy — safe to capture in an after-commit task that
 * runs on another thread after the persistence context has closed.
 */
public record MediaFileKey(UUID id, String r2Key, EntityType entityType, UUID entityId) {
}
