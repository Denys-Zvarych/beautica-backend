package com.beautica.review.repository;

import com.beautica.review.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Optional<Review> findByBookingId(UUID bookingId);

    Page<Review> findByMasterIdOrderByCreatedAtDesc(UUID masterId, Pageable pageable);

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
