package com.beautica.notification.inapp.repository;

import com.beautica.notification.inapp.entity.InAppNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * Data access for the in-app notification feed (phase 332). Every write here is a bulk
 * {@code UPDATE}/{@code INSERT ... ON CONFLICT} — this table is never mutated through
 * {@code save()} on a loaded {@link InAppNotification} instance, so there is no
 * {@code findById}-then-{@code save} round trip anywhere in this interface.
 *
 * <p>Extends the bare {@link Repository} marker, not {@code JpaRepository} (audit-fix cycle 1,
 * finding 6, §B/IDOR territory): {@code JpaRepository} would inherit unscoped
 * {@code findById}/{@code findAll}/{@code deleteById}, none of which check
 * {@code recipient_user_id} against the caller — a latent "read/delete anyone's notification by
 * guessing its id" bug waiting for a careless call site. Every method below is either scoped to a
 * recipient explicitly, or is an aggregate/insert/delete that does not expose another user's row.
 */
public interface InAppNotificationRepository extends Repository<InAppNotification, UUID> {

    /**
     * The recipient's feed, newest first — rides {@code in_app_notification_feed_idx}
     * {@code (recipient_user_id, created_at DESC, id DESC)}. {@code id DESC} breaks ties
     * deterministically when two rows share a {@code created_at} (same-millisecond writes inside
     * one visit-level transaction).
     */
    Page<InAppNotification> findByRecipientUserIdOrderByCreatedAtDescIdDesc(
            UUID recipientUserId, Pageable pageable);

    /**
     * The bell red-dot count — rides the partial index {@code in_app_notification_unread_idx}
     * {@code (recipient_user_id) WHERE read_at IS NULL}.
     */
    long countByRecipientUserIdAndReadAtIsNull(UUID recipientUserId);

    /**
     * Marks a single item read, scoped to its owning recipient so one user can never mark another
     * user's item read by guessing an id (Anti-Bug §B territory: this is the ownership guard, not
     * the caller's principal check). A no-op ({@code 0} rows) if the id does not exist, belongs to
     * someone else, or is already read.
     *
     * <p>{@code clearAutomatically = true}: a bulk JPQL {@code UPDATE} writes straight to the DB and
     * does not touch the persistence context, so an {@link InAppNotification} the caller already
     * loaded in the SAME transaction (e.g. an ownership check right before this call) would keep
     * reading a stale, pre-update {@code readAt} from Hibernate's first-level cache on any later
     * {@code findById} unless the context is cleared here.
     *
     * @return {@code 1} if this call marked the row read, {@code 0} otherwise
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE InAppNotification n
               SET n.readAt = :now
             WHERE n.id = :id
               AND n.recipientUserId = :recipientUserId
               AND n.readAt IS NULL
            """)
    int markRead(@Param("id") UUID id, @Param("recipientUserId") UUID recipientUserId, @Param("now") Instant now);

    /**
     * Marks every still-unread item created at or before {@code upTo} read, for one recipient —
     * the "mark all read" action. {@code upTo} is the client's page-load cutoff so an item that
     * arrives concurrently (e.g. a new booking notification, written after the client opened the
     * feed) is NOT swept up as read before the client ever saw it.
     *
     * <p>{@code clearAutomatically = true} — same reason as {@link #markRead}: a bulk update bypasses
     * the persistence context, so it must be cleared for a later {@code findById} in the same
     * transaction to see the fresh {@code readAt}.
     *
     * @return the number of rows marked read
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE InAppNotification n
               SET n.readAt = :now
             WHERE n.recipientUserId = :recipientUserId
               AND n.readAt IS NULL
               AND n.createdAt <= :upTo
            """)
    int markAllRead(@Param("recipientUserId") UUID recipientUserId, @Param("upTo") Instant upTo, @Param("now") Instant now);

    /** Ownership check without hydrating the row — used before a deep-link navigation (phase 334/364). */
    boolean existsByIdAndRecipientUserId(UUID id, UUID recipientUserId);

    /**
     * Inserts one feed row, or silently no-ops if {@code (recipient_user_id, dedup_key)} already
     * exists ({@code in_app_notification_dedup_uq}) — the idempotency guard for re-entrant
     * transitions (retry, a per-booking loop inside one whole-visit operation). {@code id} is
     * generated by the caller (mirrors every other {@code GenerationType.UUID} entity in this
     * codebase — a native INSERT bypasses Hibernate's id-generation strategy, so the caller must
     * supply one explicitly). {@code created_at} is left to the column's {@code DEFAULT now()};
     * {@code read_at} defaults to {@code NULL}.
     *
     * <p>Native, not JPQL: {@code type} is bound as its {@link Enum#name()} string against the
     * plain {@code VARCHAR} column, matching {@code AppointmentRepository#consumeCancelToken}'s
     * convention for the same reason — there is no {@code @Enumerated} metadata to consult outside
     * an entity attribute path in a native query.
     *
     * @return {@code 1} if a row was inserted, {@code 0} if the conflict guard suppressed it
     */
    @Modifying
    @Query(value = """
            INSERT INTO in_app_notification
                (id, recipient_user_id, type, booking_id, appointment_id, salon_id, subject_user_id, dedup_key)
            VALUES (:id, :recipientUserId, :type, :bookingId, :appointmentId, :salonId, :subjectUserId, :dedupKey)
            ON CONFLICT (recipient_user_id, dedup_key) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringDuplicate(
            @Param("id") UUID id,
            @Param("recipientUserId") UUID recipientUserId,
            @Param("type") String type,
            @Param("bookingId") UUID bookingId,
            @Param("appointmentId") UUID appointmentId,
            @Param("salonId") UUID salonId,
            @Param("subjectUserId") UUID subjectUserId,
            @Param("dedupKey") String dedupKey);

    /**
     * Retention sweep (phase 335): deletes up to {@code limit} rows older than {@code cutoff},
     * oldest-first. Bounded via a driving subquery — Postgres {@code DELETE} has no {@code LIMIT}
     * clause of its own — mirroring the bounded-delete shape used elsewhere in this codebase for
     * housekeeping sweeps.
     *
     * @return the number of rows actually deleted (may be less than {@code limit})
     */
    @Modifying
    @Query(value = """
            DELETE FROM in_app_notification
             WHERE id IN (
                 SELECT id FROM in_app_notification
                  WHERE created_at < :cutoff
                  ORDER BY created_at
                  LIMIT :limit
             )
            """, nativeQuery = true)
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
