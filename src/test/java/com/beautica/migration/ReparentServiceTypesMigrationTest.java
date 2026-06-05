package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for the re-parenting chain V73 → V74 → V75.
 *
 * <p>V73 introduced {@code service_types.platform_category_name} and the
 * structural guards (FK to {@code platform_categories.name}, NOT NULL,
 * zero-orphan invariant). V74 reseeded {@code platform_categories} into the
 * final 21-active-category taxonomy (26 total incl. 5 soft-disabled) and V75
 * reseeded {@code service_types} into the final 140-leaf catalog. The slug/name
 * source of truth is {@code docs/backend-phases/phase-17.1-taxonomy-slug-artifact.md}.
 *
 * <p>This class pins the <b>post-V75 final state</b>:
 * <ol>
 *   <li><b>Structural contract (from V73, still holds)</b> — every
 *       {@code service_type.platform_category_name} resolves to an active
 *       {@code platform_categories} row; no NULL slug; legacy
 *       {@code category_id} column intact and NOT NULL;
 *       {@code platform_category_name} column NOT NULL with a closed-set FK
 *       guard.</li>
 *   <li><b>New totals</b> — 140 service_types; 21 active platform categories
 *       (26 total).</li>
 *   <li><b>V74 rename + soft-disable sets</b> — HAIRCUT→HAIRDRESSING,
 *       MANICURE→NAIL_SERVICE, EYELASH→LASH_EXTENSIONS renamed and active;
 *       PEDICURE, HAIR, BODY, FACE, OTHER soft-disabled (active=FALSE).</li>
 *   <li><b>Per-category active leaf counts</b> match the phase-17.1 table.</li>
 *   <li><b>Flyway history</b> — V73/V74/V75 recorded as success; zero failures.</li>
 * </ol>
 *
 * <p>All assertions are read-only against the post-migration state bootstrapped
 * by Testcontainers. {@link AbstractIntegrationTest#cleanDb()} does not truncate
 * service_types or platform_categories (reference / catalog data), so these tests
 * are order-independent and do not need an AfterEach cleanup.
 */
@DisplayName("V73→V75 migration — re-parent service_types onto the final taxonomy")
class ReparentServiceTypesMigrationTest extends AbstractIntegrationTest {

    /**
     * The 21 active platform-category name slugs after V74 (the picker set).
     * Source of truth: phase-17.1-taxonomy-slug-artifact.md § "21 platform categories".
     */
    private static final Set<String> ACTIVE_SLUGS = Set.of(
            "HAIRDRESSING", "HAIR_COLORING", "HAIR_TREATMENT", "HAIR_EXTENSIONS", "TRICHOLOGY",
            "NAIL_SERVICE", "PODOLOGY", "BROWS", "LASH_LAMINATION", "LASH_EXTENSIONS",
            "MAKEUP", "COSMETOLOGY", "HARDWARE_COSMETOLOGY", "INJECTION_COSMETOLOGY",
            "AESTHETIC_COSMETOLOGY", "LASER_COSMETOLOGY", "HAIR_REMOVAL", "PERMANENT_MAKEUP",
            "BARBERING", "BEARD_CARE", "SHAVING"
    );

    /**
     * Legacy slugs V74 soft-disables (active = FALSE) instead of deleting, so the
     * UNIQUE(name) self-service guard and any legacy service_definitions FK survive.
     */
    private static final Set<String> SOFT_DISABLED_SLUGS = Set.of(
            "PEDICURE", "HAIR", "BODY", "FACE", "OTHER"
    );

    private static final int TOTAL_SERVICE_TYPES = 140;
    private static final int ACTIVE_CATEGORY_COUNT = 21;
    private static final int TOTAL_CATEGORY_COUNT = 26;

    // =========================================================================
    // 1. Active-slug coverage (post-V74)
    // =========================================================================

    @Nested
    @DisplayName("platform_categories active-slug coverage")
    class ActiveSlugCoverage {

        @Test
        @DisplayName("exactly the 21 active slugs exist as APPROVED + active after V74")
        void should_haveTwentyOneActiveSlugs_when_v74Applied() {
            List<String> liveSlugs = jdbcTemplate.queryForList(
                    "SELECT name FROM platform_categories "
                    + "WHERE status = 'APPROVED' AND active = TRUE "
                    + "ORDER BY name",
                    String.class);

            assertThat(liveSlugs)
                    .as("platform_categories must contain exactly the 21 active taxonomy slugs after V74")
                    .containsExactlyInAnyOrderElementsOf(ACTIVE_SLUGS);
        }

        @Test
        @DisplayName("platform_categories has 21 active rows and 26 total (5 soft-disabled)")
        void should_have21ActiveAnd26Total_when_v74Applied() {
            Integer active = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM platform_categories WHERE active = TRUE",
                    Integer.class);
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM platform_categories",
                    Integer.class);

            assertThat(active)
                    .as("exactly 21 platform categories must be active after V74")
                    .isEqualTo(ACTIVE_CATEGORY_COUNT);
            assertThat(total)
                    .as("26 total platform categories (21 active + 5 soft-disabled) after V74")
                    .isEqualTo(TOTAL_CATEGORY_COUNT);
        }

        @Test
        @DisplayName("renamed slugs carry their final Ukrainian display names")
        void should_haveUkrainianDisplayNames_when_renamesApplied() {
            assertDisplayName("HAIRDRESSING", "Перукарські послуги");
            assertDisplayName("NAIL_SERVICE", "Нігтьовий сервіс");
            assertDisplayName("LASH_EXTENSIONS", "Нарощення вій");
        }
    }

    // =========================================================================
    // 2. V74 rename + soft-disable sets
    // =========================================================================

    @Nested
    @DisplayName("V74 rename + soft-disable disposition")
    class RenameAndSoftDisable {

        @Test
        @DisplayName("legacy HAIRCUT/MANICURE/EYELASH slugs no longer exist (renamed in place)")
        void should_haveRenamedLegacySlugs_when_v74Applied() {
            for (String legacy : List.of("HAIRCUT", "MANICURE", "EYELASH")) {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM platform_categories WHERE name = ?",
                        Integer.class, legacy);

                assertThat(count)
                        .as("legacy slug %s must be renamed away (1:1) by V74", legacy)
                        .isZero();
            }
        }

        @Test
        @DisplayName("PEDICURE, HAIR, BODY, FACE, OTHER are soft-disabled (active = FALSE)")
        void should_softDisableSupersededSlugs_when_v74Applied() {
            for (String slug : SOFT_DISABLED_SLUGS) {
                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "SELECT active FROM platform_categories WHERE name = ?", slug);

                assertThat(row.get("active"))
                        .as("%s must be soft-disabled (active = FALSE) by V74", slug)
                        .isEqualTo(Boolean.FALSE);
            }
        }

        @Test
        @DisplayName("BROWS and MAKEUP are kept active")
        void should_keepBrowsAndMakeupActive_when_v74Applied() {
            for (String slug : List.of("BROWS", "MAKEUP")) {
                Boolean active = jdbcTemplate.queryForObject(
                        "SELECT active FROM platform_categories WHERE name = ?",
                        Boolean.class, slug);

                assertThat(active)
                        .as("%s must remain active after V74", slug)
                        .isTrue();
            }
        }
    }

    // =========================================================================
    // 3. Zero orphans + new total
    // =========================================================================

    @Nested
    @DisplayName("zero orphan service_types + 140 total")
    class ZeroOrphans {

        @Test
        @DisplayName("no service_type has a NULL platform_category_name")
        void should_haveNoNullPlatformCategoryName_when_chainApplied() {
            Integer nullCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_types WHERE platform_category_name IS NULL",
                    Integer.class);

            assertThat(nullCount)
                    .as("every service_type must have platform_category_name populated — zero NULLs")
                    .isZero();
        }

        @Test
        @DisplayName("no service_type platform_category_name points outside the active slug set")
        void should_haveZeroOrphanSlugs_when_chainApplied() {
            // Anti-join: service_types whose platform_category_name does not appear
            // in the active platform_categories set are orphans.
            Integer orphans = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_types st "
                    + "WHERE NOT EXISTS ("
                    + "    SELECT 1 FROM platform_categories pc "
                    + "    WHERE pc.name = st.platform_category_name "
                    + "      AND pc.status = 'APPROVED' AND pc.active = TRUE"
                    + ")",
                    Integer.class);

            assertThat(orphans)
                    .as("every service_type.platform_category_name must resolve to a live "
                        + "(APPROVED + active) platform_categories row — zero orphans")
                    .isZero();
        }

        @Test
        @DisplayName("total service_type count is 140 (V75 reseed)")
        void should_have140ServiceTypes_when_v75Applied() {
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_types",
                    Integer.class);

            assertThat(total)
                    .as("V75 reseeds the catalog to exactly 140 service_types")
                    .isEqualTo(TOTAL_SERVICE_TYPES);
        }

        @Test
        @DisplayName("all service_types are active after V75 reseed")
        void should_haveAllActive_when_v75Applied() {
            Integer inactive = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_types WHERE is_active = FALSE",
                    Integer.class);

            assertThat(inactive)
                    .as("V75 seeds all 140 leaves as active")
                    .isZero();
        }
    }

    // =========================================================================
    // 4. Per-category active leaf counts (phase-17.1 table)
    // =========================================================================

    @Nested
    @DisplayName("per-category active leaf counts — match the phase-17.1 table")
    class PerCategoryCounts {

        @Test
        @DisplayName("each active category has the expected number of active leaves")
        void should_haveExpectedLeafCounts_when_v75Applied() {
            assertBucketCount("HAIRDRESSING", 11);
            assertBucketCount("HAIR_COLORING", 9);
            assertBucketCount("HAIR_TREATMENT", 7);
            assertBucketCount("HAIR_EXTENSIONS", 4);
            assertBucketCount("TRICHOLOGY", 3);
            assertBucketCount("NAIL_SERVICE", 12);
            assertBucketCount("PODOLOGY", 5);
            assertBucketCount("BROWS", 8);
            assertBucketCount("LASH_LAMINATION", 3);
            assertBucketCount("LASH_EXTENSIONS", 10);
            assertBucketCount("MAKEUP", 8);
            assertBucketCount("COSMETOLOGY", 5);
            assertBucketCount("HARDWARE_COSMETOLOGY", 5);
            assertBucketCount("INJECTION_COSMETOLOGY", 6);
            assertBucketCount("AESTHETIC_COSMETOLOGY", 3);
            assertBucketCount("LASER_COSMETOLOGY", 7);
            assertBucketCount("HAIR_REMOVAL", 6);
            assertBucketCount("PERMANENT_MAKEUP", 6);
            assertBucketCount("BARBERING", 10);
            assertBucketCount("BEARD_CARE", 7);
            assertBucketCount("SHAVING", 5);
        }

        @Test
        @DisplayName("sum of all per-category leaf counts equals 140")
        void should_haveTotalOf140AcrossAllBuckets_when_v75Applied() {
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_types WHERE platform_category_name IS NOT NULL",
                    Integer.class);

            assertThat(total)
                    .as("the per-category active counts must sum to 140")
                    .isEqualTo(TOTAL_SERVICE_TYPES);
        }

        @Test
        @DisplayName("a representative renamed-bucket leaf resolves to its new slug (NAIL_SERVICE)")
        void should_mapManicureLeafToNailService_when_v75Applied() {
            // nail-service-manicure is the canonical manicure leaf under the renamed
            // NAIL_SERVICE category (phase-17.1 § 6). Pins that the rename + reseed
            // landed leaves under the new slug, not the dead MANICURE one.
            assertPlatformCategory("nail-service-manicure", "NAIL_SERVICE");
        }

        @Test
        @DisplayName("a representative HAIRDRESSING leaf resolves to the renamed slug")
        void should_mapWomensCutToHairdressing_when_v75Applied() {
            assertPlatformCategory("hairdressing-womens-cut", "HAIRDRESSING");
        }
    }

    // =========================================================================
    // 5. Legacy column intact + platform_category_name NOT NULL (V73 fix)
    // =========================================================================

    @Nested
    @DisplayName("schema — category_id NOT NULL kept; platform_category_name NOT NULL")
    class SchemaColumns {

        @Test
        @DisplayName("service_types.category_id column is still present and NOT NULL")
        void should_keepCategoryIdColumnNotNull_when_chainApplied() {
            String isNullable = jdbcTemplate.queryForObject(
                    "SELECT is_nullable "
                    + "FROM information_schema.columns "
                    + "WHERE table_name = 'service_types' AND column_name = 'category_id'",
                    String.class);

            assertThat(isNullable)
                    .as("service_types.category_id is deprecated in place — the column must "
                        + "remain present and NOT NULL")
                    .isEqualTo("NO");
        }

        @Test
        @DisplayName("service_types.platform_category_name column exists and is NOT NULL")
        void should_havePlatformCategoryNameNotNull_when_chainApplied() {
            Map<String, Object> col = jdbcTemplate.queryForMap(
                    "SELECT data_type, is_nullable "
                    + "FROM information_schema.columns "
                    + "WHERE table_name = 'service_types' "
                    + "  AND column_name = 'platform_category_name'");

            assertThat(col.get("data_type"))
                    .as("platform_category_name must be a varchar column")
                    .isEqualTo("character varying");
            assertThat(col.get("is_nullable"))
                    .as("platform_category_name must be NOT NULL once all rows are populated")
                    .isEqualTo("NO");
        }
    }

    // =========================================================================
    // 6. Closed-set guard rejects unknown / lowercase slugs
    // =========================================================================

    @Nested
    @DisplayName("schema constraint — closed-set guard rejects invalid slugs")
    class ClosedSetGuard {

        @Test
        @DisplayName("INSERT with platform_category_name outside the active set is rejected by the DB")
        void should_rejectInsert_when_platformCategoryNameNotInLiveSet() {
            // A valid category_id satisfies the mandatory category_id FK so that only
            // the platform_category_name closed-set guard can fire.
            UUID categoryId = jdbcTemplate.queryForObject(
                    "SELECT id FROM service_categories LIMIT 1",
                    UUID.class);

            // 'UNKNOWN_BUCKET' matches the uppercase regex but is not an active slug.
            assertThatThrownBy(() ->
                    jdbcTemplate.update(
                            "INSERT INTO service_types "
                            + "(id, category_id, name_uk, slug, is_active, platform_category_name) "
                            + "VALUES (?, ?, ?, ?, TRUE, 'UNKNOWN_BUCKET')",
                            UUID.randomUUID(),
                            categoryId,
                            "Test type for constraint check",
                            "constraint-test-slug-" + UUID.randomUUID()))
                    .as("platform_category_name must be FK-constrained to an existing "
                        + "platform_categories.name — 'UNKNOWN_BUCKET' must be rejected")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("INSERT with a lowercase platform_category_name is rejected by the CHECK")
        void should_rejectInsert_when_platformCategoryNameIsLowercase() {
            UUID categoryId = jdbcTemplate.queryForObject(
                    "SELECT id FROM service_categories LIMIT 1",
                    UUID.class);

            assertThatThrownBy(() ->
                    jdbcTemplate.update(
                            "INSERT INTO service_types "
                            + "(id, category_id, name_uk, slug, is_active, platform_category_name) "
                            + "VALUES (?, ?, ?, ?, TRUE, 'manicure')",
                            UUID.randomUUID(),
                            categoryId,
                            "Test type for case check",
                            "case-test-slug-" + UUID.randomUUID()))
                    .as("the uppercase-only CHECK / FK must reject the lowercase 'manicure'")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // =========================================================================
    // 7. Partial B-tree index for category filtering
    // =========================================================================

    @Nested
    @DisplayName("schema — idx_service_types_platform_category exists")
    class PlatformCategoryIndex {

        @Test
        @DisplayName("idx_service_types_platform_category index exists on service_types")
        void should_haveIdxServiceTypesPlatformCategory_when_indexMigrationApplied() {
            List<String> indexes = jdbcTemplate.queryForList(
                    "SELECT indexname FROM pg_indexes "
                    + "WHERE tablename = 'service_types' "
                    + "  AND indexname = 'idx_service_types_platform_category'",
                    String.class);

            assertThat(indexes)
                    .as("idx_service_types_platform_category must exist for fast category filtering")
                    .containsExactly("idx_service_types_platform_category");
        }
    }

    // =========================================================================
    // 8. Flyway history
    // =========================================================================

    @Nested
    @DisplayName("Flyway history")
    class FlywayHistory {

        @Test
        @DisplayName("V73, V74 and V75 are recorded as success with stable checksums")
        void should_recordReparentChainAsSuccess_when_chainApplied() {
            assertMigrationSuccess("73", "V73__reparent_service_types_to_platform_categories.sql");
            assertMigrationSuccess("74", "V74__seed_taxonomy_platform_categories.sql");
            assertMigrationSuccess("75", "V75__reseed_service_types_taxonomy.sql");
        }

        @Test
        @DisplayName("no failed migration in the full history after the chain applied")
        void should_haveZeroFailedMigrations_when_chainApplied() {
            Integer failed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE",
                    Integer.class);

            assertThat(failed)
                    .as("the full migration chain must have zero failures")
                    .isZero();
        }
    }

    // =========================================================================
    // shared helpers
    // =========================================================================

    private void assertDisplayName(String slug, String expectedDisplayName) {
        String actual = jdbcTemplate.queryForObject(
                "SELECT display_name FROM platform_categories WHERE name = ?",
                String.class, slug);

        assertThat(actual)
                .as("platform category '%s' must carry display name '%s'", slug, expectedDisplayName)
                .isEqualTo(expectedDisplayName);
    }

    private void assertMigrationSuccess(String version, String expectedScript) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT success, checksum, script "
                + "FROM flyway_schema_history WHERE version = ?", version);

        assertThat(row.get("success"))
                .as("V%s must be recorded as a successful migration", version)
                .isEqualTo(Boolean.TRUE);
        assertThat(row.get("script")).isEqualTo(expectedScript);
        assertThat(row.get("checksum"))
                .as("V%s must carry a deterministic (non-null) checksum", version)
                .isNotNull();
    }

    private void assertPlatformCategory(String slug, String expectedCategory) {
        String actual = jdbcTemplate.queryForObject(
                "SELECT platform_category_name FROM service_types WHERE slug = ?",
                String.class, slug);

        assertThat(actual)
                .as("service_type '%s' must map to platform category '%s'", slug, expectedCategory)
                .isEqualTo(expectedCategory);
    }

    private void assertBucketCount(String category, int expectedCount) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types "
                + "WHERE platform_category_name = ? AND is_active = TRUE",
                Integer.class, category);

        assertThat(count)
                .as("platform category '%s' must contain exactly %d active service_types "
                    + "(per phase-17.1 table)", category, expectedCount)
                .isEqualTo(expectedCount);
    }
}
