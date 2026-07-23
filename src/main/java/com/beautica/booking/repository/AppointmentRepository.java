package com.beautica.booking.repository;

import com.beautica.booking.entity.Appointment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for the multi-service single-visit aggregate header (BE-3). Overlap protection stays
 * per-child-row on {@code bookings} (the {@code no_overlapping_bookings} EXCLUDE constraint), so this
 * repository holds only the aggregate save + the idempotent-replay lookup.
 */
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /**
     * The client's live (CONFIRMED) appointment for a given idempotency key, if any — the
     * idempotent-replay lookup, aligned to the partial-unique index
     * {@code ux_appointments_client_idempotency_key_active} (V124,
     * {@code WHERE idempotency_key IS NOT NULL AND status = 'CONFIRMED'}). Filtering by
     * {@code status = CONFIRMED} lets the planner use that partial index rather than scanning all
     * rows, exactly as {@code BookingRepository#findActiveByClientIdAndIdempotencyKey} does for the
     * single-service path.
     *
     * <p>Returns only the header — the caller re-reads the chained bookings via
     * {@code BookingRepository#findByAppointmentIdWithGraph} to build the response.
     */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.client.id = :clientId
              AND a.idempotencyKey = :idempotencyKey
              AND a.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
            """)
    Optional<Appointment> findActiveByClientIdAndIdempotencyKey(
            @Param("clientId") UUID clientId,
            @Param("idempotencyKey") String idempotencyKey);
}
