package com.beautica.service.repository;

import com.beautica.service.entity.PlatformCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PlatformCategoryRepository extends JpaRepository<PlatformCategory, Long> {

    boolean existsByNameIgnoreCase(String name);

    /**
     * Returns {@code true} when a category with the given name (exact case) exists,
     * is currently active, and is APPROVED. Used by the service-creation usability
     * gate: PENDING and REJECTED categories must never be selectable.
     */
    boolean existsByNameAndActiveTrueAndStatus(
            String name, com.beautica.service.entity.PlatformCategoryStatus status);

    /**
     * Returns {@code true} when a name is already taken by an APPROVED or PENDING
     * row — the 409 guard for new self-service requests. A previously REJECTED name
     * may be requested again.
     */
    boolean existsByNameIgnoreCaseAndStatusIn(
            String name, List<com.beautica.service.entity.PlatformCategoryStatus> statuses);

    /**
     * Token lookup for the approve/reject POST. Matched on the hashed token only;
     * expiry, status, and constant-time comparison are enforced in the service.
     * Returns at most one row — token_hash is effectively unique while PENDING.
     */
    Optional<PlatformCategory> findByTokenHash(String tokenHash);

    /**
     * Approved + active categories for the authenticated picker
     * ({@code GET /api/v1/service-categories/approved}), ordered for deterministic
     * output. Bounded by the predicate (no unbounded full-table scan of stale rows).
     */
    @Query("""
            SELECT pc FROM PlatformCategory pc
             WHERE pc.status = com.beautica.service.entity.PlatformCategoryStatus.APPROVED
               AND pc.active = true
             ORDER BY pc.displayName
            """)
    List<PlatformCategory> findApprovedActive();

    /**
     * Returns all platform categories with their usage counts from
     * {@code service_definitions.category}.
     *
     * <p>The LEFT JOIN ensures categories with zero usages are included.
     * The result is ordered by category name for deterministic output.
     *
     * <p>Convention note: this query crosses into {@code service_definitions}.
     * The crossing is isolated to this native query — no service-layer cross-repo
     * call is introduced; only the repository method wraps the join.
     */
    @Query(value = """
            SELECT pc.id,
                   pc.name,
                   pc.active,
                   pc.created_at,
                   COUNT(sd.category) AS usage_count
              FROM platform_categories pc
              LEFT JOIN service_definitions sd ON sd.category = pc.name
             GROUP BY pc.id, pc.name, pc.active, pc.created_at
             ORDER BY pc.name
            """, nativeQuery = true)
    List<CategoryUsageProjection> findAllWithUsageCounts();

    /**
     * Projection interface for the usage-count query result.
     */
    interface CategoryUsageProjection {
        Long getId();
        String getName();
        boolean isActive();
        java.time.OffsetDateTime getCreatedAt();
        long getUsageCount();
    }
}
