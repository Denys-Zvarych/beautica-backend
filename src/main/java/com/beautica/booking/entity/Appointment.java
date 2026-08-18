package com.beautica.booking.entity;

import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.common.AuditableEntity;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Aggregate header for a multi-service single-visit booking (BE-1).
 *
 * <p>An appointment groups N unchanged {@link Booking} rows into ONE client-facing visit: the
 * client picks a single date+time for several services performed back-to-back by a SINGLE master,
 * and each selected service stays exactly one {@code bookings} row (chained in time) pointing back
 * here via the nullable {@code bookings.appointment_id} FK. Legacy single-service bookings keep
 * {@code appointment_id = null}, so this aggregate is purely additive.
 *
 * <p>This entity is the visit HEADER only. It deliberately carries NO time window, price or
 * duration — those stay per-service on the child {@code bookings} rows, which is why the Postgres
 * {@code no_overlapping_bookings} EXCLUDE constraint stays on {@code bookings.master_id} and is not
 * duplicated on this table. The status / notes / source / guest-identity fields below are a
 * denormalization of the rows this header aggregates (locked design: single master per visit,
 * all-or-nothing visit status; reviews are per-booking, not per-visit — locked rule: 1 booking = 1
 * feedback).
 *
 * <p>Every column mirrors the equivalent {@link Booking} column type/length so the denormalized
 * values stay byte-compatible with the rows they group (see {@code V124__create_appointments.sql}).
 * BE-1 is schema + mapping only: there is no create / read / status-transition logic here yet
 * (BE-3 / BE-4 / BE-5).
 */
@Entity
@Table(
        name = "appointments",
        indexes = {
                // FK-lookup indices (V124). These annotations mirror the DB indices for reader
                // accuracy only; empirically Hibernate 6.5 ddl-auto=validate does NOT check
                // @Table(indexes=...) against the real schema (see Booking.java for the full
                // finding) — the migration is the source of truth.
                @Index(name = "idx_appointments_client_id", columnList = "client_id"),
                @Index(name = "idx_appointments_salon_id", columnList = "salon_id"),
                // partial UNIQUE index (V124): guest-cancel token lookup. JPA cannot encode the
                // WHERE cancel_token IS NOT NULL predicate nor the partial-uniqueness — both live
                // in V124's ux_appointments_cancel_token only.
                @Index(name = "ux_appointments_cancel_token", columnList = "cancel_token"),
                // partial UNIQUE dedup index (V124), mirroring bookings' uq_client_idempotency_key_active
                // (V18/V113): stops a client-retry race persisting duplicate appointment headers. JPA
                // cannot encode the WHERE idempotency_key IS NOT NULL AND status = 'CONFIRMED' predicate
                // nor the partial-uniqueness — both live in V124's ux_appointments_client_idempotency_key_active.
                @Index(name = "ux_appointments_client_idempotency_key_active", columnList = "client_id, idempotency_key")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Appointment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Nullable: a guest (LINK) visit has no registered account, mirroring bookings.client_id
    // (nullable since V89). The BE-3 create path will enforce the APP/LINK invariant.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private User client;

    // Nullable: null = an independent-master visit (no salon), mirroring bookings.salon_id.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salon_id")
    private Salon salon;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    // The booking-CREATION note for the visit (set once at create time), mirroring
    // bookings.client_comment. No status-coupling CHECK — legitimately present on
    // CONFIRMED/COMPLETED visits too (see V124 / V114).
    @Column(name = "client_comment", length = 1000)
    private String clientComment;

    // The client's OWN cancellation note, mirroring bookings.client_cancellation_note (V114).
    // chk_appointment_client_cancellation_note_status (V124) pins it to CANCELLED visits.
    @Setter
    @Column(name = "client_cancellation_note", length = 1000)
    private String clientCancellationNote;

    // The provider note on a provider-terminated visit, mirroring bookings.provider_comment.
    // chk_appointment_provider_comment_status (V124) pins it to DECLINED/NOT_COMPLETED visits.
    @Setter
    @Column(name = "provider_comment", length = 1000)
    private String providerComment;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 50)
    private CancellationReason cancellationReason;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    // Uniqueness is enforced by the V124 partial-unique index (UNIQUE only over non-NULL rows);
    // `unique = true` here would direct ddl-auto to recreate a full unique constraint — omitted,
    // exactly as on Booking.cancelToken.
    //
    // @JsonIgnore: cancel_token is a guest-cancel CAPABILITY token — whoever holds it can cancel
    // the visit. Shielded from every JSON serialization path so a future accidental entity-return
    // can never leak it (matches InviteToken/RefreshToken token-field shielding).
    @JsonIgnore
    @Column(name = "cancel_token")
    private UUID cancelToken;

    // Defaults to APP so the registered-client path need not set it explicitly — Hibernate always
    // emits the column in the INSERT, so a null here would violate the NOT NULL despite the DB
    // DEFAULT. Mirrors Booking.bookingSource.
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "booking_source", nullable = false, length = 10)
    private BookingSource bookingSource = BookingSource.APP;

    @Column(name = "guest_name", length = 100)
    private String guestName;

    @Column(name = "guest_surname", length = 100)
    private String guestSurname;

    @Column(name = "guest_phone", length = 20)
    private String guestPhone;

    /**
     * Factory for an auto-confirmed guest (LINK) multi-service visit header (BE-7). Enforces the LINK
     * invariant (guest identity + cancel token non-null, no registered client) before the row exists —
     * mirroring {@link com.beautica.booking.entity.Booking#guestBooking} and the DB CHECK
     * {@code chk_appointment_guest_fields} (V126): a partially-populated guest visit can never be
     * constructed in code.
     *
     * <p>The header carries the ONE cancel token the guest uses to cancel the WHOLE visit (surfaced in
     * the confirmation SMS). The chained child {@code Booking} rows each carry their OWN token (the V91
     * {@code chk_bookings_guest_fields} CHECK requires a non-null token on every CONFIRMED LINK row);
     * those per-item tokens are never surfaced and are nulled together with the header's on cancel.
     *
     * @param salon        the visit's salon, or {@code null} for an independent-master visit
     * @param guestName    OTP-verified guest first name (required)
     * @param guestSurname guest surname (optional — column is nullable)
     * @param guestPhone   E.164 phone copied from the guest JWT {@code sub} (required)
     * @param cancelToken  the visit-level one-time cancel token (required)
     */
    public static Appointment guestAppointment(
            Salon salon,
            String guestName,
            String guestSurname,
            String guestPhone,
            UUID cancelToken) {
        if (guestName == null || guestName.isBlank()) {
            throw new IllegalArgumentException("guestName must not be blank for a LINK appointment");
        }
        if (guestPhone == null || guestPhone.isBlank()) {
            throw new IllegalArgumentException("guestPhone must not be blank for a LINK appointment");
        }
        if (cancelToken == null) {
            throw new IllegalArgumentException("cancelToken must not be null for a LINK appointment");
        }
        return Appointment.builder()
                // No client FK: a guest visit has no registered account (V126 enforces LINK ⇒ client NULL).
                .salon(salon)
                .status(BookingStatus.CONFIRMED)
                .bookingSource(BookingSource.LINK)
                .guestName(guestName)
                .guestSurname(guestSurname)
                .guestPhone(guestPhone)
                .cancelToken(cancelToken)
                .build();
    }
}
