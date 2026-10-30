package com.beautica.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

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
    @DisplayName("non-test profile — every Flyway connection starts with lock_timeout = 10s")
    void should_setLockTimeoutOnConnectionInit_when_flywayPoolBuilt() {
        nonTestProfile().run(context -> {
            HikariDataSource pool = (HikariDataSource) context.getBean("flywayDataSource");
            assertThat(pool.getConnectionInitSql()).isEqualTo("SET lock_timeout = '10s'");
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
                    .as("idleTimeout keeps the pool from pinning an idle database connection for the app lifetime")
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

    // ---------------------------------------------------------------------------------------------
    // App-pool wiring (hotfix fix/app-datasource-pool). With DataSourceAutoConfiguration present, the
    // Flyway pool must NOT satisfy @ConditionalOnMissingBean(DataSource): Boot has to build its own
    // pool from spring.datasource.hikari.*, and only Flyway may see flyway-pool.
    // ---------------------------------------------------------------------------------------------

    /** Configured app pool size — application.yml spring.datasource.hikari.maximum-pool-size. */
    private static final int APP_POOL_MAX = 10;
    private static final String APP_INIT_SQL = "SET statement_timeout = '5s'";
    private static final long APP_LEAK_DETECTION_MS = 35_000L;
    private static final long APP_CONNECTION_TIMEOUT_MS = 10_000L;
    private static final String FLYWAY_POOL_NAME = "flyway-pool";

    /**
     * Full DataSource + JdbcTemplate + Flyway auto-configuration under profile {@code prod}, reading the
     * REAL {@code application.yml} / {@code application-prod.yml} (only the URL/credentials are pointed
     * at the mock driver). A no-op {@link FlywayMigrationStrategy} keeps {@code migrate()} from running.
     */
    private ApplicationContextRunner prodProfileWithAutoConfiguredAppPool() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withUserConfiguration(FlywayDataSourceConfig.class)
                .withBean(FlywayMigrationStrategy.class, () -> flyway -> { })
                .withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues(MOCK_DATASOURCE_PROPS);
    }

    @Test
    @DisplayName("prod profile — two DataSources: Boot's app pool plus the dedicated flyway-pool")
    void should_registerAppAndFlywayDataSources_when_prodProfileWithAutoConfiguration() {
        prodProfileWithAutoConfiguredAppPool().run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context.getBeanNamesForType(DataSource.class))
                    .as("Boot's dataSource must exist alongside flywayDataSource, not back off because of it")
                    .containsExactlyInAnyOrder("dataSource", "flywayDataSource");
        });
    }

    @Test
    @DisplayName("prod profile — the injected app DataSource is Boot's Hikari pool sized from spring.datasource.hikari.*")
    void should_injectHikariAppPoolFromHikariProperties_when_prodProfileWithAutoConfiguration() {
        prodProfileWithAutoConfiguredAppPool().run(context -> {
            HikariDataSource app = context.getBean(DataSource.class).unwrap(HikariDataSource.class);

            assertThat(app.getPoolName()).as("app code must never run on Flyway's pool").isNotEqualTo(FLYWAY_POOL_NAME);
            assertThat(app.getMaximumPoolSize()).isEqualTo(APP_POOL_MAX);
            assertThat(app.getLeakDetectionThreshold()).isEqualTo(APP_LEAK_DETECTION_MS);
            assertThat(app.getConnectionTimeout()).isEqualTo(APP_CONNECTION_TIMEOUT_MS);
            assertThat(app.getConnectionInitSql())
                    .as("app connections run the statement_timeout backstop, never Flyway's lock_timeout")
                    .isEqualTo(APP_INIT_SQL)
                    .doesNotContain("lock_timeout");
        });
    }

    @Test
    @DisplayName("prod profile — JdbcTemplate is wired to the app pool, not flyway-pool")
    void should_wireJdbcTemplateToAppPool_when_prodProfileWithAutoConfiguration() {
        prodProfileWithAutoConfiguredAppPool().run(context -> {
            DataSource jdbcTemplateDs = context.getBean(JdbcTemplate.class).getDataSource();

            assertThat(jdbcTemplateDs).isSameAs(context.getBean("dataSource"));
            assertThat(jdbcTemplateDs.unwrap(HikariDataSource.class).getPoolName()).isNotEqualTo(FLYWAY_POOL_NAME);
        });
    }

    @Test
    @DisplayName("prod profile — Flyway is still routed to flyway-pool via the @FlywayDataSource qualifier")
    void should_wireFlywayToFlywayPool_when_prodProfileWithAutoConfiguration() {
        prodProfileWithAutoConfiguredAppPool().run(context -> {
            DataSource flywayDs = context.getBean(Flyway.class).getConfiguration().getDataSource();

            assertThat(flywayDs).isSameAs(context.getBean("flywayDataSource"));
            assertThat(flywayDs.unwrap(HikariDataSource.class).getPoolName()).isEqualTo(FLYWAY_POOL_NAME);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Transport parity (security LOW, hotfix fix/app-datasource-pool). Railway hands the app a
    // postgresql:// DATABASE_URL with no sslmode; DatabaseUrlNormalizerPostProcessor rewrites it to a
    // jdbc: URL with sslmode=require. Now that the app pool is Boot's own (no longer flyway-pool), BOTH
    // pools must read that normalised URL — neither may fall back to the raw value or to pgjdbc's
    // plaintext-capable default (prefer). Runs the real post-processor; no database is contacted.
    // ---------------------------------------------------------------------------------------------

    private static final String RAILWAY_DATABASE_URL = "postgresql://u:p@x.railway.internal/db";

    /**
     * Same auto-configuration as {@link #prodProfileWithAutoConfiguredAppPool()}, but the datasource comes
     * from {@code DATABASE_URL} via the real {@link DatabaseUrlNormalizerPostProcessor} (applied after
     * config data, as Boot orders it — the class is not {@code Ordered}). Only the driver is a stub.
     */
    private ApplicationContextRunner prodProfileFromRailwayDatabaseUrl() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(ctx -> new DatabaseUrlNormalizerPostProcessor()
                        .postProcessEnvironment(ctx.getEnvironment(), new org.springframework.boot.SpringApplication()))
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withUserConfiguration(FlywayDataSourceConfig.class)
                .withBean(FlywayMigrationStrategy.class, () -> flyway -> { })
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "DATABASE_URL=" + RAILWAY_DATABASE_URL,
                        "spring.datasource.driver-class-name=" + RailwayInternalMockJdbcDriver.class.getName());
    }

    @Test
    @DisplayName("prod + Railway DATABASE_URL — BOTH the app pool and flyway-pool connect with sslmode=require")
    void should_requireSslOnBothPools_when_prodRailwayDatabaseUrlNormalized() {
        prodProfileFromRailwayDatabaseUrl().run(context -> {
            assertThat(context).hasNotFailed();
            HikariDataSource app = context.getBean("dataSource", DataSource.class).unwrap(HikariDataSource.class);
            HikariDataSource flyway = context.getBean("flywayDataSource", HikariDataSource.class);

            assertThat(app).as("the app pool must be its own pool, not flyway-pool").isNotSameAs(flyway);
            assertThat(app.getJdbcUrl())
                    .as("app pool must use the normalised private-network URL with TLS forced, actual=%s",
                            app.getJdbcUrl())
                    .startsWith(RailwayInternalMockJdbcDriver.URL_PREFIX + "db?")
                    .contains("sslmode=require");
            assertThat(flyway.getJdbcUrl())
                    .as("flyway-pool must use the same normalised URL with TLS forced, actual=%s", flyway.getJdbcUrl())
                    .startsWith(RailwayInternalMockJdbcDriver.URL_PREFIX + "db?")
                    .contains("sslmode=require");
        });
    }

    @Test
    @DisplayName("prod + Railway DATABASE_URL — both pools authenticate as the DATABASE_URL user-info")
    void should_useDatabaseUrlCredentialsOnBothPools_when_prodRailwayDatabaseUrlNormalized() {
        prodProfileFromRailwayDatabaseUrl().run(context -> {
            HikariDataSource app = context.getBean("dataSource", DataSource.class).unwrap(HikariDataSource.class);
            HikariDataSource flyway = context.getBean("flywayDataSource", HikariDataSource.class);

            assertThat(app.getUsername()).as("app pool username from DATABASE_URL user-info").isEqualTo("u");
            assertThat(flyway.getUsername()).as("flyway-pool username from DATABASE_URL user-info").isEqualTo("u");
            assertThat(app.getPassword())
                    .as("both pools must carry the DATABASE_URL password (values not echoed)")
                    .isEqualTo(flyway.getPassword())
                    .isEqualTo("p");
        });
    }
}
