package com.beautica.booking.repository;

import com.beautica.booking.entity.Appointment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // ── Guest (LINK) visit cancel by link (BE-7) ──────────────────────────────
    /**
     * Resolves a guest (LINK) visit by its one-time {@code cancel_token} for the public cancel page —
     * the visit-level analogue of {@code BookingRepository#findByCancelTokenWithGraph}. Rides the V124
     * partial-unique index {@code ux_appointments_cancel_token} (UNIQUE over non-NULL rows only). A
     * consumed token is {@code NULL} (nulled by {@link #consumeCancelToken}), so a replayed link returns
     * empty. Header only — the caller reads the chained items via
     * {@code BookingRepository#findByAppointmentIdWithGraph} for the master/service labels + eviction.
     */
    @Query("SELECT a FROM Appointment a WHERE a.cancelToken = :cancelToken")
    Optional<Appointment> findByCancelToken(@Param("cancelToken") UUID cancelToken);

    /**
     * Atomically consumes a visit's cancel token: flips a still-{@code CONFIRMED} guest visit HEADER to
     * {@code CANCELLED}, stamps {@code CLIENT_CANCELLED}, and nulls the token, in a single conditional
     * UPDATE. Mirrors {@code BookingRepository#consumeCancelToken} lifted to the aggregate header: of N
     * concurrent {@code POST /cancel/{token}} requests, exactly ONE affects 1 row (the winner cancels
     * the visit's items + fires the side-effects); every other affects 0 → mapped to 404. The child
     * {@code bookings} rows are cancelled separately by {@code BookingRepository#cancelItemsByAppointmentId}.
     *
     * @return the number of header rows updated — {@code 1} for the winner, {@code 0} otherwise
     */
    @Modifying
    @Query("""
            UPDATE Appointment a
               SET a.status = com.beautica.booking.enums.BookingStatus.CANCELLED,
                   a.cancellationReason = com.beautica.booking.enums.CancellationReason.CLIENT_CANCELLED,
                   a.cancelToken = null
             WHERE a.cancelToken = :cancelToken
               AND a.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
            """)
    int consumeCancelToken(@Param("cancelToken") UUID cancelToken);
}
