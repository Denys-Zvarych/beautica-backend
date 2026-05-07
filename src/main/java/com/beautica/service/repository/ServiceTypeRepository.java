package com.beautica.service.repository;

import com.beautica.service.entity.ServiceType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ServiceTypeRepository extends JpaRepository<ServiceType, UUID> {

    // Kept for backward compatibility — does not JOIN FETCH; use findByCategoryWithCategory for list endpoints.
    List<ServiceType> findAllByCategoryIdAndActiveTrueOrderByNameUkAsc(UUID categoryId);

    /**
     * Callers MUST validate: q is not blank, length <= 100, stripped of control characters.
     * Minimum 3 characters recommended for meaningful trigram similarity results.
     */
    @Query(
        value = """
            SELECT * FROM service_types t
            WHERE t.is_active = true
              AND (similarity(t.name_uk, :q) > 0.2 OR similarity(t.name_en, :q) > 0.2)
            ORDER BY GREATEST(similarity(t.name_uk, :q), similarity(t.name_en, :q)) DESC
            LIMIT 20
            """,
        nativeQuery = true
    )
    List<ServiceType> searchByName(@Param("q") String q);

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
}
