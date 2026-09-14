package com.beautica.service.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Q7 (2026-09-13 audit) — {@code V165}'s fail-fast {@code DO $$} guard (lines 28-43), which had no
 * test of any kind.
 *
 * <p><b>What it guards.</b> The D8 backfill derives {@code price_type_override} from the linked
 * {@code service_definitions.price_type}. If a {@code master_services} row carries a
 * {@code price_override} but its definition is missing or has a NULL {@code price_type}, the
 * backfill leaves that row with a floor and no shape — a PARTIAL band — and
 * {@code VALIDATE CONSTRAINT chk_master_service_price_mode} then aborts the migration with a bare
 * constraint name and no indication of which rows or why. The {@code DO} block exists to abort
 * FIRST, with a row count and an explanation. Mirrors V67's own MEDIUM-1 guard.
 *
 * <p><b>Why this needs its own container and its own class.</b> The guard fires DURING migration,
 * so it can only be observed by migrating a database that already holds the offending row — which
 * means stopping at V164, seeding, and then migrating. {@code MasterServiceBandBackfillIT}'s
 * container is already at HEAD by the time any of its tests run, and {@code
 * AbstractIntegrationTest}'s shared container is at HEAD before any {@code @SpringBootTest} starts.
 * A dedicated container is the only harness that can see a migration FAIL.
 *
 * <p><b>ONE container, one per-test DATABASE (2026-09-13 cycle-2 audit, B9).</b> That argument
 * justifies <em>a</em> dedicated container, never one PER TEST: this class used to start and stop a
 * fresh {@code PostgreSQLContainer} in each test method, paying the image start twice to replay
 * V1-V164 twice. The container is now {@code static}, started once in {@link #startContainer()};
 * isolation comes from {@code CREATE DATABASE} per test ({@link #migrateToV164()}), which is what
 * the tests actually need — a virgin schema Flyway can migrate from scratch — and is far cheaper
 * than a container. {@code Flyway.clean()} was the alternative and is deliberately not used: it
 * would leave the two tests sharing one database and therefore one failure mode if a clean ever
 * half-succeeded.
 *
 * <p><b>The NOT NULL drop is deliberate, not a shortcut.</b> {@code service_definitions.price_type}
 * is {@code NOT NULL} since V67, so the guard's condition is unreachable through the V164 schema as
 * shipped — which is exactly why it is a defensive guard. Dropping the constraint in the fixture
 * reproduces the only state in which the guard can matter (a hand-repaired or partially-restored
 * database), and is the single smallest deviation that makes the branch observable at all.
 */
@DisplayName("V165 — the DO $$ fail-fast guard aborts the migration before VALIDATE CONSTRAINT")
class V165FailFastGuardIT {

    /**
     * ONE container for the whole class (B9). Not {@code @Container}-managed and not stopped in an
     * {@code @AfterAll}: Testcontainers' Ryuk sidecar reaps it when the JVM exits, exactly as for
     * {@code MasterServiceBandBackfillIT}'s own static container.
     */
    private static PostgreSQLContainer<?> postgres;

    /** Per-test database name counter — isolation without a second container. */
    private static int databaseSequence;

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startContainer() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @Test
    @DisplayName("Q7: a price_override row whose definition has a NULL price_type aborts V165 with "
            + "the guard's own message and row count — not an opaque constraint-name failure")
    void should_abortWithExplanatoryMessage_when_aPriceOverrideRowCannotDeriveItsShape() {
        migrateToV164();

        UUID salonId = insertSalonWithOwner();
        UUID masterId = insertSalonMaster(salonId);
        List<UUID> typeIds = resolveServiceTypeIds(1);

        // Reproduce the only state the guard can fire in (see the class javadoc).
        jdbc.execute("ALTER TABLE service_definitions ALTER COLUMN price_type DROP NOT NULL");
        jdbc.execute("ALTER TABLE service_definitions DROP CONSTRAINT chk_service_def_price_type");
        jdbc.execute("ALTER TABLE service_definitions DROP CONSTRAINT chk_service_def_price_mode");

        UUID defId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, price_max, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Shapeless', ?, 60, NULL, 400.00, NULL, 0, true, now(), now())",
                defId, salonId, typeIds.get(0));
        jdbc.update(
                "INSERT INTO master_services (id, master_id, service_def_id, price_override, "
                        + "is_active, created_at, updated_at) VALUES (?, ?, ?, ?, true, now(), now())",
                UUID.randomUUID(), masterId, defId, new BigDecimal("550.00"));

        Throwable thrown = catchThrowable(this::migrateToHead);

        assertThat(thrown)
                .as("V165 must REFUSE to run against a row whose shape it cannot derive")
                .isNotNull();
        assertThat(rootMessage(thrown))
                .as("the abort must name the guard's own diagnosis and the offending row count, "
                        + "not just 'chk_master_service_price_mode'")
                .contains("V165 aborted")
                .contains("1 master_services row(s)")
                .contains("price_type");

        assertThat(columnExists("master_services", "price_type_override"))
                .as("the migration is transactional — a failed V165 leaves the schema at V164, so "
                        + "the next deploy retries cleanly rather than half-applying")
                .isFalse();
    }

    @Test
    @DisplayName("Q7 non-vacuity: the SAME fixture with a derivable price_type migrates cleanly and "
            + "the backfill lifts the row — the guard rejects the data, not the shape of the test")
    void should_migrateCleanly_when_theSameRowCanDeriveItsShape() {
        migrateToV164();

        UUID salonId = insertSalonWithOwner();
        UUID masterId = insertSalonMaster(salonId);
        List<UUID> typeIds = resolveServiceTypeIds(1);

        UUID defId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, price_max, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Shaped', ?, 60, 'FIXED', 400.00, NULL, 0, true, now(), now())",
                defId, salonId, typeIds.get(0));
        UUID assignmentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO master_services (id, master_id, service_def_id, price_override, "
                        + "is_active, created_at, updated_at) VALUES (?, ?, ?, ?, true, now(), now())",
                assignmentId, masterId, defId, new BigDecimal("550.00"));

        migrateToHead();

        assertThat(jdbc.queryForObject(
                "SELECT price_type_override FROM master_services WHERE id = ?", String.class, assignmentId))
                .as("the guard passed and the D8 backfill ran")
                .isEqualTo("FIXED");
    }

    // ── harness ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a FRESH, empty database on the shared container and migrates it to exactly V164.
     * Each test gets its own database, so a failed migration in one cannot be observed by the
     * other and no {@code clean()} is needed between them.
     */
    private void migrateToV164() {
        String database = "v165guard_" + (++databaseSequence);
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("CREATE DATABASE " + database);

        dataSource = new DriverManagerDataSource(
                jdbcUrlFor(database), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("164"))
                .load()
                .migrate();
    }

    private void migrateToHead() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * Swaps the database name in the container's JDBC URL, keeping host, port and every query
     * parameter. Built by index rather than {@code replaceFirst} so the container's own database
     * name cannot be matched somewhere else in the URL.
     */
    private static String jdbcUrlFor(String database) {
        String url = postgres.getJdbcUrl();
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        String params = query < 0 ? "" : url.substring(query);
        return base.substring(0, base.lastIndexOf('/') + 1) + database + params;
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            sb.append(cur.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }

    private UUID insertSalonWithOwner() {
        UUID ownerId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, created_at, updated_at) "
                        + "VALUES (?, ?, 'x', 'SALON_OWNER', true, true, now(), now())",
                ownerId, "v165guard-owner-" + ownerId + "@beautica.test");
        UUID salonId = UUID.randomUUID();
        UUID cityId = jdbc.queryForObject("SELECT id FROM cities LIMIT 1", UUID.class);
        jdbc.update(
                "INSERT INTO salons (id, owner_id, name, is_active, city_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'V165 Guard Salon', true, ?, now(), now())",
                salonId, ownerId, cityId);
        return salonId;
    }

    private UUID insertSalonMaster(UUID salonId) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified, created_at, updated_at) "
                        + "VALUES (?, ?, 'x', 'SALON_MASTER', ?, true, true, now(), now())",
                userId, "v165guard-master-" + userId + "@beautica.test", salonId);
        UUID masterId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, now(), now())",
                masterId, userId, salonId);
        return masterId;
    }

    private List<UUID> resolveServiceTypeIds(int n) {
        return jdbc.queryForList(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT ?",
                UUID.class, n);
    }
}
