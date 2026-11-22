package com.beautica.booking.enums;

/**
 * Phase 527 — why a guest (LINK) or walk-in (STAFF) booking was attached to a CLIENT account by its verified phone.
 * Values must match the DB CHECK {@code chk_bookings_client_link} / {@code chk_appointments_client_link} (V260).
 */
public enum AttachReason {

    /** Attached at create: the booking phone already belonged to a verified CLIENT account. */
    BOOKING,

    /** Attached when the account verified the phone (registration, add phone, phone change). */
    PHONE_VERIFIED,

    /** Attached by the V261 backfill. */
    BACKFILL
}
