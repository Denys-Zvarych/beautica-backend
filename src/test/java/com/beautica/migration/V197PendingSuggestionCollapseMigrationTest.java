package com.beautica.migration;

import com.beautica.TestPostgresImage;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for V197's duplicate-collapse UPDATE — the one statement in the race fix that only ever
 * acts on PRE-EXISTING data, so {@code AbstractIntegrationTest}'s empty, fully-migrated container runs it
 * as a permanent no-op and can never exercise it.
 *
 * <p>Mirrors {@code V146RefreshTokenFamilyIdBackfillMigrationTest}: a private container is migrated to
 * V196 (the schema V197 really runs against — V76's NON-unique dedup index, so duplicate PENDING rows are
 * insertable), legacy-shaped rows are seeded once, then Flyway migrates to head ONCE in {@code @BeforeAll}.
 * Every test reads the post-migration state; none mutates it except the last, which only adds a row inside
 * a statement that is expected to fail.
 *
 * <p>Fixture ids are deliberately fixed (not random): the {@code (created_at, id)} tie-break is a
 * byte-order UUID comparison, so the test must control which id sorts first.
 */
@DisplayName("V197 migration — collapse pre-existing duplicate PENDING suggestions, then enforce uniqueness")
class V197PendingSuggestionCollapseMigrationTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.UTC);

    // Group A — three PENDING rows, case-variant names, distinct created_at. OLDEST is the middle id.
    private static final UUID A_OLDEST = UUID.fromString("a0000000-0000-0000-0000-000000000005");
    private static final UUID A_NEWER = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID A_NEWEST = UUID.fromString("a0000000-0000-0000-0000-000000000009");
    // Group B — two PENDING rows with IDENTICAL created_at → the lower id must survive.
    private static final UUID B_LOW_ID = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID B_HIGH_ID = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    // Non-duplicates that must be left untouched.
    private static final UUID SAME_NAME_OTHER_CATEGORY = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    private static final UUID TRAILING_SPACE_VARIANT = UUID.fromString("c0000000-0000-0000-0000-000000000002");
    private static final UUID OLDER_REJECTED_SAME_KEY = UUID.fromString("c0000000-0000-0000-0000-000000000003");
    private static final UUID SINGLETON_PENDING = UUID.fromString("c0000000-0000-0000-0000-000000000004");

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static OffsetDateTime migrationStartedAt;

    @BeforeAll
    static void migrateTo196SeedDuplicatesThenMigrateToHead() {
        postgres = new PostgreSQLContainer<>(TestPostgresImage.IMAGE);
        postgres.start();
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("196")).load().migrate();

        // Group A: oldest row has the MIDDLE uuid, so an id-only ordering would pick the wrong survivor.
        insertPending(A_NEWER, "HAIRDRESSING", "Hot Towel Shave", T0.plusMinutes(5), "hash-a-newer");
        insertPending(A_OLDEST, "HAIRDRESSING", "hot towel shave", T0, "hash-a-oldest");
        insertPending(A_NEWEST, "HAIRDRESSING", "HOT TOWEL SHAVE", T0.plusMinutes(9), "hash-a-newest");
        // Group B: created_at tie.
        insertPending(B_HIGH_ID, "NAILS", "Gel Tips", T0, "hash-b-high");
        insertPending(B_LOW_ID, "NAILS", "gel tips", T0, "hash-b-low");
        // Not duplicates of group A: other category (exact match, no lower()), trailing space (no trim).
        insertPending(SAME_NAME_OTHER_CATEGORY, "BARBER", "Hot Towel Shave", T0.plusMinutes(1), "hash-c-other-cat");
        insertPending(TRAILING_SPACE_VARIANT, "HAIRDRESSING", "Hot Towel Shave ", T0.plusMinutes(2), "hash-c-space");
        // An OLDER decided row with group B's key must not make group B's survivor lose.
        jdbc.update("""
                INSERT INTO service_type_suggestion (id, category_name, suggested_name, status, decided_at, created_at)
                VALUES (?, 'NAILS', 'Gel Tips', 'REJECTED', ?, ?)
                """, OLDER_REJECTED_SAME_KEY, T0.minusDays(2), T0.minusDays(3));
        insertPending(SINGLETON_PENDING, "MAKEUP", "Bridal Trial", T0, "hash-c-singleton");

        Integer v76Duplicates = jdbc.queryForObject("""
                SELECT count(*) FROM service_type_suggestion WHERE status = 'PENDING'
                   AND category_name = 'HAIRDRESSING' AND lower(suggested_name) = 'hot towel shave'
                """, Integer.class);
        assertThat(v76Duplicates).as("precondition: V76's non-unique index admits the duplicates").isEqualTo(3);

        migrationStartedAt = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void insertPending(UUID id, String category, String name, OffsetDateTime createdAt, String tokenHash) {
        jdbc.update("""
                INSERT INTO service_type_suggestion
                    (id, category_name, suggested_name, status, token_hash, token_expires_at, created_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?)
                """, id, category, name, tokenHash, createdAt.plusDays(7), createdAt);
    }

    private static Map<String, Object> row(UUID id) {
        return jdbc.queryForMap(
                "SELECT status, token_hash, token_expires_at, decided_at, decision_reason "
                        + "FROM service_type_suggestion WHERE id = ?", id);
    }

    @Test
    @DisplayName("V197 + V198 recorded as successful after collapsing real duplicates")
    void should_recordV197AndV198Successful_when_duplicatesExistedBeforeMigration() {
        Integer successful = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IN ('197','198') AND success",
                Integer.class);

        assertThat(successful).isEqualTo(2);
    }

    @Test
    @DisplayName("group with distinct created_at — the OLDEST row stays PENDING with its token intact")
    void should_keepOldestPendingWithToken_when_groupHasDistinctCreatedAt() {
        assertThat(row(A_OLDEST))
                .containsEntry("status", "PENDING")
                .containsEntry("token_hash", "hash-a-oldest")
                .containsEntry("decided_at", null);
    }

    @Test
    @DisplayName("group with distinct created_at — every newer row is REJECTED, token cleared, decided_at set, no reason")
    void should_rejectNewerRowsAndClearTokens_when_groupHasDistinctCreatedAt() {
        for (UUID loser : new UUID[] {A_NEWER, A_NEWEST}) {
            Map<String, Object> r = row(loser);
            assertThat(r).as("loser %s", loser)
                    .containsEntry("status", "REJECTED")
                    .containsEntry("token_hash", null)
                    .containsEntry("token_expires_at", null)
                    .containsEntry("decision_reason", null);
            assertThat(((java.sql.Timestamp) r.get("decided_at")).toInstant())
                    .as("decided_at stamped by the migration, loser %s", loser)
                    .isAfterOrEqualTo(migrationStartedAt.toInstant());
        }
    }

    @Test
    @DisplayName("created_at tie — the lower id survives, the higher id is REJECTED")
    void should_keepLowerId_when_createdAtTies() {
        assertThat(row(B_LOW_ID)).containsEntry("status", "PENDING").containsEntry("token_hash", "hash-b-low");
        assertThat(row(B_HIGH_ID)).containsEntry("status", "REJECTED").containsEntry("token_hash", null);
    }

    @Test
    @DisplayName("an auto-rejected duplicate's old token no longer resolves to any row (decide → ALREADY_DECIDED)")
    void should_resolveNoRow_when_autoRejectedDuplicateTokenLookedUp() {
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM service_type_suggestion WHERE token_hash IN ('hash-a-newer','hash-a-newest','hash-b-high')",
                Integer.class);

        assertThat(matches)
                .as("findByTokenHashForUpdate finds nothing → ServiceTypeSuggestionService.decide returns ALREADY_DECIDED")
                .isZero();
    }

    @Test
    @DisplayName("non-duplicates untouched: other category, trailing-space variant, singleton, older decided row")
    void should_leaveNonDuplicatesUntouched_when_keyDiffersOrRowNotPending() {
        assertThat(row(SAME_NAME_OTHER_CATEGORY)).containsEntry("status", "PENDING").containsEntry("token_hash", "hash-c-other-cat");
        assertThat(row(TRAILING_SPACE_VARIANT)).containsEntry("status", "PENDING").containsEntry("token_hash", "hash-c-space");
        assertThat(row(SINGLETON_PENDING)).containsEntry("status", "PENDING").containsEntry("token_hash", "hash-c-singleton");
        assertThat(((java.sql.Timestamp) row(OLDER_REJECTED_SAME_KEY).get("decided_at")).toInstant())
                .as("pre-existing decided row keeps its original decided_at")
                .isEqualTo(T0.minusDays(2).toInstant());
    }

    @Test
    @DisplayName("exactly 3 rows collapsed overall and no in-app notification created for them")
    void should_rejectExactlyThreeAndNotifyNobody_when_migrationCollapsesDuplicates() {
        Integer collapsed = jdbc.queryForObject(
                "SELECT count(*) FROM service_type_suggestion WHERE status = 'REJECTED' AND decided_at >= ?",
                Integer.class, migrationStartedAt);
        Integer notifications = jdbc.queryForObject(
                "SELECT count(*) FROM in_app_notification WHERE service_type_suggestion_id IS NOT NULL "
                        + "OR type LIKE 'SERVICE_TYPE_SUGGESTION_%'",
                Integer.class);

        assertThat(collapsed).isEqualTo(3);
        assertThat(notifications).as("housekeeping reject is silent").isZero();
    }

    @Test
    @DisplayName("after the collapse, the unique index rejects a new case-variant PENDING duplicate")
    void should_rejectNewDuplicatePending_when_indexBuiltAfterCollapse() {
        assertThatThrownBy(() -> insertPending(
                UUID.randomUUID(), "HAIRDRESSING", "HoT tOwEl ShAvE", T0.plusHours(1), "hash-new-dup"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_service_type_suggestion_pending_dedup");
    }
}
