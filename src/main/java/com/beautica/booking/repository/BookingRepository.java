package com.beautica.booking.repository;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingViewAccess;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    // ── ID-only paginated queries — two-query pattern (Fix H1 — HHH90003004) ──
    //
    // JOIN FETCH + Pageable on a collection path forces Hibernate to load all rows
    // into memory and paginate in the application layer (HHH90003004). The fix is a
    // two-query pattern: (1) paginate on IDs only (no JOIN FETCH → correct SQL LIMIT/
    // OFFSET), then (2) batch-hydrate the full graph for only those IDs.

    @Query(value = """
            SELECT b.id FROM Booking b
            WHERE b.client.id = :clientId
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.client.id = :clientId
            """)
    Page<UUID> findIdsByClientId(@Param("clientId") UUID clientId, Pageable pageable);

    @Query(value = """
            SELECT b.id FROM Booking b
            WHERE b.client.id = :clientId AND b.status = :status
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.client.id = :clientId AND b.status = :status
            """)
    Page<UUID> findIdsByClientIdAndStatus(
            @Param("clientId") UUID clientId,
            @Param("status") BookingStatus status,
            Pageable pageable);

    @Query(value = """
            SELECT b.id FROM Booking b
            WHERE b.master.id = :masterId
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.master.id = :masterId
            """)
    // Callers must supply the authenticated user's own master.id — not an arbitrary UUID.
    // Scope enforcement: BookingService resolves masterId via masterRepository.findByUserId(actorUserId).
    Page<UUID> findIdsByMasterId(@Param("masterId") UUID masterId, Pageable pageable);

    @Query(value = """
            SELECT b.id FROM Booking b
            WHERE b.master.id = :masterId AND b.status = :status
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.master.id = :masterId AND b.status = :status
            """)
    // Callers must supply the authenticated user's own master.id — not an arbitrary UUID.
    Page<UUID> findIdsByMasterIdAndStatus(
            @Param("masterId") UUID masterId,
            @Param("status") BookingStatus status,
            Pageable pageable);

    @Query(value = """
            SELECT b.id FROM Booking b
            JOIN b.master m
            JOIN m.salon s
            JOIN s.owner o
            WHERE s.id = :salonId
            AND o.id = :ownerId
            AND m.isActive = true
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            JOIN b.master m
            JOIN m.salon s
            JOIN s.owner o
            WHERE s.id = :salonId
            AND o.id = :ownerId
            AND m.isActive = true
            """)
    Page<UUID> findIdsBySalonIdAndOwnerId(
            @Param("salonId") UUID salonId,
            @Param("ownerId") UUID ownerId,
            Pageable pageable);

    @Query(value = """
            SELECT b.id FROM Booking b
            JOIN b.master m
            JOIN m.salon s
            JOIN s.owner o
            WHERE s.id = :salonId
            AND o.id = :ownerId
            AND m.isActive = true
            AND b.status = :status
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            JOIN b.master m
            JOIN m.salon s
            JOIN s.owner o
            WHERE s.id = :salonId
            AND o.id = :ownerId
            AND m.isActive = true
            AND b.status = :status
            """)
    Page<UUID> findIdsBySalonIdAndOwnerIdAndStatus(
            @Param("salonId") UUID salonId,
            @Param("ownerId") UUID ownerId,
            @Param("status") BookingStatus status,
            Pageable pageable);

    // ── SALON_OWNER multi-salon queries (Fix HIGH-1) ───────────────────────────
    //
    // The previous approach resolved salonId via userRepository.findSalonIdById which only
    // returns a value for invited roles (SALON_ADMIN, SALON_MASTER). For SALON_OWNER the
    // relationship is stored on Salon.owner_id, not User.salonId — always returning empty.
    // These methods accept a pre-resolved list of salonIds (from SalonRepository
    // .findIdsByOwnerIdAndIsActiveTrue) and join through master → salon, covering all active
    // salons owned by the actor in a single query.

    @Query(value = """
            SELECT b.id FROM Booking b
            JOIN b.master m
            JOIN m.salon s
            WHERE s.id IN :salonIds
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            JOIN b.master m
            JOIN m.salon s
            WHERE s.id IN :salonIds
            """)
    Page<UUID> findIdsBySalonIds(
            @Param("salonIds") List<UUID> salonIds,
            Pageable pageable);

    @Query(value = """
            SELECT b.id FROM Booking b
            JOIN b.master m
            JOIN m.salon s
            WHERE s.id IN :salonIds
            AND b.status = :status
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            JOIN b.master m
            JOIN m.salon s
            WHERE s.id IN :salonIds
            AND b.status = :status
            """)
    Page<UUID> findIdsBySalonIdsAndStatus(
            @Param("salonIds") List<UUID> salonIds,
            @Param("status") BookingStatus status,
            Pageable pageable);

    @Query(value = """
            SELECT b.id FROM Booking b
            WHERE b.master.id = :masterId
            AND b.startsAt >= :from
            AND b.startsAt < :to
            AND b.status IN (com.beautica.booking.enums.BookingStatus.PENDING,
                             com.beautica.booking.enums.BookingStatus.CONFIRMED,
                             com.beautica.booking.enums.BookingStatus.COMPLETED)
            ORDER BY b.startsAt ASC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.master.id = :masterId
            AND b.startsAt >= :from
            AND b.startsAt < :to
            AND b.status IN (com.beautica.booking.enums.BookingStatus.PENDING,
                             com.beautica.booking.enums.BookingStatus.CONFIRMED,
                             com.beautica.booking.enums.BookingStatus.COMPLETED)
            """)
    Page<UUID> findActiveIdsByMasterIdAndStartsAtBetween(
            @Param("masterId") UUID masterId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            Pageable pageable);

    /**
     * Batch-hydrates a bounded set of booking IDs with the full association graph.
     * Always called with the result of an ID-only page query, so the IN list size
     * equals the configured page size (default 20) — never unbounded.
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            LEFT JOIN FETCH m.salon s
            LEFT JOIN FETCH s.owner
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.id IN :ids
            """)
    List<Booking> findAllByIdsWithGraph(@Param("ids") List<UUID> ids);

    // ── Client booking-detail projection (Phase 19.3) ─────────────────────────
    /**
     * One-query, N+1-free projection for {@code GET /bookings/me}: every field
     * {@link com.beautica.booking.dto.BookingDetailResponse} needs for a client row,
     * plus a {@code reviewExists} flag via {@code LEFT JOIN Review}.
     *
     * <p>{@code statusFilter} is optional: when {@code null} the
     * {@code (:statusFilter IS NULL OR b.status = :statusFilter)} idiom matches all rows
     * (one method covers both the filtered and unfiltered list paths).
     *
     * <p><b>Discovery locality is district-primary via the salon link</b> — the salon's
     * city/district/address wins when the master is salon-employed, else the master's own
     * user row. This mirrors {@code SearchService}'s {@code COALESCE(salon, user)} rule so
     * the booking detail and search results agree on a provider's locality. The projection
     * returns the FK ids only; {@code BookingService} resolves the {@code name_uk} labels
     * through the {@code DiscoveryLocationResolver} M2 seam (§E: batched, not per row).
     *
     * <p>Pagination is safe to apply directly here: the query projects scalar columns (no
     * JOIN FETCH on a collection), so Hibernate emits a correct SQL {@code LIMIT/OFFSET}
     * and the HHH90003004 in-memory-pagination trap does not apply.
     *
     * <p>Scope: callers MUST pass the authenticated client's own user id — the predicate
     * {@code b.client.id = :clientId} is the ownership boundary (Anti-Bug §E-4).
     */
    @Query(value = """
            SELECT new com.beautica.booking.repository.ClientBookingDetailProjection(
                b.id,
                b.client.id,
                m.id,
                ms.id,
                sd.name,
                b.status,
                b.startsAt,
                b.endsAt,
                b.priceAtBooking,
                b.durationMinutesAtBooking,
                b.createdAt,
                b.client.firstName,
                b.client.lastName,
                mu.firstName,
                mu.lastName,
                b.clientComment,
                b.providerComment,
                mu.avatarUrl,
                mu.role,
                s.name,
                COALESCE(s.cityId, mu.cityId),
                COALESCE(s.districtId, mu.districtId),
                COALESCE(s.street, mu.street),
                COALESCE(s.buildingNo, mu.buildingNo),
                sd.category,
                CASE WHEN r.id IS NOT NULL THEN true ELSE false END
            )
            FROM Booking b
            JOIN b.client
            JOIN b.master m
            JOIN m.user mu
            LEFT JOIN m.salon s
            JOIN b.masterService ms
            JOIN ms.serviceDefinition sd
            LEFT JOIN Review r ON r.booking = b
            WHERE b.client.id = :clientId
              AND (:statusFilter IS NULL OR b.status = :statusFilter)
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.client.id = :clientId
              AND (:statusFilter IS NULL OR b.status = :statusFilter)
            """)
    Page<ClientBookingDetailProjection> findClientBookingDetails(
            @Param("clientId") UUID clientId,
            @Param("statusFilter") BookingStatus statusFilter,
            Pageable pageable);

    // ── Full-graph single lookup (Fix M6 — lazy loads on mutation response) ────

    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            LEFT JOIN FETCH m.salon s
            LEFT JOIN FETCH s.owner
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.id = :id
            """)
    Optional<Booking> findByIdWithFullGraph(@Param("id") UUID id);

    // ── Calendar / overlap queries (kept as native SQL) ────────────────────────

    // ── Idempotency lookup — partial-index aligned (Fix M5) ───────────────────

    /**
     * Matches the partial unique index {@code uq_client_idempotency_key_active}
     * which covers only PENDING and CONFIRMED rows. Filtering by status here
     * allows the planner to use the partial index rather than scanning all rows.
     *
     * <p>Intentional design: idempotency keys can be reused once a booking reaches a
     * terminal state (COMPLETED, CANCELLED, etc.) — a repeat request creates a new booking.
     * This avoids permanent client-side key exhaustion for long-lived users.
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            LEFT JOIN FETCH m.salon s
            LEFT JOIN FETCH s.owner
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.client.id = :clientId
              AND b.idempotencyKey = :idempotencyKey
              AND b.status IN (com.beautica.booking.enums.BookingStatus.PENDING,
                               com.beautica.booking.enums.BookingStatus.CONFIRMED)
            """)
    Optional<Booking> findActiveByClientIdAndIdempotencyKey(
            @Param("clientId") UUID clientId,
            @Param("idempotencyKey") String idempotencyKey);

    @Query(value = """
            SELECT * FROM bookings
            WHERE master_id = :masterId
              AND status IN ('PENDING','CONFIRMED')
              AND starts_at < :windowEnd
              AND ends_at   > :windowStart
            """, nativeQuery = true)
    // Callers must pass a narrow [windowStart, windowEnd) spanning only the target day.
    // A wide window causes full table scans and inflates the returned list unnecessarily.
    List<Booking> findOverlappingByMaster(
            @Param("masterId") UUID masterId,
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd
    );

    /**
     * All PENDING/CONFIRMED bookings for a master overlapping the {@code [windowStart, windowEnd)}
     * range, ordered by start. Backs the batched free-slot bookability gate
     * ({@code SlotCalculationService#filterBookableAssignments} / {@code hasBookableFutureSlot}):
     * the whole booking horizon is loaded ONCE per master and sliced per-day in memory, instead of
     * one {@link #findOverlappingByMaster} query per day. The overlap predicate ({@code starts_at <
     * windowEnd AND ends_at > windowStart}) matches {@link #findOverlappingByMaster} so a booking whose
     * tail spills past a day boundary is still returned. Bounded by the service layer's ≤180-day
     * booking horizon (Anti-Bug §E-3 — not an unbounded scan), and aligned to the same
     * {@code (master_id, status, starts_at)} access path as the per-day finder.
     */
    @Query(value = """
            SELECT * FROM bookings
            WHERE master_id = :masterId
              AND status IN ('PENDING','CONFIRMED')
              AND starts_at < :windowEnd
              AND ends_at   > :windowStart
            ORDER BY starts_at ASC
            """, nativeQuery = true)
    List<Booking> findActiveByMasterInRange(
            @Param("masterId") UUID masterId,
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd
    );

    @Query(value = """
            SELECT EXISTS (
              SELECT 1 FROM bookings
               WHERE master_id = :masterId
                 AND status IN ('PENDING','CONFIRMED')
                 AND starts_at < :requestedEndsAt
                 AND ends_at   > :requestedStartsAt
            )
            """, nativeQuery = true)
    boolean existsOverlap(
            @Param("masterId") UUID masterId,
            @Param("requestedStartsAt") OffsetDateTime requestedStartsAt,
            @Param("requestedEndsAt") OffsetDateTime requestedEndsAt
    );

    /**
     * Overlap check that excludes a single booking's own row — used by the reschedule
     * flow so a booking does not collide with itself when only its time changes.
     *
     * <p>Same predicate as {@link #existsOverlap(UUID, OffsetDateTime, OffsetDateTime)}
     * (PENDING/CONFIRMED rows only, half-open interval overlap) plus
     * {@code id <> :excludeBookingId}. Callers must hold the per-master advisory lock
     * (see {@link #acquireAdvisoryLock(UUID)}) before invoking, identical to create.
     */
    @Query(value = """
            SELECT EXISTS (
              SELECT 1 FROM bookings
               WHERE master_id = :masterId
                 AND id <> :excludeBookingId
                 AND status IN ('PENDING','CONFIRMED')
                 AND starts_at < :requestedEndsAt
                 AND ends_at   > :requestedStartsAt
            )
            """, nativeQuery = true)
    boolean existsOverlapExcluding(
            @Param("masterId") UUID masterId,
            @Param("requestedStartsAt") OffsetDateTime requestedStartsAt,
            @Param("requestedEndsAt") OffsetDateTime requestedEndsAt,
            @Param("excludeBookingId") UUID excludeBookingId
    );

    // ── Client-scoped conflict check (cross-master/salon double-booking) ─────────
    /**
     * Id of the client's earliest {@code PENDING}/{@code CONFIRMED} booking — with ANY
     * master/salon — that overlaps the requested {@code [requestedStartsAt, requestedEndsAt)}
     * window. Half-open interval overlap, same predicate shape as {@link #existsOverlap}, but
     * scoped by {@code client_id} instead of {@code master_id} so it catches a client double-
     * booking themselves across two different masters. {@code ORDER BY starts_at ASC LIMIT 1}
     * makes the earliest conflict deterministic when a client somehow holds more than one
     * overlapping booking. Backed by the partial index {@code idx_bookings_client_slot_overlap}
     * (V112), mirroring {@code idx_bookings_master_slot_overlap} (V26).
     *
     * <p>Callers must hold the per-client advisory lock (see
     * {@link #acquireClientAdvisoryLockWithTimeout(UUID)}) before invoking, so a concurrent
     * request from the same client cannot race this check-then-insert.
     */
    @Query(value = """
            SELECT id FROM bookings
             WHERE client_id = :clientId
               AND status IN ('PENDING','CONFIRMED')
               AND starts_at < :requestedEndsAt
               AND ends_at   > :requestedStartsAt
             ORDER BY starts_at ASC
             LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findFirstConflictingClientBookingId(
            @Param("clientId") UUID clientId,
            @Param("requestedStartsAt") OffsetDateTime requestedStartsAt,
            @Param("requestedEndsAt") OffsetDateTime requestedEndsAt
    );

    /**
     * Same as {@link #findFirstConflictingClientBookingId} but excludes a single booking's own
     * row — used by the reschedule flow so a booking does not conflict with itself when only
     * its time changes.
     */
    @Query(value = """
            SELECT id FROM bookings
             WHERE client_id = :clientId
               AND id <> :excludeBookingId
               AND status IN ('PENDING','CONFIRMED')
               AND starts_at < :requestedEndsAt
               AND ends_at   > :requestedStartsAt
             ORDER BY starts_at ASC
             LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findFirstConflictingClientBookingIdExcluding(
            @Param("clientId") UUID clientId,
            @Param("requestedStartsAt") OffsetDateTime requestedStartsAt,
            @Param("requestedEndsAt") OffsetDateTime requestedEndsAt,
            @Param("excludeBookingId") UUID excludeBookingId
    );

    /**
     * Fused, single-round-trip form of the per-client advisory lock: sets this transaction's
     * {@code lock_timeout} to 3s via {@code set_config('lock_timeout', '3s', true)} — the
     * {@code is_local=true} third argument makes this functionally identical to
     * {@code SET LOCAL lock_timeout = '3s'} (transaction-scoped; resets automatically at
     * commit/rollback and never leaks onto the next borrower of the pooled connection) — AND
     * acquires the salt-{@code 1} advisory lock in the SAME statement. Folding the two into one
     * round trip removes a network hop from every booking write (perf finding; measurable on
     * Neon's serverless proxy, which is a real network hop per statement, not a local call).
     *
     * <p>Postgres evaluates a SELECT target list left-to-right per row, so
     * {@code set_config(...)} is guaranteed to run before {@code pg_advisory_xact_lock(...)} on
     * the same row — the 3s ceiling is already in force for THIS lock acquisition. Because the
     * GUC is transaction-scoped (not just statement-scoped), it also remains in force for the
     * rest of the transaction, so it still bounds the subsequent per-master
     * {@link #acquireAdvisoryLock(UUID)} wait in {@code BookingService.doCreateBooking} /
     * {@code rescheduleBooking} WITHOUT needing to be re-applied there.
     *
     * <p>Serializes concurrent create/reschedule requests from the SAME client so two
     * simultaneous requests cannot both pass the client-conflict check before either insert
     * commits (the classic check-then-act race). Without the timeout, a single authenticated
     * CLIENT firing N concurrent requests would deterministically serialize all of them on the
     * identical advisory lock — parking N-1 Hikari connections for up to the full
     * connection-timeout and starving the pool (Neon free-tier {@code maximum-pool-size: 10})
     * for every other tenant (security fix, booking advisory-lock DoS audit).
     *
     * <p>Uses salt {@code 1} (vs. salt {@code 0} for {@link #acquireAdvisoryLock(UUID)}) so the
     * client-lock and master-lock hash spaces never collide, even in the (already vanishingly
     * unlikely) event a master id and a client's user id share the same UUID text.
     *
     * <p><b>Deadlock freedom:</b> every caller acquires the CLIENT lock (salt 1) BEFORE the
     * master lock (salt 0) — see {@code BookingService.doCreateBooking} /
     * {@code rescheduleBooking}. Because both request paths are the only writers that ever take
     * both locks, and both always acquire them in the same client-then-master order, no session
     * can hold a master lock while waiting on a client lock — the precondition for a two-lock
     * deadlock cycle never arises. {@code GuestBookingService} takes ONLY the master lock (via
     * {@link #acquireAdvisoryLockWithTimeout(UUID)} — no client id to key on), so it can never
     * participate in a two-lock cycle either.
     *
     * <p>A session that waits longer than {@code lock_timeout} aborts the lock wait with
     * Postgres {@code 55P03 lock_not_available}. Hibernate/Spring exception translation
     * surfaces this as {@link org.springframework.dao.CannotAcquireLockException} — mapped to
     * a clean 409 by {@code GlobalExceptionHandler#handleCannotAcquireLock} — instead of
     * parking the connection for the full Hikari connection-timeout (20 s) or surfacing a
     * bare 500.
     */
    @Query(value = """
            SELECT 1 FROM (
                SELECT set_config('lock_timeout', '3s', true),
                       pg_advisory_xact_lock(hashtextextended(CAST(:clientId AS text), 1))
            ) sub
            """, nativeQuery = true)
    Integer acquireClientAdvisoryLockWithTimeout(@Param("clientId") UUID clientId);

    // ── View-access projection — ownership-only, role from SecurityContext ───────
    /**
     * Returns booking ownership data for {@code canViewBooking} in one round-trip.
     *
     * <p>The actor join was removed (Finding 2): the cross-entity join
     * {@code JOIN com.beautica.user.User actor ON actor.id = :actorId} was producing
     * a Cartesian product in SQL and pulling {@code actorRole} from the database on
     * every access check. The actor's role is already present in the
     * {@code SecurityContextHolder} (set by {@code JwtAuthenticationFilter}) —
     * resolving it from there eliminates the cross-join and the extra DB column read.
     *
     * <p>Returns empty when the booking does not exist.
     */
    @Query("""
            SELECT new com.beautica.booking.repository.BookingViewAccess(
                b.client.id,
                bm.user.id,
                sOwner.id
            )
            FROM Booking b
            JOIN b.master bm
            JOIN bm.user
            LEFT JOIN bm.salon bs
            LEFT JOIN bs.owner sOwner
            WHERE b.id = :bookingId
            """)
    Optional<BookingViewAccess> findViewAccessById(@Param("bookingId") UUID bookingId);

    /**
     * Completion-authorization projection (Phase 18.4). Mirrors {@link #findViewAccessById}
     * but returns the booking's {@code salonId} (null for an independent-master booking) so
     * {@code AuthorizationService.canCompleteBooking} can admit a {@code SALON_ADMIN} assigned
     * to that salon — not only the owner. Returns empty when the booking does not exist.
     */
    @Query("""
            SELECT new com.beautica.booking.repository.BookingCompletionAccess(
                bm.user.id,
                bs.id
            )
            FROM Booking b
            JOIN b.master bm
            JOIN bm.user
            LEFT JOIN bm.salon bs
            WHERE b.id = :bookingId
            """)
    Optional<BookingCompletionAccess> findCompletionAccessById(@Param("bookingId") UUID bookingId);

    // Hash collision risk: hashtextextended produces a 64-bit hash of the UUID text.
    // Birthday-paradox probability is negligible for current master counts (<10,000)
    // but should be revisited if the platform scales significantly.
    //
    // Ordering: BookingService.doCreateBooking / rescheduleBooking acquire this lock AFTER
    // acquireClientAdvisoryLockWithTimeout (see that method's javadoc for the full
    // deadlock-freedom argument) — the per-client conflict check runs and can fail fast
    // without ever contending this shared per-master lock, which every other client racing
    // for the same popular master is waiting on. No timeout is (re-)applied here: the fused
    // set_config('lock_timeout', ..., true) issued by acquireClientAdvisoryLockWithTimeout
    // earlier in the SAME transaction is transaction-scoped, so its 3s ceiling already covers
    // this wait too.
    @Query(value = """
            SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(CAST(:masterId AS text), 0))) sub
            """, nativeQuery = true)
    Integer acquireAdvisoryLock(@Param("masterId") UUID masterId);

    /**
     * Fused, single-round-trip form of the per-master advisory lock for callers that take
     * ONLY the master lock (no client lock beforehand) — currently just
     * {@code GuestBookingService#persistBooking}. Sets this transaction's {@code lock_timeout}
     * to 3s via {@code set_config('lock_timeout', '3s', true)} (transaction-scoped, equivalent
     * to {@code SET LOCAL}) AND acquires the salt-{@code 0} advisory lock in the SAME
     * statement/round-trip — see {@link #acquireClientAdvisoryLockWithTimeout(UUID)} for the
     * full rationale (evaluation-order guarantee, DoS-closing motivation, perf round-trip
     * saving) shared by both fused methods.
     *
     * <p>The guest-booking endpoint ({@code POST /api/v1/book/{slug}/booking}) is
     * {@code permitAll}, gated only by a non-IP-bound guest JWT plus a per-IP rate limit, so an
     * unbounded lock wait there is an equally viable advisory-lock DoS vector as the
     * authenticated path — hence the timeout is fused here too, not just on the client lock.
     *
     * <p>{@code BookingService} does NOT use this method for its own master lock: it always
     * takes the client lock first via {@link #acquireClientAdvisoryLockWithTimeout(UUID)},
     * which already sets the transaction-scoped timeout, so its later master lock uses the
     * plain {@link #acquireAdvisoryLock(UUID)} — re-applying the timeout there would be a
     * redundant round trip.
     */
    @Query(value = """
            SELECT 1 FROM (
                SELECT set_config('lock_timeout', '3s', true),
                       pg_advisory_xact_lock(hashtextextended(CAST(:masterId AS text), 0))
            ) sub
            """, nativeQuery = true)
    Integer acquireAdvisoryLockWithTimeout(@Param("masterId") UUID masterId);

    // ── Guest-booking reminder sweep (Phase 13.3) ─────────────────────────────
    /**
     * Loads guest (LINK) bookings due for a 24h reminder SMS. Bounded by a narrow
     * {@code [from, to)} window supplied by {@link com.beautica.booking.job.BookingReminderJob}
     * (Anti-Bug §E-3: not unbounded), and aligned with the partial index
     * {@code idx_bookings_reminder} (LINK + reminder_sent = FALSE).
     *
     * <p>The fetched rows are mutated ({@code reminderSent = true}) and saved by the
     * job inside its transaction, so the {@code masterService}/{@code serviceDefinition}
     * graph is joined to render the reminder text without a lazy load.
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.master m
            JOIN FETCH m.user
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.bookingSource = com.beautica.booking.enums.BookingSource.LINK
              AND b.reminderSent = false
              AND b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
              AND b.startsAt BETWEEN :from AND :to
            """)
    List<Booking> findGuestBookingsForReminder(
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    // ── Guest-cancel by link (Phase 13.4) ─────────────────────────────────────
    /**
     * Resolves a guest (LINK) booking by its one-time {@code cancel_token} for the
     * public cancel page. The lookup hits the V90 partial-unique index
     * {@code idx_bookings_cancel_token} (UNIQUE over non-NULL rows only).
     *
     * <p>The master + service graph is JOIN-FETCHed so the cancel-info page can render
     * {@code masterName}/{@code serviceName} without a lazy load (Anti-Bug §E-2).
     *
     * <p>A consumed token is {@code NULL} (set by {@link #consumeCancelToken}), so a
     * replayed link returns empty → 404 (no info leak about token state).
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.master m
            JOIN FETCH m.user
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.cancelToken = :cancelToken
            """)
    Optional<Booking> findByCancelTokenWithGraph(@Param("cancelToken") UUID cancelToken);

    /**
     * Atomically consumes a cancel token: flips a still-{@code CONFIRMED} guest booking
     * to {@code CANCELLED} and nulls the token, in a single conditional UPDATE.
     *
     * <p><b>One-time / race-safe.</b> The {@code WHERE cancel_token = :token AND status =
     * CONFIRMED} predicate guarantees that of N concurrent {@code POST /cancel/{token}}
     * requests, exactly ONE UPDATE affects 1 row; every other affects 0. Only the winner
     * fires the cancellation SMS + master notification, so the side-effects run exactly
     * once. A replayed POST (token already NULL) updates 0 rows → the service maps it to
     * 404. This mirrors the atomic check-and-set used by the Phase 13.2 OTP recorder and
     * avoids the check-then-act double-cancel race a load→mutate→save flow would leave open.
     *
     * @return the number of rows updated — {@code 1} for the winner, {@code 0} otherwise
     */
    @Modifying
    @Query("""
            UPDATE Booking b
               SET b.status = com.beautica.booking.enums.BookingStatus.CANCELLED,
                   b.cancelToken = null
             WHERE b.cancelToken = :cancelToken
               AND b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
            """)
    int consumeCancelToken(@Param("cancelToken") UUID cancelToken);
}
