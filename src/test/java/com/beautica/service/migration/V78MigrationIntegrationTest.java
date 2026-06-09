package com.beautica.service.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content-assertion IT for the V78 migration (Phase 16.9.4) against the real Flyway-applied
 * schema (Testcontainers). "Flyway applied with no error" proves nothing about the data —
 * these assertions pin V78's actual contract (QA pattern Q21):
 * <ol>
 *   <li>{@code service_definitions.is_draft} exists, NOT NULL, DEFAULT FALSE — an INSERT
 *       that omits it yields {@code false} (existing create paths stay non-draft);</li>
 *   <li>every seeded platform category that V78 maps carries a non-null
 *       {@code service_category_id} (e.g. HAIRDRESSING → the Hair bucket);</li>
 *   <li>a platform category V78 does NOT map (e.g. a brand-new self-service category)
 *       keeps {@code service_category_id} NULL — the promotion writer's fallback branch;</li>
 *   <li>the backfill UPDATEs are guarded {@code WHERE service_category_id IS NULL}, so a
 *       manual re-run touches zero already-mapped rows (idempotent / no-op).</li>
 * </ol>
 *
 * <p>{@link AbstractIntegrationTest#cleanDb()} does not truncate {@code platform_categories}
 * or {@code service_definitions} seed/reference state beyond rows it owns; the temporary
 * rows this test inserts are removed in {@link #cleanup()}.
 */
@DisplayName("V78 migration — content + idempotency assertions")
class V78MigrationIntegrationTest extends AbstractIntegrationTest {

    private static final UUID HAIR_BUCKET = UUID.fromString("11111111-0004-0000-0000-000000000000");
    private static final String UNMAPPED_CATEGORY = "V78_IT_UNMAPPED_CATEGORY";

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM service_definitions WHERE name = ?", "V78_IT_draft_default_probe");
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", UNMAPPED_CATEGORY);
    }

    @Test
    @DisplayName("service_definitions.is_draft defaults FALSE when an INSERT omits the column")
    void should_defaultIsDraftFalse_when_columnOmittedOnInsert() {
        UUID id = UUID.randomUUID();
        // INSERT deliberately omits is_draft — the V78 DEFAULT FALSE must apply so that
        // every legacy/create-path row is non-draft.
        jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, base_duration_minutes, price_type, base_price, is_active) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'V78_IT_draft_default_probe', 30, 'FIXED', 100.00, true)",
                id, UUID.randomUUID());

        Boolean isDraft = jdbcTemplate.queryForObject(
                "SELECT is_draft FROM service_definitions WHERE id = ?", Boolean.class, id);
        assertThat(isDraft).as("V78 ADD COLUMN ... DEFAULT FALSE applies when INSERT omits is_draft").isFalse();
    }

    @Test
    @DisplayName("mapped seeded platform category (HAIRDRESSING) has the Hair System-A bucket")
    void should_mapSeededCategoryToBucket_when_v78BackfillApplied() {
        UUID bucket = jdbcTemplate.queryForObject(
                "SELECT service_category_id FROM platform_categories WHERE name = 'HAIRDRESSING'",
                UUID.class);
        assertThat(bucket)
                .as("V78 backfilled HAIRDRESSING → Hair bucket (matches V75:51 assignment)")
                .isEqualTo(HAIR_BUCKET);
    }

    @Test
    @DisplayName("a platform category V78 does not map keeps service_category_id NULL (fallback branch)")
    void should_leaveServiceCategoryIdNull_when_categoryUnmapped() {
        // A brand-new self-service category that never received a V75 bucket: V78's name-list
        // backfill does not match it, so the column stays NULL (the promotion writer's
        // "cannot create a ServiceType" path).
        jdbcTemplate.update(
                "INSERT INTO platform_categories (name, display_name, active, status, created_at) "
                        + "VALUES (?, 'V78 IT Unmapped', true, 'APPROVED', NOW())",
                UNMAPPED_CATEGORY);

        UUID bucket = jdbcTemplate.queryForObject(
                "SELECT service_category_id FROM platform_categories WHERE name = ?",
                UUID.class, UNMAPPED_CATEGORY);
        assertThat(bucket)
                .as("unmapped category → service_category_id NULL (V78 backfill is name-scoped)")
                .isNull();
    }

    @Test
    @DisplayName("re-running a V78 backfill UPDATE is a no-op (guarded WHERE service_category_id IS NULL)")
    void should_beIdempotent_when_v78BackfillReRun() {
        // The actual V78 statement, verbatim guard. Already-mapped HAIRDRESSING must NOT be
        // re-touched (0 rows updated), proving the migration is safe to re-apply.
        int rows = jdbcTemplate.update(
                "UPDATE platform_categories "
                        + "SET service_category_id = '11111111-0004-0000-0000-000000000000' "
                        + "WHERE service_category_id IS NULL "
                        + "  AND name IN ('HAIRDRESSING', 'HAIR_COLORING', 'HAIR_TREATMENT', 'HAIR_EXTENSIONS', "
                        + "               'TRICHOLOGY', 'BARBERING', 'BEARD_CARE', 'SHAVING')");
        assertThat(rows)
                .as("re-running the V78 Hair backfill touches no already-mapped rows (idempotent)")
                .isZero();
    }
}
