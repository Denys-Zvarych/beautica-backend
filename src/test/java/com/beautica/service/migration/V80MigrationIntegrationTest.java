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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V80 migration-safety regression test (Step 2.7 QA gate, Rule 3 — bug-fix regression net;
 * QA pattern Q21 — a migration's correctness IS its contract, "Flyway applied with no error"
 * proves nothing about legacy data).
 *
 * <p><b>The bug this guards.</b> The Phase 16.9 Option-B draft writer (since reverted to
 * Option A, catalog-only) persisted {@code service_definitions} rows with the
 * {@code base_duration_minutes = 0} sentinel, permitted ONLY while V79's relaxed CHECK
 * {@code (is_draft OR base_duration_minutes > 0)} was live. V80 re-validates the STRICT V6
 * invariant {@code base_duration_minutes > 0} via {@code NOT VALID + VALIDATE CONSTRAINT}.
 * If any such legacy sentinel row exists in the deploy target (dev WAS deployed under V79),
 * {@code VALIDATE CONSTRAINT} scans the table, finds the offending row and ABORTS — Flyway
 * fails the migration and Railway enters a crash-loop (memory:
 * project_flyway_checksum_recovery). The fix in this same batch is a pre-VALIDATE cleanup
 * sweep in V80 that deletes the orphaned sentinel definitions (and their {@code master_services}
 * assignments) before the strict CHECK is validated.
 *
 * <p><b>Why a dedicated container.</b> {@link com.beautica.AbstractIntegrationTest}'s singleton
 * container is already fully migrated to V80 — {@code is_draft} is gone and the strict CHECK is
 * active, so a legacy sentinel row can no longer be inserted there. To genuinely reproduce the
 * deploy-target scenario we drive Flyway PROGRAMMATICALLY on a private container: migrate to
 * {@code target = 79} (the last schema state where the sentinel is legal), seed the legacy row
 * chain, then migrate to latest (V80) and assert the post-cleanup contract.
 *
 * <p><b>Assertions (post-cleanup behaviour — RED until backend-dev adds the V80 sweep).</b>
 * <ol>
 *   <li>the V79→V80 migration COMPLETES without throwing (no {@code VALIDATE CONSTRAINT} abort);</li>
 *   <li>the strict CHECK {@code base_duration_minutes > 0} is ACTIVE afterwards (a fresh
 *       {@code base_duration_minutes = 0} insert is rejected);</li>
 *   <li>the legacy sentinel definition AND its {@code master_services} assignment were
 *       cleaned up (zero rows remain).</li>
 * </ol>
 */
