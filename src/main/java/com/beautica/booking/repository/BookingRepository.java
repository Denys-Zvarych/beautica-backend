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

    // Hash collision risk: hashtextextended produces a 64-bit hash of the UUID text.
    // Birthday-paradox probability is negligible for current master counts (<10,000)
    // but should be revisited if the platform scales significantly.
    @Query(value = """
            SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(CAST(:masterId AS text), 0))) sub
            """, nativeQuery = true)
    Integer acquireAdvisoryLock(@Param("masterId") UUID masterId);

}
