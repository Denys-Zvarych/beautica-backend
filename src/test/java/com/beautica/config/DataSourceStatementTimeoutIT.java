package com.beautica.config;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that every connection handed out by the application pool carries a server-side
 * {@code statement_timeout}.
 *
 * <p><b>Why this needs a real database.</b> The setting lives in
 * {@code spring.datasource.hikari.connection-init-sql} and is executed by HikariCP on each physical
 * connection as it is created. Nothing about that is observable from a property assertion or a
 * slice test: a typo in the SQL, a profile that overrides the key, or a datasource built outside
 * the {@code spring.datasource.hikari} namespace all leave the property looking correct while no
 * connection ever runs it. The only honest probe is to borrow a pooled connection and ask
 * PostgreSQL what it thinks the timeout is.
 *
 * <p><b>What it guards.</b> Before this, {@code statement_timeout} appeared only inside individual
 * migrations ({@code SET LOCAL} in V139/V159/V165 and friends) and never on the runtime datasource,
 * so no application query had any ceiling at all. That is the backstop whose absence turned one
 * pathological plan — the Phase 326 zero-trigram sequential scan, 119 ms x 240 requests/minute/IP
 * on a {@code permitAll} endpoint — into total pool saturation rather than a handful of slow
 * requests. The service-layer guard fixes that one query; this fixes the class.
 *
 * <p><b>It also proves the migration path survived.</b> This class extends
 * {@link AbstractIntegrationTest}, and under the {@code test} profile {@code FlywayDataSourceConfig}
 * is {@code @Profile("!test")} — so Flyway borrows this very pool and ran all 173 migrations under
 * the 5 s ceiling to get here. A migration that needed longer and did not {@code SET LOCAL} its own
 * timeout would fail the context load, not this assertion.
 */
@DisplayName("datasource — runtime statement_timeout backstop")
class DataSourceStatementTimeoutIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("a pooled connection reports a non-zero statement_timeout")
    void should_reportNonZeroStatementTimeout_when_aConnectionIsBorrowedFromThePool() {
        String timeout = jdbcTemplate.queryForObject("SHOW statement_timeout", String.class);

        assertThat(timeout)
                .as("PostgreSQL reports '0' when statement_timeout is disabled — which is what "
                        + "this pool reported before connection-init-sql was set, i.e. no query "
                        + "had any ceiling")
                .isNotNull()
                .isNotEqualTo("0")
                .isEqualTo("5s");
    }

    @Test
    @DisplayName("the timeout actually aborts a statement, it is not merely configured")
    void should_abortTheStatement_when_itExceedsTheConfiguredTimeout() {
        // A configured-but-unenforced timeout looks identical to an enforced one in SHOW. This is
        // the half that cannot pass vacuously. pg_sleep is used rather than a genuinely expensive
        // query so the test costs the timeout and nothing more, and the margin over 5 s is large
        // enough that a loaded CI box cannot make it flaky in the passing direction.
        assertThat(
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> jdbcTemplate.execute("SELECT pg_sleep(8)")))
                .as("a statement running past statement_timeout must be cancelled by the server")
                .isNotNull();
    }
}
