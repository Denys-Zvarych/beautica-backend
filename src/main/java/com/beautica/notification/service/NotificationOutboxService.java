package com.beautica.notification.service;

import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.UUID;

@Service
public class NotificationOutboxService {

    // Implementations MUST only write to the outbox table (one INSERT). Do not call NotificationService here — the drain worker calls it outside the transaction.

    /**
     * Enqueues a "new booking" notification for Phase 5 implementation.
     *
     * <p><strong>Phase 5 contract:</strong>
     * <ul>
     *   <li>This method MUST join the caller's active transaction (default {@code REQUIRED}
     *       propagation). The outbox INSERT must commit atomically with the booking row.
     *   <li>Do NOT annotate with {@code @Async} — async dispatch would de-couple the write
     *       from the transaction boundary and risk sending a notification for a booking that
     *       was never actually persisted.
     *   <li>The drain worker (a separate scheduled task) reads the outbox and calls
     *       {@link NotificationService} outside of any booking transaction.
     * </ul>
     */
    public void enqueueNewBooking(UUID bookingId) { Objects.requireNonNull(bookingId, "bookingId"); }

    /**
     * Enqueues a "booking status changed" notification for Phase 5 implementation.
     *
     * <p><strong>Phase 5 contract:</strong>
     * <ul>
     *   <li>This method MUST join the caller's active transaction (default {@code REQUIRED}
     *       propagation). The outbox INSERT must commit atomically with the status update.
     *   <li>Do NOT annotate with {@code @Async} — async dispatch would de-couple the write
     *       from the transaction boundary and risk sending a notification for a transition
     *       that was rolled back.
     *   <li>The drain worker (a separate scheduled task) reads the outbox and calls
     *       {@link NotificationService} outside of any booking transaction.
     * </ul>
     */
    public void enqueueStatusChanged(UUID bookingId) { Objects.requireNonNull(bookingId, "bookingId"); }

    /**
     * Enqueues a "client cancelled booking" notification for Phase 5 implementation.
     *
     * <p><strong>Phase 5 contract:</strong>
     * <ul>
     *   <li>This method MUST join the caller's active transaction (default {@code REQUIRED}
     *       propagation). The outbox INSERT must commit atomically with the cancellation.
     *   <li>Do NOT annotate with {@code @Async} — async dispatch would de-couple the write
     *       from the transaction boundary and risk sending a notification for a cancellation
     *       that was rolled back.
     *   <li>The drain worker (a separate scheduled task) reads the outbox and calls
     *       {@link NotificationService} outside of any booking transaction.
     * </ul>
     */
    public void enqueueClientCancelled(UUID bookingId) { Objects.requireNonNull(bookingId, "bookingId"); }
}
