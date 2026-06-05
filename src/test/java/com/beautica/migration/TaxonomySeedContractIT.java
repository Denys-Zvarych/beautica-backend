package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dedicated <b>seed-contract</b> test for the V74 / V75 taxonomy.
 *
 * <p>This pins the genuinely-new data invariants that
 * {@link ReparentServiceTypesMigrationTest} does <em>not</em> already assert, so a
 * future seed edit cannot silently drift. The slug/name source of truth is
 * {@code docs/backend-phases/phase-17.1-taxonomy-slug-artifact.md}.
 *
 * <p><b>Division of labour</b> — to avoid a redundant shell, the totals (140 /
 * 21 / 26), the rename + soft-disable sets, the per-platform-category active leaf
 * counts, zero-orphans, NOT-NULL columns and the closed-set DB guard are owned by
 * {@code ReparentServiceTypesMigrationTest}. This class owns only:
 * <ol>
 *   <li><b>Slug surface</b> — all 140 slugs are globally unique and every one
 *       matches the DB CHECK regex {@code ^[a-z0-9][a-z0-9\-]*[a-z0-9]$}
 *       (asserted per-row in app code, not just trusting the DB constraint).</li>
 *   <li><b>Display names</b> — {@code name_uk} and {@code name_en} are non-null
 *       and non-blank on every one of the 140 rows (the reparent test never looks
 *       at names at all).</li>
 *   <li><b>category_id FK integrity</b> — every {@code category_id} resolves to a
 *       real {@code service_categories} row (not merely NOT NULL).</li>
 *   <li><b>Coarse-bucket distribution</b> — the 21→8 {@code service_categories}
 *       leaf distribution matches the phase-17.1 "Bucket distribution" table.</li>
 *   <li><b>Cross-category duplicate display name → distinct slugs</b> — the
 *       known collision {@code Корекція} exists under 3 platform categories with 3
 *       distinct slugs (the whole reason slugs are category-prefixed).</li>
 * </ol>
 *
 * <p>All assertions are read-only against the Testcontainers post-migration state.
 * {@link AbstractIntegrationTest#cleanDb()} does not truncate {@code service_types},
 * {@code platform_categories} or {@code service_categories} (reference / catalog
 * data), so these tests are order-independent and need no AfterEach cleanup.
 */
@DisplayName("V74/V75 taxonomy seed — data-contract invariants")
class TaxonomySeedContractIT extends AbstractIntegrationTest {

    private static final int TOTAL_SERVICE_TYPES = 140;

    /**
     * The DB CHECK on {@code service_types.slug}
     * (chk_service_types_slug): no leading/trailing hyphen, min length 2,
     * lowercase alnum + internal hyphens only. Re-asserted in app code so the
     * contract is pinned even on an env where the CHECK was added late.
     */
    private static final Pattern SLUG_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9\\-]*[a-z0-9]$");

    // =========================================================================
    // 1. Slug surface — global uniqueness + regex on all 140
    // =========================================================================

    @Nested
    @DisplayName("slug surface — globally unique + regex-valid")
    class SlugSurface {

        @Test
        @DisplayName("all 140 slugs are globally unique (zero duplicates across categories)")
        void should_haveGloballyUniqueSlugs_when_v75Applied() {
            List<String> slugs = jdbcTemplate.queryForList(
                    "SELECT slug FROM service_types", String.class);
            Integer distinctSlugs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT slug) FROM service_types", Integer.class);

            assertThat(slugs)
                    .as("V75 seeds exactly 140 slug rows")
                    .hasSize(TOTAL_SERVICE_TYPES);
            assertThat(distinctSlugs)
                    .as("every service_type slug must be globally unique — %d distinct of %d rows",
                            distinctSlugs, slugs.size())
                    .isEqualTo(TOTAL_SERVICE_TYPES);
        }

        @Test
        @DisplayName("every slug matches the DB CHECK regex ^[a-z0-9][a-z0-9-]*[a-z0-9]$")
        void should_matchSlugRegexOnEveryRow_when_v75Applied() {
            List<String> slugs = jdbcTemplate.queryForList(
                    "SELECT slug FROM service_types ORDER BY slug", String.class);

            assertThat(slugs)
                    .as("all 140 slugs must satisfy the slug CHECK regex")
                    .hasSize(TOTAL_SERVICE_TYPES)
                    .allSatisfy(slug -> assertThat(SLUG_PATTERN.matcher(slug).matches())
                            .as("slug '%s' must match ^[a-z0-9][a-z0-9-]*[a-z0-9]$", slug)
                            .isTrue());
        }
    }

    // =========================================================================
    // 2. Display names — name_uk / name_en non-null + non-blank on all 140
    // =========================================================================

    @Nested
    @DisplayName("display names — name_uk / name_en non-null + non-blank")
    class DisplayNames {

        @Test
        @DisplayName("no service_type has a NULL or blank name_uk")
        void should_haveNonBlankNameUk_when_v75Applied() {
            Integer bad = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_types "
                    + "WHERE name_uk IS NULL OR btrim(name_uk) = ''",
                    Integer.class);

            assertThat(bad)
                    .as("every one of the 140 service_types must carry a non-blank name_uk")
                    .isZero();
        }

        @Test
        @DisplayName("no service_type has a NULL or blank name_en")
        void should_haveNonBlankNameEn_when_v75Applied() {
            Integer bad = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_types "
                    + "WHERE name_en IS NULL OR btrim(name_en) = ''",
                    Integer.class);

            assertThat(bad)
                    .as("every one of the 140 service_types must carry a non-blank name_en")
                    .isZero();
        }
    }

    // =========================================================================
    // 3. category_id FK integrity — resolves to a real service_categories row
    // =========================================================================

    @Nested
    @DisplayName("category_id — resolves to a real service_categories row")
    class CategoryIdIntegrity {

        @Test
        @DisplayName("every service_type.category_id resolves to an existing service_categories row")
        void should_resolveEveryCategoryId_when_v75Applied() {
            // Anti-join: any service_type whose category_id has no matching
            // service_categories row is an orphan. The FK is ON DELETE RESTRICT but
            // this pins the seed picked real V13 bucket UUIDs, not stray ones.
            Integer orphans = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_types st "
                    + "WHERE NOT EXISTS ("
                    + "    SELECT 1 FROM service_categories sc WHERE sc.id = st.category_id"
                    + ")",
                    Integer.class);

            assertThat(orphans)
                    .as("every service_type.category_id must resolve to a real "
                        + "service_categories bucket — zero orphans")
                    .isZero();
        }
    }

    // =========================================================================
    // 4. Coarse-bucket distribution (21 platform categories → 8 buckets)
    // =========================================================================

    @Nested
    @DisplayName("coarse-bucket distribution — matches the phase-17.1 table")
    class BucketDistribution {

        // phase-17.1 § "Bucket distribution (21 → 8)" — leaf count per coarse bucket.
        @Test
        @DisplayName("Nails bucket (0001) holds 17 leaves")
        void should_have17NailLeaves_when_v75Applied() {
            assertBucketLeafCount("11111111-0001-0000-0000-000000000000", 17);
        }

        @Test
        @DisplayName("Eyelashes bucket (0002) holds 13 leaves")
        void should_have13EyelashLeaves_when_v75Applied() {
            assertBucketLeafCount("11111111-0002-0000-0000-000000000000", 13);
        }

        @Test
        @DisplayName("Brows bucket (0003) holds 8 leaves")
        void should_have8BrowLeaves_when_v75Applied() {
            assertBucketLeafCount("11111111-0003-0000-0000-000000000000", 8);
        }

        @Test
        @DisplayName("Hair bucket (0004) holds 56 leaves")
        void should_have56HairLeaves_when_v75Applied() {
            assertBucketLeafCount("11111111-0004-0000-0000-000000000000", 56);
        }

        @Test
        @DisplayName("Face/Skin bucket (0005) holds 26 leaves")
        void should_have26FaceLeaves_when_v75Applied() {
            assertBucketLeafCount("11111111-0005-0000-0000-000000000000", 26);
        }

        @Test
        @DisplayName("Body bucket (0006) holds 6 leaves")
        void should_have6BodyLeaves_when_v75Applied() {
            assertBucketLeafCount("11111111-0006-0000-0000-000000000000", 6);
        }

        @Test
        @DisplayName("Makeup bucket (0007) holds 14 leaves")
        void should_have14MakeupLeaves_when_v75Applied() {
            assertBucketLeafCount("11111111-0007-0000-0000-000000000000", 14);
        }

        @Test
        @DisplayName("Інше bucket (0008) holds 0 leaves — no category files under it")
        void should_have0OtherLeaves_when_v75Applied() {
            assertBucketLeafCount("11111111-0008-0000-0000-000000000000", 0);
        }
    }

    // =========================================================================
    // 5. Cross-category duplicate display name → distinct slugs
    // =========================================================================

    @Nested
    @DisplayName("duplicate display name across categories → distinct slugs")
    class DuplicateNameDistinctSlug {

        @Test
        @DisplayName("'Корекція' exists under 3 platform categories with 3 distinct slugs")
        void should_haveDistinctSlugsForDuplicateName_when_v75Applied() {
            // 'Корекція' is the canonical cross-category collision: it appears under
            // HAIR_EXTENSIONS, BROWS and PERMANENT_MAKEUP. Category-prefixed slugs
            // keep all three globally unique (phase-17.1 § slug rationale).
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT platform_category_name, slug FROM service_types "
                    + "WHERE name_uk = 'Корекція' ORDER BY slug");

            assertThat(rows)
                    .as("'Корекція' must be seeded under exactly 3 platform categories")
                    .hasSize(3);

            assertThat(rows)
                    .extracting(r -> (String) r.get("platform_category_name"))
                    .as("the 3 'Корекція' rows must sit under HAIR_EXTENSIONS, BROWS and PERMANENT_MAKEUP")
                    .containsExactlyInAnyOrder("HAIR_EXTENSIONS", "BROWS", "PERMANENT_MAKEUP");

            assertThat(rows)
                    .extracting(r -> (String) r.get("slug"))
                    .as("each duplicate-name row must carry a distinct, category-prefixed slug")
                    .containsExactlyInAnyOrder(
                            "hair-extensions-correction", "brows-correction", "permanent-makeup-correction")
                    .doesNotHaveDuplicates();
        }
    }

    // =========================================================================
    // shared helpers
    // =========================================================================

    private void assertBucketLeafCount(String bucketId, int expectedCount) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE category_id = ?::uuid",
                Integer.class, bucketId);

        assertThat(count)
                .as("coarse bucket %s must hold exactly %d leaves (per phase-17.1 distribution)",
                        bucketId, expectedCount)
                .isEqualTo(expectedCount);
    }
}
