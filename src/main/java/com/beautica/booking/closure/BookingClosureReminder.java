package com.beautica.booking.closure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps {@code booking_closure_reminders} (V132) — the dedupe ledger for the Phase 29.6
 * closure-reminder job. One row per booking, EVER; {@code booking_id} is the primary key, not a
 * surrogate id.
 *
 * <p>This entity exists to satisfy {@link org.springframework.data.jpa.repository.JpaRepository}'s
 * generic-type requirement for {@link ClosureReminderRepository}. The application never mutates
 * rows through JPA — every write happens inside {@link ClosureReminderRepository}'s single native
 * {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING} claim statement (mirrors {@code
 * NotificationOutboxRepository#claimPendingBatch}'s atomic-claim discipline), so this class has no
 * public setters and no factory method that produces a "half-built" row.
 */
@Entity
@Table(name = "booking_closure_reminders")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BookingClosureReminder {

    @Id
    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "reminder_count", nullable = false)
    private short reminderCount;

    @Column(name = "last_sent_at", nullable = false)
    private OffsetDateTime lastSentAt;
}
