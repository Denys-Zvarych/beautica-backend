package com.beautica.review.event;

import java.util.UUID;

/**
 * Phase 396 D3 — a provider&rarr;client review changed {@code moderation_status} (support remove/restore). Fires
 * the same after-commit recalculation of {@code users.avg_rating}/{@code review_count} as
 * {@link ClientReviewCreatedEvent}.
 *
 * @param clientId the reviewed client (the aggregate's row)
 */
public record ClientReviewModerationChangedEvent(UUID clientId) {}
