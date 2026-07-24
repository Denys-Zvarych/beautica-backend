package com.beautica.client.repository;

import com.beautica.booking.entity.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Read-only aggregation repository backing the BEAUTY PASSPORT + BEAUTY TIMELINE
 * (Phase 19.5). Owns a concern distinct from {@code BookingRepository}'s booking
 * management — passport/timeline projections over the signed-in client's history —
 * so per the feature-packaging + SRP rules it lives in the {@code client} package
 * rather than widening the booking repository. Spring Data permits several
 * repositories over the same {@link Booking} aggregate.
 *
 * <p><b>Every query is scoped {@code client_id = :clientId AND status = COMPLETED}</b>
 * and the callers must pass the authenticated client's own user id — that predicate is
 * the ownership boundary (Anti-Bug §E-4). All ranking/aggregation is done IN SQL
 * (GROUP BY + ORDER BY count DESC, bounded by {@code Pageable}); none of these methods
 * pulls the full booking set into memory.
 */
public interface ClientAggregationRepository extends JpaRepository<Booking, UUID> {

    /**
     * Top service-type (category) names by COMPLETED-booking count, most-booked first.
     * The category string is the platform service-type classifier on
     * {@code ServiceDefinition}. Null categories are excluded. Bound the result with
     * {@code PageRequest.of(0, 3)} for the top-3 contract (SQL LIMIT — no in-memory cut).
     */
    @Query("""
            SELECT sd.category
            FROM Booking b
            JOIN b.masterService ms
            JOIN ms.serviceDefinition sd
            WHERE b.client.id = :clientId
              AND b.status = com.beautica.booking.enums.BookingStatus.COMPLETED
              AND sd.category IS NOT NULL
            GROUP BY sd.category
            ORDER BY COUNT(b) DESC, sd.category ASC
            """)
    List<String> findTopServiceTypes(@Param("clientId") UUID clientId, Pageable pageable);

    /**
     * Top discovery districts by COMPLETED-booking count, most-visited first, as
     * {@link DistrictCount} rows carrying the FK id only (the service resolves the label
     * via the taxonomy join — occupied-territory ban). Discovery district is
     * district-primary: salon-presence wins outright via
     * {@code CASE WHEN s.id IS NOT NULL THEN s.districtId ELSE mu.districtId END}, even
     * when the salon's own {@code districtId} is {@code NULL} (a legacy salon predating
     * the Phase 10.3 locality columns, or a salon whose city has no urban districts).
     * Rows whose resolved district id is null are excluded — including a salon-employed
     * master's completed booking whose salon has no district, which is deliberately
     * dropped from the aggregate rather than falling back to {@code mu.districtId}.
     *
     * <p><b>Do not use {@code COALESCE(s.districtId, mu.districtId)}</b> — unlike
     * {@code SearchService} / {@code FavoriteRepository} (where the salon join is
     * structurally always {@code NULL} for the rows those queries can ever return, making
     * the {@code COALESCE} vs {@code CASE WHEN} choice moot), {@code Booking.master} here
     * is unrestricted by role: a {@code SALON_MASTER} or {@code SALON_OWNER}-type master's
     * completed booking DOES join a real salon row. {@code COALESCE} would silently fall
     * through to {@code mu.districtId} whenever {@code s.districtId} is {@code NULL},
     * exactly the class of bug fixed in {@code findClientBookingDetails} (19.3) — for a
     * {@code SALON_OWNER}-type master, {@code mu.districtId} is a one-time mirror of the
     * salon's OWN locality taken at salon-creation time ({@code SalonService.createSalon}),
     * never re-synced on salon relocation ({@code SalonService.updateSalon} does not touch
     * it), so it can go stale and point at a district the salon no longer occupies. This
     * mirrors {@link com.beautica.booking.dto.BookingDetailResponse#from}'s ternary exactly.
     */
    @Query("""
            SELECT new com.beautica.client.repository.DistrictCount(
                CASE WHEN s.id IS NOT NULL THEN s.districtId ELSE mu.districtId END,
                COUNT(b)
            )
            FROM Booking b
            JOIN b.master m
            JOIN m.user mu
            LEFT JOIN m.salon s
            WHERE b.client.id = :clientId
              AND b.status = com.beautica.booking.enums.BookingStatus.COMPLETED
              AND CASE WHEN s.id IS NOT NULL THEN s.districtId ELSE mu.districtId END IS NOT NULL
            GROUP BY CASE WHEN s.id IS NOT NULL THEN s.districtId ELSE mu.districtId END
            ORDER BY COUNT(b) DESC
            """)
    List<DistrictCount> findTopDistricts(@Param("clientId") UUID clientId, Pageable pageable);

    /**
     * Single-row spend aggregate over the client's COMPLETED bookings. {@code avg/min/max}
     * are null and {@code total} is 0 when there are none (empty-set aggregate) — the
     * service maps that to the empty-state.
     */
    @Query("""
            SELECT new com.beautica.client.repository.BudgetAggregate(
                CAST(AVG(b.priceAtBooking) AS BigDecimal),
                MIN(b.priceAtBooking),
                MAX(b.priceAtBooking),
                COUNT(b)
            )
            FROM Booking b
            WHERE b.client.id = :clientId
              AND b.status = com.beautica.booking.enums.BookingStatus.COMPLETED
            """)
    BudgetAggregate aggregateBudget(@Param("clientId") UUID clientId);

    /**
     * Paginated, most-recent-first timeline of COMPLETED bookings. Scalar projection, so
     * Hibernate applies a correct SQL LIMIT/OFFSET for {@code pageable} (no HHH90003004).
     */
    @Query(value = """
            SELECT new com.beautica.client.repository.TimelineItemProjection(
                b.id,
                sd.category,
                b.startsAt,
                m.id,
                sd.name
            )
            FROM Booking b
            JOIN b.master m
            JOIN b.masterService ms
            JOIN ms.serviceDefinition sd
            WHERE b.client.id = :clientId
              AND b.status = com.beautica.booking.enums.BookingStatus.COMPLETED
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.client.id = :clientId
              AND b.status = com.beautica.booking.enums.BookingStatus.COMPLETED
            """)
    Page<TimelineItemProjection> findTimeline(@Param("clientId") UUID clientId, Pageable pageable);
}
