package com.beautica.review.repository;

import com.beautica.review.dto.MyReviewResponse;
import com.beautica.review.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    // Cheaper than findByBookingId(...).isPresent() — derived as SELECT COUNT(*) > 0.
    boolean existsByBookingId(UUID bookingId);

    /**
     * Batch existence check: returns the subset of the supplied booking ids that already
     * have a review. Used by {@code BookingService} to compute {@code canReview} for a page
     * of provider-scoped bookings in ONE query (§E: no per-row {@code existsByBookingId}).
     * The CLIENT list path does not use this — its projection carries {@code reviewExists}
     * inline via a {@code LEFT JOIN}.
     */
    @Query("SELECT r.booking.id FROM Review r WHERE r.booking.id IN :bookingIds")
    java.util.List<UUID> findReviewedBookingIds(@Param("bookingIds") java.util.List<UUID> bookingIds);

    // Two-query pattern — avoids HHH90003004 (Hibernate in-memory pagination warning).
    // Step 1: paginate on IDs only — SQL LIMIT/OFFSET, no JOIN FETCH.
    @Query(value = """
            SELECT r.id FROM Review r
            WHERE r.master.id = :masterId
            ORDER BY r.createdAt DESC
            """,
           countQuery = """
            SELECT COUNT(r) FROM Review r
            WHERE r.master.id = :masterId
            """)
    Page<UUID> findIdsByMasterIdOrderByCreatedAtDesc(@Param("masterId") UUID masterId, Pageable pageable);

    /**
     * Batch-hydrates a bounded set of reviews with their associations.
     *
     * <p><strong>Result order is undefined.</strong> Callers must reorder the returned list
     * using the ID sequence from {@link #findIdsByMasterIdOrderByCreatedAtDesc} — for example,
     * by building a {@code Map<UUID, Review>} and streaming the ID list through it.
     *
     * <p>No {@code ORDER BY} clause: ordering is driven by the caller's ID stream,
     * which is cheaper than a redundant DB sort on an unindexed set.
     */
    @EntityGraph(attributePaths = {"booking", "client", "master"})
    @Query("SELECT r FROM Review r WHERE r.id IN :ids")
    List<Review> findByIdsWithGraph(@Param("ids") List<UUID> ids);

    /**
     * Principal-scoped list of the reviews authored by one client, backing
     * {@code GET /reviews/me} (Phase 19.4). Single constructor-expression query: joins
     * {@code review.master.user} (master name) and {@code review.booking.masterService
     * .serviceDefinition} (service name) so the whole {@link MyReviewResponse} row is built in
     * ONE SQL statement — no N+1, no lazy traversal at mapping time.
     *
     * <p>Filters strictly on {@code r.client.id = :clientId}: the caller-supplied id always
     * originates from the authenticated principal, never a request parameter (principal scoping).
     * {@code ORDER BY r.createdAt DESC} is hardcoded; the service strips any caller-supplied
     * sort to avoid a {@code PropertyReferenceException} leaking entity property names.
     */
    @Query(value = """
            SELECT new com.beautica.review.dto.MyReviewResponse(
                r.id,
                m.id,
                mu.firstName,
                mu.lastName,
                sd.name,
                CAST(r.rating AS integer),
                r.comment,
                r.createdAt,
                b.id
            )
            FROM Review r
            JOIN r.master m
            JOIN m.user mu
            JOIN r.booking b
            JOIN b.masterService ms
            JOIN ms.serviceDefinition sd
            WHERE r.client.id = :clientId
            ORDER BY r.createdAt DESC
            """,
           countQuery = """
            SELECT COUNT(r) FROM Review r
            WHERE r.client.id = :clientId
            """)
    Page<MyReviewResponse> findMyReviews(@Param("clientId") UUID clientId, Pageable pageable);

    // Named to distinguish from the inherited JpaRepository.findById (which is lazy).
    // Use this method whenever ReviewResponse.from() will be called on the result.
    @EntityGraph(attributePaths = {"booking", "client", "master"})
    @Query("SELECT r FROM Review r WHERE r.id = :id")
    Optional<Review> findByIdWithAssociations(@Param("id") UUID id);

    // Single-pass native SQL: FROM subquery aggregates AVG + COUNT in one index scan.
    // COALESCE handles the no-reviews edge case (AVG of empty set = NULL → 0.00).
    // flushAutomatically = true: flush pending INSERT before UPDATE so new review is included.
    // clearAutomatically = true: evict Master from L1 cache so next read sees updated avgRating.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE masters m
               SET avg_rating   = agg.avg_rating,
                   review_count = agg.cnt
              FROM (SELECT COALESCE(AVG(r.rating::numeric), 0) AS avg_rating,
                           COUNT(*) AS cnt
                      FROM reviews r
                     WHERE r.master_id = :masterId) agg
             WHERE m.id = :masterId
            """, nativeQuery = true)
    void recalculateMasterRating(@Param("masterId") UUID masterId);

    /**
     * Salon-scoped symmetric twin of {@link #recalculateMasterRating}, added for Phase 13.6
     * (Public Salon Profile). Same single-pass native aggregate, same COALESCE-to-zero
     * no-reviews handling, same {@code clearAutomatically}/{@code flushAutomatically}
     * contract. Called from {@link com.beautica.review.event.ReviewEventListener#onReviewCreated}
     * only when the reviewed booking carried a non-null salon id.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE salons s
               SET avg_rating   = agg.avg_rating,
                   review_count = agg.cnt
              FROM (SELECT COALESCE(AVG(r.rating::numeric), 0) AS avg_rating,
                           COUNT(*) AS cnt
                      FROM reviews r
                     WHERE r.salon_id = :salonId) agg
             WHERE s.id = :salonId
            """, nativeQuery = true)
    void recalculateSalonRating(@Param("salonId") UUID salonId);

    /**
     * Rating-bucket counts for a salon's reviews, backing {@link com.beautica.review.dto.SalonReviewSummaryResponse}'s
     * {@code ratingDistribution}. Only buckets with at least one review are returned —
     * the service layer zero-fills the missing 1-5 buckets so every bucket is present in
     * the response (never omit a zero-count star rating).
     */
    @Query("SELECT r.rating AS rating, COUNT(r) AS count FROM Review r WHERE r.salon.id = :salonId GROUP BY r.rating")
    List<RatingCountProjection> countBySalonIdGroupByRating(@Param("salonId") UUID salonId);

    // ── Salon reviews — sortable list (Phase 13.6) ─────────────────────────────
    //
    // Four fixed, named finder methods — one per SalonReviewSort value — instead of
    // accepting a caller-supplied Sort/@SortDefault. A free-form sort string would let a
    // caller probe entity property names via PropertyReferenceException (the same reason
    // findIdsByMasterIdOrderByCreatedAtDesc above hardcodes its ORDER BY). Same two-query
    // ID-then-hydrate pattern: paginate on IDs only (SQL LIMIT/OFFSET, no JOIN FETCH),
    // then hydrate the bounded ID set via findByIdsWithGraphForSalonReviews below.

    @Query(value = """
            SELECT r.id FROM Review r
            WHERE r.salon.id = :salonId
            ORDER BY r.createdAt DESC
            """,
           countQuery = """
            SELECT COUNT(r) FROM Review r
            WHERE r.salon.id = :salonId
            """)
    Page<UUID> findIdsBySalonIdOrderByCreatedAtDesc(@Param("salonId") UUID salonId, Pageable pageable);

    @Query(value = """
            SELECT r.id FROM Review r
            WHERE r.salon.id = :salonId
            ORDER BY r.createdAt ASC
            """,
           countQuery = """
            SELECT COUNT(r) FROM Review r
            WHERE r.salon.id = :salonId
            """)
    Page<UUID> findIdsBySalonIdOrderByCreatedAtAsc(@Param("salonId") UUID salonId, Pageable pageable);

    @Query(value = """
            SELECT r.id FROM Review r
            WHERE r.salon.id = :salonId
            ORDER BY r.rating DESC, r.createdAt DESC
            """,
           countQuery = """
            SELECT COUNT(r) FROM Review r
            WHERE r.salon.id = :salonId
            """)
    Page<UUID> findIdsBySalonIdOrderByRatingDescCreatedAtDesc(@Param("salonId") UUID salonId, Pageable pageable);

    @Query(value = """
            SELECT r.id FROM Review r
            WHERE r.salon.id = :salonId
            ORDER BY r.rating ASC, r.createdAt DESC
            """,
           countQuery = """
            SELECT COUNT(r) FROM Review r
            WHERE r.salon.id = :salonId
            """)
    Page<UUID> findIdsBySalonIdOrderByRatingAscCreatedAtDesc(@Param("salonId") UUID salonId, Pageable pageable);

    /**
     * Batch-hydrates a bounded set of salon reviews with the associations
     * {@link com.beautica.review.dto.SalonReviewResponse} needs: {@code client} (masking),
     * {@code master.user} (master display name), and the two-hop
     * {@code booking.masterService.serviceDefinition} chain (service name).
     *
     * <p>Deliberately a NEW method rather than widening {@link #findByIdsWithGraph}: that
     * method backs the master-reviews path and does not need the booking/service-definition
     * chain — adding it there would regress that path with an unneeded JOIN (anti-bug §E.1).
     *
     * <p><strong>Result order is undefined</strong> — same contract as
     * {@link #findByIdsWithGraph}. Callers must reorder using the ID sequence from the
     * {@code findIdsBySalonIdOrderBy...} method that produced {@code ids}.
     */
    @Query("""
            SELECT r FROM Review r
            JOIN FETCH r.client
            JOIN FETCH r.master m
            JOIN FETCH m.user
            JOIN FETCH r.booking b
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE r.id IN :ids
            """)
    List<Review> findByIdsWithGraphForSalonReviews(@Param("ids") List<UUID> ids);
}
