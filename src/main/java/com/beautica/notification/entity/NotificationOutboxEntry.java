package com.beautica.notification.entity;

import com.beautica.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Transactional outbox entry for reliable notification delivery.
 *
 * <p>A row is written atomically with the domain event it represents (e.g. a new booking
 * confirmation). A separate scheduler drains {@code PENDING} rows and advances their
 * {@link OutboxStatus}. This pattern guarantees at-least-once delivery without
 * two-phase commit between the application DB and the notification transport.
 *
 * <p>Schema defined in {@code V32__create_notification_outbox.sql}.
 */
@Entity
@Table(
    name = "notification_outbox",
    indexes = {
        // idx_outbox_pending is a partial index (WHERE status = 'PENDING') in V32 — JPA @Index
        // cannot express the partial predicate; see V32__create_notification_outbox.sql for full DDL.
        @Index(name = "idx_outbox_pending", columnList = "created_at"),
        // idx_outbox_status added by V33__add_outbox_status_index.sql
        @Index(name = "idx_outbox_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class NotificationOutboxEntry extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Identifies the type of domain event that triggered this notification.
     * Typed as {@link OutboxEventType} so that unknown values are rejected at
     * compile time rather than silently stored and later failing the DB CHECK
     * constraint {@code chk_outbox_event}.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private OutboxEventType eventType;

    /**
     * UUID of the domain aggregate that generated the event (e.g. a booking ID).
     * Nullable only for {@code INVITE} events — enforced by the DB CHECK constraint
     * {@code chk_outbox_booking_aggregate}.
     */
    @Column(name = "aggregate_id")
    private UUID aggregateId;

    /**
     * JSON payload carrying event-specific data. Required for {@code INVITE} events
     * (enforced by {@code chk_outbox_invite_payload}); nullable for all others.
     * Stored as {@code VARCHAR(4000)} — sufficient for all current event shapes.
     */
    @Size(max = 4000)
    @Column(length = 4000)
    private String payload;

    /**
     * Processing state of this entry. Defaults to {@link OutboxStatus#PENDING} so that
     * a builder caller that omits {@code status()} still produces a valid row.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    /**
     * Number of delivery attempts made so far. Initialised to {@code 0}; the scheduler
     * increments it before each attempt. Primitive — can never be {@code null}.
     */
    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    /**
     * Human-readable error message from the last failed delivery attempt.
     * Truncated to 500 characters to fit the column — callers should not rely on
     * the full exception message being preserved.
     */
    @Size(max = 500)
    @Column(name = "last_error", length = 500)
    private String lastError;
}
