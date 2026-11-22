package com.beautica.service.repository;

import com.beautica.common.repository.TransactionLockTimeout;
import com.beautica.service.entity.ServiceType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for service type catalog data: read-only for the apps; the B504 catalog-admin rename / hide / unhide
 * writes through {@link #findByIdForUpdate}.
 *
 * <p>Auth policy: {@code GET /api/v1/service-types} (list and search) is declared
 * {@code permitAll()} in {@code SecurityConfig} — repository query methods here must not
 * assume an authenticated principal. The {@code POST /api/v1/service-types/suggest}
 * endpoint requires {@code SALON_OWNER}, {@code INDEPENDENT_MASTER}, or {@code SALON_ADMIN}
 * roles, enforced at the controller layer via {@code @PreAuthorize}.
 */
public interface ServiceTypeRepository extends JpaRepository<ServiceType, UUID>, TransactionLockTimeout {

    /**
     * @deprecated No JOIN FETCH — accessing {@code category} fields on returned entities
     *             will trigger N+1 lazy loads or a LazyInitializationException outside a
     *             transaction. Use {@link #findByCategoryWithCategory(UUID)} for any endpoint
     *             that projects category fields. Retained for the repository-layer test only.
     */
    @Deprecated(since = "phase-3", forRemoval = false)
    List<ServiceType> findAllByCategoryIdAndActiveTrueOrderByNameUkAsc(UUID categoryId);

    /**
     * Callers MUST validate: q is not blank, length <= 100, stripped of control characters.
     * Minimum 3 characters required for meaningful trigram similarity results.
     */
    @Query("""
        SELECT t FROM ServiceType t
        JOIN FETCH t.category
        WHERE t.active = true
          AND (FUNCTION('similarity', t.nameUk, :q) > 0.2
            OR FUNCTION('similarity', t.nameEn, :q) > 0.2)
        ORDER BY GREATEST(
          FUNCTION('similarity', t.nameUk, :q),
          FUNCTION('similarity', t.nameEn, :q)) DESC
        """)
    List<ServiceType> searchByName(@Param("q") String q, Pageable pageable);

    /**
     * Bounded replacement for the former unbounded findAllByActiveTrueOrderByNameUkAsc.
     * Callers should use {@code PageRequest.of(0, 200)} when fetching dropdown data.
     */
    @Query("SELECT t FROM ServiceType t WHERE t.active = true ORDER BY t.nameUk ASC")
    List<ServiceType> findAllActiveOrderByNameUk(Pageable pageable);

    /**
     * Eager-loads the category association in a single JOIN FETCH query.
     * Use for list endpoints that project category fields to avoid N+1 queries.
     */
    @Query("SELECT t FROM ServiceType t JOIN FETCH t.category WHERE t.active = true ORDER BY t.nameUk ASC")
    List<ServiceType> findAllActiveWithCategory();

    /**
     * Pageable overload of {@link #findAllActiveWithCategory()} for large datasets.
     * Use {@code PageRequest.of(0, 200)} for dropdown-style requests.
     */
    @Query(value = "SELECT t FROM ServiceType t JOIN FETCH t.category WHERE t.active = true ORDER BY t.nameUk ASC",
           countQuery = "SELECT count(t) FROM ServiceType t WHERE t.active = true")
    List<ServiceType> findAllActiveWithCategory(Pageable pageable);

    /**
     * Filtered by category with JOIN FETCH to avoid N+1 queries on list endpoints.
     */
    @Query("""
            SELECT t FROM ServiceType t
            JOIN FETCH t.category
            WHERE t.category.id = :categoryId
              AND t.active = true
            ORDER BY t.nameUk ASC
            """)
    List<ServiceType> findByCategoryWithCategory(@Param("categoryId") UUID categoryId);

    /**
     * Pageable overload of {@link #findByCategoryWithCategory(UUID)} for large datasets.
     */
    @Query(value = """
            SELECT t FROM ServiceType t
            JOIN FETCH t.category
            WHERE t.category.id = :categoryId
              AND t.active = true
            ORDER BY t.nameUk ASC
            """,
           countQuery = "SELECT count(t) FROM ServiceType t WHERE t.category.id = :categoryId AND t.active = true")
    List<ServiceType> findByCategoryWithCategory(@Param("categoryId") UUID categoryId, Pageable pageable);

    /**
     * Trigram-similarity search scoped to a specific category.
     * Pushes the {@code categoryId} filter into the DB query to avoid post-pagination
     * in-memory filtering on a capped page window.
     *
     * Callers MUST validate: q is not blank, length <= 100, stripped of control characters.
     * Minimum 3 characters required for meaningful trigram similarity results.
     */
    @Query("""
        SELECT t FROM ServiceType t
        JOIN FETCH t.category
        WHERE t.active = true
          AND t.category.id = :categoryId
          AND (FUNCTION('similarity', t.nameUk, :q) > 0.2
            OR FUNCTION('similarity', t.nameEn, :q) > 0.2)
        ORDER BY GREATEST(
          FUNCTION('similarity', t.nameUk, :q),
          FUNCTION('similarity', t.nameEn, :q)) DESC
        """)
    List<ServiceType> searchByNameAndCategory(@Param("q") String q, @Param("categoryId") UUID categoryId, Pageable pageable);

    /**
     * Returns active service types for the given platform-category name slug,
     * ordered alphabetically by Ukrainian name.
     *
     * <p>No {@code JOIN FETCH} of {@code category} is performed — the caller maps
     * to {@link com.beautica.service.dto.PlatformServiceTypeResponse} which reads
     * only {@code platformCategoryName} (a plain {@code String} column), so the
     * {@code category} lazy association is never traversed.
     *
     * <p>The match is case-sensitive against the canonical uppercase slugs stored
     * in {@code platform_categories.name} (e.g. {@code EYELASH}, {@code HAIR}).
     *
     * <p>Backed by the B-tree index {@code idx_service_types_platform_category} (V73 partial on
     * {@code is_active = TRUE}; FULL since V265, B504, so hidden-inclusive admin reads use it too).
     *
     * <p>Phase doc specified "display order" but no {@code display_order} column
     * exists on {@code service_types} — ordering falls back to {@code nameUk ASC},
     * consistent with every other active-type finder in this repository.
     */
    @Query("""
            SELECT t FROM ServiceType t
            WHERE t.platformCategoryName = :categoryName
              AND t.active = true
            ORDER BY t.nameUk ASC
            """)
    List<ServiceType> findActiveByPlatformCategoryName(@Param("categoryName") String categoryName);

    /**
     * True when a service type already carries the given globally-unique slug. Used by
     * {@code SlugGenerator} to de-duplicate a generated slug before a new
     * {@link ServiceType} insert (Phase 16.9.1).
     */
    boolean existsBySlug(String slug);

    /**
     * Reuse lookup for the promotion writer (Phase 16.9.1): returns the first active type
     * with the given platform-category slug and a case-insensitive {@code nameUk} match.
     * When present, promotion reuses it instead of creating a duplicate type (idempotency
     * + dedup when two masters request the same name under the same category).
     *
     * <p>{@code JOIN FETCH t.category} so the caller may read the bucket without an N+1;
     * the result drives a draft {@link com.beautica.service.entity.ServiceDefinition}
     * whose response never traverses the category, but keeping the graph eager here is
     * harmless and future-proof.
     */
    @Query("""
            SELECT t FROM ServiceType t
            JOIN FETCH t.category
            WHERE t.platformCategoryName = :categoryName
              AND LOWER(t.nameUk) = LOWER(:nameUk)
              AND t.active = true
            ORDER BY t.nameUk ASC
            """)
    Optional<ServiceType> findFirstActiveByPlatformCategoryNameAndNameUkIgnoreCase(
            @Param("categoryName") String categoryName, @Param("nameUk") String nameUk);

    /**
     * B504 — the type a catalog-admin rename / hide / unhide mutates, locked ({@code FOR UPDATE}) so two concurrent
     * portal writes on one type serialise. No graph: the write reads only plain columns.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ServiceType t where t.id = :id")
    Optional<ServiceType> findByIdForUpdateNoLockTimeout(@Param("id") UUID id);

    /** {@link #findByIdForUpdateNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout}. */
    default Optional<ServiceType> findByIdForUpdate(UUID id) {
        applyTransactionLockTimeout();
        return findByIdForUpdateNoLockTimeout(id);
    }

    /**
     * B504 — true when ANOTHER type in the category already has this {@code nameUk} (case-insensitive). {@code
     * activeOnly = false} counts hidden types too (the rename rule); {@code true} only active ones (the unhide rule).
     */
    @Query("""
            SELECT COUNT(t) > 0 FROM ServiceType t
             WHERE t.platformCategoryName = :categoryName
               AND LOWER(t.nameUk) = LOWER(:nameUk)
               AND t.id <> :excludeId
               AND (:activeOnly = false OR t.active = true)
            """)
    boolean existsOtherInCategoryByNameUkIgnoreCase(@Param("categoryName") String categoryName,
                                                    @Param("nameUk") String nameUk,
                                                    @Param("excludeId") UUID excludeId,
                                                    @Param("activeOnly") boolean activeOnly);
}
