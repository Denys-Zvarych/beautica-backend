package com.beautica.review.repository;

import com.beautica.review.entity.ClientReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Data access for {@link ClientReview} — the master&rarr;client review direction (Phase 27.4-27.6).
 */
public interface ClientReviewRepository extends JpaRepository<ClientReview, UUID> {

    // Duplicate-review guard (Phase 27.5) — cheaper than findByBookingId(...).isPresent(),
    // mirrors ReviewRepository#existsByBookingId.
    boolean existsByBookingId(UUID bookingId);

    /**
     * Recalculates {@code users.avg_rating}/{@code users.review_count} for one client, mirroring
     * {@code ReviewRepository#recalculateMasterRating}'s single-pass native aggregate exactly
     * (same {@code COALESCE}-to-{@code NULL} no-reviews handling — unlike the master/salon
     * variants, which COALESCE to 0.00, {@code users.avg_rating} stays {@code NULL} with zero
     * reviews per this migration's locked "nullable, no default" contract, mirroring
     * {@code Salon#avgRating}'s V103 precedent, not {@code Master#avgRating}'s NOT NULL DEFAULT
     * 0.00 one).
     *
     * <p>{@code flushAutomatically = true}: flush the pending {@code client_reviews} INSERT before
     * this UPDATE runs, so the just-created review is included in the aggregate.
     * {@code clearAutomatically = true}: evict {@code User} from the L1 cache so a subsequent read
     * in the same persistence context sees the updated {@code avgRating}/{@code reviewCount}.
     *
     * <p>Called only from the {@code AFTER_COMMIT}/{@code REQUIRES_NEW} event listener — never
     * inline in the review-creation transaction (Anti-Bug §F.2/§F.8).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE users u
               SET avg_rating   = agg.avg_rating,
                   review_count = agg.cnt
              FROM (SELECT AVG(cr.rating::numeric) AS avg_rating,
                           COUNT(*) AS cnt
                      FROM client_reviews cr
                     WHERE cr.subject_client_id = :clientId) agg
             WHERE u.id = :clientId
            """, nativeQuery = true)
    void recalculateClientRating(@Param("clientId") UUID clientId);

    /**
     * Rating-bucket counts for a client's received reviews, backing
     * {@code UserRatingResponse}'s {@code ratingDistribution} (Phase 27.x, {@code GET
     * /users/me/rating}). Only buckets with at least one review are returned — {@code
     * UserService.getMyRating} zero-fills the missing 1-5 buckets so every star rating is
     * present in the response, mirroring {@code ReviewRepository#countByMasterIdGroupByRating}
     * exactly. No new index needed: {@code client_reviews.subject_client_id} is already indexed
     * ({@code idx_client_reviews_subject_client}, created in {@code
     * V128__create_client_reviews_and_user_rating.sql:33}, declared on the entity too), and this
     * query reuses the very same column {@link #recalculateClientRating} filters on. That index
     * covers the {@code WHERE subject_client_id = :clientId} predicate but not the {@code GROUP BY
     * cr.rating}, so Postgres runs a small Hash Aggregate over the filtered rows — deliberate, and
     * no composite index is warranted: the aggregated set is bounded by a single client's own
     * lifetime review count, not by table-wide growth, and the master/salon twins ({@code
     * ReviewRepository#countByMasterIdGroupByRating} / {@code #countBySalonIdGroupByRating}) take
     * the same approach.
     */
    @Query("SELECT cr.rating AS rating, COUNT(cr) AS count FROM ClientReview cr WHERE cr.subjectClient.id = :clientId GROUP BY cr.rating")
    List<RatingCountProjection> countBySubjectClientIdGroupByRating(@Param("clientId") UUID clientId);
}
