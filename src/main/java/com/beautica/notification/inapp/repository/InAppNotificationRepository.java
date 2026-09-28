package com.beautica.notification.inapp.repository;

import com.beautica.notification.inapp.entity.InAppNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
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
     * Phase 334 — the bell red-dot count, bounded at the SOURCE rather than merely clamped after a
     * full {@code COUNT(*)}: the driving subquery stops scanning
     * {@code in_app_notification_unread_idx} at 100 matched rows, so a recipient with an
     * unbounded backlog never pays for more than a 100-row index scan. {@code
     * NotificationFeedService#unreadCount} clamps the result to 99 (so the wire value never
     * silently implies "exactly 100" — the true count could be higher).
     */
    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT 1 FROM in_app_notification
                 WHERE recipient_user_id = :recipientUserId AND read_at IS NULL
                 LIMIT 100
            ) capped
            """, nativeQuery = true)
    long countUnreadCapped(@Param("recipientUserId") UUID recipientUserId);

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
     * Phase 333 perf follow-up — bulk counterpart of {@link #insertIgnoringDuplicate} for the
     * salon-closure / master-removal / master-self-delete cascades ({@code
     * BookingService#declineFutureConfirmed}), recipient = the booking's client only (rows 9/10 of
     * the matrix). ONE set-based statement writes a row for every {@code (booking, client)} pair in
     * {@code bookingIds} at once, so a cascade touching V visits costs the SAME ONE statement
     * whether V is 1, 20 or 60 — mirroring {@code BookingRepository#declineConfirmedBulk}'s
     * "one UPDATE ... WHERE id IN" shape for the identical reason: this method exists ONLY because a
     * per-visit Java loop calling {@code insertIgnoringDuplicate} once per representative id was
     * caught scaling the whole cascade's JDBC statement count with V by {@code
     * MasterSelfDeleteBookingDisposalIT#should_keepStatementCountFlat_asDistinctAppointmentVisitCountGrows}
     * (2026-09-28) — never re-introduce that Java-side loop.
     *
     * <p>{@code b.client_id IS NOT NULL} — a guest/walk-in booking's client is null and contributes
     * no row, exactly like {@link com.beautica.notification.inapp.service.InAppNotificationService}'s
     * per-row path. {@code IS DISTINCT FROM :actorUserId} is defence-in-depth (the client is never
     * the actor for any of this method's three callers — an owner/master closes/removes, never the
     * client themselves), mirroring every other write path's actor-exclusion rule uniformly rather
     * than special-casing this one as "structurally unreachable, so skip the guard".
     *
     * @return the number of rows actually inserted (may be less than {@code bookingIds.size()} —
     *         a guest booking or a conflict-suppressed duplicate contributes 0)
     */
    @Modifying
    @Query(value = """
            INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, salon_id, dedup_key)
            SELECT gen_random_uuid(), b.client_id, :type, b.id, m.salon_id, :type || ':' || b.id
              FROM bookings b
              JOIN masters m ON m.id = b.master_id
             WHERE b.id IN (:bookingIds)
               AND b.client_id IS NOT NULL
               AND b.client_id IS DISTINCT FROM :actorUserId
            ON CONFLICT (recipient_user_id, dedup_key) DO NOTHING
            """, nativeQuery = true)
    int insertClientOnlyBulk(
            @Param("type") String type,
            @Param("bookingIds") Collection<UUID> bookingIds,
            @Param("actorUserId") UUID actorUserId);

    /**
     * Phase 333 perf follow-up — bulk counterpart of {@link #insertIgnoringDuplicate} for the
     * CLIENT self-delete cascade ({@code BookingService#enqueueClientCancelledPerVisit}), recipient
     * = the provider set (salon owner + all active {@code SALON_ADMIN}s + the performing master's
     * own user — row 2 of the matrix). Computes the SAME recipient set {@code
     * InAppRecipientResolver#providerSet} does, per booking, entirely in SQL via a lateral join, so
     * a self-delete cancelling V standalone bookings (each its own "visit" — see {@code
     * SalonClosureBookingCandidate#visitKey()}) costs ONE statement regardless of V. See {@link
     * #insertClientOnlyBulk}'s javadoc for why a per-visit Java loop is never acceptable here — the
     * identical regression was caught on this method's own call site by {@code
     * ClientSelfDeleteBatchedCancelPerfIT#should_lowerPerBookingStatementCost_when_graphPreloadIsBatched}.
     *
     * <p>The lateral subquery's three legs mirror {@code InAppRecipientResolver#addMasterUser} /
     * {@code #addOwnerAndAdmins} exactly: the master's own user (if any — a detached master
     * contributes nothing), the salon owner (if the master belongs to a salon), and every active
     * {@code SALON_ADMIN} of that salon. {@code UNION} (not {@code UNION ALL}) de-duplicates within
     * one booking's own recipient set — the "owner who is also the performing {@code SALON_OWNER}-
     * type master gets ONE row, not two" rule — and the {@code (recipient_user_id, dedup_key)}
     * unique constraint's {@code ON CONFLICT DO NOTHING} de-duplicates ACROSS a re-entrant call.
     *
     * @return the number of rows actually inserted
     */
    @Modifying
    @Query(value = """
            INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, salon_id, dedup_key)
            SELECT gen_random_uuid(), recipient.user_id, :type, b.id, m.salon_id, :type || ':' || b.id
              FROM bookings b
              JOIN masters m ON m.id = b.master_id
              CROSS JOIN LATERAL (
                  SELECT m.user_id AS user_id WHERE m.user_id IS NOT NULL
                  UNION
                  SELECT s.owner_id FROM salons s WHERE s.id = m.salon_id
                  UNION
                  SELECT u.id FROM users u
                   WHERE u.salon_id = m.salon_id AND u.role = 'SALON_ADMIN' AND u.is_active = true
              ) recipient
             WHERE b.id IN (:bookingIds)
               AND recipient.user_id IS DISTINCT FROM :actorUserId
            ON CONFLICT (recipient_user_id, dedup_key) DO NOTHING
            """, nativeQuery = true)
    int insertProviderSetBulk(
            @Param("type") String type,
            @Param("bookingIds") Collection<UUID> bookingIds,
            @Param("actorUserId") UUID actorUserId);

    /**
     * Audit-fix cycle 1 (findings 1 &amp; 3) — single-EVENT counterpart of {@link #insertProviderSetBulk}
     * / {@link #insertClientOnlyBulk}, for every per-event write in {@code InAppNotificationService}
     * ({@code notifyBookingEvent}, {@code notifyVisitEvent}, {@code notifyRescheduled}, {@code
     * notifyReviewReceived}). ONE statement writes a row for every id in {@code recipientIds} at once,
     * replacing the K-round-trip {@code insertIgnoringDuplicate} loop those methods used to run per
     * event (master + owner + N admins) — caught scaling the JDBC statement count with the recipient
     * count by {@code StaffBookingIT}'s {@code CREATE_FIXED_STATEMENTS} gate and {@code
     * AppointmentItemCompleteIT#should_costPinnedStatementCount_when_completingSingleServiceVisit}.
     *
     * <p><b>Recipients are resolved in JAVA</b>, off the caller's own already-loaded {@code
     * Booking}/{@code Appointment} graph (finding 1 — {@code InAppNotificationService} no longer
     * reloads a booking/appointment by id at all), not re-derived in SQL from a booking id. This is
     * the opposite division of labour from {@link #insertProviderSetBulk}'s LATERAL shape, which MUST
     * compute recipients in SQL because its cascade callers ({@code
     * BookingService#cancelFutureConfirmedBookingsForClientSelfDelete} and siblings) never load a
     * {@code Booking} entity at all — reusing that LATERAL statement here would reintroduce the exact
     * reload this method exists to avoid. This method's own job is therefore only "write K rows in one
     * round trip", never "compute K rows from a booking id".
     *
     * <p>Driven by {@code users}, filtered by {@code IN (:recipientIds)} — every id the caller passes
     * is already a resolved user id ({@code Master.user}, {@code Salon.owner}, an active {@code
     * SALON_ADMIN} from {@code InAppRecipientResolver}), so the join is a cardinality-preserving
     * filter (one row in, one row out per existing id), never a widening one; a stale/foreign id
     * (defensive only — nothing in this codebase can produce one) simply contributes no row rather
     * than failing the whole insert.
     *
     * @return the number of rows actually inserted (fewer than {@code recipientIds.size()} when a
     *         conflict-suppressed duplicate or a stale id is present)
     */
    @Modifying
    @Query(value = """
            INSERT INTO in_app_notification
                (id, recipient_user_id, type, booking_id, appointment_id, salon_id, subject_user_id, dedup_key)
            SELECT gen_random_uuid(), u.id, :type, :bookingId, :appointmentId, :salonId, :subjectUserId, :dedupKey
              FROM users u
             WHERE u.id IN (:recipientIds)
            ON CONFLICT (recipient_user_id, dedup_key) DO NOTHING
            """, nativeQuery = true)
    int insertForRecipients(
            @Param("type") String type,
            @Param("recipientIds") Collection<UUID> recipientIds,
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
     * <p>{@code ORDER BY created_at, id} (audit-fix cycle 1, finding 4) — {@code id} is a tiebreak
     * for deterministic batch membership when two or more rows share a {@code created_at} (same-
     * millisecond writes inside one visit-level transaction, e.g. {@link #insertForRecipients}).
     * Without it, Postgres may return an arbitrary subset of the tied rows for a given {@code LIMIT},
     * which is harmless for correctness (every tied row is still {@code < cutoff} and eligible) but
     * makes batch boundaries non-reproducible across repeated runs with the same inputs — undesirable
     * for a query a test or an operator might want to reason about deterministically. {@code id} adds
     * no extra index requirement: it is only a tiebreak for rows Postgres has already located via
     * {@code in_app_notification_created_idx} on {@code created_at}, not a widening of the WHERE
     * predicate.
     *
     * @return the number of rows actually deleted (may be less than {@code limit})
     */
    @Modifying
    @Query(value = """
            DELETE FROM in_app_notification
             WHERE id IN (
                 SELECT id FROM in_app_notification
                  WHERE created_at < :cutoff
                  ORDER BY created_at, id
                  LIMIT :limit
             )
            """, nativeQuery = true)
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
