package com.beautica.booking.entity;

import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.common.AuditableEntity;
import com.beautica.common.exception.BusinessException;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.MasterServiceAssignment;
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
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

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
                // NOTE: idx_bookings_salon_completed_starts_at (V43, salon_id + starts_at WHERE
                // status='COMPLETED') was DROPPED in V123 and its mirror removed here so this
                // annotation block does not drift from the schema (§E-6). It was provably subsumed
                // by idx_bookings_salon_status_starts_at (V22/V113 — salon_id, status, starts_at
                // DESC WHERE status IN ('CONFIRMED','COMPLETED')): the dashboard revenue query
                // pins status='COMPLETED', so all four of its predicates remain an Index Cond on
                // the surviving index with identical row/heap-block counts. Do not re-add.
                // The master-scope sibling above is NOT redundant — there is no
                // (master_id, status, starts_at) partial index for it to fall back to.
                // partial UNIQUE index (V90): cancel-token lookup for the public guest-cancel page.
                // JPA cannot encode WHERE cancel_token IS NOT NULL nor the partial-uniqueness —
                // the predicate + UNIQUE live in V90 only (V90 dropped the V89 full unique
                // constraint + non-unique partial index in favour of one partial-unique index).
                @Index(name = "idx_bookings_cancel_token", columnList = "cancel_token"),
                // partial index (V89): hourly guest-reminder sweep.
                // JPA cannot encode WHERE booking_source='LINK' AND reminder_sent=FALSE —
                // predicate lives in V89 only; do NOT treat this annotation as authoritative.
                @Index(name = "idx_bookings_reminder", columnList = "starts_at"),
                // idx_bookings_master_client_starts_at (V93) served the per-client "latest
                // booking" LATERAL subquery formerly in FavoriteRepository.findFavoriteMasterRows.
                // DROPPED by V144 once the favourites category axis was reversed from "last
                // booked category" to "every category the provider offers" — that LATERAL (and
                // its salon-arm sibling) was the only query ever combining (master_id, client_id)
                // in one predicate, and it was deleted along with LastBookedCategoryLookup. Do NOT
                // re-add this @Index entry without also re-creating the migration: Hibernate 6.5's
                // ddl-auto=validate does NOT check @Table(indexes=...) against the real schema, so
                // an orphaned annotation here would be silently cosmetic, not caught at boot.
                // Removing the annotation is still required so the entity doesn't lie about the
                // schema to the next reader; the regression guard is
                // BookingReviewQueryIndexesMigrationTest's direct pg_indexes check.
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
                @Index(name = "idx_bookings_client_slot_overlap", columnList = "client_id, starts_at, ends_at"),
                // partial index (V125): "fetch all rows of this multi-service visit" lookup.
                // JPA cannot encode WHERE appointment_id IS NOT NULL — the predicate lives in V125
                // only; this annotation mirrors the column for reader accuracy, not enforcement.
                @Index(name = "idx_bookings_appointment", columnList = "appointment_id"),
                // partial index (V137): "bookings created by staff member X" + the ON DELETE RI lookup for
                // created_by_user_id. JPA cannot encode WHERE created_by_user_id IS NOT NULL — the predicate
                // lives in V137 only; documentation, not enforcement.
                @Index(name = "idx_bookings_created_by", columnList = "created_by_user_id")
        }
)
// Root-cause fix (G1, cycle-7 audit 2026-08-03) for the entity-staleness/terminal-state-
// resurrection defect class the F1 freshness rechecks (BookingRepository#existsConfirmedById,
// #findConfirmedIdsByAppointmentId) treat symptomatically throughout this package. Without
// @DynamicUpdate, Hibernate's default UPDATE writes EVERY mapped column from the in-memory
// snapshot, regardless of which fields the current transaction actually touched — so a
// status-only write (cancel/decline/not-complete) also re-writes startsAt/endsAt from its
// pre-transaction snapshot, and a time-only write (reschedule) also re-writes status. Two
// concurrent writers of the SAME row, each racing to save a full snapshot taken before the
// other's commit, can therefore have the LOSER's stale columns silently overwrite the WINNER's
// fresh ones on unrelated fields — a status-changing write "resurrecting" a status the other
// writer already moved past, or a status-changing write clobbering a reschedule's new time back
// to the old one. @DynamicUpdate makes Hibernate compute the actual dirty-property set at flush
// time and include ONLY those columns in the UPDATE, so a cancel's UPDATE never mentions
// starts_at/ends_at and a reschedule's UPDATE never mentions status — the two are now
// column-disjoint and cannot clobber each other regardless of commit order.
//
// This does NOT replace the F1 freshness rechecks scattered through BookingService /
// AppointmentTransitionService — it changes what a LOST race looks like, from a silent partial
// overwrite to a clean, retryable 409. The rechecks still exist to reject the operation whose
// precondition (the target being CONFIRMED) is already stale by the time its own write would
// run; @DynamicUpdate is what makes the write that DOES proceed safe to interleave on disjoint
// columns even without a recheck (see the standalone-booking analysis in BookingService, where
// this is now the ONLY guard on that path). See each recheck's own Javadoc
// ("carries no @DynamicUpdate" — now stale prose describing the pre-fix world) for the exploit
// each one independently closes.
//
// Precedent: User.java:22 already carries this annotation for the analogous reason on that
// entity. Verified against jakarta.persistence lifecycle callbacks (none in this class or in
// AuditableEntity — @CreationTimestamp/@UpdateTimestamp are Hibernate-managed value generators,
// not @PrePersist/@PreUpdate hooks, and updatedAt's regenerated value does not widen the affected
// column set) and against the V18/V113 no_overlapping_bookings GIST EXCLUDE constraint (Postgres
// re-validates an EXCLUDE constraint against whatever the row's post-UPDATE column values are,
// which is correct regardless of whether a given UPDATE statement re-asserted a column's existing
// value or omitted it — omitting an unchanged column can never make the constraint see a
// DIFFERENT value than it would have seen otherwise, so a narrower column list cannot weaken the
// constraint's coverage).
@Entity
@DynamicUpdate
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
    //
    // @JsonIgnore: cancel_token is a guest-cancel CAPABILITY token — whoever holds it can cancel
    // this booking. With multi-service visits (BE-7) each item carries its own per-item token, so
    // there are now N per visit; shield every JSON serialization path so a future accidental
    // entity-return can never leak them (mirrors Appointment.cancelToken).
    @JsonIgnore
    @Column(name = "cancel_token")
    private UUID cancelToken;

    @Setter
    @Column(name = "reminder_sent", nullable = false)
    private boolean reminderSent;

    // ── Staff-booking provenance (Phase 22.1 / V137) ──────────────────────────
    /**
     * The staff user (SALON_OWNER / SALON_ADMIN) who keyed this booking in, or {@code null}
     * for a self-service APP/LINK booking and for all pre-V137 history.
     *
     * <p>Orthogonal to {@link #bookingSource}: the enum says <em>what kind</em> of booking
     * this is, this column says <em>which human</em> created it. That is why STAFF is a
     * single source value rather than a family of them.
     *
     * <p>Stored as a raw id, deliberately NOT a {@code @ManyToOne User} — nothing on the
     * booking read path renders the creating staff member, so an association here would only
     * add a lazy proxy (and an N+1 risk) to every booking load. The FK integrity lives in the
     * DB ({@code REFERENCES users(id) ON DELETE RESTRICT}, V137).
     *
     * <p>{@code RESTRICT}, not {@code SET NULL}: the column exists for attribution, and a NULLed
     * creator is indistinguishable from a pre-V137 row that never had one — so {@code SET NULL}
     * would let deleting the account under suspicion erase the audit trail undetectably. Nothing
     * in the app hard-deletes a user today (accounts are deactivated), so this constraint is
     * unreachable in practice; a future GDPR erasure flow must anonymise the creating user rather
     * than delete the row. See V137's comment for the full rationale.
     *
     * <p>Nullability is deliberately NOT enforced by {@code chk_bookings_guest_fields} — it is
     * a soft, application-layer expectation for STAFF rows only.
     *
     * <p>{@code @JsonIgnore} for the same reason {@link #cancelToken} carries it: this is an
     * internal identifier (which employee keyed the booking in) that no client-facing DTO renders,
     * so a stray entity-return can never leak it.
     */
    @JsonIgnore
    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    // ── Multi-service single-visit aggregate (BE-1 / V125) ────────────────────
    // Nullable by design: a legacy single-service booking has no appointment (appointment_id stays
    // NULL). A multi-service visit (BE-3) groups its N chained booking rows under one Appointment
    // header. This is the OWNING side of the FK. LAZY so existing single-service reads never
    // trigger an extra load — nothing in BE-1 traverses this association. Purely additive: no
    // existing field, query or the no_overlapping_bookings EXCLUDE constraint is affected.
    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

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
     * Factory for an auto-confirmed staff walk-in (STAFF) booking — a walk-in / phone booking
     * entered by a {@code SALON_OWNER}/{@code SALON_ADMIN} on behalf of one of their masters.
     *
     * <p>Mirrors the walk-in mode of the DB CHECK {@code chk_bookings_guest_fields} (V137):
     * no {@code client} FK, all three identity fields required, and {@code cancelToken} left
     * {@code null} (a staff booking has no self-service guest cancel link). Enforcing it here
     * as well means a half-populated STAFF row can never be constructed in code.
     *
     * <p>Unlike {@link #guestBooking}, {@code guestSurname} is <strong>required</strong>: the
     * LINK flow treats a surname as optional, the staff walk-in flow does not (locked product
     * decision). The two branches of the CHECK differ for exactly this reason.
     *
     * <p>The linked-platform-CLIENT identity mode is permitted by the V137 CHECK but is
     * deferred at the service layer (re-scoped Phase 22.3); enabling it later needs a sibling
     * factory here and <em>no</em> migration.
     *
     * <p>{@code status} is {@code CONFIRMED} at creation, identically to APP and LINK
     * (track 24.x auto-confirm). Snapshot parameters match {@link #guestBooking} exactly so
     * the 22.2 service fills them the same way.
     *
     * @param guestName         walk-in client's first name (required)
     * @param guestSurname      walk-in client's last name (required — unlike the LINK flow)
     * @param guestPhone        walk-in client's phone, E.164-normalised by the caller
     *                          (required; {@code chk_bookings_guest_phone_format} rejects any
     *                          other shape)
     * @param createdByUserId   id of the staff user creating the booking (required)
     * @param priceMaxAtBooking frozen RANGE ceiling, or {@code null} for a single price
     *                          (see {@link #priceMaxAtBooking})
     */
    public static Booking staffBooking(
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
            String guestPhone,
            UUID createdByUserId) {
        requireStaffWalkInIdentity(guestName, guestSurname, guestPhone, createdByUserId);
        return Booking.builder()
                // No client FK: the walk-in mode has no registered account
                // (the V137 CHECK enforces STAFF walk-in => client_id NULL).
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
                .bookingSource(BookingSource.STAFF)
                .guestName(guestName)
                .guestSurname(guestSurname)
                .guestPhone(guestPhone)
                // cancelToken stays null: no guest self-cancel link for a staff booking.
                .createdByUserId(createdByUserId)
                .reminderSent(false)
                .build();
    }

    /**
     * <b>{@code BusinessException(BAD_REQUEST)}, never {@code IllegalArgumentException}</b>
     * (security LOW, 2026-08-18). {@code GlobalExceptionHandler} has no
     * {@code IllegalArgumentException} handler, so one escaping this factory falls to the
     * {@code Exception.class} catch-all: a <b>500 plus a full ERROR stack trace</b> for what is, by
     * construction, a missing required input. These four values are the same ones the Phase 22.2
     * command records reject as a 400 ({@code StaffBookingCommand}, {@code StaffClientRef.Guest},
     * {@code StaffBookingScope}), so the status must agree rather than depend on which layer noticed.
     *
     * <p>No caller can reach these branches today — {@code StaffClientRef.Guest} rejects blank
     * identity fields, {@code UkrainianPhoneNormalizer#toE164} rejects an unusable phone and
     * {@code StaffBookingService} rejects a null actor before the load. That is exactly why the
     * status matters: this is the backstop for the day a second caller appears, and a backstop that
     * answers 500 is not one.
     *
     * <p><b>Package-private, not private</b> (Phase 22.9): {@link Appointment#staffAppointment} in
     * this same package reuses this exact validation for the visit header, so the header and its
     * chained child rows cannot drift on what "blank" means. Do not fork a copy onto
     * {@code Appointment} — see that factory's javadoc.
     */
    static void requireStaffWalkInIdentity(
            String guestName, String guestSurname, String guestPhone, UUID createdByUserId) {
        requireStaffText(guestName, "guestName");
        requireStaffText(guestSurname, "guestSurname");
        requireStaffText(guestPhone, "guestPhone");
        if (createdByUserId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "createdByUserId must not be null for a STAFF booking");
        }
    }

    private static void requireStaffText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    field + " must not be blank for a STAFF walk-in booking");
        }
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
