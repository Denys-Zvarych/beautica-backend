package com.beautica.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for {@link FlywayDataSourceConfig} — the dedicated, leak-detection-OFF
 * Hikari pool Flyway borrows during the startup {@code migrate()} run.
 *
 * <p><strong>What this protects.</strong> The whole point of the bean is to keep the main
 * pool's {@code leak-detection-threshold: 10000} (10 s) in force for business code while
 * letting Flyway hold a single connection for the entire migration run without tripping a
 * spurious "Apparent connection leak detected" WARN. If a future edit deletes
 * {@code setLeakDetectionThreshold(0)} (or bumps {@code maximumPoolSize}, drops {@code minimumIdle=0},
 * renames the pool, or removes the {@code @FlywayDataSource} qualifier), the contract breaks
 * silently. The assertions below FAIL on exactly those regressions.
 *
 * <p><strong>Why this is DB-less and fast.</strong> The production factory builds the pool with
 * {@code new HikariDataSource(cfg)}, which is <em>eager</em> — HikariCP opens and validates one
 * connection in the constructor (it is <em>not</em> lazy until {@code getConnection()} for this
 * constructor form). To keep the test hermetic — no Postgres, no Docker, no Testcontainers — we
 * register an in-JVM {@link MockJdbcDriver} for a private {@code jdbc:mock-flyway:} scheme and
 * point {@code spring.datasource.*} at it. HikariCP's startup probe gets a stub connection that
 * reports healthy, so the real production factory code runs end to end with zero network I/O.
 * We then read the pool's configuration (which is the regression heart) off the built instance.
 *
 * @see MockJdbcDriver
 */
@DisplayName("FlywayDataSourceConfig — dedicated leak-detection-OFF Flyway pool")
class FlywayDataSourceConfigTest {

    /**
     * Datasource properties pointing at the in-JVM mock driver. {@code driver-class-name} is set
     * explicitly so the production {@code DataSourceProperties.determineDriverClassName()} returns
     * the mock (it cannot infer a driver from the non-standard {@code mock-flyway} scheme).
     */
    private static final String[] MOCK_DATASOURCE_PROPS = {
            "spring.datasource.url=jdbc:mock-flyway://in-jvm/dummy",
            "spring.datasource.username=dummy-user",
            "spring.datasource.password=dummy-pass",
            "spring.datasource.driver-class-name=" + MockJdbcDriver.class.getName()
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            // Enables @ConfigurationProperties binding so flywayDataSourceProperties() actually
            // receives the spring.datasource.* values (determineUrl() returns null otherwise).
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(FlywayDataSourceConfig.class)
            .withPropertyValues(MOCK_DATASOURCE_PROPS);

    /** Activates a non-{@code test} profile so the {@code @Profile("!test")} gate opens. */
    private ApplicationContextRunner nonTestProfile() {
        return contextRunner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("local"));
    }

    @Test
    @DisplayName("non-test profile — registers the flywayDataSource bean (the @Profile(\"!test\") gate is open)")
    void should_registerFlywayDataSourceBean_when_nonTestProfileActive() {
        nonTestProfile().run(context -> assertThat(context)
                .as("flywayDataSource must be registered under any non-test profile")
                .hasBean("flywayDataSource"));
    }

    @Test
    @DisplayName("non-test profile — pool has leak detection OFF (0), so Flyway's long single-connection hold never trips a false leak WARN")
    void should_disableLeakDetection_when_flywayPoolBuilt() {
        nonTestProfile().run(context -> {
            HikariDataSource pool = (HikariDataSource) context.getBean("flywayDataSource");
            assertThat(pool.getLeakDetectionThreshold())
                    .as("leakDetectionThreshold MUST be 0 (OFF); re-enabling it brings back the spurious "
                            + "'Apparent connection leak detected' WARN during the seed migration")
                    .isZero();
        });
    }

    @Test
    @DisplayName("non-test profile — pool allows the two concurrent connections Flyway needs, with no idle hold afterward")
    void should_sizePoolForFlywayTwoConnectionsWithNoIdleHold_when_flywayPoolBuilt() {
        nonTestProfile().run(context -> {
            HikariDataSource pool = (HikariDataSource) context.getBean("flywayDataSource");
            assertThat(pool.getMaximumPoolSize())
                    .as("maximumPoolSize MUST be >= 2 — Flyway opens a primary connection AND a "
                            + "separate migration connection concurrently; a pool of 1 deadlocks startup")
                    .isGreaterThanOrEqualTo(2);
            assertThat(pool.getMinimumIdle())
                    .as("minimumIdle MUST be 0 — the pool must collapse to zero connections after migration")
                    .isZero();
        });
    }

    @Test
    @DisplayName("non-test profile — pool is named 'flyway-pool' and short-lived (10s idle / 60s max-lifetime)")
    void should_nameAndTimeOutFlywayPool_when_flywayPoolBuilt() {
        nonTestProfile().run(context -> {
            HikariDataSource pool = (HikariDataSource) context.getBean("flywayDataSource");
            assertThat(pool.getPoolName())
                    .as("poolName identifies these connections in logs as Flyway's, not the main pool's")
                    .isEqualTo("flyway-pool");
            assertThat(pool.getIdleTimeout())
                    .as("idleTimeout keeps the pool from pinning an idle Neon connection for the app lifetime")
                    .isEqualTo(10_000L);
            assertThat(pool.getMaxLifetime())
                    .as("maxLifetime caps how long the single connection may live")
                    .isEqualTo(60_000L);
        });
    }

    @Test
    @DisplayName("non-test profile — bean carries the @FlywayDataSource qualifier and is NOT the primary DataSource")
    void should_qualifyBeanForFlywayOnly_when_flywayPoolBuilt() {
        nonTestProfile().run(context -> {
            // The qualifier annotation must be present on the bean definition's factory method,
            // otherwise Flyway would never pick this pool up and would fall back to the main pool.
            assertThat(context.getBeanFactory()
                    .findAnnotationOnBean("flywayDataSource", FlywayDataSource.class))
                    .as("@FlywayDataSource qualifier MUST be present, or Flyway ignores this pool")
                    .isNotNull();
            // No other DataSource is registered in this minimal slice, so the Flyway pool must
            // never be exposed as the catch-all primary DataSource type used by business code.
            assertThat(context.getBeanNamesForType(DataSource.class))
                    .as("only the dedicated flywayDataSource exists in this slice")
                    .containsExactly("flywayDataSource");
        });
    }

    @Test
    @DisplayName("test profile — flywayDataSource bean is ABSENT, so Testcontainers' default Flyway datasource wins")
    void should_notRegisterFlywayDataSourceBean_when_testProfileActive() {
        contextRunner
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test"))
                .run(context -> assertThat(context)
                        .as("@Profile(\"!test\") MUST exclude the bean under 'test' — the Testcontainers carve-out")
                        .doesNotHaveBean("flywayDataSource"));
    }
}
