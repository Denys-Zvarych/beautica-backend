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

    Page<Booking> findByClientId(UUID clientId, Pageable pageable);

    Page<Booking> findByMasterId(UUID masterId, Pageable pageable);

    Page<Booking> findByClientIdAndStatus(UUID clientId, BookingStatus status, Pageable pageable);

    Page<Booking> findByMasterIdAndStatus(UUID masterId, BookingStatus status, Pageable pageable);

    Page<Booking> findByMasterIdAndStartsAtBetween(UUID masterId, OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    @Query(value = """
            SELECT * FROM bookings
            WHERE master_id = :masterId
              AND status IN ('PENDING','CONFIRMED')
              AND starts_at BETWEEN :from AND :to
            """,
            countQuery = """
            SELECT COUNT(*) FROM bookings
            WHERE master_id = :masterId
              AND status IN ('PENDING','CONFIRMED')
              AND starts_at BETWEEN :from AND :to
            """,
            nativeQuery = true)
    Page<Booking> findActiveByMasterIdAndStartsAtBetween(
            @Param("masterId") UUID masterId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            Pageable pageable
    );

    Optional<Booking> findByClientIdAndIdempotencyKey(UUID clientId, String idempotencyKey);

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

    @Modifying
    @Query(value = """
            SELECT pg_advisory_xact_lock(hashtextextended(CAST(:masterId AS text), 0))
            """, nativeQuery = true)
    void acquireAdvisoryLock(@Param("masterId") UUID masterId);

    @Query(value = """
            SELECT * FROM bookings
            WHERE salon_id = :salonId
              AND salon_id IN (SELECT id FROM salons WHERE owner_id = :ownerId)
            """,
            countQuery = """
            SELECT COUNT(*) FROM bookings
            WHERE salon_id = :salonId
              AND salon_id IN (SELECT id FROM salons WHERE owner_id = :ownerId)
            """,
            nativeQuery = true)
    Page<Booking> findBySalonIdAndOwnerId(@Param("salonId") UUID salonId, @Param("ownerId") UUID ownerId, Pageable pageable);
}
