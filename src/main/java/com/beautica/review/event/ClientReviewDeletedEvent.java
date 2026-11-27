package com.beautica.review.event;

import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * B570 (REV-45) — a provider deleted their own client rating. Fires the same after-commit recalculation of
 * {@code users.avg_rating}/{@code review_count} as {@link ClientReviewCreatedEvent}.
 *
 * @param clientId the reviewed registered client, or {@code null} for a phone-keyed guest rating (its readout is
 *                 computed on read, so nothing is recalculated)
 */
public record ClientReviewDeletedEvent(@Nullable UUID clientId) {}
