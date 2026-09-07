package com.beautica.review.repository;

import com.beautica.review.dto.MyReviewResponse;
import com.beautica.review.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // ── Master reviews — sortable list (Phase 8.11) ────────────────────────────
    //
    // Three additional fixed, named finders — one per non-NEWEST SalonReviewSort value —
    // mirroring the salon list (findIdsBySalonIdOrderBy... below). Same rationale as there and
    // as findIdsByMasterIdOrderByCreatedAtDesc above: a closed set of hardcoded ORDER BY
    // clauses instead of a caller-supplied Sort, so a free-form sort string can never probe
    // entity property names via PropertyReferenceException. NEWEST reuses the existing
    // findIdsByMasterIdOrderByCreatedAtDesc. HIGHEST/LOWEST tie-break on createdAt DESC for a
    // stable order within equal ratings — identical to the salon variants.

    @Query(value = """
            SELECT r.id FROM Review r
            WHERE r.master.id = :masterId
            ORDER BY r.createdAt ASC
            """,
           countQuery = """
            SELECT COUNT(r) FROM Review r
            WHERE r.master.id = :masterId
            """)
    Page<UUID> findIdsByMasterIdOrderByCreatedAtAsc(@Param("masterId") UUID masterId, Pageable pageable);

    @Query(value = """
            SELECT r.id FROM Review r
            WHERE r.master.id = :masterId
            ORDER BY r.rating DESC, r.createdAt DESC
            """,
           countQuery = """
            SELECT COUNT(r) FROM Review r
            WHERE r.master.id = :masterId
            """)
    Page<UUID> findIdsByMasterIdOrderByRatingDescCreatedAtDesc(@Param("masterId") UUID masterId, Pageable pageable);

    @Query(value = """
            SELECT r.id FROM Review r
            WHERE r.master.id = :masterId
            ORDER BY r.rating ASC, r.createdAt DESC
            """,
           countQuery = """
            SELECT COUNT(r) FROM Review r
            WHERE r.master.id = :masterId
            """)
    Page<UUID> findIdsByMasterIdOrderByRatingAscCreatedAtDesc(@Param("masterId") UUID masterId, Pageable pageable);

    /**
     * Batch-hydrates a bounded set of reviews with the associations {@link
     * com.beautica.review.dto.ReviewResponse} needs: {@code client} (masking), {@code master}
     * (masterId), and the two-hop {@code booking.masterService.serviceDefinition} chain (service
     * name). Widened from a {@code booking}/{@code client}/{@code master}-only fetch graph when
     * {@code serviceName} was added to {@code ReviewResponse} — without the extra two hops, every
     * row in {@link com.beautica.review.service.ReviewService#getReviewsForMaster}'s page would
     * lazily N+1 on {@code booking.masterService.serviceDefinition} (anti-bug §E.2).
     *
     * <p><strong>Result order is undefined.</strong> Callers must reorder the returned list
     * using the ID sequence from {@link #findIdsByMasterIdOrderByCreatedAtDesc} — for example,
     * by building a {@code Map<UUID, Review>} and streaming the ID list through it.
     *
     * <p>No {@code ORDER BY} clause: ordering is driven by the caller's ID stream,
     * which is cheaper than a redundant DB sort on an unindexed set.
     *
     * <p><b>{@code client} is a {@code LEFT JOIN FETCH}, not INNER</b> (Phase 300 D3 —
     * {@code reviews.client_id} became nullable so a self-deleted client's review survives with
     * its rating and comment intact). An INNER join would silently drop that review from every
     * page it belongs to, with the paged {@code totalElements} still counting it — the same
     * failure class the V157 audit already fixed for {@code m.user}. {@link
     * com.beautica.review.dto.ReviewResponse#from} renders the sentinel when {@code getClient()}
     * is {@code null}.
     */
    @Query("""
            SELECT r FROM Review r
            LEFT JOIN FETCH r.client
            JOIN FETCH r.master
            JOIN FETCH r.booking b
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE r.id IN :ids
            """)
    List<Review> findByIdsWithGraph(@Param("ids") List<UUID> ids);

    /**
     * Principal-scoped list of the reviews authored by one client, backing
     * {@code GET /reviews/me} (Phase 19.4). Single constructor-expression query: joins
     * {@code review.master.user} (master name) and {@code review.booking.masterService
     * .serviceDefinition} (service name) so the whole {@link MyReviewResponse} row is built in
     * ONE SQL statement — no N+1, no lazy traversal at mapping time.
     *
     * <p><b>{@code m.user} is a {@code LEFT JOIN} and must stay one</b> (V157 / phase 294 D1 —
     * 2026-09 audit finding 6). It was an INNER join, which made a client's OWN authored review
     * DISAPPEAR from {@code GET /reviews/me} the moment the master they reviewed was detached (staff
     * {@code users} row hard-deleted) — the client's own writing, silently gone, with the paged
     * {@code totalElements} still counting it (the count query never joined {@code m.user}). The two
     * name columns {@code COALESCE} onto the V157 {@code detached_*} snapshot, i.e. exactly what
     * {@code Master#displayFirstName()}/{@code displayLastName()} return, so this list keeps naming
     * the provider the client actually saw. This is the client's OWN receipt, which the 2026-09-04
     * product decision explicitly covers — unlike the anonymous salon listing
     * ({@code SalonReviewResponse}), which masks the name instead.
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
                COALESCE(mu.firstName, m.detachedFirstName),
                COALESCE(mu.lastName, m.detachedLastName),
                sd.name,
                CAST(r.rating AS integer),
                r.comment,
                r.createdAt,
                b.id
            )
            FROM Review r
            JOIN r.master m
            LEFT JOIN m.user mu
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

    // countByClientId (reviews AUTHORED by one client, for the BEAUTY PASSPORT identity strip)
    // was removed by the 2026-08 perf audit (F2). Its only caller, ClientPassportService, now
    // reads that count as a correlated subquery inside ClientAggregationRepository.findStanding,
    // alongside the registration instant, so the passport pays one statement instead of two.
    // It remains index-served by idx_reviews_client_created (V96) — note the actual index name;
    // the javadoc removed with this method mis-spelled it "reviews_client_created_index" (F8).
    // Still distinct from countByMasterIdGroupByRating / countBySalonIdGroupByRating, which are
    // rating histograms of reviews RECEIVED.

    // Named to distinguish from the inherited JpaRepository.findById (which is lazy).
    // Use this method whenever ReviewResponse.from() will be called on the result.
    // Widened alongside findByIdsWithGraph above to also JOIN FETCH booking.masterService
    // .serviceDefinition — ReviewResponse.serviceName needs it, and this single-row lookup backs
    // GET /reviews/{reviewId}, the second ReviewResponse.from() call site.
    @Query("""
            SELECT r FROM Review r
            LEFT JOIN FETCH r.client
            JOIN FETCH r.master
            JOIN FETCH r.booking b
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE r.id = :id
            """)
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
     * Recomputes {@code salons.avg_rating} / {@code salons.review_count} as the
     * <b>EQUAL-WEIGHTED mean of the salon's active masters' salon-scoped ratings</b>.
     *
     * <h4>Formula (locked user decision, mobile Phase 111) — supersedes the Phase 13.6 one</h4>
     * This method previously computed a flat {@code AVG(reviews.rating) WHERE salon_id = :salonId}
     * — a per-REVIEW average, so a single high-volume master dominated the salon's headline
     * number. It is now a two-level aggregate:
     * <ol>
     *   <li><b>Inner</b> — per master, the mean of that master's reviews carrying BOTH
     *       {@code master_id = <that master>} AND {@code salon_id = :salonId}.</li>
     *   <li><b>Outer</b> — the unweighted {@code AVG} of those per-master means. Each master
     *       counts once regardless of review volume. <b>That equal weighting is the point of the
     *       decision — do not "improve" it into a volume-weighted mean, which is exactly the
     *       formula this replaced.</b></li>
     * </ol>
     *
     * <h4>Salon-scoped, NOT lifetime — the load-bearing distinction</h4>
     * The inner aggregate reads {@code reviews} rows filtered by {@code r.salon_id = :salonId}.
     * It deliberately does NOT reuse {@code masters.avg_rating}, which
     * {@link #recalculateMasterRating} maintains as a LIFETIME figure ({@code AVG(reviews) WHERE
     * master_id = X}, no salon predicate). Reusing it would import reputation a master earned at
     * a previous employer or while independent into this salon's rating. The
     * {@code JOIN masters m ON m.id = r.master_id AND m.salon_id = :salonId} additionally
     * restricts contributors to masters <b>currently attached</b> to this salon, and
     * {@code m.is_active = true} drops departed/deactivated staff.
     *
     * <h4>{@code review_count} semantics — the number of underlying REVIEWS</h4>
     * {@code cnt} is {@code SUM} of the per-master review counts, i.e. the count of the reviews
     * that actually fed the average — <b>not</b> the number of contributing masters. The public
     * salon profile prints it next to a star; "3 reviews" beside an average derived from 3
     * masters holding 40 reviews between them would be a lie. It is therefore the count over the
     * SAME row set the average is computed from, which is also what keeps it consistent with
     * {@link #countBySalonIdGroupByRating} (that histogram carries the identical predicate, so it
     * still sums to this number).
     *
     * <h4>Trigger contract — reviews are no longer the only input</h4>
     * Because the contributing set is "the salon's currently-active masters", this number moves
     * on STAFF changes with no review involved. It is therefore fired from two places:
     * {@link com.beautica.review.event.ReviewEventListener#onReviewCreated} (a review was
     * written against a salon-affiliated booking) and
     * {@code com.beautica.review.event.SalonStaffRatingListener} (a master joined, left, was
     * reactivated, or was rotated between salons). Both use the same fire-and-log-don't-fail
     * contract in a {@code REQUIRES_NEW} transaction.
     *
     * <h4>Unchanged mechanics</h4>
     * Still a single statement; still {@code COALESCE}-to-zero for the no-contributor case (an
     * {@code AVG} over an empty set is {@code NULL}, and the DTO layer — not this column —
     * converts a {@code review_count} of 0 back to a {@code null} rating rather than printing a
     * fabricated {@code 0.00}); still {@code clearAutomatically}/{@code flushAutomatically} so a
     * pending review INSERT is included and the {@code Salon} L1 entry is evicted afterwards.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE salons s
               SET avg_rating   = agg.avg_rating,
                   review_count = agg.cnt
              FROM (SELECT COALESCE(AVG(pm.master_avg), 0) AS avg_rating,
                           COALESCE(SUM(pm.master_cnt), 0) AS cnt
                      FROM (SELECT r.master_id,
                                   AVG(r.rating::numeric) AS master_avg,
                                   COUNT(*)               AS master_cnt
                              FROM reviews r
                              JOIN masters m ON m.id = r.master_id
                             WHERE r.salon_id = :salonId
                               AND m.salon_id = :salonId
                               AND m.is_active = true
                             GROUP BY r.master_id) pm) agg
             WHERE s.id = :salonId
            """, nativeQuery = true)
    void recalculateSalonRating(@Param("salonId") UUID salonId);

    /**
     * Rating-bucket counts for a salon's reviews, backing {@link com.beautica.review.dto.SalonReviewSummaryResponse}'s
     * {@code ratingDistribution}. Only buckets with at least one review are returned —
     * the service layer zero-fills the missing 1-5 buckets so every bucket is present in
     * the response (never omit a zero-count star rating).
     *
     * <p><b>Scoped to the salon's currently-active masters (mobile Phase 111).</b> The
     * {@code JOIN r.master m} + {@code m.salon.id = :salonId} + {@code m.isActive = true}
     * predicates are the SAME contributor set {@link #recalculateSalonRating} averages over.
     * Without them the histogram would count every review ever written against this salon while
     * the headline {@code avgRating}/{@code reviewCount} above it counted only the current
     * staff's — so the bars would not sum to the number printed beside them the moment any
     * reviewed master left. The two queries must be changed together; if one grows a predicate,
     * the other does too.
     */
    @Query("""
            SELECT r.rating AS rating, COUNT(r) AS count
            FROM Review r
            JOIN r.master m
            WHERE r.salon.id = :salonId
              AND m.salon.id = :salonId
              AND m.isActive = true
            GROUP BY r.rating
            """)
    List<RatingCountProjection> countBySalonIdGroupByRating(@Param("salonId") UUID salonId);

    /**
     * Master-scoped twin of {@link #countBySalonIdGroupByRating}, backing
     * {@link com.beautica.review.dto.MasterReviewSummaryResponse}'s {@code ratingDistribution}
     * (Phase 8.10). Only buckets with at least one review are returned — the service layer
     * ({@code ReviewService.getMasterReviewSummary}) zero-fills the missing 1-5 buckets so
     * every star rating is present in the response. No new index needed: {@code reviews.master_id}
     * is already indexed (used by {@link #findIdsByMasterIdOrderByCreatedAtDesc}).
     */
    @Query("SELECT r.rating AS rating, COUNT(r) AS count FROM Review r WHERE r.master.id = :masterId GROUP BY r.rating")
    List<RatingCountProjection> countByMasterIdGroupByRating(@Param("masterId") UUID masterId);

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
     *
     * <p><b>{@code m.user} is a LEFT fetch and must stay one</b> (V157, phase 294 D1). A review
     * whose master has been detached (staff account hard-deleted, historical {@code masters} stub
     * kept) still belongs to the salon and still counts toward its rating; an INNER
     * {@code JOIN FETCH m.user} would silently drop it from the salon's public review list.
     * {@code SalonReviewResponse#from} reads the provider name via {@code Master#displayFirstName()}
     * accordingly.
     */
    @Query("""
            SELECT r FROM Review r
            LEFT JOIN FETCH r.client
            JOIN FETCH r.master m
            LEFT JOIN FETCH m.user
            JOIN FETCH r.booking b
            JOIN FETCH b.masterService ms
            JOIN FETCH ms.serviceDefinition
            WHERE r.id IN :ids
            """)
    List<Review> findByIdsWithGraphForSalonReviews(@Param("ids") List<UUID> ids);
}
