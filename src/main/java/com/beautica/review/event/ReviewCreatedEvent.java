package com.beautica.review.event;

import java.util.UUID;

/**
 * Published after a review is persisted (post-commit rating recalculation trigger).
 *
 * @param masterId     the reviewed master — always present. Key of the {@code master-detail}
 *                     cache backing the public {@code GET /masters/{masterId}} profile.
 * @param masterUserId the {@code users.id} of the reviewed master — always present. Key of the
 *                     {@code master-detail-by-user} cache backing the master's OWN
 *                     {@code GET /masters/me} view (Phase 240 re-audit, Finding 1). Carried
 *                     separately because that cache is keyed by userId, not masterId
 *                     ({@code MasterService#getMyMasterDetail}, {@code key = "#userId"}), so the
 *                     masterId above cannot address it. Free to obtain at the publisher:
 *                     {@code BookingRepository#findByIdWithFullGraph} already {@code JOIN FETCH}es
 *                     {@code m.user}, so reading it costs no lazy load and no extra statement.
 * @param salonId      the reviewed master's salon at booking time, or {@code null} when the
 *                     master is an {@code INDEPENDENT_MASTER} with no salon. Drives the
 *                     symmetric salon-rating recalculation branch in
 *                     {@link ReviewEventListener#onReviewCreated}.
 */
public record ReviewCreatedEvent(UUID masterId, UUID masterUserId, UUID salonId) {}
