package com.beautica.review.invite.repository;

import com.beautica.review.invite.entity.ReviewInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 399/400 — data access for {@link ReviewInvite}. Unscoped by design: invites are never read on behalf of an
 * authenticated user. Every multi-row statement is bounded by {@code :limit} (phase 400 D4a) or by a caller-supplied id
 * batch (phase 402 D3 skip). The token lookup / atomic redeem (401) and the retention purge (402) are added by the
 * phases that own them.
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

    /**
     * Phase 401 D2 — the redemption lookup, read side (the guest review page's GET): the invite whose token hashes to
     * {@code tokenHash} ONLY when it is redeemable ({@code SENT} and unexpired) or already {@code CONSUMED} — hash,
     * status and expiry in ONE predicate, never a load-by-hash followed by a redeemability check in Java. The caller
     * only tells the two matching states apart ({@code CONSUMED} → «Відгук уже залишено», website 019 input); every
     * other row (SCHEDULED, SKIPPED, FAILED, EXPIRED, SENT past expiry) and an unknown hash are both absent → the one
     * 404. Phase 401 audit-fix (perf INFO): replaces the former redeemable lookup + consumed-exists probe pair, so an
     * {@code ALREADY_REVIEWED} or unknown token costs one statement instead of two. Served by
     * {@code ux_review_invites_token_hash}.
     */
    @Query(value = """
            SELECT * FROM review_invites
            WHERE token_hash = :tokenHash
              AND ((status = 'SENT' AND expires_at > :now) OR status = 'CONSUMED')
            """, nativeQuery = true)
    Optional<ReviewInvite> findRedeemableOrConsumed(@Param("tokenHash") byte[] tokenHash, @Param("now") Instant now);

    /**
     * Phase 401 D2/D4 — the submit's atomic redeem: {@code SENT → CONSUMED} for the invite whose token hashes to
     * {@code tokenHash}, only while still {@code SENT} and unexpired, returning the consumed row (empty = not redeemable
     * → 404). Phase 401 audit-fix (perf INFO): one statement replaces the former {@code SELECT … FOR UPDATE} + {@code
     * UPDATE … WHERE id} pair with the same guarantees —
     * <ul>
     *   <li><b>race</b>: a concurrent submit of the same token blocks on the row lock this UPDATE takes; once the winner
     *       commits, Postgres (READ COMMITTED) re-checks the predicate on the new row version and the loser updates no
     *       row (→ 404). If the winner rolls back, the loser consumes it — exactly the {@code FOR UPDATE} behaviour;</li>
     *   <li><b>rollback</b>: the caller validates the items and writes the reviews AFTER this, in the same
     *       transaction, so a 400/404/409 on any item rolls the consume back and the token stays {@code SENT}.</li>
     * </ul>
     * Not {@code @Modifying}: {@code @Modifying} routes the query through {@code executeUpdate}, which rejects a
     * statement that returns rows — {@code RETURNING *} makes this a row-returning statement mapped to the entity.
     * Phase 401 audit-fix cycle 2 (sec INFO): explicitly read-write {@code @Transactional} — a declared query method
     * otherwise inherits {@code SimpleJpaRepository}'s class-level {@code readOnly = true} — joining the caller's
     * transaction (the submit), so the consume still rolls back with any 400/404/409 of the items.
     */
    @Transactional
    @Query(value = """
            UPDATE review_invites SET status = 'CONSUMED', consumed_at = :now
            WHERE token_hash = :tokenHash AND status = 'SENT' AND expires_at > :now
            RETURNING *
            """, nativeQuery = true)
    Optional<ReviewInvite> consumeRedeemable(@Param("tokenHash") byte[] tokenHash, @Param("now") Instant now);

    /**
     * Phase 401 D6 — the opt-out lookup: an invite that was actually sent ({@code SENT}, {@code CONSUMED} or
     * {@code EXPIRED}) no earlier than {@code sentAfter} (now − 30 days), so a guest can still opt out after reviewing
     * or after the review window closed. Hash, status and send-time in one predicate.
     */
    @Query(value = """
            SELECT * FROM review_invites
            WHERE token_hash = :tokenHash AND status IN ('SENT', 'CONSUMED', 'EXPIRED') AND sent_at > :sentAfter
            """, nativeQuery = true)
    Optional<ReviewInvite> findOptOutable(@Param("tokenHash") byte[] tokenHash, @Param("sentAfter") Instant sentAfter);

    /**
     * Phase 402 D3 — visit-keyed arm of the anonymisation skip: every {@code SCHEDULED} invite of a visit in
     * {@code appointmentIds} becomes {@code SKIPPED (ANONYMISED)}. Served by {@code ux_review_invites_appointment};
     * never called with an empty list (the caller skips the statement instead of binding {@code IN ()}). Only
     * {@code SCHEDULED} rows, which carry no token material, so {@code chk_review_invites_unsent_no_token} (V217)
     * holds. {@code clearAutomatically}: a {@code ReviewInvite} managed in this persistence context must not be
     * flushed later with its stale {@code SCHEDULED} state.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review_invites SET status = 'SKIPPED', skip_reason = 'ANONYMISED'
             WHERE status = 'SCHEDULED' AND appointment_id IN (:appointmentIds)
            """, nativeQuery = true)
    int skipScheduledForAppointments(@Param("appointmentIds") Collection<UUID> appointmentIds);

    /**
     * Phase 402 D3 — booking-keyed arm of {@link #skipScheduledForAppointments} (single LINK bookings, phase 400 D7).
     * Served by {@code ux_review_invites_booking}; never called with an empty list.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review_invites SET status = 'SKIPPED', skip_reason = 'ANONYMISED'
             WHERE status = 'SCHEDULED' AND booking_id IN (:bookingIds)
            """, nativeQuery = true)
    int skipScheduledForBookings(@Param("bookingIds") Collection<UUID> bookingIds);

    /**
     * Phase 402 D3 — deletes up to {@code limit} terminal invites whose terminal moment is before {@code cutoff}
     * (now − 30 days). Subject-agnostic: visit- and booking-keyed rows alike.
     * <ul>
     *   <li>{@code CONSUMED}: {@code consumed_at};</li>
     *   <li>{@code EXPIRED}: {@code expires_at};</li>
     *   <li>{@code SENT} past {@code expires_at} (audit-fix cycle 1, sec LOW): a stalled expiry — the expire sweep
     *       never reached it — is purged on the same clock as an {@code EXPIRED} row, so a stalled sweep can never
     *       keep a row past its promised retention;</li>
     *   <li>{@code SKIPPED} / {@code FAILED}: the table has no terminal timestamp for these, so {@code created_at}
     *       stands in (audit-fix cycle 1, sec LOW — formerly {@code GREATEST(created_at, send_after)}, which let a
     *       {@code send_after} deferred by quiet hours / the completion delay push the purge PAST the promised 30
     *       days). {@code created_at} is never later than the terminal moment, so the row is purged no later than
     *       promised; purging it earlier is acceptable — the row holds no PII beyond the phone hash.</li>
     * </ul>
     * {@code SCHEDULED} and unexpired {@code SENT} rows are never deleted. The {@code MATERIALIZED} CTE evaluates the
     * {@code LIMIT} once ({@link #expireSent}); {@code FOR UPDATE SKIP LOCKED} keeps it off rows a send tick holds.
     * No index serves this query: the {@code OR} across four status arms (each on a different timestamp) is a
     * bounded sequential scan of a small table, stopped early by {@code LIMIT} and kept off locked rows by
     * {@code SKIP LOCKED}. {@code idx_review_invites_sent_expires} (V216) is not used here. Add a partial index on
     * the predicate only if production timing of the nightly purge exceeds ~100 ms (phase 399/402 audit).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            WITH due AS MATERIALIZED (
                SELECT id FROM review_invites
                 WHERE (status = 'CONSUMED' AND consumed_at < :cutoff)
                    OR (status = 'EXPIRED' AND expires_at < :cutoff)
                    OR (status = 'SENT' AND expires_at < :cutoff)
                    OR (status IN ('SKIPPED', 'FAILED') AND created_at < :cutoff)
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM review_invites r USING due WHERE r.id = due.id
            """, nativeQuery = true)
    int purgeTerminalBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /**
     * Phase 402 audit-fix cycle 1 (sec LOW) — the erase arm for SENT tokens: every {@code SENT} invite of the phone
     * hashed to {@code phoneHash} becomes {@code EXPIRED} with {@code expires_at = now}, so its link stops resolving
     * at once ({@link #findRedeemableOrConsumed} / {@link #consumeRedeemable} → 404). {@code EXPIRED} keeps
     * {@code token_hash} / {@code sent_at} / {@code expires_at} ({@code chk_review_invites_sent_fields},
     * {@code chk_review_invites_unsent_no_token} — V213/V217), so {@link #findOptOutable} still accepts the token
     * within 30 days of sending, and {@link #purgeTerminalBefore} deletes the row 30 days after the erase. Served by
     * the partial {@code idx_review_invites_sent_expires} (V216): the scan is bounded by the live {@code SENT} rows,
     * which the expire sweep keeps to one review window. Manual erase only — never on a hot path.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review_invites SET status = 'EXPIRED', expires_at = :now
             WHERE status = 'SENT' AND phone_hash = :phoneHash
            """, nativeQuery = true)
    int expireSentForPhoneHash(@Param("phoneHash") byte[] phoneHash, @Param("now") Instant now);
}
