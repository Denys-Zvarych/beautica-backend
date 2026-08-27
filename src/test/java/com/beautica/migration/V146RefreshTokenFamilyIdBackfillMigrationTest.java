package com.beautica.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V146 migration-safety regression test (track token-lifetime-and-reuse-detection, QA pattern
 * Q21: a migration's correctness IS its contract, "Flyway applied with no error" proves nothing
 * about legacy data).
 *
 * <p><b>What V146 does.</b> Adds {@code refresh_tokens.family_id} (nullable), backfills every
 * pre-existing row with {@code family_id = id} (a legacy token becomes a family-of-one, since it
 * was minted by the pre-family login/register code path and has no rotation history to
 * reconstruct), then sets the column {@code NOT NULL} and indexes it. See
 * {@code V146__refresh_tokens_family_id.sql} for the full rationale, including the deliberate
 * single-transaction lock trade-off.
 *
 * <p><b>Why this test exists.</b> The {@code UPDATE refresh_tokens SET family_id = id WHERE
 * family_id IS NULL} statement is the one that protects LIVE PRODUCTION SESSIONS on deploy — every
 * refresh_tokens row that existed before this release has no family_id until it runs. No other
 * test touches it: every IT/DataJpaTest boots Flyway V1→latest against an empty schema, so there
 * is never a pre-V146 legacy row for the backfill to act on. If the predicate or the target column
 * were wrong, the whole suite would stay green and every user would be logged out on release (a
 * NOT NULL violation on the ALTER COLUMN step would crash-loop the deploy instead — either failure
 * mode is exactly what this test exists to catch before it reaches production).
 *
 * <p><b>Why a dedicated container.</b> {@link com.beautica.AbstractIntegrationTest}'s singleton
 * container is already fully migrated past V146 — {@code family_id} is already NOT NULL there, so
 * a legacy pre-family row can no longer be inserted. Mirrors {@code V80MigrationIntegrationTest}
 * / {@code PendingStatusRemovalMigrationTest}: drive Flyway PROGRAMMATICALLY against a private
 * container — migrate to {@code target = 145} (the last schema state before {@code family_id}
 * exists), seed legacy refresh_tokens rows the way pre-release production has them, migrate the
 * remainder (V146+), and assert the backfilled contract.
 */
@DisplayName("V146 migration — legacy refresh_tokens rows are backfilled to family-of-one, not left NULL")
class V146RefreshTokenFamilyIdBackfillMigrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private static final UUID LEGACY_USER_ID = UUID.randomUUID();
    private static final UUID LEGACY_TOKEN_ID_1 = UUID.randomUUID();
    private static final UUID LEGACY_TOKEN_ID_2 = UUID.randomUUID();

    @BeforeAll
    static void startContainerAndSeedUnderV145() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // ── Arrange: migrate ONLY up to V145 — the last state before family_id exists. ──
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("145")
                .load()
                .migrate();

        // Precondition: family_id does not exist yet, so the legacy INSERT below (no family_id
        // column at all) is the real pre-V146 production shape, not a stand-in for it.
        Integer familyIdCol = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'refresh_tokens' AND column_name = 'family_id'",
                Integer.class);
        assertThat(familyIdCol)
                .as("precondition: under V145 schema state refresh_tokens.family_id does not yet exist")
                .isZero();

        seedLegacyRefreshTokens();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * Seeds one user with TWO legacy refresh_tokens rows — the exact pre-V146 production shape
     * (no family_id column at all). Two rows, not one, so the backfill's family-of-one semantic
     * (each row gets its OWN id, never a shared value) is actually exercised: a backfill that
     * wrongly assigned one shared family_id to every legacy row for a user would pass a
     * single-row test but fail this one.
     */
    private static void seedLegacyRefreshTokens() {
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, now(), now())",
                LEGACY_USER_ID, "v146-legacy-" + LEGACY_USER_ID + "@example.com", "x");

        jdbc.update(
                "INSERT INTO refresh_tokens (id, token, user_id, expires_at, is_revoked, created_at, updated_at) "
                        + "VALUES (?, ?, ?, now() + interval '30 days', false, now(), now())",
                LEGACY_TOKEN_ID_1, "legacy-token-1-" + LEGACY_TOKEN_ID_1, LEGACY_USER_ID);
        jdbc.update(
                "INSERT INTO refresh_tokens (id, token, user_id, expires_at, is_revoked, created_at, updated_at) "
                        + "VALUES (?, ?, ?, now() + interval '30 days', false, now(), now())",
                LEGACY_TOKEN_ID_2, "legacy-token-2-" + LEGACY_TOKEN_ID_2, LEGACY_USER_ID);

        // Sanity: both legacy rows are actually present under V145 before V146 runs.
        Long seeded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Long.class, LEGACY_USER_ID);
        assertThat(seeded)
                .as("precondition: both legacy refresh_tokens rows are seeded under V145")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("V146 backfills each legacy row's family_id to its OWN id, and the column ends up NOT NULL")
    void should_backfillFamilyIdToOwnId_when_legacyRowsExistAtV146() {
        // ── Act: run the remaining migration (V146+) over a DB that holds legacy rows. ──
        // If V146's backfill predicate or target column were wrong, either this throws (a
        // NOT NULL violation on ALTER COLUMN — the production crash-loop) or the assertions
        // below fail (a silent data-shape bug: live sessions logged out on release).
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        UUID familyId1 = jdbc.queryForObject(
                "SELECT family_id FROM refresh_tokens WHERE id = ?", UUID.class, LEGACY_TOKEN_ID_1);
        UUID familyId2 = jdbc.queryForObject(
                "SELECT family_id FROM refresh_tokens WHERE id = ?", UUID.class, LEGACY_TOKEN_ID_2);

        assertThat(familyId1)
                .as("legacy row 1's family_id must be backfilled to its OWN id (family-of-one)")
                .isEqualTo(LEGACY_TOKEN_ID_1);
        assertThat(familyId2)
                .as("legacy row 2's family_id must be backfilled to its OWN id — NOT row 1's id, "
                        + "and NOT any other shared value")
                .isEqualTo(LEGACY_TOKEN_ID_2);
        assertThat(familyId1)
                .as("two legacy rows for the same user must NOT be backfilled into one shared "
                        + "family — they have no real rotation history linking them")
                .isNotEqualTo(familyId2);
    }

    @Test
    @DisplayName("V146 leaves family_id NOT NULL — no legacy row survives with a null family_id")
    void should_leaveFamilyIdNotNull_when_migrationCompletes() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        String isNullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'refresh_tokens' AND column_name = 'family_id'",
                String.class);
        assertThat(isNullable)
                .as("family_id must end up NOT NULL — a nullable column would mean the backfill "
                        + "step didn't run before the constraint was applied")
                .isEqualTo("NO");

        Long nullFamilyIdRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE family_id IS NULL", Long.class);
        assertThat(nullFamilyIdRows)
                .as("no refresh_tokens row — legacy or otherwise — may survive V146 with a null family_id")
                .isZero();
    }
}
