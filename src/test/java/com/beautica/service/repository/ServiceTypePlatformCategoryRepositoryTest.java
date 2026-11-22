package com.beautica.service.repository;

import com.beautica.AbstractIntegrationTest;
import com.beautica.service.entity.ServiceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers IT for {@link ServiceTypeRepository#findActiveByPlatformCategoryName(String)}.
 *
 * <p>Asserts the Phase 16.2 lookup query against the real V74 + V75 seeded taxonomy:
 * <ul>
 *   <li>per-slug active row counts match the phase-17.1 table
 *       (LASH_EXTENSIONS=10, BROWS=8, MAKEUP=8);</li>
 *   <li>results are ordered by {@code name_uk ASC} (the repository's ORDER BY clause);</li>
 *   <li>inactive rows are excluded (the {@code is_active = true} predicate);</li>
 *   <li>an unknown slug returns an empty list (no NPE, no 404 oracle at this layer).</li>
 * </ul>
 *
 * <p>{@link AbstractIntegrationTest#cleanDb()} does NOT truncate {@code service_types}
 * (reference / catalog data), so the V74+V75 seed is stable across classes. The single
 * inactive row this test inserts is removed in {@link #cleanInsertedRow()} so the
 * per-bucket counts other IT classes rely on stay intact.
 */
@DisplayName("ServiceTypeRepository.findActiveByPlatformCategoryName — Testcontainers IT")
class ServiceTypePlatformCategoryRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private ServiceTypeRepository serviceTypeRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID insertedInactiveId;

    @AfterEach
    void cleanInsertedRow() {
        if (insertedInactiveId != null) {
            jdbcTemplate.update("DELETE FROM service_types WHERE id = ?", insertedInactiveId);
            insertedInactiveId = null;
        }
    }

    @Test
    @DisplayName("returns exactly the 10 active LASH_EXTENSIONS types from the V75-seeded catalog")
    void should_returnTenActiveTypes_when_categoryNameIsLashExtensions() {
        List<ServiceType> result =
                serviceTypeRepository.findActiveByPlatformCategoryName("LASH_EXTENSIONS");

        assertThat(result)
                .as("LASH_EXTENSIONS bucket has exactly 10 active service_types per the phase-17.1 table")
                .hasSize(10)
                .allSatisfy(t -> {
                    assertThat(t.getPlatformCategoryName()).isEqualTo("LASH_EXTENSIONS");
                    assertThat(t.isActive()).isTrue();
                });
    }

    @Test
    @DisplayName("returns rows for BROWS (8) and MAKEUP (8) matching the phase-17.1 per-category counts")
    void should_returnPerSlugCounts_when_queriedByDifferentSlugs() {
        assertThat(serviceTypeRepository.findActiveByPlatformCategoryName("BROWS"))
                .as("BROWS bucket has exactly 8 active service_types")
                .hasSize(8);
        // FACE is soft-disabled (0 active leaves); MAKEUP is a live category with 8 leaves.
        assertThat(serviceTypeRepository.findActiveByPlatformCategoryName("MAKEUP"))
                .as("MAKEUP bucket has exactly 8 active service_types")
                .hasSize(8);
    }

    @Test
    @DisplayName("orders results by name_uk ASC")
    void should_orderByNameUkAscending_when_categoryNameIsLashExtensions() {
        List<ServiceType> result =
                serviceTypeRepository.findActiveByPlatformCategoryName("LASH_EXTENSIONS");

        List<String> namesUk = result.stream().map(ServiceType::getNameUk).toList();

        assertThat(namesUk)
                .as("repository ORDER BY t.nameUk ASC must produce a Ukrainian-collated ascending order")
                .isSortedAccordingTo(String::compareTo);
    }

    @Test
    @DisplayName("excludes inactive rows even when their platform_category_name matches")
    void should_excludeInactiveRows_when_inactiveTypeShareSlug() {
        int activeBefore = serviceTypeRepository.findActiveByPlatformCategoryName("LASH_EXTENSIONS").size();

        // Insert an inactive LASH_EXTENSIONS type. A valid category_id is required by the
        // NOT NULL FK on service_types.category_id, and platform_category_name is FK-
        // constrained to an active platform_categories slug — reuse any seeded category.
        UUID categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM service_categories LIMIT 1", UUID.class);
        insertedInactiveId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_types "
                + "(id, category_id, name_uk, slug, is_active, platform_category_name) "
                + "VALUES (?, ?, ?, ?, FALSE, 'LASH_EXTENSIONS')",
                insertedInactiveId,
                categoryId,
                "Неактивний тип вій",
                "lash-inactive-it-" + insertedInactiveId);

        List<ServiceType> result =
                serviceTypeRepository.findActiveByPlatformCategoryName("LASH_EXTENSIONS");

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

    // ── V265 (B504): the platform-category index is FULL, so hidden-inclusive reads use it too ──

    @Test
    @DisplayName("V265 — idx_service_types_platform_category is a full (non-partial) index on platform_category_name")
    void should_beFullIndex_when_v265Applied() {
        String indexdef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'service_types' "
                        + "AND indexname = 'idx_service_types_platform_category'", String.class);

        assertThat(indexdef).contains("(platform_category_name)").doesNotContain("WHERE");
    }

    /**
     * The three predicate shapes on platform_category_name: the active-only picker (V73's original reader) and the
     * two hidden-inclusive B504 admin reads (type list, type_count). {@code enable_seqscan = off} (transaction-local)
     * because the seeded table is small enough that the planner would otherwise prefer a scan regardless; under
     * V73's partial index the two hidden-inclusive shapes could not use it at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT id FROM service_types WHERE platform_category_name = 'BROWS' AND is_active = TRUE",
            "SELECT id, name_uk FROM service_types WHERE platform_category_name = 'BROWS' ORDER BY name_uk",
            "SELECT COUNT(*) FROM service_types st WHERE st.platform_category_name = 'BROWS'"
    })
    @DisplayName("V265 — every platform_category_name lookup (active-only and hidden-inclusive) can use the index")
    void should_useIndex_when_filteringByPlatformCategoryName(String sql) {
        List<String> plan = new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return jdbcTemplate.queryForList("EXPLAIN " + sql, String.class);
        });

        assertThat(String.join("\n", plan)).contains("idx_service_types_platform_category");
    }
}
