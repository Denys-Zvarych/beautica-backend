package com.beautica.media.repository;

import com.beautica.media.entity.EntityType;

import java.util.UUID;

/**
 * Scalar projection of one {@code media_files} row for an account-blob purge (backend-perf P-L1): the R2 key
 * to delete plus the {@code (entityType, entityId)} pair the portfolio-cache eviction needs. No entity, no
 * uploader fetch — safe to capture in an after-commit closure.
 */
public record UploaderMediaKey(UUID uploaderId, String r2Key, EntityType entityType, UUID entityId) {
}
