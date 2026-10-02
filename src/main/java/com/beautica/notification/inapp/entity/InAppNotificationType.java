package com.beautica.notification.inapp.entity;

/**
 * The set of in-app feed event types (phase 332). Mirrors the DB CHECK constraint
 * {@code in_app_notification_type_chk} in {@code V181__create_in_app_notification.sql} exactly —
 * an unknown value is rejected at compile time here rather than reaching the DB and failing the
 * CHECK at insert time. Any addition to this enum MUST be added to that CHECK in the same PR.
 *
 * <p>Render text is never stored — the mobile client resolves each type to an ARB key and fills
 * display params at read time (phase 334) from live rows the reader is already allowed to see.
 *
 * <p>Recipient-matrix source: {@code docs/backend-phases/phase-333-inapp-notification-write-path.md}.
 */
public enum InAppNotificationType {
    BOOKING_CREATED,
    BOOKING_CANCELLED_BY_CLIENT,
    BOOKING_DECLINED,
    BOOKING_NOT_COMPLETED,
    BOOKING_RESCHEDULED,
    REVIEW_REQUESTED,
    BOOKING_CANCELLED_SALON_CLOSED,
    BOOKING_CANCELLED_MASTER_REMOVED,
    REVIEW_RECEIVED,
    INVITE_ACCEPTED
}
