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
                // LOAD-BEARING (Phase 26.8 audit): this index intentionally has NO id column, unlike
                // idx_bookings_master_starts_at (V117). BookingMyBookingsSortIT
                // #should_maintainDeterministicPagination_when_manyBookingsShareIdenticalStartsAt
                // proves the code-level `id ASC` tiebreaker is necessary ONLY because the
                // ?status=COMPLETED query plan lands here, where ties resolve by heap physical order
                // instead of index key order. Widening this index to add id (as V117 did to its
                // siblings) would silently defang that test — it would stay green whether or not the
                // tiebreaker exists. Guarded by
                // V118DropPriceSortIndicesMigrationTest#should_notContainIdColumn_when_completedStartsAtIndexInspected.
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
                // direction — V93 declares starts_at DESC; this annotation mirrors the columns for
                // reader accuracy only. It is documentation, not enforcement: empirically verified
                // (Phase 26.8 audit), Hibernate 6.5's ddl-auto=validate does NOT check
                // @Table(indexes=...) against the real schema — see the note at
                // idx_bookings_master_service_starts_at below for the full finding.
                @Index(name = "idx_bookings_master_client_starts_at", columnList = "master_id, client_id, starts_at"),
                // composite index (V95, widened by V117): BookingRepository.findClientBookingDetails
                // unfiltered shape — WHERE client_id = ? ORDER BY starts_at DESC, id ASC. JPA cannot
                // encode the DESC sort direction nor the trailing id tiebreaker column order — V117
                // declares (client_id, starts_at DESC, id ASC); this annotation mirrors the columns
                // for reader accuracy only, NOT because ddl-auto=validate enforces it (see the note
                // at idx_bookings_master_service_starts_at below). The trailing id (Phase 26.6) lets
                // the index alone satisfy the Phase 26.3 `id ASC` tiebreaker at any OFFSET with no
                // extra sort node — see V117's javadoc-style comment for the EXPLAIN evidence.
                @Index(name = "idx_bookings_client_starts_at", columnList = "client_id, starts_at, id"),
                // composite index (V18, widened by V117): the provider "Мої записи" default-sort /
                // date-range shapes — WHERE master_id = ? [AND starts_at BETWEEN ...] ORDER BY
                // starts_at DESC, id ASC. Same V117 trailing-id widening as
                // idx_bookings_client_starts_at above, for the master-scope sibling query family
                // (BookingRepositoryCustomImpl.findIdPage).
                @Index(name = "idx_bookings_master_starts_at", columnList = "master_id, starts_at, id"),
                // idx_bookings_master_price_id / idx_bookings_client_price_id (V117) served
                // GET /bookings/me?sort=priceAtBooking. DROPPED by V118 (Phase 26.8) once mobile
                // Phase 7.8 deleted that sort's only caller (the provider sort sheet, retired for a
                // timeline where a card's position IS its time). BookingService's
                // SORTABLE_BOOKING_PROPERTIES was narrowed to {startsAt} in the same change, so the
                // query shape these indices served can never run again. Do NOT re-add these @Index
                // entries without also re-creating the migration: empirically (Phase 26.8 audit),
                // Hibernate 6.5's ddl-auto=validate does NOT check @Table(indexes=...) against the
                // real schema — an orphaned annotation here would be silently cosmetic, not caught
                // at boot, contrary to the "ddl-auto=validate sees the index exists" comments
                // elsewhere in this class (those predate this finding and describe the documentation
                // *intent*, not a verified enforcement mechanism). Removing the annotation is still
                // required so the entity doesn't lie about the schema to the next reader; the actual
                // regression guard is V118DropPriceSortIndicesMigrationTest's direct pg_indexes check.
                // composite index (V117): GET /bookings/me?serviceId=... with no date range narrowing
                // it — Phase 26.4's masterServiceIdIn predicate otherwise applies as a post-scan
                // Filter on idx_bookings_master_starts_at, which loses early-LIMIT termination for a
                // rare single-service filter (measured: Rows Removed by Filter scaling with the
                // master's TOTAL row count, not the service's). Converts that shape to a direct
                // index-range seek on (master_id, master_service_id).
                @Index(name = "idx_bookings_master_service_starts_at", columnList = "master_id, master_service_id, starts_at"),
                // partial index (V112, predicate narrowed by V113): client-scoped cross-master/salon
                // overlap check (BookingRepository.findFirstConflictingClientBookingId[Excluding]).
                // JPA cannot encode WHERE status = 'CONFIRMED' AND client_id IS NOT NULL — the
                // predicate lives in V113 only; mirrors idx_bookings_master_slot_overlap (V26) but
                // keyed by client_id. client_id IS NOT NULL excludes guest (LINK) bookings, which
                // always have a null client_id (V89 chk_bookings_guest_fields) and can never match
                // this query's client_id equality — indexing them would be pure write amplification.
                @Index(name = "idx_bookings_client_slot_overlap", columnList = "client_id, starts_at, ends_at")
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

    /**
     * Frozen RANGE ceiling (V119), the companion snapshot to {@link #priceAtBooking}'s floor.
     *
     * <p>{@code null} means "single price" — render {@code priceAtBooking} alone. That is the
     * majority case: every FIXED service, and every RANGE service whose master set a
     * {@code priceOverride} (the override IS the agreed price, so there is no band). The value is
     * computed once, on the create paths, by
     * {@link com.beautica.booking.dto.BookingPriceRange#resolveCeiling}.
     *
     * <p>Like {@code priceAtBooking} this is a SNAPSHOT and is deliberately never recomputed —
     * not on read, and not by {@link #reschedule}. A provider editing their service must not
     * retroactively rewrite the band a client already agreed to.
     */
    @Column(name = "price_max_at_booking")
    private BigDecimal priceMaxAtBooking;

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

    // Phase 25.4 (V114) — the client's OWN cancellation note, written by cancelBooking
    // (CONFIRMED -> CANCELLED). Distinct from clientComment (the booking-CREATION note set once
    // at POST /bookings and legitimately present on CONFIRMED/COMPLETED rows too — see V114's
    // deliberate absence of a CHECK on that column). Per the locked "notes are visible to all
    // sides" decision, this note is shown to the PROVIDER, mirroring how providerComment is
    // shown to the client on DECLINED/NOT_COMPLETED. chk_client_cancellation_note_status (V114)
    // enforces it is only ever non-null on a CANCELLED row.
    @Setter
    @Column(name = "client_cancellation_note", length = 1000)
    private String clientCancellationNote;

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
     * @param guestName         OTP-verified client's first name (required)
     * @param guestSurname      client's surname (optional — column is nullable)
     * @param guestPhone        E.164 phone copied from the guest JWT {@code sub} (required)
     * @param priceMaxAtBooking frozen RANGE ceiling, or {@code null} for a single price
     *                          (see {@link #priceMaxAtBooking})
     */
    public static Booking guestBooking(
            Master master,
            MasterServiceAssignment masterService,
            Salon salon,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            BigDecimal priceAtBooking,
            BigDecimal priceMaxAtBooking,
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
                .priceMaxAtBooking(priceMaxAtBooking)
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

    /**
     * Moves this booking to a new time window.
     *
     * <p>Per the track 24.x locked state machine (auto-confirm), a booking is always
     * {@code CONFIRMED} from creation, and rescheduling never reverts it — there is no
     * provider re-approval queue to re-enter. {@code priceAtBooking},
     * {@code priceMaxAtBooking} and {@code durationMinutesAtBooking} are frozen at the
     * original booking and are deliberately NOT recomputed here — the caller computes
     * {@code newEndsAt} from the frozen duration (+ buffer) before invoking this method.
     * Moving a booking in time must never re-price it, and in particular must never
     * re-derive the band from the service's CURRENT price_type/price_max.
     *
     * <p>Allowed source-state and slot/overlap validation are the caller's
     * responsibility (see {@code BookingService.rescheduleBooking}); this method only
     * moves the time window once those checks have passed.
     *
     * @param newStartsAt the new start instant (already validated by the service)
     * @param newEndsAt   the new end instant ({@code newStartsAt + duration + buffer})
     */
    public void reschedule(OffsetDateTime newStartsAt, OffsetDateTime newEndsAt) {
        this.startsAt = newStartsAt;
        this.endsAt = newEndsAt;
    }
}
