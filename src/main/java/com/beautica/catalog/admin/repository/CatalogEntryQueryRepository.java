package com.beautica.catalog.admin.repository;

import com.beautica.catalog.admin.dto.CatalogCategoryAdminDto;
import com.beautica.catalog.admin.dto.CatalogServiceTypeAdminDto;
import com.beautica.service.entity.PlatformCategoryStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * B504 — the catalog portal's read of the LIVE catalog with usage counts. Counts are correlated sub-selects backed by
 * {@code idx_service_def_category} / {@code idx_service_def_service_type}; both counts include inactive service
 * definitions. The hidden-inclusive {@code service_types.platform_category_name} filters use the FULL
 * {@code idx_service_types_platform_category} (V265; V73's partial one could not serve them). Unpaged on purpose: the category set is reference data (a few dozen rows plus decided requests) and one
 * category holds at most a few dozen types; the caller is the key-only internal portal.
 */
@Repository
public class CatalogEntryQueryRepository {

    private static final String CATEGORIES_SQL = """
            SELECT pc.name, pc.display_name, pc.status, pc.active,
                   (SELECT COUNT(*) FROM service_definitions sd WHERE sd.category = pc.name) AS usage_count,
                   (SELECT COUNT(*) FROM service_types st WHERE st.platform_category_name = pc.name) AS type_count
              FROM platform_categories pc
             ORDER BY pc.display_name, pc.name
            """;

    private static final String SERVICE_TYPES_SQL = """
            SELECT st.id, st.slug, st.name_uk, st.name_en, st.is_active,
                   (SELECT COUNT(*) FROM service_definitions sd WHERE sd.service_type_id = st.id) AS usage_count
              FROM service_types st
             WHERE st.platform_category_name = :category
             ORDER BY st.name_uk, st.id
            """;

    private static final String CATEGORY_EXISTS_SQL =
            "SELECT EXISTS (SELECT 1 FROM platform_categories WHERE name = :name)";

    @PersistenceContext
    private EntityManager entityManager;

    /** Every category in any status, by display name. */
    public List<CatalogCategoryAdminDto> findCategories() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(CATEGORIES_SQL).getResultList();
        return rows.stream()
                .map(row -> new CatalogCategoryAdminDto(
                        (String) row[0],
                        (String) row[1],
                        PlatformCategoryStatus.valueOf((String) row[2]),
                        (Boolean) row[3],
                        ((Number) row[4]).longValue(),
                        ((Number) row[5]).longValue()))
                .toList();
    }

    /** Every type of the category (hidden included), by Ukrainian name. */
    public List<CatalogServiceTypeAdminDto> findServiceTypes(String categoryName) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(SERVICE_TYPES_SQL)
                .setParameter("category", categoryName)
                .getResultList();
        return rows.stream()
                .map(row -> new CatalogServiceTypeAdminDto(
                        (UUID) row[0],
                        (String) row[1],
                        (String) row[2],
                        (String) row[3],
                        (Boolean) row[4],
                        ((Number) row[5]).longValue()))
                .toList();
    }

    public boolean categoryExists(String name) {
        return (Boolean) entityManager.createNativeQuery(CATEGORY_EXISTS_SQL)
                .setParameter("name", name)
                .getSingleResult();
    }
}
