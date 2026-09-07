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
     * Batch existence check: the subset of the supplied booking ids that already carry a
     * provider&rarr;client review. Mirrors {@code ReviewRepository#findReviewedBookingIds}
     * (the client&rarr;provider direction) exactly — same shape, same purpose.
     *
     * <p>Used by {@code BookingService#listProviderBookings} to compute
     * {@code providerCanReviewClient} for a page of provider-scoped bookings in ONE statement
     * instead of a per-row {@link #existsByBookingId} (anti-bug §E: no N+1). The caller narrows
     * the id list to the rows that are still candidates (review-eligible, with a registered
     * client, and inside the actor's provider authority) before calling, so the {@code IN} list
     * is bounded by the page and the statement is skipped entirely when no row qualifies —
     * the same "only query when it can flip the result" short-circuit
     * {@code BookingService#getBooking} applies on the single-row path.
     *
     * @implNote <b>UNSCOPED — the caller MUST pre-narrow {@code bookingIds} to bookings the actor
     * holds provider authority over</b> (anti-bug §E-4: repository finders are unscoped by default).
     * This query has no actor predicate whatsoever: it answers "which of these bookings carry a
     * provider&rarr;client review" for ANY id list handed to it, including bookings belonging to a
     * different salon or master. That is deliberate — the authority decision is not the repository's
     * to make, and folding an {@code actorId} in here would duplicate, in JPQL, the ownership rule
     * {@code AuthorizationService#filterBookingIdsWithProviderAuthority} owns, giving two
     * implementations that can drift. It is safe ONLY because the sole caller,
     * {@code BookingService#loadProviderReviewBatch}, passes exactly the {@code withAuthority} set
     * that method returned, and returns early when it is empty. Review-existence is a weak signal
     * (a boolean per id, no review content), but it is still information about a stranger's booking,
     * and a caller that skipped the narrowing would additionally hand attacker-chosen ids straight
     * into an unbounded {@code IN} list. Any NEW caller must narrow first, or this method must gain
     * its own scoping before that caller lands. Contrast {@code SalonRepository#findIdsByIdInAndOwnerId},
     * which IS actor-scoped ({@code s.owner.id = :ownerId}) and needs no such contract.
     */
    @Query("SELECT cr.booking.id FROM ClientReview cr WHERE cr.booking.id IN :bookingIds")
    List<UUID> findReviewedBookingIds(@Param("bookingIds") List<UUID> bookingIds);

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

    /**
     * Purges every provider&rarr;client review authored ABOUT {@code subjectClientId} — the CLIENT
     * account self-deletion cascade (Phase 300 D3/D4). Unlike the client&rarr;provider direction
     * ({@code reviews}, detached and kept), these rows are DELETED outright: they rate the
     * *client*, the aggregate they feed ({@code users.avg_rating}/{@code review_count}) dies with
     * the row being deleted anyway, and by the locked two-sided-ratings decision the client is the
     * only reader of their own rating — a detached {@code client_review} would have no subject, no
     * aggregate and no reader, so retaining it would relax {@code subject_client_id}'s NOT NULL
     * (V128:19) for nobody. Must run BEFORE the {@code users} row is deleted — see {@code
     * ClientAccountDeletionService}.
     */
    @Modifying
    @Query("DELETE FROM ClientReview cr WHERE cr.subjectClient.id = :subjectClientId")
    void deleteBySubjectClientId(@Param("subjectClientId") UUID subjectClientId);
}
