package com.beautica.booking.entity;

import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.common.AuditableEntity;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.user.User;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "bookings",
        indexes = {
                // partial index (V43): dashboard revenue — INDEPENDENT_MASTER path
                // JPA cannot encode WHERE status='COMPLETED' — predicate lives in V43 only;
                // do NOT remove it from the migration thinking the annotation is the source of truth.
                @Index(name = "idx_bookings_master_completed_starts_at", columnList = "master_id, starts_at"),
                // partial index (V43): dashboard revenue — SALON_OWNER path
                // same JPA partial-index limitation as above; V43 is authoritative for the WHERE clause.
                @Index(name = "idx_bookings_salon_completed_starts_at", columnList = "salon_id, starts_at"),
                // partial UNIQUE index (V90): cancel-token lookup for the public guest-cancel page.
                // JPA cannot encode WHERE cancel_token IS NOT NULL nor the partial-uniqueness —
                // the predicate + UNIQUE live in V90 only (V90 dropped the V89 full unique
                // constraint + non-unique partial index in favour of one partial-unique index).
                @Index(name = "idx_bookings_cancel_token", columnList = "cancel_token"),
                // partial index (V89): hourly guest-reminder sweep.
                // JPA cannot encode WHERE booking_source='LINK' AND reminder_sent=FALSE —
                // predicate lives in V89 only; do NOT treat this annotation as authoritative.
                @Index(name = "idx_bookings_reminder", columnList = "starts_at"),
                // composite index (V93): per-client "latest booking" LATERAL subquery in
                // FavoriteRepository.findFavoriteMasterRows. JPA cannot encode the DESC sort
                // direction — V93 declares starts_at DESC; this annotation mirrors the columns
                // only so ddl-auto=validate sees the index exists.
                @Index(name = "idx_bookings_master_client_starts_at", columnList = "master_id, client_id, starts_at")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Nullable since V89: a guest (LINK) booking has no registered account. The DB
    // CHECK chk_bookings_guest_fields keeps APP bookings' client_id NOT NULL.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private User client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_id", nullable = false)
    private Master master;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_service_id", nullable = false)
    private MasterServiceAssignment masterService;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salon_id")
    private Salon salon;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "price_at_booking", nullable = false)
    private BigDecimal priceAtBooking;

    @Column(name = "duration_minutes_at_booking", nullable = false)
    private int durationMinutesAtBooking;

    @Column(name = "buffer_minutes_at_booking")
    private int bufferMinutesAtBooking;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason")
    private CancellationReason cancellationReason;

    @Column(name = "client_comment", length = 1000)
    private String clientComment;

    @Setter
    @Column(name = "provider_comment", length = 1000)
    private String providerComment;

    // ── Guest-booking columns (Phase 13.3 / V89; relaxed by V91) ──────────────
    // A LINK booking is created via beautica.app/book/{slug} by a phone-verified,
    // account-less client. The DB CHECK chk_bookings_guest_fields enforces that
    // LINK ⇒ guestName/guestPhone non-null and APP ⇒ all null; the application
    // mirror lives in Booking.guestBooking(...). V91 relaxes the cancelToken clause
    // so a LINK row may have a NULL cancelToken once status is terminal
    // (CANCELLED/COMPLETED/NOT_COMPLETED/DECLINED) — the guest-cancel UPDATE nulls
    // the token while setting status = CANCELLED.

    // Defaults to APP so the regular (registered-client) booking path and existing
    // fixtures need not set it explicitly — Hibernate always emits the column in the
    // INSERT, so a null here would violate the NOT NULL despite the DB DEFAULT. The
    // guest factory overrides this to LINK.
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

    // Uniqueness is enforced by the V90 partial-unique index (UNIQUE only over non-NULL
    // rows). `unique = true` here would direct Hibernate ddl-auto to recreate the full
    // unique constraint V90 deliberately dropped, so it is intentionally omitted.
    @Column(name = "cancel_token")
    private UUID cancelToken;

    @Setter
    @Column(name = "reminder_sent", nullable = false)
    private boolean reminderSent;

    /**
     * Factory for an auto-confirmed guest (LINK) booking. Enforces the LINK
     * invariant (guest identity + cancel token non-null) before the row exists,
     * so a partially-populated guest booking can never be constructed in code —
     * mirroring the DB CHECK {@code chk_bookings_guest_fields}.
     *
     * @param guestName    OTP-verified client's first name (required)
     * @param guestSurname client's surname (optional — column is nullable)
     * @param guestPhone   E.164 phone copied from the guest JWT {@code sub} (required)
     */
    public static Booking guestBooking(
            Master master,
            MasterServiceAssignment masterService,
            Salon salon,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            BigDecimal priceAtBooking,
            int durationMinutesAtBooking,
            int bufferMinutesAtBooking,
            String guestName,
            String guestSurname,
            String guestPhone) {
        if (guestName == null || guestName.isBlank()) {
            throw new IllegalArgumentException("guestName must not be blank for a LINK booking");
        }
        if (guestPhone == null || guestPhone.isBlank()) {
            throw new IllegalArgumentException("guestPhone must not be blank for a LINK booking");
        }
        return Booking.builder()
                // No client FK: a guest booking has no registered account
                // (client_id is nullable since V89; the DB CHECK enforces LINK ⇒ client_id NULL).
                .master(master)
                .masterService(masterService)
                .salon(salon)
                .status(BookingStatus.CONFIRMED)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .priceAtBooking(priceAtBooking)
                .durationMinutesAtBooking(durationMinutesAtBooking)
                .bufferMinutesAtBooking(bufferMinutesAtBooking)
                .bookingSource(BookingSource.LINK)
                .guestName(guestName)
                .guestSurname(guestSurname)
                .guestPhone(guestPhone)
                .cancelToken(UUID.randomUUID())
                .reminderSent(false)
                .build();
    }
}
