package com.beautica.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Gives every full-context IT a pool whose connections carry no migration-time session settings.
 *
 * <p>{@code V83__…sql:18} runs a session-level {@code SET lock_timeout = '3s'}. In prod and local, Flyway runs on its
 * own dedicated pool ({@code FlywayDataSourceConfig}), so that setting dies with the migration connection. That config
 * is {@code @Profile("!test")}, though: under the {@code test} profile Flyway borrows the APP Hikari pool, and the
 * migrated connection returns to it with a 3 s default {@code lock_timeout} for the rest of its life. A lock-timeout IT
 * could then pass even if the code under test had lost its own fused {@code set_config('lock_timeout', …)}. V83 is
 * applied everywhere and therefore immutable, so the fix belongs here, not in the migration.
 *
 * <p>After all singletons exist (so after the Flyway initializer has migrated), this soft-evicts the pool (idle
 * connections close now, in-use ones on return) and fails the context fast if a fresh connection still reports a
 * non-default {@code lock_timeout}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestPoolSessionResetConfig {

    @Bean
    SmartInitializingSingleton evictMigrationTaintedConnections(DataSource dataSource, JdbcTemplate jdbc) {
        return () -> {
            softEvict(dataSource);
            String v = jdbc.queryForObject("SHOW lock_timeout", String.class);
            if (!"0".equals(v)) {
                throw new IllegalStateException("session-level lock_timeout leaked into the app pool: " + v);
            }
        };
    }

    private static void softEvict(DataSource dataSource) {
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
                if (pool.getHikariPoolMXBean() != null) {
                    pool.getHikariPoolMXBean().softEvictConnections();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot unwrap the test DataSource to Hikari", e);
        }
    }
}
