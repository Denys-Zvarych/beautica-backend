package com.beautica.config;

import com.beautica.DataJpaPostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 371 M1: against a real Postgres, the JDBC URL produced by {@link DatabaseUrlNormalizerPostProcessor}
 * keeps the server's {@code Detail: Failing row contains (...)} / {@code Key (x)=(value)} text out of
 * {@code SQLException#getMessage()} (what Hibernate's {@code SqlExceptionHelper} logs), while the primary message
 * (with the constraint name, which Hibernate reads from the structured server message) stays. A control run on the plain Testcontainers URL proves the assertion is not vacuous.
 */
@DisplayName("pgjdbc server Detail text — dropped by the normalized JDBC URL")
class PostgresServerErrorDetailIT {

    private static final PostgreSQLContainer<?> POSTGRES = DataJpaPostgresContainer.INSTANCE;
    private static final String ROW_VALUE = "ROW-VALUE-CANARY-371";
    private static final String EMAIL_CANARY = "unique-canary-371@x.ua";

    /** The URL the app would boot with if {@code DATABASE_URL} pointed at this container. */
    private static String normalizedJdbcUrl() {
        String databaseUrl = "postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
                + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName()
                + "?sslmode=disable";
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> source = new HashMap<>();
        source.put("DATABASE_URL", databaseUrl);
        env.getPropertySources().addLast(new MapPropertySource("rawEnv", source));
        new DatabaseUrlNormalizerPostProcessor(new DeferredLogs()).postProcessEnvironment(env, null);
        return env.getProperty("spring.datasource.url");
    }

    private static SQLException trip(String jdbcUrl, String constraintKind) throws SQLException {
        String table = "detail_probe_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE " + table
                    + " (id int, note text NOT NULL, CONSTRAINT " + table + "_note_len CHECK (length(note) < 10))");
            String insert = "NOT_NULL".equals(constraintKind)
                    ? "INSERT INTO " + table + " VALUES (1, NULL)"
                    : "INSERT INTO " + table + " VALUES (1, '" + ROW_VALUE + "')";
            return (SQLException) catchThrowable(() -> statement.execute(insert));
        }
    }

    /** Inserts the same e-mail twice into a UNIQUE column — Postgres renders {@code Key (email)=(value)} as Detail. */
    private static SQLException tripUnique(String jdbcUrl) throws SQLException {
        String table = "detail_probe_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE " + table
                    + " (id int, email text, CONSTRAINT " + table + "_email_uq UNIQUE (email))");
            statement.execute("INSERT INTO " + table + " VALUES (1, '" + EMAIL_CANARY + "')");
            return (SQLException) catchThrowable(
                    () -> statement.execute("INSERT INTO " + table + " VALUES (2, '" + EMAIL_CANARY + "')"));
        }
    }

    private static Throwable catchThrowable(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    @DisplayName("control: the plain Testcontainers URL DOES leak 'Failing row contains' (assertion is not vacuous)")
    void should_leakDetail_when_urlHasNoLogServerErrorDetailFalse() throws SQLException {
        SQLException leaked = trip(POSTGRES.getJdbcUrl(), "NOT_NULL");

        assertThat((Object) leaked).isNotNull();
        assertThat(leaked.getMessage()).contains("Failing row contains");
    }

    @Test
    @DisplayName("NOT NULL violation on the normalized URL — message has no 'Failing row contains' / 'Detail:'")
    void should_omitFailingRowDetail_when_notNullViolatedOnNormalizedUrl() throws SQLException {
        SQLException thrown = trip(normalizedJdbcUrl(), "NOT_NULL");

        assertThat((Object) thrown).isNotNull();
        assertThat(thrown.getMessage()).contains("violates not-null constraint")
                .doesNotContain("Failing row contains").doesNotContain("Detail:");
    }

    @Test
    @DisplayName("CHECK violation on the normalized URL — row value absent from the message, constraint name still in the primary message")
    void should_omitRowValueButKeepConstraintName_when_checkViolatedOnNormalizedUrl() throws SQLException {
        SQLException thrown = trip(normalizedJdbcUrl(), "CHECK");

        assertThat((Object) thrown).isNotNull();
        assertThat(thrown.getMessage()).doesNotContain(ROW_VALUE).doesNotContain("Failing row contains");
        assertThat(thrown.getMessage()).contains("_note_len");
    }

    @Test
    @DisplayName("control: UNIQUE violation on the plain Testcontainers URL DOES leak 'Key (email)=(value)'")
    void should_leakKeyValue_when_uniqueViolatedOnPlainUrl() throws SQLException {
        SQLException leaked = tripUnique(POSTGRES.getJdbcUrl());

        assertThat((Object) leaked).isNotNull();
        assertThat(leaked.getMessage()).contains("Key (email)=").contains(EMAIL_CANARY);
    }

    @Test
    @DisplayName("UNIQUE violation on the normalized URL — 'Key (email)=(value)' and the e-mail absent, constraint name kept")
    void should_omitKeyValueButKeepConstraintName_when_uniqueViolatedOnNormalizedUrl() throws SQLException {
        SQLException thrown = tripUnique(normalizedJdbcUrl());

        assertThat((Object) thrown).isNotNull();
        assertThat(thrown.getSQLState()).as("unique_violation").isEqualTo("23505");
        assertThat(thrown.getMessage()).doesNotContain(EMAIL_CANARY).doesNotContain("Key (").doesNotContain("Detail:");
        assertThat(thrown.getMessage()).contains("_email_uq");
    }
}
