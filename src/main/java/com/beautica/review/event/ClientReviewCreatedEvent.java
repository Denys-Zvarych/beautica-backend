package com.beautica.review.event;

import java.util.UUID;

/**
 * Published after a {@code ClientReview} is persisted (post-commit rating recalculation trigger)
 * — the master&rarr;client mirror of {@link ReviewCreatedEvent} (Phase 27.5).
 *
 * @param clientId the reviewed client (the subject) — always present.
 */
public record ClientReviewCreatedEvent(UUID clientId) {}
