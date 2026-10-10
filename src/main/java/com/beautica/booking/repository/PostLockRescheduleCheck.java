package com.beautica.booking.repository;

/**
 * One-row native projection of the post-advisory-lock reschedule re-check: the freshness half
 * ({@code still_confirmed}) and the overlap half ({@code overlap_exists}) in a single statement —
 * the reschedule analogue of {@link PostLockSlotCheck}. Boxed {@link Boolean}s; the guard fails
 * closed on {@code null}.
 */
public interface PostLockRescheduleCheck {

    Boolean getStillConfirmed();

    Boolean getOverlapExists();
}
