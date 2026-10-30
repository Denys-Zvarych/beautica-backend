package com.beautica.booking.repository;

/**
 * Phase 431 — both halves of one phone's SMS activity in the budget window, read in ONE statement
 * ({@link BookingRepository#countPhoneSmsBudgetUsedSince}), read only by the reschedule SMS budget, which
 * spends {@link #total()}. The walk-in create never reads this: its 429 uses
 * {@code BookingRepository#countStaffWalkInVisitsForPhoneSince} alone (pre-431 behaviour — another
 * provider's reschedule SMS must never block a walk-in).
 */
public interface PhoneSmsBudgetUsage {

    /** STAFF walk-in VISITS for this phone in the window — the pre-431 walk-in budget denominator. */
    long getWalkInVisits();

    /** Guest reschedule SMS sent to this phone in the window. */
    long getRescheduleSends();

    /** Walk-in confirmations plus reschedule SMS — the shared reschedule-SMS budget. */
    default long total() {
        return getWalkInVisits() + getRescheduleSends();
    }
}
