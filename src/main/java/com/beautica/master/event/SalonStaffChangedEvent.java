package com.beautica.master.event;

import java.util.UUID;

/**
 * Published by {@code MasterService} whenever the set of masters attached to a salon changes —
 * a master joined (invite accept, owner-as-master creation), left (deactivation), was
 * reactivated, or was rotated between salons.
 *
 * <h3>Why this event exists (mobile Phase 111)</h3>
 * {@code ReviewRepository#recalculateSalonRating} was redefined as the equal-weighted mean of the
 * salon's <b>currently-active</b> masters' salon-scoped ratings. That makes the salon's headline
 * rating a function of the STAFF SET, not only of the review set — so it must be recomputed on a
 * staff change with no review involved. Before this event, nothing fired a salon recalc outside
 * {@code ReviewEventListener#onReviewCreated}, so a reviewed master leaving would leave their
 * scores in the salon's average indefinitely.
 *
 * <h3>Why an event rather than a direct call</h3>
 * {@code MasterService} must not reach into {@code ReviewRepository} — cross-feature access goes
 * through a seam, never repo-to-repo. Publishing lets {@code review} own its own recalculation
 * (see {@code com.beautica.review.event.SalonStaffRatingListener}) and keeps the staff write path
 * unaware that a rating exists at all. It also gets the AFTER_COMMIT ordering for free: the
 * listener must observe the committed {@code masters.salon_id}/{@code is_active} value, since
 * that is precisely what its aggregate filters on.
 *
 * @param salonId the salon whose staff set changed. Never {@code null} — publishers that may hold
 *                a {@code null} salon (an {@code INDEPENDENT_MASTER} has none) must skip
 *                publishing rather than emit a null-bearing event. A rotation publishes TWICE,
 *                once per salon, because both averages move.
 */
public record SalonStaffChangedEvent(UUID salonId) {

    public SalonStaffChangedEvent {
        if (salonId == null) {
            throw new IllegalArgumentException("salonId must not be null");
        }
    }
}
