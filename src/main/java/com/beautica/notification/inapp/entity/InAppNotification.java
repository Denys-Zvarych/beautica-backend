package com.beautica.notification.inapp.entity;

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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A single per-recipient in-app feed item — phase 332. Stores WHAT happened to WHICH
 * booking/visit, FOR WHOM, as ids + type + timestamps only. Never a name, phone, price, or booking
 * note (CLAUDE.md "Booking notes" rule); render text is produced by the mobile client from ARB keys
 * with display params resolved at read time (phase 334) from live rows the reader may see.
 *
 * <p>Deliberately does NOT extend {@code AuditableEntity} — this row has no {@code updated_at} /
 * {@code created_by}; once written it is only ever flipped {@code read_at} (via
 * {@link com.beautica.notification.inapp.repository.InAppNotificationRepository#markRead}/
 * {@code #markAllRead}, both bulk {@code UPDATE}s — never through this entity's own persistence
 * lifecycle) or deleted by the retention sweep (phase 335).
 *
 * <p>No JPA relations to {@code User}/{@code Booking}/{@code Salon}/{@code Appointment} — every
 * foreign column is a plain {@code UUID} id, matching the repository's derived-query method names
 * ({@code findByRecipientUserId...}, {@code countByRecipientUserIdAndReadAtIsNull}) and the "ids
 * only, resolved at read time" design: this layer never needs to hydrate the related aggregates.
 *
 * <p>Schema: {@code V181__create_in_app_notification.sql}.
 */
@Entity
@Table(
    name = "in_app_notification",
    indexes = {
        @Index(name = "in_app_notification_feed_idx",
                columnList = "recipient_user_id, created_at DESC, id DESC"),
        // in_app_notification_unread_idx, _subject_idx, _booking_idx, _appointment_idx and
        // _salon_idx are all partial indexes (WHERE <col> IS NOT NULL / IS NULL) — JPA @Index
        // cannot express a partial predicate, so they are documented here but not listed; see
        // V181__create_in_app_notification.sql for their definitions.
        @Index(name = "in_app_notification_created_idx", columnList = "created_at")
    }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class InAppNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The user this feed item is for. FK {@code users(id) ON DELETE CASCADE}. */
    @NotNull
    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    /**
     * The event type. Typed so an unknown value is rejected at compile time rather than reaching
     * the DB CHECK {@code in_app_notification_type_chk}.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private InAppNotificationType type;

    /**
     * The single-service booking this item is about, if any. FK
     * {@code bookings(id) ON DELETE CASCADE}, partially indexed by
     * {@code in_app_notification_booking_idx WHERE booking_id IS NOT NULL}.
     */
    @Column(name = "booking_id")
    private UUID bookingId;

    /**
     * The multi-service visit this item is about, if any — one row per recipient per visit event
     * (a 3-service cancel must not produce 3 bell items). FK
     * {@code appointments(id) ON DELETE CASCADE}, partially indexed by
     * {@code in_app_notification_appointment_idx WHERE appointment_id IS NOT NULL}.
     */
    @Column(name = "appointment_id")
    private UUID appointmentId;

    /**
     * The salon context, if any. FK {@code salons(id) ON DELETE SET NULL} — the item survives salon
     * deletion. Partially indexed by
     * {@code in_app_notification_salon_idx WHERE salon_id IS NOT NULL}.
     */
    @Column(name = "salon_id")
    private UUID salonId;

    /**
     * The new teammate for {@code INVITE_ACCEPTED}; null for every other type. FK
     * {@code users(id) ON DELETE SET NULL} — not CASCADE: this row's recipient is a DIFFERENT user
     * (the owner/admin who was notified), so the subject teammate self-deleting their own account
     * (phase 337/338) must not delete someone else's feed history. Indexed partially (
     * {@code in_app_notification_subject_idx WHERE subject_user_id IS NOT NULL}) since users are
     * hard-deleted in prod and this FK's action would otherwise seq-scan the table.
     */
    @Column(name = "subject_user_id")
    private UUID subjectUserId;

    /**
     * Idempotency key, unique per {@code (recipient_user_id, dedup_key)} — enforced by
     * {@code in_app_notification_dedup_uq}. A re-entrant transition (retry, a per-booking loop
     * inside a whole-visit op) collapses to one item via {@code INSERT ... ON CONFLICT DO NOTHING}.
     */
    @NotNull
    @Size(max = 160)
    @Column(name = "dedup_key", nullable = false, length = 160)
    private String dedupKey;

    /**
     * Populated by {@code @CreationTimestamp} for the ORM path, IN ADDITION to (not instead of)
     * the DB {@code DEFAULT now()} that backstops raw-SQL inserts (fixtures, the native
     * {@code insertIgnoringDuplicate} write path) — memory rule §O-1.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null while unread. Flipped only via the bulk {@code markRead}/{@code markAllRead} UPDATEs. */
    @Column(name = "read_at")
    private Instant readAt;
}
