package com.beautica.review.event;

import java.util.UUID;

/**
 * Phase 396 D3 — a client&rarr;provider review changed {@code moderation_status} (auto-hide, restore, dismiss,
 * remove). Same recalculation + eviction contract as {@link ReviewCreatedEvent}, plus the per-review
 * {@code review-detail} entry. Ids only.
 *
 * @param reviewId     the moderated review — key of {@code review-detail}
 * @param masterId     the reviewed master
 * @param masterUserId the master's {@code users.id}; {@code null} for a detached master (eviction skipped)
 * @param salonId      the review's salon, {@code null} for an independent master
 */
public record ReviewModerationChangedEvent(UUID reviewId, UUID masterId, UUID masterUserId, UUID salonId) {}
