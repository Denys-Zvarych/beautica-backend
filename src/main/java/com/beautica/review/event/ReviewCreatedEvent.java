package com.beautica.review.event;

import java.util.UUID;

/**
 * Published after a review is persisted (post-commit rating recalculation trigger).
 *
 * @param masterId the reviewed master — always present.
 * @param salonId  the reviewed master's salon at booking time, or {@code null} when the
 *                 master is an {@code INDEPENDENT_MASTER} with no salon. Drives the
 *                 symmetric salon-rating recalculation branch in
 *                 {@link ReviewEventListener#onReviewCreated}.
 */
public record ReviewCreatedEvent(UUID masterId, UUID salonId) {}