@DisplayName("V80 migration safety — legacy 0-minute draft sentinel does not crash VALIDATE CONSTRAINT")
class V80MigrationIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    // Stable IDs so assertions/cleanup can target the exact legacy chain.
    private static final UUID LEGACY_USER_ID = UUID.randomUUID();
    private static final UUID LEGACY_MASTER_ID = UUID.randomUUID();
    private static final UUID SENTINEL_DEF_ID = UUID.randomUUID();
    private static final UUID SENTINEL_ASSIGNMENT_ID = UUID.randomUUID();

    @BeforeAll
    static void startContainerAndSeedUnderV79() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // ── Arrange: migrate ONLY up to V79 — the last state where the sentinel is legal. ──
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("79")
                .load()
                .migrate();

        // The relaxed V79 CHECK must be live (otherwise the seed below cannot represent the
        // production hazard) and the column the sentinel relies on must exist.
        Integer isDraftCol = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'service_definitions' AND column_name = 'is_draft'",
                Integer.class);
        assertThat(isDraftCol)
                .as("precondition: under V79 schema state service_definitions.is_draft still exists")
                .isEqualTo(1);

        seedLegacySentinelChain();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * Seeds the exact legacy chain the Option-B writer produced: a master (with its user) →
     * a DRAFT service_definitions row carrying the {@code base_duration_minutes = 0} sentinel →
     * an inactive master_services assignment pointing at it. Valid under V79's relaxed duration
     * CHECK; would abort V80's VALIDATE CONSTRAINT if the cleanup sweep is absent.
     */
    private static void seedLegacySentinelChain() {
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, now(), now())",
                LEGACY_USER_ID, "v80-legacy-" + LEGACY_USER_ID + "@example.com", "x");

        jdbc.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, now(), now())",
                LEGACY_MASTER_ID, LEGACY_USER_ID);

        // The hazard: base_duration_minutes = 0 + is_draft = TRUE — legal ONLY under V79.
        jdbc.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, category, base_duration_minutes, "
                        + " base_price, price_type, buffer_minutes_after, is_active, is_draft, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Legacy Draft Sentinel', 'LASHES', 0, "
                        + " 0, 'FIXED', 0, false, true, now(), now())",
                SENTINEL_DEF_ID, LEGACY_MASTER_ID);

        jdbc.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, false, now(), now())",
                SENTINEL_ASSIGNMENT_ID, LEGACY_MASTER_ID, SENTINEL_DEF_ID);

        // Sanity: the sentinel row is actually present under V79 before V80 runs.
        Long sentinelCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE id = ?", Long.class, SENTINEL_DEF_ID);
        assertThat(sentinelCount)
                .as("precondition: the 0-minute draft sentinel is seeded under V79")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("V80 completes (no VALIDATE abort), strict CHECK is active, and the legacy sentinel + its assignment are cleaned up")
    void should_notCrashAndCleanUpSentinel_when_legacyDraftRowExistsAtV80() {
        // ── Act: run the remaining migration (V80) over a DB that holds the legacy sentinel. ──
        // If V80 lacks the pre-VALIDATE cleanup sweep, VALIDATE CONSTRAINT aborts and migrate()
        // throws here (the production crash-loop) — this is the RED-pending-fix signal.
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // (a) migration completed — getting here at all proves no VALIDATE abort. Confirm V80
        // is recorded as applied and the dead column is gone.
        Integer isDraftCol = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'service_definitions' AND column_name = 'is_draft'",
                Integer.class);
        assertThat(isDraftCol)
                .as("V80 dropped service_definitions.is_draft")
                .isZero();

        // (c) the legacy sentinel definition AND its assignment were swept (FK-order: assignment first).
        Long sentinelAssignments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE id = ?", Long.class, SENTINEL_ASSIGNMENT_ID);
        assertThat(sentinelAssignments)
                .as("V80 pre-VALIDATE sweep deleted the sentinel's master_services assignment")
                .isZero();

        Long sentinelDefs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE id = ?", Long.class, SENTINEL_DEF_ID);
        assertThat(sentinelDefs)
                .as("V80 pre-VALIDATE sweep deleted the 0-minute sentinel service_definition")
                .isZero();

        // Belt-and-braces: NO 0-minute rows survive anywhere (the sweep targets all of them).
        Long anyZeroDuration = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE base_duration_minutes <= 0", Long.class);
        assertThat(anyZeroDuration)
                .as("no base_duration_minutes <= 0 row may survive V80 — VALIDATE would have aborted otherwise")
                .isZero();

        // (b) the strict CHECK is ACTIVE: a fresh base_duration_minutes = 0 insert is rejected.
        assertThat(constraintRejectsZeroDuration())
                .as("V80 re-added the strict chk_service_def_base_duration (> 0) and it is enforced")
                .isTrue();
    }

    /** @return true iff inserting a base_duration_minutes = 0 row is rejected by the DB CHECK. */
    private boolean constraintRejectsZeroDuration() {
        UUID probeId = UUID.randomUUID();
        try {
            jdbc.update(
                    "INSERT INTO service_definitions "
                            + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, price_type, "
                            + " buffer_minutes_after, is_active, created_at, updated_at) "
                            + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Strict Check Probe', ?, 0, 10, 'FIXED', 0, true, now(), now())",
                    probeId, LEGACY_MASTER_ID, resolveServiceTypeId());
            // Insert unexpectedly succeeded — strict CHECK not enforced. Roll back the probe.
            jdbc.update("DELETE FROM service_definitions WHERE id = ?", probeId);
            return false;
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            return true;
        }
    }

    /**
     * Resolves a real, selectable {@code service_types.id} from THIS test's private container.
     * By the time this probe runs, the full migration set (through latest, including V111's
     * NOT NULL + V13/V64/V73-V75/V81's taxonomy seed data) has already applied, so a real,
     * active, APPROVED-category type is available — the specific category is irrelevant to the
     * base_duration_minutes CHECK this probe exercises.
     */
    private static UUID resolveServiceTypeId() {
        return jdbc.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }
}
