package com.beautica.review.invite.repository;

import com.beautica.review.invite.entity.ReviewInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Phase 399/400 — data access for {@link ReviewInvite}. Unscoped by design: invites are never read on behalf of an
 * authenticated user. Every multi-row statement is bounded by {@code :limit} (phase 400 D4a). The token lookup /
 * atomic consume (401) and the retention purge (402) are added by the phases that own them.
 */
public interface ReviewInviteRepository extends JpaRepository<ReviewInvite, UUID> {

    /**
     * Phase 400 D1 — one invite per visit: inserts, or does nothing when the visit already has one (an item-level
     * completion followed by the header completion, or a replayed event). Returns the inserted row count (0 or 1).
     */
    @Modifying
    @Query(value = """
            INSERT INTO review_invites (id, appointment_id, phone_hash, status, send_after, attempts, skip_reason,
                                        created_at)
            VALUES (:id, :appointmentId, :phoneHash, :status, :sendAfter, 0, :skipReason, :createdAt)
            ON CONFLICT (appointment_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("appointmentId") UUID appointmentId,
                       @Param("phoneHash") byte[] phoneHash,
                       @Param("status") String status,
                       @Param("sendAfter") Instant sendAfter,
                       @Param("skipReason") String skipReason,
                       @Param("createdAt") Instant createdAt);

    /**
     * Phase 400 D4 — claims up to {@code limit} due {@code SCHEDULED} invites, oldest {@code send_after} first, served
     * by the partial {@code idx_review_invites_scheduled_send_after (send_after) WHERE status = 'SCHEDULED'} (V216) —
     * keep this predicate and that index's {@code WHERE} identical, or the planner stops using it. {@code FOR UPDATE SKIP LOCKED}: a concurrent tick (another
     * thread or another instance during a rolling deploy) skips these rows; the claimer flips them out of
     * {@code SCHEDULED} before its transaction commits, so they are never claimed twice. Callers must hold a
     * transaction.
     */
    @Query(value = """
            SELECT * FROM review_invites
            WHERE status = 'SCHEDULED' AND send_after <= :now
            ORDER BY send_after
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ReviewInvite> claimDue(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Phase 400 D6 — up to {@code limit} {@code SENT} invites past {@code expires_at} become {@code EXPIRED}. The
     * locking select is a {@code MATERIALIZED} CTE so the {@code LIMIT} is evaluated once (same reason as
     * {@code NotificationOutboxRepository#claimPendingBatch}). Served by the partial
     * {@code idx_review_invites_sent_expires (expires_at) WHERE status = 'SENT'} (V216): an index range scan in
     * {@code expires_at} order over SENT rows only, so the sweep never walks or sorts non-SENT rows.
     */
    @Modifying
    @Query(value = """
            WITH due AS MATERIALIZED (
                SELECT id FROM review_invites
                WHERE status = 'SENT' AND expires_at <= :now
                ORDER BY expires_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE review_invites r SET status = 'EXPIRED' FROM due WHERE r.id = due.id
            """, nativeQuery = true)
    int expireSent(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Phase 400 D2 — the gate inputs for each visit in {@code appointmentIds}, read in one statement. The guest
     * phone is selected for the send itself; it never leaves the service that reads it except as the SMS recipient.
     */
    @Query(value = """
            SELECT a.id                                    AS appointmentId,
                   CAST(NULL AS uuid)                      AS bookingId,
                   a.booking_source                        AS bookingSource,
                   (a.client_id IS NOT NULL)               AS hasClient,
                   a.guest_phone                           AS guestPhone,
                   (a.guest_pii_anonymised_at IS NOT NULL) AS anonymised,
                   a.guest_privacy_policy_version          AS policyVersion,
                   EXISTS (SELECT 1 FROM bookings b
                           WHERE b.appointment_id = a.id AND b.status = 'COMPLETED'
                             AND NOT EXISTS (SELECT 1 FROM reviews r WHERE r.booking_id = b.id))
                                                           AS hasUnreviewedCompleted
            FROM appointments a
            WHERE a.id IN (:appointmentIds)
            """, nativeQuery = true)
    List<ReviewInviteVisitView> findVisitViews(@Param("appointmentIds") Collection<UUID> appointmentIds);

    /**
     * Phase 400 D7 — {@link #insertIfAbsent} for a single LINK booking with no visit header: one invite per booking
     * ({@code ON CONFLICT (booking_id)}, backed by {@code ux_review_invites_booking}). Returns 0 or 1.
     */
    @Modifying
    @Query(value = """
            INSERT INTO review_invites (id, booking_id, phone_hash, status, send_after, attempts, skip_reason,
                                        created_at)
            VALUES (:id, :bookingId, :phoneHash, :status, :sendAfter, 0, :skipReason, :createdAt)
            ON CONFLICT (booking_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsentForBooking(@Param("id") UUID id,
                                 @Param("bookingId") UUID bookingId,
                                 @Param("phoneHash") byte[] phoneHash,
                                 @Param("status") String status,
                                 @Param("sendAfter") Instant sendAfter,
                                 @Param("skipReason") String skipReason,
                                 @Param("createdAt") Instant createdAt);

    /**
     * Phase 400 D7 — {@link #findVisitViews} for single bookings with no visit header, same columns: the booking is
     * its own one-service visit. A booking that belongs to a visit is never returned (its invite is visit-keyed).
     */
    @Query(value = """
            SELECT CAST(NULL AS uuid)                      AS appointmentId,
                   b.id                                    AS bookingId,
                   b.booking_source                        AS bookingSource,
                   (b.client_id IS NOT NULL)               AS hasClient,
                   b.guest_phone                           AS guestPhone,
                   (b.guest_pii_anonymised_at IS NOT NULL) AS anonymised,
                   b.guest_privacy_policy_version          AS policyVersion,
                   (b.status = 'COMPLETED'
                    AND NOT EXISTS (SELECT 1 FROM reviews r WHERE r.booking_id = b.id))
                                                           AS hasUnreviewedCompleted
            FROM bookings b
            WHERE b.id IN (:bookingIds) AND b.appointment_id IS NULL
            """, nativeQuery = true)
    List<ReviewInviteVisitView> findBookingViews(@Param("bookingIds") Collection<UUID> bookingIds);
}
