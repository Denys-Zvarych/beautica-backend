package com.beautica.review.entity;

import com.beautica.booking.entity.Booking;
import com.beautica.common.AuditableEntity;
import com.beautica.master.entity.Master;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;
import java.util.UUID;

/**
 * A provider's (master's) 1-5 star rating of a CLIENT after a completed booking — the reverse
 * direction of {@link Review} (client rates provider). Phase 27.4-27.6 — REVERSES the previously
 * deferred/out-of-scope status of master&rarr;client reviews.
 *
 * <p>Deliberately a SEPARATE table from {@code reviews}, not a reuse: {@code reviews} carries
 * {@code UNIQUE(booking_id)} already claimed by the client-authored review of the SAME booking
 * (client rates provider), so a master-authored review of the same booking (provider rates
 * client) cannot share that row — {@code client_reviews} has its own {@code UNIQUE(booking_id)}
 * (constraint {@code uq_client_reviews_booking}), independent of {@code reviews}'.
 *
 * <p>Aggregates onto {@code users.avg_rating}/{@code users.review_count} (V128), mirroring
 * {@code masters.avg_rating}/{@code masters.review_count} (V4) and
 * {@code salons.avg_rating}/{@code salons.review_count} (V103) — maintained by {@link
 * com.beautica.review.repository.ClientReviewRepository#recalculateClientRating(UUID)}, called
 * from an {@code AFTER_COMMIT}/{@code REQUIRES_NEW} listener (Anti-Bug §F.2/§F.8), never inline in
 * the write transaction.
 */
@Entity
@Table(
        name = "client_reviews",
        indexes = {
                // Backs "reviews I've received" reads and the recalculateClientRating aggregate. PARTIAL in the DB
                // since V270 (WHERE subject_client_id IS NOT NULL — phone-keyed guest rows carry NULL); @Index cannot
                // declare the predicate, the name is mirrored for drift checks.
                @Index(name = "idx_client_reviews_subject_client", columnList = "subject_client_id"),
                // V159 (phase 295 audit HIGH-3). Mirrors the DB index so ddl-auto=validate catches
                // drift. Backs both the third EXISTS arm of
                // MasterRepository#findIdsWithHistoricalReferences and the RI check Postgres runs
                // on DELETE FROM masters — client_reviews_author_master_id_fkey is NO ACTION, and
                // Postgres never indexes the referencing side of an FK on its own. Without it both
                // Seq Scanned this table once per master of the salon being deleted.
                @Index(name = "idx_client_reviews_author_master", columnList = "author_master_id")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@Builder
public class ClientReview extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    /**
     * The registered client being reviewed, or {@code null} for a guest / walk-in subject (B546, REV-38): exactly one of
     * this and {@link #subjectGuestPhone} is set (V269 {@code chk_client_reviews_one_subject}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_client_id")
    private User subjectClient;

    /**
     * B546 (REV-38) — the E.164 guest phone of a rated guest / walk-in booking that has no account. Moves to
     * {@link #subjectClient} when the phone is attached (REV-39, a native UPDATE); deleted with the guest PII (REV-40).
     * Backed by the partial index {@code idx_client_reviews_subject_guest_phone} (V269), which {@code @Index} cannot
     * express. Personal data: never logged, never in a response.
     */
    @Size(max = 20)
    @Column(name = "subject_guest_phone", length = 20)
    private String subjectGuestPhone;

    /** The provider (master) who wrote the review. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_master_id", nullable = false)
    private Master authorMaster;

    /** Null for an independent master (no salon affiliation at booking time). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salon_id")
    private Salon salon;

    @NotNull
    @Min(1)
    @Max(5)
    @Column(nullable = false)
    private Short rating;

    @Size(max = 2000)
    @Column(length = 2000)
    private String comment;

    /**
     * Phase 396 D1. Public reads and rating aggregates include only {@link ModerationStatus#VISIBLE}. Changed only
     * through {@link #moderate}, by {@code ReviewModerationService}.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 16)
    private ModerationStatus moderationStatus = ModerationStatus.VISIBLE;

    /**
     * B546 (REV-38) — a rating of a guest / walk-in booking with no account, keyed to its E.164 phone. The registered
     * form stays the builder with {@code subjectClient}.
     */
    public static ClientReview forGuest(Booking booking, String guestPhone, Master authorMaster, Salon salon,
                                        short rating, String comment) {
        return ClientReview.builder()
                .booking(Objects.requireNonNull(booking, "booking"))
                .subjectGuestPhone(Objects.requireNonNull(guestPhone, "guestPhone"))
                .authorMaster(Objects.requireNonNull(authorMaster, "authorMaster"))
                .salon(salon)
                .rating(rating)
                .comment(comment)
                .build();
    }

    /**
     * Moves this review to {@code target}. {@link ModerationStatus#REMOVED} is terminal: a late RESTORE / DISMISS
     * (e.g. on a report that raced the takedown) never brings removed content back.
     *
     * @return {@code true} when the status changed (the caller then publishes a recalculation event)
     */
    public boolean moderate(ModerationStatus target) {
        if (moderationStatus == target || moderationStatus == ModerationStatus.REMOVED) {
            return false;
        }
        moderationStatus = target;
        return true;
    }
}
