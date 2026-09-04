package com.beautica.master.entity;

import com.beautica.common.AuditableEntity;
import com.beautica.salon.entity.Salon;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "masters",
        indexes = {
                // Backs owner-master lookup (Phase 12.2/12.4) and dashboard scope join.
                // DB index is partial: WHERE master_type = 'SALON_OWNER' AND is_active = true (V56).
                // JPA @Index cannot express partial WHERE — column list only.
                @Index(name = "idx_masters_salon_owner_active", columnList = "salon_id, user_id"),
                // V56: partial unique — one SALON_OWNER-type master row per user
                // (WHERE master_type = 'SALON_OWNER'). DB index is partial unique; using unique=true
                // here would generate a full unique constraint under ddl-auto=create-drop, which is
                // wrong — omit unique=true. JPA @Index cannot express partial WHERE — column list only.
                @Index(name = "idx_masters_user_owner_type", columnList = "user_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Master extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The account behind this provider — {@code null} on a DETACHED row.
     *
     * <p>Nullable since V157 (phase 294, the 2026-09-04 reversal of 267 D1). When a salon is
     * deleted the staff {@code users} row is hard-deleted; the FK is {@code ON DELETE SET NULL},
     * so this reference becomes {@code null} while the historical {@code masters} row survives as
     * a name-only stub for the bookings and reviews that still point at it.
     *
     * <p><b>Never dereference this directly on a historical read path</b> (a booking, a review, a
     * client review, a notification payload) — go through {@link #displayFirstName()} /
     * {@link #displayLastName()} / {@link #isDetached()}. Live-scope paths (roster, catalogue,
     * search, slot calculation) are {@code is_active = true}-scoped and can never observe a
     * detached row, so they read this field as before.
     */
    @Nullable
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salon_id")
    private Salon salon;

    @Enumerated(EnumType.STRING)
    @Column(name = "master_type", nullable = false)
    private MasterType masterType;

    @Column(name = "avg_rating")
    private BigDecimal avgRating;

    @Column(name = "review_count")
    private int reviewCount;

    @Column(name = "is_active")
    private boolean isActive;

    /**
     * Pre-computed minimum effective price for this master — the lowest of
     * {@code MIN(COALESCE(ms.price_override, sd.base_price))} across all
     * active service assignments. {@code null} means the master has no active
     * services.
     *
     * <p>Maintained by {@link com.beautica.service.service.ServiceCatalogService}
     * whenever a master service assignment is created, removed, or when a service
     * definition is deactivated. Read by the search query (PERF-M2, V58) to avoid
     * the per-request aggregate join.
     */
    @Nullable
    @Column(name = "min_effective_price", precision = 10, scale = 2)
    private BigDecimal minEffectivePrice;

    /**
     * Public booking-page slug — the unguessable-ish key behind
     * {@code beautica.app/book/{slug}} (Phase 13.1). Globally unique across all
     * masters (partial unique index {@code idx_masters_booking_slug}). Nullable:
     * backfilled lazily by {@link com.beautica.booking.service.BookingSlugService}
     * on first lookup or on master creation. ASCII, lowercase, &le; 60 chars.
     */
    // V86: partial unique index idx_masters_booking_slug (WHERE booking_slug IS NOT NULL)
    // owns the constraint — legacy NULL rows must stay non-unique. unique=true here would
    // generate a FULL unique constraint under ddl-auto, which is wrong (and drifts under
    // ddl-auto=validate) — omit unique=true. JPA @Index cannot express partial WHERE.
    @Nullable
    @Column(name = "booking_slug", length = 60)
    private String bookingSlug;

    // ── detachment snapshot (V157, phase 294 D2) ────────────────────────────────────────────────
    //
    // Written ONCE, by the phase 294 detach path, at the moment the staff `users` row is
    // hard-deleted; never synced afterwards. There is no trigger and no sync-on-profile-update, and
    // therefore no drift hazard: a live master's name always comes from `users`, a detached
    // master's always from here. Nothing in the codebase writes detached_at as of phase 294 — that
    // is phase 295's job.
    //
    // @Setter(AccessLevel.NONE) overrides the class-level @Setter: these three columns are only
    // ever written together, coherently, through detach(...) below — the DB CHECK
    // chk_masters_detachment_coherent (V157) refuses a half-detached row, and a public
    // setDetachedFirstName() would invite exactly that write.

    @Nullable
    @Setter(AccessLevel.NONE)
    @Column(name = "detached_first_name", length = 100)
    private String detachedFirstName;

    @Nullable
    @Setter(AccessLevel.NONE)
    @Column(name = "detached_last_name", length = 100)
    private String detachedLastName;

    @Nullable
    @Setter(AccessLevel.NONE)
    @Column(name = "detached_at")
    private Instant detachedAt;

    /**
     * The provider's first name for display, from whichever of the two states this row is in —
     * the live {@code users} row when attached, the V157 snapshot when detached.
     *
     * <p>This accessor pair is the whole point of phase 294 D3: it confines the blast radius of a
     * nullable {@code user} to one place instead of the ~25 {@code getMaster().getUser()} sites
     * that would each have had to grow their own null guard.
     */
    public String displayFirstName() {
        return user != null ? user.getFirstName() : detachedFirstName;
    }

    /** Last name for display — see {@link #displayFirstName()}. May be null in either state. */
    public String displayLastName() {
        return user != null ? user.getLastName() : detachedLastName;
    }

    /**
     * {@code true} when the staff account behind this provider has been hard-deleted and only the
     * historical name stub survives. A detached master is {@code is_active = false} and unreachable
     * from any roster, catalogue or search result — it exists solely so a client's own past receipt
     * can still name who performed the service.
     */
    public boolean isDetached() {
        return user == null;
    }

    /**
     * Stands in for {@code detached_first_name} when the hard-deleted account had no first name of
     * its own — {@code users.first_name} is nullable (V1:6) but
     * {@code chk_masters_detachment_coherent} (V157) requires the snapshot's first name, so
     * SOMETHING must be written. Applied by {@link #detach} itself so no caller can produce a row
     * the CHECK refuses.
     *
     * <p>Deliberately the same bare Ukrainian noun as
     * {@code SalonReviewResponse#DETACHED_MASTER_LABEL}, the neutral label the PUBLIC salon-review
     * list already renders for a detached master. The coincidence is intended and harmless: that
     * constant masks a name that exists, this one substitutes for a name that never did, and a
     * client reading their own past receipt sees the same generic role noun either way. They are
     * NOT the same constant, because the two decisions are independent — phase 294 cases 9 and 14
     * pin them as deliberately disagreeing about a NAMED detached master and must never be
     * "aligned".
     */
    public static final String DETACHED_FALLBACK_FIRST_NAME = "Майстер";

    /**
     * Snapshots the display name and severs the account link — the ONLY writer of the three
     * {@code detached_*} columns.
     *
     * <p>Widened from package-private to {@code public} in phase 295, which added its FIRST and
     * ONLY production caller: {@code SalonService#deleteSalonStaff}, in {@code
     * com.beautica.salon.service} — a different package, so package-private was not reachable from
     * it. Nothing else may call this. Detachment is not a general-purpose master mutation: it is
     * the second half of "the staff account was hard-deleted", and calling it without deleting the
     * matching {@code users} row leaves a live account whose provider profile has silently
     * vanished from every roster.
     *
     * <p>Ordering matters at the DB level too: {@code chk_masters_detachment_coherent} is evaluated
     * against the row as a whole, so the snapshot and the null must land in the same statement —
     * which they do, since Hibernate flushes this entity as one UPDATE.
     *
     * <p><b>A null or blank {@code firstName} is normalised to {@link #DETACHED_FALLBACK_FIRST_NAME}
     * here, not rejected</b> (phase 295). {@code users.first_name} is NULLABLE (V1:6) — an invited
     * staff member who never completed their profile genuinely has none — while
     * {@code chk_masters_detachment_coherent} requires {@code detached_first_name IS NOT NULL}. Left
     * to the caller, that mismatch takes the whole {@code DELETE /salons/&#123;id&#125;} down with a
     * constraint violation for one nameless master. Normalising INSIDE the mutator makes the CHECK
     * unviolatable by construction rather than by every caller remembering.
     *
     * @param firstName the deleted account's first name; null/blank is normalised, never rejected
     * @param lastName  the deleted account's last name; may legitimately be null and is stored as is
     * @param at        the detachment instant, from the injected {@code Clock} — never
     *                  {@code Instant.now()}
     */
    public void detach(String firstName, String lastName, Instant at) {
        this.detachedFirstName =
                (firstName == null || firstName.isBlank()) ? DETACHED_FALLBACK_FIRST_NAME : firstName;
        this.detachedLastName = lastName;
        this.detachedAt = at;
        this.user = null;
        this.isActive = false;
    }
}
