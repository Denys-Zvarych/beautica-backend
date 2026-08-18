package com.beautica.review.entity;

import com.beautica.booking.entity.Booking;
import com.beautica.common.AuditableEntity;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
                // Backs "reviews I've received" reads and the recalculateClientRating aggregate.
                @Index(name = "idx_client_reviews_subject_client", columnList = "subject_client_id")
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

    /** The client being reviewed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_client_id", nullable = false)
    private User subjectClient;

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
}
