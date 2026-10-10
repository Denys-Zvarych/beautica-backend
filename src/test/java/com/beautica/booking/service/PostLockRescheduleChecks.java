package com.beautica.booking.service;

import com.beautica.booking.repository.PostLockRescheduleCheck;

/** Test fixtures for the {@link PostLockRescheduleCheck} projection the reschedule guard reads. */
public final class PostLockRescheduleChecks {

    private PostLockRescheduleChecks() {
    }

    /** Still CONFIRMED, window free. */
    public static PostLockRescheduleCheck free() {
        return of(true, false);
    }

    /** Still CONFIRMED, window collides. */
    public static PostLockRescheduleCheck overlap() {
        return of(true, true);
    }

    /** No longer CONFIRMED (declined/cancelled while queued on the master lock). */
    public static PostLockRescheduleCheck stale() {
        return of(false, false);
    }

    private static PostLockRescheduleCheck of(boolean stillConfirmed, boolean overlapExists) {
        return new PostLockRescheduleCheck() {
            @Override public Boolean getStillConfirmed() { return stillConfirmed; }
            @Override public Boolean getOverlapExists() { return overlapExists; }
        };
    }
}
