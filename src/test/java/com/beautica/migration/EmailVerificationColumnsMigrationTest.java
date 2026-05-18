package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that V48__add_email_verification_to_users.sql correctly adds the
 * email_verified, verification_code, and verification_code_expires_at columns
 * to the users table with the specified types, nullability, and default value.
 *
 * Extends AbstractIntegrationTest so Flyway runs against the shared singleton
 * Testcontainers PostgreSQL instance. Schema assertions are made via
 * information_schema.columns to remain independent of JPA entity state.
 */
@DisplayName("V48 migration — email verification columns")
class EmailVerificationColumnsMigrationTest extends AbstractIntegrationTest {

    private static final String COLUMN_QUERY = """
            SELECT data_type
            FROM information_schema.columns
            WHERE table_name = 'users'
              AND column_name = ?
            """;

    private static final String NULLABLE_QUERY = """
            SELECT is_nullable
            FROM information_schema.columns
            WHERE table_name = 'users'
              AND column_name = ?
            """;

    private static final String MAX_LENGTH_QUERY = """
            SELECT character_maximum_length
            FROM information_schema.columns
            WHERE table_name = 'users'
              AND column_name = ?
            """;

    private static final String COLUMN_DEFAULT_QUERY = """
            SELECT column_default
            FROM information_schema.columns
            WHERE table_name = 'users'
              AND column_name = ?
            """;

    // -------------------------------------------------------------------------
    // Column existence and type assertions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should_addEmailVerifiedColumn_when_migrationApplied")
    void should_addEmailVerifiedColumn_when_migrationApplied() {
        String dataType = jdbcTemplate.queryForObject(COLUMN_QUERY, String.class, "email_verified");
        String isNullable = jdbcTemplate.queryForObject(NULLABLE_QUERY, String.class, "email_verified");

        assertThat(dataType).isEqualTo("boolean");
        assertThat(isNullable).isEqualTo("NO");
    }

    @Test
    @DisplayName("should_addVerificationCodeHashColumn_when_migrationApplied")
    void should_addVerificationCodeHashColumn_when_migrationApplied() {
        String dataType = jdbcTemplate.queryForObject(COLUMN_QUERY, String.class, "verification_code_hash");
        String isNullable = jdbcTemplate.queryForObject(NULLABLE_QUERY, String.class, "verification_code_hash");
        Integer maxLength = jdbcTemplate.queryForObject(MAX_LENGTH_QUERY, Integer.class, "verification_code_hash");

        assertThat(dataType).isEqualTo("character varying");
        assertThat(isNullable).isEqualTo("YES");
        assertThat(maxLength).isEqualTo(64);
    }

    @Test
    @DisplayName("should_addVerificationAttemptsColumn_when_migrationApplied")
    void should_addVerificationAttemptsColumn_when_migrationApplied() {
        String dataType = jdbcTemplate.queryForObject(COLUMN_QUERY, String.class, "verification_attempts");
        String isNullable = jdbcTemplate.queryForObject(NULLABLE_QUERY, String.class, "verification_attempts");
        String columnDefault = jdbcTemplate.queryForObject(COLUMN_DEFAULT_QUERY, String.class, "verification_attempts");

        assertThat(dataType).isEqualTo("smallint");
        assertThat(isNullable).isEqualTo("NO");
        assertThat(columnDefault).isEqualTo("0");
    }

    @Test
    @DisplayName("should_addVerificationCodeExpiresAtColumn_when_migrationApplied")
    void should_addVerificationCodeExpiresAtColumn_when_migrationApplied() {
        String dataType = jdbcTemplate.queryForObject(COLUMN_QUERY, String.class, "verification_code_expires_at");
        String isNullable = jdbcTemplate.queryForObject(NULLABLE_QUERY, String.class, "verification_code_expires_at");

        assertThat(dataType).isEqualTo("timestamp with time zone");
        assertThat(isNullable).isEqualTo("YES");
    }

    // -------------------------------------------------------------------------
    // Default value assertion (requires a seeded row)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should_defaultEmailVerifiedToFalse_when_existingRowsMigrated")
    void should_defaultEmailVerifiedToFalse_when_existingRowsMigrated() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, role)
                VALUES (?, ?, ?, ?)
                """,
                userId,
                "migration-test-" + userId + "@beautica.com",
                "$2a$10$placeholder.hash.only.for.migration.schema.test",
                "CLIENT");

        Boolean emailVerified = jdbcTemplate.queryForObject(
                "SELECT email_verified FROM users WHERE id = ?",
                Boolean.class,
                userId);

        assertThat(emailVerified).isFalse();
    }

    // -------------------------------------------------------------------------
    // V49 CHECK constraint on verification_code_hash
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should_rejectInvalidHash_when_checkConstraintIsPresent")
    void should_rejectInvalidHash_when_checkConstraintIsPresent() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, role)
                VALUES (?, ?, ?, ?)
                """,
                userId,
                "migration-test-invalid-hash-" + userId + "@beautica.com",
                "$2a$10$placeholder.hash.only.for.migration.schema.test",
                "CLIENT");

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "UPDATE users SET verification_code_hash = 'INVALID' WHERE id = ?",
                        userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("should_acceptValidHexHash_when_checkConstraintIsPresent")
    void should_acceptValidHexHash_when_checkConstraintIsPresent() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, role)
                VALUES (?, ?, ?, ?)
                """,
                userId,
                "migration-test-valid-hash-" + userId + "@beautica.com",
                "$2a$10$placeholder.hash.only.for.migration.schema.test",
                "CLIENT");

        String validHash = "a".repeat(64);

        jdbcTemplate.update(
                "UPDATE users SET verification_code_hash = ? WHERE id = ?",
                validHash,
                userId);

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT verification_code_hash FROM users WHERE id = ?",
                String.class,
                userId);

        assertThat(storedHash).isEqualTo(validHash);
    }
}
