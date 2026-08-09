package com.beautica.notification.entity;

/**
 * Domain event types that may appear in the notification outbox.
 *
 * <p>Values must match the DB CHECK constraint {@code chk_outbox_event}
 * defined in {@code V32__create_notification_outbox.sql} and last widened in
 * {@code V109} (via {@code V94}) — keep this enum in lockstep with the current
 * CHECK, not just the V32 original.
 * Any divergence causes an {@link IllegalArgumentException} during Hibernate hydration.
 *
 * <p>Using a typed enum instead of a raw {@code String} prevents unknown event names
 * from reaching the outbox and makes exhaustive-switch analysis possible at compile time.
 */
public enum OutboxEventType {

    /** A new booking has been created and auto-confirmed (track 24.x — no approval step). */
    NEW_BOOKING,

    /** An existing booking changed status (e.g. CONFIRMED → COMPLETED). */
    STATUS_CHANGED,

    /** A client cancelled a confirmed booking. */
    CLIENT_CANCELLED,

    /** A salon owner or admin sent an invite to a new master or admin. */
    INVITE,

    /**
     * A client moved an existing CONFIRMED booking to a new time (Phase 19.2). Per the track
     * 24.x auto-confirm state machine the booking stays CONFIRMED at the new time — the
     * provider (master / salon-admin) is simply notified of the new time.
     */
    BOOKING_RESCHEDULED,

    /** A booking was completed; prompt the client to leave a rating + comment (Phase 18.1). */
    REVIEW_REQUESTED,

    /**
     * Nudges the PROVIDER (never the client) that an elapsed {@code CONFIRMED} booking is still
     * awaiting closure (Phase 29.5 — see {@code com.beautica.booking.domain.BookingClosureRule}).
     * This is a work-queue reminder, not a status transition: nothing that enqueues or drains this
     * event may ever write {@code bookings.status} — see {@code ClosureReminderArchitectureTest}.
     */
    CLOSURE_REMINDER
}
