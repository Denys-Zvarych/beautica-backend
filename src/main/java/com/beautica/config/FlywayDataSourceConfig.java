package com.beautica.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * Dedicated, short-lived Hikari pool used <em>only</em> by Flyway during the
 * startup {@code migrate()} run, isolated from the main application pool.
 *
 * <p><strong>Why this exists.</strong> Flyway opens a single JDBC connection and
 * holds it for the entire {@code migrate()} run while it applies every migration
 * (including the large {@code V53__seed_locality_taxonomy.sql} seed). On a
 * networked prod database that single hold can exceed the main pool's
 * {@code leak-detection-threshold: 10000} (10 s), so HikariCP logs a spurious
 * "Apparent connection leak detected" WARN. The connection is returned correctly
 * — it is a long, legitimate hold, not a leak. Routing Flyway through its own
 * pool with leak detection turned off removes the false positive while the main
 * pool keeps its 10 s leak guard meaningful for business code.
 *
 * <p><strong>Credentials source.</strong> The pool is built from the same
 * {@code spring.datasource.*} properties the primary {@link DataSource} uses
 * (post-normalization by {@link DatabaseUrlNormalizerPostProcessor}). It is bound
 * via a private {@link DataSourceProperties} so prod (Postgres via {@code DATABASE_URL}),
 * {@code local} (Docker), and any other non-test profile resolve the correct URL,
 * username, and password automatically — nothing is hardcoded.
 *
 * <p><strong>Both beans are {@code defaultCandidate = false}.</strong> {@link FlywayDataSource} is only
 * a qualifier: without this flag the bean is a by-type autowire candidate, so
 * {@code DataSourceAutoConfiguration}'s {@code @ConditionalOnMissingBean(DataSource.class)} backs off
 * and JPA/JdbcTemplate run on this 2-connection pool (the bug fixed here). Non-default candidates are
 * invisible to {@code @ConditionalOnMissingBean} and to unqualified injection, so Boot builds the
 * application pool from {@code spring.datasource.hikari.*} while Flyway still resolves this bean via
 * its {@code @FlywayDataSource} qualifier.
 *
 * <p><strong>Test profile is deliberately excluded</strong> ({@code @Profile("!test")}).
 * Integration tests supply the datasource dynamically via Testcontainers
 * {@code @DynamicPropertySource} at context-load time. Under {@code test} we let
 * Spring Boot's default behaviour stand (Flyway borrows the Testcontainers-backed
 * main datasource), so the whole suite keeps working unchanged.
 */
@Configuration
@Profile("!test")
public class FlywayDataSourceConfig {

    /**
     * Flyway holds its single connection for the whole run; once {@code migrate()}
     * finishes nothing else uses this pool, so it must not pin an idle
     * connection for the app's lifetime. A short idle timeout collapses the pool
     * back to zero connections shortly after migration completes.
     */
    private static final long FLYWAY_IDLE_TIMEOUT_MS = 10_000L;
    private static final long FLYWAY_MAX_LIFETIME_MS = 60_000L;

    /**
     * Binds {@code spring.datasource} so this pool resolves the same (normalized)
     * URL/username/password as the primary {@link DataSource}, across every
     * non-test profile, without hardcoding.
     */
    @Bean(defaultCandidate = false)
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties flywayDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(defaultCandidate = false)
    @FlywayDataSource
    public DataSource flywayDataSource(
            @Qualifier("flywayDataSourceProperties") DataSourceProperties flywayDataSourceProperties) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(flywayDataSourceProperties.determineUrl());
        cfg.setUsername(flywayDataSourceProperties.determineUsername());
        cfg.setPassword(flywayDataSourceProperties.determinePassword());
        cfg.setDriverClassName(flywayDataSourceProperties.determineDriverClassName());
        // Flyway opens TWO connections concurrently during migrate(): a primary
        // connection (JdbcConnectionFactory) plus a separate migration connection
        // (Database.getMigrationConnection). A pool of 1 deadlocks the second
        // request → 30s timeout → startup failure. 2 is the documented minimum
        // for a Flyway-dedicated pool.
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);              // do not hold idle connections after migration
        cfg.setLeakDetectionThreshold(0);   // OFF — Flyway legitimately holds one connection for the whole run
        cfg.setIdleTimeout(FLYWAY_IDLE_TIMEOUT_MS);
        cfg.setMaxLifetime(FLYWAY_MAX_LIFETIME_MS);
        cfg.setPoolName("flyway-pool");
        // Bound every lock WAIT a migration statement can queue behind (e.g. an overlapping old instance
        // holding a row/table lock during a Railway rolling deploy) so boot fails fast instead of hanging
        // until the healthcheck timeout. per-migration SET LOCAL lock_timeout (V120 uses 30s, the
        // rest 5s: V138/V150/V169/V173/V197) still override this within their own transaction.
        // Flyway's own schema-history lock is pg_try_advisory_lock (non-blocking retry), unaffected.
        cfg.setConnectionInitSql("SET lock_timeout = '10s'");
        return new HikariDataSource(cfg);
    }
}
