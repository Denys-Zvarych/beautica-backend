package com.beautica.moderation;

import com.beautica.TestPostgresImage;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V209 over a database that already holds an ORPHANED OTHER report: under V208 (ON DELETE SET NULL) a deleted
 * reporter leaves the row with reporter_user_id NULL but its >= 5-char comment intact, which V207's
 * chk_content_reports_other_comment still demands. V209 must drop that CHECK before it nulls the comment, or the
 * migration aborts. A private container is migrated to V208, seeded, then migrated to V209 once
 * (pattern of {@code V206CancelTokenHashMigrationTest}).
 */
@DisplayName("V209 migration — orphaned OTHER report loses its comment without aborting the migration")
class V209OrphanReportCommentMigrationIT {

    private static final String MIGRATIONS = "classpath:db/migration";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");
    private static final String ORPHAN_COMMENT = "reporter text that must not outlive the reporter";
    private static final String KEPT_COMMENT = "live reporter keeps this text";

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static UUID orphanReport;
    private static UUID liveReport;

    @BeforeAll
    static void migrateTo208SeedOrphanedOtherReportThenMigrateTo209() {
        postgres = new PostgreSQLContainer<>(TestPostgresImage.IMAGE);
        postgres.start();
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        migrateTo(dataSource, "208");

        UUID deletedReporter = insertClient();
        UUID liveReporter = insertClient();
        UUID target = UUID.randomUUID();
        orphanReport = insertOtherReport(deletedReporter, target, ORPHAN_COMMENT);
        liveReport = insertOtherReport(liveReporter, target, KEPT_COMMENT);
        // V208 FK is ON DELETE SET NULL; no V209 trigger yet, so the comment survives on the orphaned row.
        jdbc.update("DELETE FROM users WHERE id = ?", deletedReporter);

        migrateTo(dataSource, "209");
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("V209 applies cleanly over an orphaned OTHER report carrying a comment")
    void should_recordV209Successful_when_orphanedOtherReportHasComment() {
        Boolean success = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '209'", Boolean.class);

        assertThat(success).isTrue();
    }

    @Test
    @DisplayName("the orphaned report keeps its row and reason but loses the reporter's text")
    void should_nullComment_when_reporterWasDeletedBeforeV209() {
        var row = jdbc.queryForMap(
                "SELECT reporter_user_id, reason, comment FROM content_reports WHERE id = ?", orphanReport);

        assertThat(row.get("reporter_user_id")).isNull();
        assertThat(row.get("reason")).isEqualTo("OTHER");
        assertThat(row.get("comment")).isNull();
    }

    @Test
    @DisplayName("a report whose reporter still exists keeps its comment")
    void should_keepComment_when_reporterStillExists() {
        String comment = jdbc.queryForObject(
                "SELECT comment FROM content_reports WHERE id = ?", String.class, liveReport);

        assertThat(comment).isEqualTo(KEPT_COMMENT);
    }

    private static void migrateTo(DataSource dataSource, String version) {
        Flyway.configure().dataSource(dataSource).locations(MIGRATIONS)
                .target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    private static UUID insertClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true, 'Ірина', 'Коваль')",
                id, "v209-client-" + id + "@beautica.test", PASSWORD_HASH);
        return id;
    }

    private static UUID insertOtherReport(UUID reporterId, UUID targetId, String comment) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, comment) "
                + "VALUES (?, ?, 'REVIEW', ?, 'OTHER', ?)", id, reporterId, targetId, comment);
        return id;
    }
}
