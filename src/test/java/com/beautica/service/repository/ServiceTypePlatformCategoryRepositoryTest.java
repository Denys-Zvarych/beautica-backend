package com.beautica.service.repository;

import com.beautica.AbstractIntegrationTest;
import com.beautica.service.entity.ServiceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers IT for {@link ServiceTypeRepository#findActiveByPlatformCategoryName(String)}.
 *
 * <p>Asserts the Phase 16.2 lookup query against the real V13 + V73 seeded catalog:
 * <ul>
 *   <li>per-slug row counts match the V73 mapping table (EYELASH=8, BROWS=6, FACE=6);</li>
 *   <li>results are ordered by {@code name_uk ASC} (the repository's ORDER BY clause);</li>
 *   <li>inactive rows are excluded (the {@code is_active = true} predicate);</li>
 *   <li>an unknown slug returns an empty list (no NPE, no 404 oracle at this layer).</li>
 * </ul>
 *
 * <p>{@link AbstractIntegrationTest#cleanDb()} does NOT truncate {@code service_types}
 * (reference / catalog data), so the V13+V73 seed is stable across classes. The single
 * inactive row this test inserts is removed in {@link #cleanInsertedRow()} so the
 * per-bucket counts other IT classes rely on stay intact.
 */
@DisplayName("ServiceTypeRepository.findActiveByPlatformCategoryName — Testcontainers IT")
class ServiceTypePlatformCategoryRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private ServiceTypeRepository serviceTypeRepository;

    private UUID insertedInactiveId;

    @AfterEach
    void cleanInsertedRow() {
        if (insertedInactiveId != null) {
            jdbcTemplate.update("DELETE FROM service_types WHERE id = ?", insertedInactiveId);
            insertedInactiveId = null;
        }
    }

    @Test
    @DisplayName("returns exactly the 8 active EYELASH types from the V73-seeded catalog")
    void should_returnEightActiveTypes_when_categoryNameIsEyelash() {
        List<ServiceType> result =
                serviceTypeRepository.findActiveByPlatformCategoryName("EYELASH");

        assertThat(result)
                .as("EYELASH bucket has exactly 8 active service_types per the V73 mapping table")
                .hasSize(8)
                .allSatisfy(t -> {
                    assertThat(t.getPlatformCategoryName()).isEqualTo("EYELASH");
                    assertThat(t.isActive()).isTrue();
                });
    }

    @Test
    @DisplayName("returns rows for BROWS (6) and FACE (6) matching the V73 per-bucket counts")
    void should_returnPerSlugCounts_when_queriedByDifferentSlugs() {
        assertThat(serviceTypeRepository.findActiveByPlatformCategoryName("BROWS"))
                .as("BROWS bucket has exactly 6 active service_types")
                .hasSize(6);
        assertThat(serviceTypeRepository.findActiveByPlatformCategoryName("FACE"))
                .as("FACE bucket has exactly 6 active service_types")
                .hasSize(6);
    }

    @Test
    @DisplayName("orders results by name_uk ASC")
    void should_orderByNameUkAscending_when_categoryNameIsEyelash() {
        List<ServiceType> result =
                serviceTypeRepository.findActiveByPlatformCategoryName("EYELASH");

        List<String> namesUk = result.stream().map(ServiceType::getNameUk).toList();

        assertThat(namesUk)
                .as("repository ORDER BY t.nameUk ASC must produce a Ukrainian-collated ascending order")
                .isSortedAccordingTo(String::compareTo);
    }

    @Test
    @DisplayName("excludes inactive rows even when their platform_category_name matches")
    void should_excludeInactiveRows_when_inactiveTypeShareSlug() {
        int activeBefore = serviceTypeRepository.findActiveByPlatformCategoryName("EYELASH").size();

        // Insert an inactive EYELASH type. A valid category_id is required by the
        // NOT NULL FK on service_types.category_id — reuse any seeded category.
        UUID categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM service_categories LIMIT 1", UUID.class);
        insertedInactiveId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_types "
                + "(id, category_id, name_uk, slug, is_active, platform_category_name) "
                + "VALUES (?, ?, ?, ?, FALSE, 'EYELASH')",
                insertedInactiveId,
                categoryId,
                "Неактивний тип вій",
                "lash-inactive-it-" + insertedInactiveId);

        List<ServiceType> result =
                serviceTypeRepository.findActiveByPlatformCategoryName("EYELASH");

        assertThat(result)
                .as("the inactive row must NOT appear — is_active = true predicate")
                .hasSize(activeBefore)
                .noneMatch(t -> insertedInactiveId.equals(t.getId()));
    }

    @Test
    @DisplayName("returns an empty list for an unknown slug (no rows, no error)")
    void should_returnEmptyList_when_categoryNameIsUnknown() {
        List<ServiceType> result =
                serviceTypeRepository.findActiveByPlatformCategoryName("NO_SUCH_BUCKET");

        assertThat(result).isEmpty();
    }
}
