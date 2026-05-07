package com.beautica.service.repository;

import com.beautica.service.entity.ServiceType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Read-only repository for service type catalog data.
 *
 * <p>Auth policy: {@code GET /api/v1/service-types} (list and search) is declared
 * {@code permitAll()} in {@code SecurityConfig} — repository query methods here must not
 * assume an authenticated principal. The {@code POST /api/v1/service-types/suggest}
 * endpoint requires {@code SALON_OWNER}, {@code INDEPENDENT_MASTER}, or {@code SALON_ADMIN}
 * roles, enforced at the controller layer via {@code @PreAuthorize}.
 */
public interface ServiceTypeRepository extends JpaRepository<ServiceType, UUID> {

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
     * Minimum 3 characters recommended for meaningful trigram similarity results.
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
}
