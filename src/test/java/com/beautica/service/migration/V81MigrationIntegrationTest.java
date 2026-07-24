package com.beautica.service.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V81 migration-safety regression test (Step 2.7 QA gate; QA pattern Q21 — a migration's
 * correctness IS its contract, "Flyway applied with no error" proves nothing about the
 * reconciled taxonomy).
 *
 * <p><b>What V81 reconciles.</b> The live taxonomy (V74 platform_categories + V75
 * service_types) carries 21 categories with a standalone "Гоління" (SHAVING) category. The
 * user's final list has 20: SHAVING folds into "Борода та вуса" (BEARD_CARE). V81:
 * <ol>
 *   <li>re-parents the 5 {@code shaving-*} service_types onto {@code BEARD_CARE} (Hair bucket,
 *       unchanged) and renames their slugs to {@code beard-*};</li>
 *   <li>guarantees all 5 {@code beard-*} shaving leaves exist (idempotent upsert);</li>
 *   <li>hard-deletes the stray {@code nail-service-pedicure-toes} leaf;</li>
 *   <li>soft-disables the {@code SHAVING} platform_categories row (active = FALSE).</li>
 * </ol>
 *
 * <p><b>Why a dedicated container.</b> Driving Flyway programmatically on a private container
 * (migrate to latest, then assert) isolates this from {@link com.beautica.AbstractIntegrationTest}'s
 * singleton and proves the full V74/V75 → V81 reconciliation end-state against a real PostgreSQL.
 *
 * <p><b>Assertions (post-V81 contract).</b>
 * <ol>
 *   <li>BEARD_CARE has exactly 12 active service_types, including the 5 {@code beard-*} shaving
 *       rows keyed by {@code name_uk} (Королівське / Класичне / голови / Контурне / шиї);</li>
 *   <li>the {@code SHAVING} platform_category is {@code active = FALSE} and absent from the
 *       approved-category read path, leaving exactly 20 active categories;</li>
 *   <li>no service_type still carries {@code platform_category_name = 'SHAVING'};</li>
 *   <li>{@code nail-service-pedicure-toes} no longer exists;</li>
 *   <li>the reconciled invariants are stable — re-running migrate() is a no-op (Flyway will not
 *       re-apply V81; we assert the invariants still hold).</li>
 * </ol>
 */
@DisplayName("V81 migration — SHAVING folds into BEARD_CARE, 20 active categories, pedicure-toes removed")
class V81MigrationIntegrationTest {

    private static final String BEARD_CARE = "BEARD_CARE";
    private static final String SHAVING = "SHAVING";

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startContainerAndMigrateToLatest() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // ── Act: migrate the whole chain (V1 .. V81) on a clean DB. ──
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("BEARD_CARE has exactly 12 active service_types including the 5 folded beard-* shaving rows")
    void should_haveTwelveActiveBeardCareTypes_when_shavingFoldedIn() {
        Long beardCareActive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE platform_category_name = ? AND is_active = TRUE",
                Long.class, BEARD_CARE);
        assertThat(beardCareActive)
                .as("BEARD_CARE = 7 original beard rows + 5 folded shaving rows")
                .isEqualTo(12L);

        List<String> shavingNamesUk = jdbc.queryForList(
                "SELECT name_uk FROM service_types "
                        + "WHERE platform_category_name = ? AND slug LIKE 'beard-%-shave' AND is_active = TRUE",
                String.class, BEARD_CARE);
        assertThat(shavingNamesUk)
                .as("the 5 folded shaving leaves live under BEARD_CARE with beard-*-shave slugs")
                .containsExactlyInAnyOrder(
                        "Королівське гоління (небезпечна бритва)",
                        "Класичне гоління",
                        "Гоління голови",
                        "Контурне гоління",
                        "Гоління шиї");

        // All folded rows sit in the Hair coarse bucket (0004), unchanged.
        Long wrongBucket = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_types "
                        + "WHERE slug LIKE 'beard-%-shave' "
                        + "  AND category_id <> '11111111-0004-0000-0000-000000000000'",
                Long.class);
        assertThat(wrongBucket)
                .as("folded shaving leaves keep the Hair bucket category_id")
                .isZero();
    }

    @Test
    @DisplayName("SHAVING platform_category is soft-disabled, leaving exactly 20 active categories")
    void should_softDisableShaving_when_migratedToLatest() {
        Boolean shavingActive = jdbc.queryForObject(
                "SELECT active FROM platform_categories WHERE name = ?", Boolean.class, SHAVING);
        assertThat(shavingActive)
                .as("SHAVING is soft-disabled (kept for UNIQUE(name) guard + FK precedent), not deleted")
                .isFalse();

        // SHAVING row still physically present (soft-disable, not DELETE).
        Long shavingRow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_categories WHERE name = ?", Long.class, SHAVING);
        assertThat(shavingRow).as("SHAVING row is retained, not deleted").isEqualTo(1L);

        // Approved read path (active = TRUE) now yields exactly 20 categories.
        Long activeApproved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_categories WHERE active = TRUE AND status = 'APPROVED'",
                Long.class);
        assertThat(activeApproved)
                .as("final list = 20 active approved categories (21 seeded minus folded SHAVING)")
                .isEqualTo(20L);

        Long shavingInApproved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_categories WHERE name = ? AND active = TRUE AND status = 'APPROVED'",
                Long.class, SHAVING);
        assertThat(shavingInApproved)
                .as("SHAVING is absent from the approved-category read path")
                .isZero();
    }

    @Test
    @DisplayName("no service_type carries platform_category_name = 'SHAVING' after folding")
    void should_haveNoServiceTypeUnderShaving_when_migratedToLatest() {
        Long underShaving = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE platform_category_name = ?",
                Long.class, SHAVING);
        assertThat(underShaving)
                .as("every shaving leaf was re-parented to BEARD_CARE")
                .isZero();

        Long oldShavingSlugs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE slug LIKE 'shaving-%'", Long.class);
        assertThat(oldShavingSlugs)
                .as("no legacy shaving-* slug survives the rename")
                .isZero();
    }

    @Test
    @DisplayName("nail-service-pedicure-toes is removed from the final taxonomy")
    void should_deletePedicureToes_when_migratedToLatest() {
        Long pedicureToes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE slug = 'nail-service-pedicure-toes'",
                Long.class);
        assertThat(pedicureToes)
                .as("the stray pedicure-toes leaf is hard-deleted")
                .isZero();
    }

    @Test
    @DisplayName("re-running migrate() is a no-op — V81 invariants remain stable")
    void should_remainStable_when_migrateRunAgain() {
        // Flyway will not re-apply V81 (already recorded); assert the end-state invariants hold.
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        Long beardCareActive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE platform_category_name = ? AND is_active = TRUE",
                Long.class, BEARD_CARE);
        assertThat(beardCareActive).as("BEARD_CARE still has 12 active types after re-migrate").isEqualTo(12L);

        Boolean shavingActive = jdbc.queryForObject(
                "SELECT active FROM platform_categories WHERE name = ?", Boolean.class, SHAVING);
        assertThat(shavingActive).as("SHAVING stays soft-disabled after re-migrate").isFalse();

        Long pedicureToes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_types WHERE slug = 'nail-service-pedicure-toes'",
                Long.class);
        assertThat(pedicureToes).as("pedicure-toes stays deleted after re-migrate").isZero();
    }
}
