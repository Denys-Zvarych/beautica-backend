package com.beautica.notification.inapp.dto;

/**
 * Where a tap on a {@link NotificationResponse} navigates — typed rather than a deep-link path
 * string (phase-103 proposed {@code deepLinkPath}; rejected, see phase-334 doc: it would couple
 * the backend to go_router paths and differ per role). Mobile maps {@code kind} to a route per
 * role (mobile phase 362).
 */
public enum TargetKind {
    /** Opens the booking (or, for a visit, the whole visit) detail screen. */
    BOOKING,
    /** Opens the booking detail with the leave-a-review CTA in view. */
    BOOKING_REVIEW,
    /** Opens the salon's team/roster screen. */
    SALON_TEAM,
    /** No navigable target — the referenced booking/appointment/salon is gone or no longer
     *  visible to this recipient. */
    NONE
}
