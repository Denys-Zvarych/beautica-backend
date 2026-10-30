package com.beautica.review.event;

import java.util.Set;
import java.util.UUID;

/**
 * Phase 402 D1 — guest review author labels were anonymised to «Гість». Ratings are unchanged, so no recalculation:
 * the listener only evicts the cached public views that render the label (per-review detail, the providers' review
 * pages and public booking pages) after commit.
 *
 * @param reviewIds the anonymised reviews
 * @param masterIds their masters
 * @param salonIds  their salons (independent masters contribute none)
 */
public record GuestReviewsAnonymisedEvent(Set<UUID> reviewIds, Set<UUID> masterIds, Set<UUID> salonIds) {

    public GuestReviewsAnonymisedEvent {
        reviewIds = Set.copyOf(reviewIds);
        masterIds = Set.copyOf(masterIds);
        salonIds = Set.copyOf(salonIds);
    }
}
