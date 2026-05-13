package com.beautica.review.repository;

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
     */
    @EntityGraph(attributePaths = {"booking", "client", "master"})
    @Query("SELECT r FROM Review r WHERE r.id IN :ids")
    List<Review> findByIdsWithGraph(@Param("ids") List<UUID> ids);

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
}
