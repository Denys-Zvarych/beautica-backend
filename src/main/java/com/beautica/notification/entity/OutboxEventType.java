package com.beautica.notification.entity;

/**
 * Domain event types that may appear in the notification outbox.
 *
 * <p>Values must match the DB CHECK constraint {@code chk_outbox_event}
 * defined in {@code V32__create_notification_outbox.sql}.
 * Any divergence causes an {@link IllegalArgumentException} during Hibernate hydration.
 *
 * <p>Using a typed enum instead of a raw {@code String} prevents unknown event names
 * from reaching the outbox and makes exhaustive-switch analysis possible at compile time.
 */
public enum OutboxEventType {

    /** A new booking has been created and is awaiting confirmation. */
    NEW_BOOKING,

    /** An existing booking changed status (e.g. CONFIRMED → COMPLETED). */
    STATUS_CHANGED,

    /** A client cancelled a confirmed booking. */
    CLIENT_CANCELLED,

    /** A salon owner or admin sent an invite to a new master or admin. */
    INVITE
}
