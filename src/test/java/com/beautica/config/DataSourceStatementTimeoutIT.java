package com.beautica.config;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("a JPA statement cancelled by statement_timeout translates to Spring QueryTimeoutException (the 503 handler's type)")
    void should_translateToQueryTimeoutException_when_jpaStatementExceedsStatementTimeout() {
        // GlobalExceptionHandler#handleQueryTimeout maps org.springframework.dao.QueryTimeoutException to 503. This
        // pins that a real 57014 on the JPA path (what every repository method runs on) arrives as exactly that
        // type after Spring's persistence-exception translation — otherwise the handler would be dead code and the
        // timeout would still fall through to the generic 500. SET LOCAL keeps the test at ~100 ms instead of 5 s.
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> tx.executeWithoutResult(status -> {
            entityManager.createNativeQuery("SELECT set_config('statement_timeout', '100ms', true)").getSingleResult();
            try {
                entityManager.createNativeQuery("SELECT CAST(pg_sleep(2) AS text)").getSingleResult();
            } catch (RuntimeException ex) {
                throw org.springframework.orm.jpa.EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible(ex);
            }
        }));

        assertThat(thrown).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
    }

    @Autowired
    private com.beautica.salon.repository.SalonRepository salonRepository;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Test
    @DisplayName("a lock wait cancelled by statement_timeout on a REAL repository call is a 503 through MockMvc, not a 500")
    void should_return503_when_repositoryLockWaitIsCancelledByStatementTimeout() throws Exception {
        // The end-to-end shape the 503 handler exists for: a statement the server cancels (57014) on the
        // repository proxy's own exception translation (not a hand-called convertJpaAccessExceptionIfPossible),
        // then resolved by the real GlobalExceptionHandler through Spring MVC's resolver chain. An advisory lock
        // held on another connection blocks lockBookingSlugCandidates; a transaction-local 300ms
        // statement_timeout fires before the query's own fused 3s lock_timeout, so the wait ends as 57014,
        // never 55P03 (that one is the 409 path).
        String slug = "stmt-timeout-" + java.util.UUID.randomUUID();
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        Throwable thrown;
        try (var ignored = com.beautica.support.HeldDbLock.salonSlugCandidates(dataSource, new String[] {slug})) {
            thrown = org.assertj.core.api.Assertions.catchThrowable(() -> tx.executeWithoutResult(status -> {
                entityManager.createNativeQuery("SELECT set_config('statement_timeout', '300ms', true)")
                        .getSingleResult();
                salonRepository.lockBookingSlugCandidates(new String[] {slug});
            }));
        }
        assertThat(thrown)
                .as("the repository proxy must translate 57014 to the 503 handler's type, actual=%s", thrown)
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);

        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new ThrowingController((RuntimeException) thrown))
                .setControllerAdvice(new com.beautica.common.exception.GlobalExceptionHandler())
                .build();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/stmt-timeout"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"success\":false,\"data\":null,\"message\":\"Service temporarily unavailable\"}", true));
    }

    @org.springframework.web.bind.annotation.RestController
    static class ThrowingController {
        private final RuntimeException toThrow;

        ThrowingController(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @org.springframework.web.bind.annotation.GetMapping("/stmt-timeout")
        String fail() {
            throw toThrow;
        }
    }
}
