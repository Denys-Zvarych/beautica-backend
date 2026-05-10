package com.beautica.notification.entity;

/**
 * Valid states for a {@link NotificationOutboxEntry}.
 *
 * <p>State machine:
 * <pre>
 *   PENDING → PROCESSING → SENT
 *                       → DEAD  (max retries exceeded)
 * </pre>
 *
 * <p>Values must match the DB CHECK constraint {@code chk_outbox_status}
 * defined in {@code V32__create_notification_outbox.sql}.
 * Any divergence causes an {@link IllegalArgumentException} during Hibernate hydration.
 */
public enum OutboxStatus {

    /** Row is waiting to be picked up by the outbox drain scheduler. */
    PENDING,

    /**
     * Row has been claimed by a scheduler instance via {@code FOR UPDATE SKIP LOCKED}
     * and is being processed. Rows stuck in this state after a crash are detected
     * by the dead-letter sweep job.
     */
    PROCESSING,

    /** Notification was successfully delivered; row can be archived or deleted. */
    SENT,

    /**
     * All retry attempts have been exhausted. Manual intervention or a
     * separate DLQ handler is required.
     */
    DEAD
}
