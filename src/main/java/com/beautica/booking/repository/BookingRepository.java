package com.beautica.booking.repository;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
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

    // ── Full-graph list queries (Fix H1 — N+1 prevention) ─────────────────────

    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.client.id = :clientId
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.client.id = :clientId
            """)
    Page<Booking> findByClientIdWithGraph(@Param("clientId") UUID clientId, Pageable pageable);

    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.client.id = :clientId AND b.status = :status
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.client.id = :clientId AND b.status = :status
            """)
    Page<Booking> findByClientIdAndStatusWithGraph(
            @Param("clientId") UUID clientId,
            @Param("status") BookingStatus status,
            Pageable pageable);

    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.master.id = :masterId
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.master.id = :masterId
            """)
    // Callers must supply the authenticated user's own master.id — not an arbitrary UUID.
    // Scope enforcement: BookingService resolves masterId via masterRepository.findByUserId(actorUserId).
    Page<Booking> findByMasterIdWithGraph(@Param("masterId") UUID masterId, Pageable pageable);

    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE b.master.id = :masterId AND b.status = :status
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.master.id = :masterId AND b.status = :status
            """)
    // Callers must supply the authenticated user's own master.id — not an arbitrary UUID.
    Page<Booking> findByMasterIdAndStatusWithGraph(
            @Param("masterId") UUID masterId,
            @Param("status") BookingStatus status,
            Pageable pageable);

    // ── Full-graph single lookup (Fix M6 — lazy loads on mutation response) ────

    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.client
            JOIN FETCH b.master m
            JOIN FETCH m.user
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            LEFT JOIN FETCH b.salon
            WHERE b.id = :id
            """)
    Optional<Booking> findByIdWithFullGraph(@Param("id") UUID id);

    // ── Calendar / overlap queries (kept as native SQL) ────────────────────────

    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.client c
            JOIN FETCH b.master m
            JOIN FETCH m.user mu
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition sd
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
    Page<Booking> findActiveByMasterIdAndStartsAtBetweenWithGraph(
            @Param("masterId") UUID masterId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            Pageable pageable
    );

    // ── Idempotency lookup — partial-index aligned (Fix M5) ───────────────────

    /**
     * Matches the partial unique index {@code uq_client_idempotency_key_active}
     * which covers only PENDING and CONFIRMED rows. Filtering by status here
     * allows the planner to use the partial index rather than scanning all rows.
     */
    @Query("""
            SELECT b FROM Booking b
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

    @Query(value = """
            SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(CAST(:masterId AS text), 0))) sub
            """, nativeQuery = true)
    Integer acquireAdvisoryLock(@Param("masterId") UUID masterId);

    // ── Fix H1 — N+1: full-graph variant used by BookingService (production path) ─────────────
    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.client c
            JOIN FETCH b.master m
            JOIN FETCH m.user mu
            JOIN m.salon s
            JOIN s.owner o
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition sd
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
    Page<Booking> findBySalonIdAndOwnerIdWithGraph(
            @Param("salonId") UUID salonId,
            @Param("ownerId") UUID ownerId,
            Pageable pageable);

    // ── Fix MEDIUM: SALON_OWNER status-filtered variant ──────────────────────────
    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.client c
            JOIN FETCH b.master m
            JOIN FETCH m.user mu
            JOIN m.salon s
            JOIN s.owner o
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition sd
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
    Page<Booking> findBySalonIdAndOwnerIdAndStatusWithGraph(
            @Param("salonId") UUID salonId,
            @Param("ownerId") UUID ownerId,
            @Param("status") BookingStatus status,
            Pageable pageable);
}
