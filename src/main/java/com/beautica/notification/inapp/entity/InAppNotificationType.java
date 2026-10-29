package com.beautica.notification.inapp.entity;

/**
 * The set of in-app feed event types (phase 332). Mirrors the DB CHECK constraint
 * {@code in_app_notification_type_chk} in {@code V181__create_in_app_notification.sql} (latest form: V221) exactly —
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
    INVITE_ACCEPTED,
    /** Phase 372 — requester-only; linked via {@code category_request_id}. */
    CATEGORY_REQUEST_APPROVED,
    CATEGORY_REQUEST_REJECTED,
    /** Phase 372 — requester-only; linked via {@code service_type_suggestion_id}. */
    SERVICE_TYPE_SUGGESTION_APPROVED,
    SERVICE_TYPE_SUGGESTION_REJECTED,
    /** Phase 415 — reporter: the reported review / client review / photo was removed. */
    REPORT_CONTENT_REMOVED,
    /** Phase 415 — reporter: dismissed or restored, no rule violation found. */
    REPORT_NO_VIOLATION,
    /** Phase 415 — reporter: neutral "we reviewed your report" (profile actioned, target already gone). */
    REPORT_REVIEWED,
    /** Phase 415 — author: their review / client review / photo was removed. */
    CONTENT_REMOVED,
    /**
     * Phase 415 follow-up — the subject of a removed review: the reviewed provider (independent master, or the salon's
     * owner + active admins) for a review, the reviewed client for a client review. No reporter, no review text.
     */
    REVIEW_ABOUT_YOU_REMOVED;

    /** Phase 415 — the moderation-outcome types; each carries {@code moderated_target_type} and no other referent. */
    public boolean isModerationOutcome() {
        return this == REPORT_CONTENT_REMOVED || this == REPORT_NO_VIOLATION
                || this == REPORT_REVIEWED || this == CONTENT_REMOVED || this == REVIEW_ABOUT_YOU_REMOVED;
    }
}
