package com.beautica.booking.entity;

import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.booking.domain.GuestConsent;
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
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.lang.Nullable;

import java.time.Instant;
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
                // partial UNIQUE index (V206): guest-cancel token-HASH lookup. JPA cannot encode the
                // WHERE cancel_token_hash IS NOT NULL predicate nor the partial-uniqueness — both live
                // in V206's ux_appointments_cancel_token_hash only. V124's ux_appointments_cancel_token
                // on the retired plaintext column (always NULL since V206) goes with that column in the
                // follow-up contract migration.
                @Index(name = "ux_appointments_cancel_token_hash", columnList = "cancel_token_hash"),
                // partial UNIQUE dedup index (V124), mirroring bookings' uq_client_idempotency_key_active
                // (V18/V113): stops a client-retry race persisting duplicate appointment headers. JPA
                // cannot encode the WHERE idempotency_key IS NOT NULL AND status = 'CONFIRMED' predicate
                // nor the partial-uniqueness — both live in V124's ux_appointments_client_idempotency_key_active.
                @Index(name = "ux_appointments_client_idempotency_key_active", columnList = "client_id, idempotency_key"),
                // partial index (V139): "visits created by staff member X" + the ON DELETE RI lookup
                // for created_by_user_id — the header-level twin of bookings' idx_bookings_created_by
                // (V137). JPA cannot encode WHERE created_by_user_id IS NOT NULL, and (per the note
                // above) ddl-auto=validate does not check @Table(indexes=...) at all, so this
                // annotation is documentation, not enforcement — V139 remains the source of truth.
                // Mirrored here because an index present in the migration but absent from the entity
                // is exactly the drift a reader of this class cannot see.
                @Index(name = "idx_appointments_created_by", columnList = "created_by_user_id"),
                // partial indexes (V199, phase 373): retention-job leftover header sweep (by
                // created_at) and the internal erase-by-phone path. Predicate WHERE client_id IS NULL
                // AND guest_pii_anonymised_at IS NULL AND client_detached_at IS NULL lives in V199
                // only; documentation, not enforcement.
                @Index(name = "idx_appointments_guest_pii_due", columnList = "created_at"),
                @Index(name = "idx_appointments_guest_phone_open", columnList = "guest_phone")
        }
)
// Phase 373 (audit-fix cycle 1): mirror Booking's @DynamicUpdate. Without it a managed header
// loaded BEFORE a guest-PII erase/retention UPDATE and flushed AFTER it (any dirty field, e.g. a
// status change) would rewrite every mapped column from its stale snapshot — guest_name /
// guest_surname / guest_phone / client notes / cancel_token included — putting the wiped PII back
// (V199's CHECK now rejects that write, so it would surface as a failed flush instead). With
// @DynamicUpdate only the actually-dirty columns are written.
@DynamicUpdate
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

    // V206: only the SHA-256 of the visit's guest cancel token is stored (CancelTokenHasher). Uniqueness
    // is enforced by the V206 partial-unique index (UNIQUE only over non-NULL rows); `unique = true` here
    // would direct ddl-auto to recreate a full unique constraint — omitted, exactly as on Booking.
    //
    // @JsonIgnore: the hash is the lookup key of a guest-cancel CAPABILITY — shielded from every JSON
    // serialization path (matches InviteToken/RefreshToken token-field shielding).
    @JsonIgnore
    @Column(name = "cancel_token_hash", length = 64)
    private String cancelTokenHash;

    // The RAW cancel token, in memory only on the instance that minted it — never persisted, never
    // loaded (always null on an appointment read from the database). Set only through the builder's
    // cancelToken(UUID), which also sets cancelTokenHash.
    @JsonIgnore
    @Transient
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

    // Which staff user keyed this visit in (Phase 22.8 / V139). Orthogonal to bookingSource — the
    // enum answers "what kind of visit", this column answers "which human created it". Null for
    // every APP/LINK header; populated on every STAFF header by staffAppointment(...) below. Plain
    // UUID, not a @ManyToOne User — mirrors Booking.createdByUserId byte-for-byte so no read path is
    // tempted to lazily fetch a creator it never renders.
    //
    // V157 / phase 294 D5: the FK is now ON DELETE SET NULL, relaxed from V139's ON DELETE RESTRICT.
    // V139 carried V137:129's attribution argument verbatim and, like it, was explicitly conditioned
    // on "nothing in the app hard-deletes a user today" while deferring the case to a future erasure
    // flow. The 2026-09-04 reversal (salon deletion HARD-DELETES staff) is that flow — see
    // Booking#createdByUserId's javadoc for the full supersession note. Header and children must
    // never diverge on this clause. Consequence: a non-null value here no longer guarantees a live
    // `users` row.
    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    // ── Client-detachment snapshot (V162, phase 300 D4) ───────────────────────
    // Mirror of Booking.clientDetachedAt for the visit header — see that field's javadoc for the
    // full rationale. Header and children must never diverge on this column.
    @Nullable
    @Column(name = "client_detached_at")
    private Instant clientDetachedAt;

    /**
     * {@code true} once this visit header has been detached from its (now hard-deleted) client
     * account — mirrors {@link Booking#isClientDetached()}.
     */
    public boolean isClientDetached() {
        return clientDetachedAt != null;
    }

    // Mirror of Booking.guestPiiAnonymisedAt (V199, phase 373) — written only by native UPDATEs.
    @Nullable
    @Column(name = "guest_pii_anonymised_at", insertable = false, updatable = false)
    private Instant guestPiiAnonymisedAt;

    // Mirror of Booking.guestPrivacyAcceptedAt / guestPrivacyPolicyVersion (V202, phase 381): the header
    // and every child booking of a guest visit carry identical values. Not PII — kept by anonymisation.
    @Nullable
    @Column(name = "guest_privacy_accepted_at", updatable = false)
    private Instant guestPrivacyAcceptedAt;

    @Nullable
    @Column(name = "guest_privacy_policy_version", length = 32, updatable = false)
    private String guestPrivacyPolicyVersion;

    /**
     * Header twin of {@link Booking#detachClient(String, Instant)} — see that method's javadoc
     * for the full rationale (one state change, {@code guestPhone} left untouched).
     *
     * @param label the fixed Ukrainian sentinel («Видалений клієнт»), never user-supplied
     * @param at    the detachment instant, from the injected {@code Clock}
     */
    public void detachClient(String label, Instant at) {
        this.guestName = label;
        this.guestSurname = null;
        this.client = null;
        this.clientDetachedAt = at;
    }

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
     * @param consent      the guest's privacy consent (phase 381), identical to its child bookings';
     *                     {@code null} only for fixtures that model a pre-V202 visit
     */
    public static Appointment guestAppointment(
            Salon salon,
            String guestName,
            String guestSurname,
            String guestPhone,
            UUID cancelToken,
            GuestConsent consent) {
        return guestAppointment(salon, guestName, guestSurname, guestPhone, cancelToken, consent, null);
    }

    /**
     * Phase 385 — {@link #guestAppointment} with the guest's optional note for the WHOLE visit, stored on the header
     * ({@code appointments.client_comment}) exactly where the app's multi-service {@code POST /appointments} stores
     * it; the chained child bookings carry none. The caller normalises it; {@code null} = no note.
     */
    public static Appointment guestAppointment(
            Salon salon,
            String guestName,
            String guestSurname,
            String guestPhone,
            UUID cancelToken,
            GuestConsent consent,
            String clientComment) {
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
                .guestPrivacyAcceptedAt(consent == null ? null : consent.acceptedAt())
                .guestPrivacyPolicyVersion(consent == null ? null : consent.policyVersion())
                .clientComment(clientComment)
                .build();
    }

    /**
     * Factory for an auto-confirmed STAFF walk-in multi-service visit header (Phase 22.9) — the
     * provider-created counterpart of {@link #guestAppointment}. Enforces the STAFF walk-in invariant
     * before the row exists, mirroring {@link Booking#staffBooking} and the DB CHECK
     * {@code chk_appointment_guest_fields} (V139).
     *
     * <p><b>No cancel token from this overload.</b> Reversed by phase 412 (V226): the walk-in create
     * path uses the 6-argument overload below, which carries the visit's guest cancel token. This
     * tokenless form remains for callers that build a header with no self-cancel link. The child rows
     * ({@code Booking.staffBooking}) stay tokenless either way.
     *
     * <p><b>Surname is REQUIRED</b>, unlike the LINK branch where it stays optional (V91's
     * rationale). The two branches intentionally differ; do not unify them.
     *
     * <p>Reuses {@link Booking#requireStaffWalkInIdentity} rather than re-implementing the guard —
     * the REUSE-FIRST move that keeps the header and its chained child rows from drifting on what
     * "blank" means. Do not copy the blank checks here.
     *
     * @param salon           the visit's salon, or {@code null} for an independent-master visit
     * @param guestName       walk-in first name (required, non-blank)
     * @param guestSurname    walk-in last name (required, non-blank — unlike the LINK flow)
     * @param guestPhone      walk-in phone, E.164-normalised BY THE CALLER
     *                        ({@code chk_appointment_guest_phone_format} rejects any other shape)
     * @param createdByUserId id of the staff user creating the visit (required)
     */
    public static Appointment staffAppointment(
            Salon salon,
            String guestName,
            String guestSurname,
            String guestPhone,
            UUID createdByUserId) {
        return staffAppointment(salon, guestName, guestSurname, guestPhone, null, createdByUserId);
    }

    /**
     * Phase 412 D1 — the walk-in visit header WITH the guest's one-time cancel token, issued exactly like
     * {@link #guestAppointment}'s (raw token in memory for the SMS link, only its {@link CancelTokenHasher}
     * hash persisted). Reverses the "no cancel token" rule above: V226 lets a LIVE walk-in header (guest
     * phone present — always, by {@link Booking#requireStaffWalkInIdentity}) carry the token; the child
     * {@code bookings} rows stay tokenless (the visit token is the only one any lookup accepts).
     *
     * @param cancelToken the visit-level one-time cancel token, or {@code null} for a tokenless header
     */
    public static Appointment staffAppointment(
            Salon salon,
            String guestName,
            String guestSurname,
            String guestPhone,
            UUID cancelToken,
            UUID createdByUserId) {
        Booking.requireStaffWalkInIdentity(guestName, guestSurname, guestPhone, createdByUserId);
        return Appointment.builder()
                // No client FK: the walk-in mode has no registered account
                // (the V139 CHECK enforces STAFF walk-in => client_id NULL).
                .salon(salon)
                .status(BookingStatus.CONFIRMED)
                .bookingSource(BookingSource.STAFF)
                .guestName(guestName)
                .guestSurname(guestSurname)
                .guestPhone(guestPhone)
                // Phase 412: the visit's guest cancel token (null only via the 5-argument overload).
                .cancelToken(cancelToken)
                .createdByUserId(createdByUserId)
                .build();
    }

    /**
     * Lombok fills in the remaining builder methods. {@code cancelToken(UUID)} is hand-written so the
     * persisted {@link #cancelTokenHash} can never drift from the in-memory raw {@link #cancelToken}
     * (V206 — only the hash is stored).
     */
    public static class AppointmentBuilder {

        public AppointmentBuilder cancelToken(UUID cancelToken) {
            this.cancelToken = cancelToken;
            this.cancelTokenHash = cancelToken == null ? null : CancelTokenHasher.hash(cancelToken);
            return this;
        }
    }
}
