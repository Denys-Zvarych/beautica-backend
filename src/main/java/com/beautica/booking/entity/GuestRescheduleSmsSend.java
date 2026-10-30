package com.beautica.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 431 — one guest reschedule SMS handed to the SMS pool (V223). Joined to
 * {@code bookings.guest_phone}, it feeds the per-phone budget shared with the walk-in confirmation
 * SMS — see {@code GuestRescheduleSmsBudget}. Holds no phone and no phone hash. Deleted with its
 * booking ({@code ON DELETE CASCADE}); rows older than the longest window are purged daily.
 *
 * <p>{@link Persistable}: the id is assigned in {@link #of} and there is no {@code @Version}, so without an
 * explicit {@code isNew} Spring Data's {@code save} would {@code merge} — one extra SELECT before every insert,
 * inside the per-phone lock transaction. The flag is set on a fresh instance and cleared on
 * {@code @PostPersist} / {@code @PostLoad} (same pattern as {@code ModerationActionToken}).
 */
@Entity
@Table(
        name = "guest_reschedule_sms_sends",
        // The unique outbox_entry_id index is PARTIAL in the DB (V223); JPA cannot express the WHERE clause.
        indexes = {
                @Index(name = "idx_guest_reschedule_sms_sends_booking", columnList = "booking_id"),
                @Index(name = "idx_guest_reschedule_sms_sends_sent", columnList = "sent_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuestRescheduleSmsSend implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "outbox_entry_id", updatable = false)
    private UUID outboxEntryId;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean newEntity;

    public static GuestRescheduleSmsSend of(UUID bookingId, UUID outboxEntryId, Instant now) {
        GuestRescheduleSmsSend send = new GuestRescheduleSmsSend();
        send.id = UUID.randomUUID();
        send.bookingId = Objects.requireNonNull(bookingId, "bookingId");
        send.outboxEntryId = outboxEntryId;
        send.sentAt = Objects.requireNonNull(now, "now");
        send.createdAt = now;
        send.newEntity = true;
        return send;
    }

    /** {@code true} only for a fresh, not yet persisted row: {@code save} then persists without a SELECT. */
    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newEntity = false;
    }
}
