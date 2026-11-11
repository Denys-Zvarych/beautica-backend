package com.beautica.booking.repository;

/**
 * Phase 478 audit-fix cycle 1 — one phone's walk-in provider-cancellation budget state, read in ONE statement
 * ({@link BookingRepository#readWalkInCancellationBudgetSince}) under the per-phone advisory lock.
 */
public interface WalkInCancellationBudgetUsage {

    /** STAFF walk-in VISITS for this phone in the window. */
    long getWalkInVisits();

    /** Reservation rows (reschedule + charged cancellation SMS) for this phone in the window. */
    long getRescheduleSends();

    /** Another declined service of the same visit already reserved the visit's cancellation charge. */
    boolean isVisitCharged();

    /** Cancellation messages already released to this phone in the window (charged + free-ride lines). */
    long getCancellationMessages();

    /** The shared per-phone budget spent — same sum as {@link PhoneSmsBudgetUsage#total()}. */
    default long budgetUsed() {
        return getWalkInVisits() + getRescheduleSends();
    }
}
