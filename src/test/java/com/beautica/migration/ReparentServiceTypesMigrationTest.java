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
 * Contract test for V73__reparent_service_types_to_platform_categories.sql.
 *
 * <p>Acceptance criteria pinned here (from phase-16.1 spec):
 * <ol>
 *   <li><b>9-slug coverage</b> — all nine target platform_category name slugs exist after V73.</li>
 *   <li><b>Zero orphans</b> — every service_type.platform_category_name resolves to one of
 *       the 9 live slugs; no NULL and no slug outside the live set.</li>
 *   <li><b>Nails split</b> — manicure-* → MANICURE, pedicure-* → PEDICURE,
 *       generic nail types → MANICURE.</li>
 *   <li><b>Hair split (ASSUMPTION)</b> — cut types (haircut-women/men/kids) → HAIRCUT;
 *       coloring/styling/treatment types → HAIR.</li>
 *   <li><b>Idempotency</b> — platform_categories has exactly the 9 approved live entries
 *       (no duplicates from re-apply); per-bucket counts are stable.</li>
 *   <li><b>Per-bucket counts</b> match the V73 mapping table:
 *       MANICURE=7, PEDICURE=2, EYELASH=8, BROWS=6, HAIRCUT=3, HAIR=6,
 *       FACE=6, BODY=6, MAKEUP=5 (total=49).</li>
 *   <li><b>Flyway history</b> — V73 recorded as success with a stable checksum.</li>
 *   <li><b>Legacy column intact</b> — service_types.category_id is still NOT NULL
 *       (deprecated in place; not dropped).</li>
 * </ol>
 *
 * <p>All assertions are read-only against the post-migration state bootstrapped by
 * Testcontainers. {@link AbstractIntegrationTest#cleanDb()} does not truncate
 * service_types or platform_categories (reference / catalog data), so these tests
 * are order-independent and do not need an AfterEach cleanup.
 */
@DisplayName("V73 migration — re-parent service_types onto platform_categories")
class ReparentServiceTypesMigrationTest extends AbstractIntegrationTest {

    /**
     * The 9 live platform-category name slugs that must exist after V73.
     * Order is the canonical display order from the seed migrations.
     */
    private static final Set<String> LIVE_SLUGS = Set.of(
            "MANICURE", "PEDICURE", "EYELASH", "HAIRCUT",
            "MAKEUP", "BROWS", "HAIR", "BODY", "FACE"
    );

    // =========================================================================
    // 1. 9-slug coverage
    // =========================================================================

    @Nested
    @DisplayName("platform_categories 9-slug coverage")
    class NineSlugCoverage {

        @Test
        @DisplayName("all 9 target slugs exist as APPROVED and active after V73")
        void should_haveAllNineSlugs_when_v73Applied() {
            List<String> liveSlugs = jdbcTemplate.queryForList(
                    "SELECT name FROM platform_categories "
                    + "WHERE status = 'APPROVED' AND active = TRUE "
                    + "ORDER BY name",
                    String.class);

            assertThat(liveSlugs)
                    .as("platform_categories must contain exactly the 9 live slugs "
                        + "after V73 seeds HAIR, BODY, FACE")
                    .containsExactlyInAnyOrderElementsOf(LIVE_SLUGS);
        }

        @Test
        @DisplayName("HAIR, BODY, FACE rows are present and APPROVED after V73")
        void should_seedHairBodyFace_when_v73Applied() {
            for (String newSlug : List.of("HAIR", "BODY", "FACE")) {
                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "SELECT name, display_name, active, status "
                        + "FROM platform_categories WHERE name = ?",
                        newSlug);

                assertThat(row.get("name")).isEqualTo(newSlug);
                assertThat(row.get("active"))
                        .as("%s must be active=TRUE", newSlug)
                        .isEqualTo(Boolean.TRUE);
                assertThat(row.get("status"))
                        .as("%s must be APPROVED", newSlug)
                        .isEqualTo("APPROVED");
                assertThat(row.get("display_name"))
                        .as("%s must have a non-blank Ukrainian display name", newSlug)
                        .isNotNull()
                        .isNotEqualTo("");
            }
        }

        @Test
        @DisplayName("HAIR display_name is the Ukrainian 'Волосся'")
        void should_haveUkrainianDisplayName_when_hairSeeded() {
            String displayName = jdbcTemplate.queryForObject(
                    "SELECT display_name FROM platform_categories WHERE name = 'HAIR'",
                    String.class);

            assertThat(displayName).isEqualTo("Волосся");
        }

        @Test
        @DisplayName("BODY display_name is the Ukrainian 'Тіло'")
        void should_haveUkrainianDisplayName_when_bodySeeded() {
            String displayName = jdbcTemplate.queryForObject(
                    "SELECT display_name FROM platform_categories WHERE name = 'BODY'",
                    String.class);

            assertThat(displayName).isEqualTo("Тіло");
        }

        @Test
        @DisplayName("FACE display_name is the Ukrainian 'Обличчя'")
        void should_haveUkrainianDisplayName_when_faceSeeded() {
            String displayName = jdbcTemplate.queryForObject(
                    "SELECT display_name FROM platform_categories WHERE name = 'FACE'",
                    String.class);

            assertThat(displayName).isEqualTo("Обличчя");
        }
    }

    // =========================================================================
    // 2. Zero orphans
    // =========================================================================

    @Nested
    @DisplayName("zero orphan service_types")
    class ZeroOrphans {

        @Test
        @DisplayName("no service_type has a NULL platform_category_name after V73")
        void should_haveNoNullPlatformCategoryName_when_v73Applied() {
            Integer nullCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_types WHERE platform_category_name IS NULL",
                    Integer.class);

            assertThat(nullCount)
                    .as("every service_type must have platform_category_name populated "
                        + "after V73 — zero NULLs allowed")
                    .isZero();
        }

        @Test
        @DisplayName("no service_type platform_category_name points outside the 9 live slugs")
        void should_haveZeroOrphanSlugs_when_v73Applied() {
            // Anti-join: service_types whose platform_category_name does not
            // appear in the live platform_categories set are orphans.
            Integer orphans = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_types st "
                    + "WHERE NOT EXISTS ("
                    + "    SELECT 1 FROM platform_categories pc "
                    + "    WHERE pc.name = st.platform_category_name "
                    + "      AND pc.status = 'APPROVED' AND pc.active = TRUE"
                    + ")",
                    Integer.class);

            assertThat(orphans)
                    .as("every service_type.platform_category_name must resolve to a "
                        + "live (APPROVED + active) platform_categories row — zero orphans")
                    .isZero();
        }

        @Test
        @DisplayName("total service_type count is 49 (matches V13 seed)")
        void should_have49ServiceTypes_when_v13AndV73Applied() {
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_types",
                    Integer.class);

            assertThat(total)
                    .as("V13 seeds exactly 49 service_types; V73 must not insert or "
                        + "delete any row — count must remain 49")
                    .isEqualTo(49);
        }
    }

    // =========================================================================
    // 3. Nails split
    // =========================================================================

    @Nested
    @DisplayName("nails split — manicure-* → MANICURE, pedicure-* → PEDICURE, generic → MANICURE")
    class NailsSplit {

        @Test
        @DisplayName("manicure-classic → MANICURE")
        void should_mapManicureClassicToManicure_when_v73Applied() {
            assertPlatformCategory("manicure-classic", "MANICURE");
        }

        @Test
        @DisplayName("manicure-hardware → MANICURE")
        void should_mapManicureHardwareToManicure_when_v73Applied() {
            assertPlatformCategory("manicure-hardware", "MANICURE");
        }

        @Test
        @DisplayName("pedicure-classic → PEDICURE")
        void should_mapPedicureClassicToPedicure_when_v73Applied() {
            assertPlatformCategory("pedicure-classic", "PEDICURE");
        }

        @Test
        @DisplayName("pedicure-hardware → PEDICURE")
        void should_mapPedicureHardwareToPedicure_when_v73Applied() {
            assertPlatformCategory("pedicure-hardware", "PEDICURE");
        }

        @Test
        @DisplayName("gel-polish (generic nail) → MANICURE")
        void should_mapGelPolishToManicure_when_v73Applied() {
            assertPlatformCategory("gel-polish", "MANICURE");
        }

        @Test
        @DisplayName("nail-art (generic nail) → MANICURE")
        void should_mapNailArtToManicure_when_v73Applied() {
            assertPlatformCategory("nail-art", "MANICURE");
        }

        @Test
        @DisplayName("nail-extension-acrylic (generic nail) → MANICURE")
        void should_mapNailExtensionAcrylicToManicure_when_v73Applied() {
            assertPlatformCategory("nail-extension-acrylic", "MANICURE");
        }

        @Test
        @DisplayName("nail-extension-gel (generic nail) → MANICURE")
        void should_mapNailExtensionGelToManicure_when_v73Applied() {
            assertPlatformCategory("nail-extension-gel", "MANICURE");
        }

        @Test
        @DisplayName("polish-removal (generic nail) → MANICURE")
        void should_mapPolishRemovalToManicure_when_v73Applied() {
            assertPlatformCategory("polish-removal", "MANICURE");
        }
    }

    // =========================================================================
    // 4. Hair split (ASSUMPTION — these are the tests that protect the sign-off)
    // =========================================================================

    @Nested
    @DisplayName("hair split (ASSUMPTION) — cut-intent → HAIRCUT, coloring/styling/treatment → HAIR")
    class HairSplit {

        @Test
        @DisplayName("haircut-women (cut intent) → HAIRCUT")
        void should_mapHaircutWomenToHaircut_when_v73Applied() {
            assertPlatformCategory("haircut-women", "HAIRCUT");
        }

        @Test
        @DisplayName("haircut-men (cut intent) → HAIRCUT")
        void should_mapHaircutMenToHaircut_when_v73Applied() {
            assertPlatformCategory("haircut-men", "HAIRCUT");
        }

        @Test
        @DisplayName("haircut-kids (cut intent) → HAIRCUT")
        void should_mapHaircutKidsToHaircut_when_v73Applied() {
            assertPlatformCategory("haircut-kids", "HAIRCUT");
        }

        @Test
        @DisplayName("hair-balayage (coloring technique) → HAIR")
        void should_mapHairBalayageToHair_when_v73Applied() {
            assertPlatformCategory("hair-balayage", "HAIR");
        }

        @Test
        @DisplayName("hair-coloring → HAIR")
        void should_mapHairColoringToHair_when_v73Applied() {
            assertPlatformCategory("hair-coloring", "HAIR");
        }

        @Test
        @DisplayName("hair-highlights → HAIR")
        void should_mapHairHighlightsToHair_when_v73Applied() {
            assertPlatformCategory("hair-highlights", "HAIR");
        }

        @Test
        @DisplayName("hair-keratin (treatment) → HAIR")
        void should_mapHairKeratinToHair_when_v73Applied() {
            assertPlatformCategory("hair-keratin", "HAIR");
        }

        @Test
        @DisplayName("hair-botox (treatment) → HAIR")
        void should_mapHairBotoxToHair_when_v73Applied() {
            assertPlatformCategory("hair-botox", "HAIR");
        }

        @Test
        @DisplayName("hair-styling (styling, not cut) → HAIR")
        void should_mapHairStylingToHair_when_v73Applied() {
            assertPlatformCategory("hair-styling", "HAIR");
        }
    }

    // =========================================================================
    // 5. Idempotency — ON CONFLICT / stable counts after re-apply
    // =========================================================================

    @Nested
    @DisplayName("idempotency — no duplicate categories, stable per-bucket counts")
    class Idempotency {

        @Test
        @DisplayName("platform_categories has no duplicate name rows after V73")
        void should_haveNoDuplicateCategoryNames_when_v73Applied() {
            Integer duplicates = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ("
                    + "  SELECT name FROM platform_categories "
                    + "  GROUP BY name HAVING COUNT(*) > 1"
                    + ") dup",
                    Integer.class);

            assertThat(duplicates)
                    .as("ON CONFLICT (name) DO NOTHING guarantees no duplicate "
                        + "platform_category rows even if V73 is applied twice")
                    .isZero();
        }

        @Test
        @DisplayName("HAIR, BODY, FACE each appear exactly once in platform_categories")
        void should_haveExactlyOneRowPerNewCategory_when_v73Applied() {
            for (String slug : List.of("HAIR", "BODY", "FACE")) {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM platform_categories WHERE name = ?",
                        Integer.class, slug);

                assertThat(count)
                        .as("%s must appear exactly once in platform_categories", slug)
                        .isEqualTo(1);
            }
        }
    }

    // =========================================================================
    // 6. Per-bucket counts
    // =========================================================================

    @Nested
    @DisplayName("per-bucket counts — match the V73 mapping table")
    class PerBucketCounts {

        @Test
        @DisplayName("MANICURE bucket contains exactly 7 service_types")
        void should_have7ManicureTypes_when_v73Applied() {
            assertBucketCount("MANICURE", 7);
        }

        @Test
        @DisplayName("PEDICURE bucket contains exactly 2 service_types")
        void should_have2PedicureTypes_when_v73Applied() {
            assertBucketCount("PEDICURE", 2);
        }

        @Test
        @DisplayName("EYELASH bucket contains exactly 8 service_types")
        void should_have8EyelashTypes_when_v73Applied() {
            assertBucketCount("EYELASH", 8);
        }

        @Test
        @DisplayName("BROWS bucket contains exactly 6 service_types")
        void should_have6BrowsTypes_when_v73Applied() {
            assertBucketCount("BROWS", 6);
        }

        @Test
        @DisplayName("HAIRCUT bucket contains exactly 3 service_types (ASSUMPTION)")
        void should_have3HaircutTypes_when_v73Applied() {
            assertBucketCount("HAIRCUT", 3);
        }

        @Test
        @DisplayName("HAIR bucket contains exactly 6 service_types (ASSUMPTION)")
        void should_have6HairTypes_when_v73Applied() {
            assertBucketCount("HAIR", 6);
        }

        @Test
        @DisplayName("FACE bucket contains exactly 6 service_types")
        void should_have6FaceTypes_when_v73Applied() {
            assertBucketCount("FACE", 6);
        }

        @Test
        @DisplayName("BODY bucket contains exactly 6 service_types")
        void should_have6BodyTypes_when_v73Applied() {
            assertBucketCount("BODY", 6);
        }

        @Test
        @DisplayName("MAKEUP bucket contains exactly 5 service_types")
        void should_have5MakeupTypes_when_v73Applied() {
            assertBucketCount("MAKEUP", 5);
        }

        @Test
        @DisplayName("sum of all per-bucket counts equals 49 (total V13 seed)")
        void should_haveTotalOf49AcrossAllBuckets_when_v73Applied() {
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_types WHERE platform_category_name IS NOT NULL",
                    Integer.class);

            assertThat(total)
                    .as("sum of MANICURE(7)+PEDICURE(2)+EYELASH(8)+BROWS(6)"
                        + "+HAIRCUT(3)+HAIR(6)+FACE(6)+BODY(6)+MAKEUP(5) = 49")
                    .isEqualTo(49);
        }
    }

    // =========================================================================
    // 7. Legacy column intact (deprecated in place, not dropped)
    // =========================================================================

    @Nested
    @DisplayName("legacy schema — category_id NOT NULL (deprecated in place)")
    class LegacySchemaIntact {

        @Test
        @DisplayName("service_types.category_id column is still present and NOT NULL")
        void should_keepCategoryIdColumnNotNull_when_v73Applied() {
            String isNullable = jdbcTemplate.queryForObject(
                    "SELECT is_nullable "
                    + "FROM information_schema.columns "
                    + "WHERE table_name = 'service_types' AND column_name = 'category_id'",
                    String.class);

            assertThat(isNullable)
                    .as("service_types.category_id is deprecated in place by V73 — "
                        + "the column must NOT be dropped; it must remain NOT NULL")
                    .isEqualTo("NO");
        }

        @Test
        @DisplayName("service_types.platform_category_name column exists after V73")
        void should_havePlatformCategoryNameColumn_when_v73Applied() {
            String dataType = jdbcTemplate.queryForObject(
                    "SELECT data_type "
                    + "FROM information_schema.columns "
                    + "WHERE table_name = 'service_types' "
                    + "  AND column_name = 'platform_category_name'",
                    String.class);

            assertThat(dataType)
                    .as("platform_category_name column must exist in service_types after V73")
                    .isEqualTo("character varying");
        }
    }

    // =========================================================================
    // 8. Flyway history
    // =========================================================================

    @Nested
    @DisplayName("Flyway history")
    class FlywayHistory {

        @Test
        @DisplayName("V73 recorded as success with a stable checksum")
        void should_recordV73AsSuccess_when_chainApplied() {
            Map<String, Object> v73 = jdbcTemplate.queryForMap(
                    "SELECT success, checksum, script "
                    + "FROM flyway_schema_history WHERE version = '73'");

            assertThat(v73.get("success"))
                    .as("V73 must be recorded as a successful migration")
                    .isEqualTo(Boolean.TRUE);
            assertThat(v73.get("script"))
                    .isEqualTo("V73__reparent_service_types_to_platform_categories.sql");
            assertThat(v73.get("checksum"))
                    .as("V73 must carry a deterministic (non-null) checksum")
                    .isNotNull();
        }

        @Test
        @DisplayName("no failed migration in the full history after V73 applied")
        void should_haveZeroFailedMigrations_when_v73InChain() {
            Integer failed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE",
                    Integer.class);

            assertThat(failed)
                    .as("the full migration chain must have zero failures after V73")
                    .isZero();
        }
    }

    // =========================================================================
    // 9. Schema constraints — pins the coming MEDIUM fixes (RED until fixed)
    // =========================================================================

    /**
     * Groups 9a–9c pin three MEDIUM findings raised by the security/perf pass
     * immediately after Phase 16.1 ships:
     * <ul>
     *   <li>9a — platform_category_name must be NOT NULL once all rows are
     *       populated (the column was added nullable in V73 to allow a two-step
     *       migration; the fix adds ALTER COLUMN … SET NOT NULL).</li>
     *   <li>9b — an INSERT whose platform_category_name is outside the 9-slug
     *       closed set must be rejected by a DB-level constraint (FK or closed
     *       CHECK); currently the open regex CHECK accepts any
     *       uppercase slug, so this test is RED until the guard lands.</li>
     *   <li>9c — idx_service_types_platform_category (B-tree, partial
     *       WHERE is_active = true) must exist for performant category
     *       filtering; RED until the index migration runs.</li>
     * </ul>
     * Tests are marked with their expected state in the comment next to
     * {@code @Test}.
     */

    @Nested
    @DisplayName("schema constraint — platform_category_name NOT NULL (pins MEDIUM fix)")
    class PlatformCategoryNameNotNull {

        // Expected: RED until the ALTER COLUMN ... SET NOT NULL migration runs.
        @Test
        @DisplayName("platform_category_name column is NOT NULL after the closed-set guard migration")
        void should_havePlatformCategoryNameAsNotNull_when_notNullConstraintAdded() {
            // information_schema.columns.is_nullable = 'NO' proves the column
            // carries a NOT NULL constraint. V73 added the column as nullable
            // to permit a two-step migration; once all 49 rows are populated
            // the column must be tightened to NOT NULL so future INSERTs that
            // omit it are rejected at the DB level — not silently stored as NULL.
            String isNullable = jdbcTemplate.queryForObject(
                    "SELECT is_nullable "
                    + "FROM information_schema.columns "
                    + "WHERE table_name = 'service_types' "
                    + "  AND column_name = 'platform_category_name'",
                    String.class);

            assertThat(isNullable)
                    .as("service_types.platform_category_name must be NOT NULL — "
                        + "V73 populated all 49 rows so the column can and must be "
                        + "tightened; a NULL here would produce an orphan that the "
                        + "ZeroOrphans anti-join tests above cannot catch until the "
                        + "next SELECT")
                    .isEqualTo("NO");
        }
    }

    @Nested
    @DisplayName("schema constraint — closed-set guard rejects unknown slugs (pins MEDIUM fix)")
    class ClosedSetGuard {

        // Expected: RED until the closed-set constraint (FK or refined CHECK) lands.
        @Test
        @DisplayName("INSERT with platform_category_name outside the 9-slug set is rejected by the DB")
        void should_rejectInsert_when_platformCategoryNameNotInLiveSet() {
            // Arrange — obtain a valid category_id from the seeded catalog so the
            // mandatory FK constraint on service_types.category_id does not
            // interfere; we want only the closed-set guard to fire.
            UUID categoryId = jdbcTemplate.queryForObject(
                    "SELECT id FROM service_categories LIMIT 1",
                    UUID.class);

            // Act / Assert — 'UNKNOWN_BUCKET' matches the current open regex
            // (^[A-Z][A-Z0-9_]*$) but is NOT one of the 9 live slugs. Once
            // the MEDIUM fix closes the set (via an additional CHECK or FK),
            // this INSERT must raise DataIntegrityViolationException. Until
            // then, the test is RED as intended — it pins the fix contract.
            assertThatThrownBy(() ->
                    jdbcTemplate.update(
                            "INSERT INTO service_types "
                            + "(id, category_id, name_uk, slug, is_active, platform_category_name) "
                            + "VALUES (?, ?, ?, ?, TRUE, 'UNKNOWN_BUCKET')",
                            UUID.randomUUID(),
                            categoryId,
                            "Test type for constraint check",
                            "constraint-test-slug-" + UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .as("service_types.platform_category_name must reject 'UNKNOWN_BUCKET' — "
                        + "a value that satisfies the current open CHECK regex "
                        + "(^[A-Z][A-Z0-9_]*$) but is outside the 9 live slugs. "
                        + "The MEDIUM fix must add a closed-set guard (FK to "
                        + "platform_categories.name or a refined CHECK constraint) "
                        + "that makes this INSERT fail.");
        }

        // Expected: GREEN now — existing CHECK already enforces the uppercase regex.
        @Test
        @DisplayName("INSERT with platform_category_name in lowercase is rejected by the existing CHECK")
        void should_rejectInsert_when_platformCategoryNameIsLowercase() {
            // This test is GREEN now: the existing chk_service_types_platform_category_name
            // CHECK (platform_category_name ~ '^[A-Z][A-Z0-9_]*$') already rejects
            // lowercase slugs. Included here to document the existing constraint shape
            // and provide a positive counterpart to the unknown-slug negative test.
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
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .as("chk_service_types_platform_category_name must reject 'manicure' "
                        + "(lowercase) — the CHECK constraint requires uppercase-only slugs "
                        + "matching ^[A-Z][A-Z0-9_]*$");
        }
    }

    @Nested
    @DisplayName("schema constraint — partial B-tree index exists (pins MEDIUM fix)")
    class PlatformCategoryIndex {

        // Expected: RED until the index migration runs.
        @Test
        @DisplayName("idx_service_types_platform_category index exists on service_types")
        void should_haveIdxServiceTypesPlatformCategory_when_indexMigrationApplied() {
            // pg_indexes.indexname is the authoritative catalog for index presence.
            // The MEDIUM perf finding requires a B-tree index named
            // idx_service_types_platform_category on service_types to make
            // platform-category filtering fast once 16.2+ endpoints start
            // querying by slug. This test is RED until the index migration runs.
            List<String> indexes = jdbcTemplate.queryForList(
                    "SELECT indexname FROM pg_indexes "
                    + "WHERE tablename = 'service_types' "
                    + "  AND indexname = 'idx_service_types_platform_category'",
                    String.class);

            assertThat(indexes)
                    .as("idx_service_types_platform_category must exist on service_types — "
                        + "required by the MEDIUM perf finding for fast category filtering "
                        + "in Phase 16.2+ endpoints")
                    .containsExactly("idx_service_types_platform_category");
        }

        // Expected: RED until the index migration runs.
        @Test
        @DisplayName("idx_service_types_platform_category is a partial index (WHERE is_active = true)")
        void should_makeIdxServiceTypesPlatformCategoryPartial_when_indexMigrationApplied() {
            // Partial indexes are visible in pg_indexes.indexdef via a WHERE clause.
            // A non-partial index on an active-filtered column wastes space on
            // inactive rows. The MEDIUM fix mandates WHERE is_active = true.
            String indexDef = jdbcTemplate.queryForObject(
                    "SELECT indexdef FROM pg_indexes "
                    + "WHERE tablename = 'service_types' "
                    + "  AND indexname = 'idx_service_types_platform_category'",
                    String.class);

            assertThat(indexDef)
                    .as("idx_service_types_platform_category must be a partial index "
                        + "scoped to WHERE is_active = true; a full index wastes storage "
                        + "on inactive/deleted service_type rows")
                    .containsIgnoringCase("where")
                    .containsIgnoringCase("is_active");
        }
    }

    // =========================================================================
    // shared helpers
    // =========================================================================

    private void assertPlatformCategory(String slug, String expectedCategory) {
        String actual = jdbcTemplate.queryForObject(
                "SELECT platform_category_name FROM service_types WHERE slug = ?",
                String.class, slug);

        assertThat(actual)
                .as("service_type '%s' must map to platform category '%s'",
                        slug, expectedCategory)
                .isEqualTo(expectedCategory);
    }

    private void assertBucketCount(String category, int expectedCount) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE platform_category_name = ?",
                Integer.class, category);

        assertThat(count)
                .as("platform category '%s' must contain exactly %d service_types "
                    + "(per V73 mapping table)", category, expectedCount)
                .isEqualTo(expectedCount);
    }
}
