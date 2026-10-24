package com.beautica.config;

import com.beautica.support.TestSecrets;
import com.beautica.TestPostgresImage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Phase 368 audit F5 — {@link DeploymentEnvironmentStamp} against a real PostgreSQL: the first
 * staging/prod boot stamps the database, a matching re-boot passes, a different environment is refused.
 * A minimal application (DataSource + JdbcTemplate + Flyway + the stamp bean) is booted; no profiles, so
 * the environment is selected through {@code beautica.environment} and the pre-context guard sees
 * consistent staging/prod wiring. The schema is reset before each test.
 */
@DisplayName("DeploymentEnvironmentStamp — DB environment stamp on real PostgreSQL (Phase 368)")
class DeploymentEnvironmentStampIT {

    static PostgreSQLContainer<?> pg;

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            FlywayAutoConfiguration.class})
    @EnableConfigurationProperties(DeploymentProperties.class)
    @Import(DeploymentEnvironmentStamp.class)
    static class MinimalConfig {
        /** The real pre-Flyway guard (FlywayRepairConfig is @Profile("prod"); no profile is active here). */
        @Bean
        FlywayMigrationStrategy flywayMigrationStrategy(Environment env, DeploymentProperties props) {
            return new FlywayRepairConfig().flywayMigrationStrategy(env, props);
        }
    }

    @BeforeAll
    static void start() {
        pg = new PostgreSQLContainer<>(TestPostgresImage.IMAGE);
        pg.start();
    }

    @AfterAll
    static void stop() {
        if (pg != null) pg.stop();
    }

    @BeforeEach
    void resetSchema() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
    }

    private static ConfigurableApplicationContext boot(DeployEnvironment environment) {
        return boot(environment, environment.token());
    }

    private static ConfigurableApplicationContext boot(DeployEnvironment environment, String stampInit,
                                                       String... extraProps) {
        List<String> props = new ArrayList<>(List.of(
                "spring.datasource.url=" + pg.getJdbcUrl(),
                "spring.datasource.username=" + pg.getUsername(),
                "spring.datasource.password=" + pg.getPassword(),
                "spring.main.banner-mode=off",
                "beautica.environment=" + environment.token()));
        if (stampInit != null) {
            props.add("BEAUTICA_DB_STAMP_INIT=" + stampInit);
        }
        props.addAll(List.of(extraProps));
        switch (environment) {
            case STAGING -> props.addAll(List.of("beautica.sms.mode=capture", "spring.mail.host=mailpit.railway.internal",
                    // The base application.yml's Brevo placeholders (staging.yml blanks them in the real profile).
                    "spring.mail.username=", "spring.mail.password=",
                    // EnvironmentFingerprintGuard link-URL + support-email rules.
                    "app.public-base-url=https://api.staging.beautica.test", "app.frontend.base-url=https://example.invalid",
                    "app.support.email=support@test.invalid", "app.internal-api-key=" + TestSecrets.INTERNAL_API_KEY,
                    // Phase 382 D7: the staging overlay runs Turnstile with Cloudflare's always-pass TEST secret.
                    "app.turnstile.enabled=true", "app.turnstile.secret-key=1x0000000000000000000000000000000AA"));
            case PROD -> props.addAll(List.of("beautica.sms.mode=turbosms", "spring.mail.host=smtp-relay.brevo.com",
                    "app.cloudflare-r2.enabled=true", "app.cloudflare-r2.bucket=beautica-media-prod",
                    "app.cloudflare-r2.public-url=https://media.beautica.ua",
                    "app.public-base-url=https://api.beautica.ua", "app.frontend.base-url=https://app.beautica.ua",
                    "app.support.email=help@beautica.ua", "app.internal-api-key=" + TestSecrets.INTERNAL_API_KEY,
                    // Phase 367 prod required-env rules.
                    "sentry.dsn=https://0123456789abcdef@o42.ingest.de.sentry.io/4500000000000001",
                    "TURBOSMS_TOKEN=turbosms-fixture-token", "ADMIN_EMAIL=admin@beautica.ua",
                    // Phase 382 D7: prod Turnstile ON, real-shaped (non-test) secret, Cloudflare's siteverify URL.
                    "app.turnstile.enabled=true", "app.turnstile.secret-key=0x4AAAAAAAfixtureNotARealSecretZZ9",
                    "app.turnstile.siteverify-url=https://challenges.cloudflare.com/turnstile/v0/siteverify",
                    // Phase 374: prod must resolve the Brevo HTTPS API transport (prod-mail-transport et al.).
                    "app.mail.transport=brevo-api", "app.mail.brevo-api.api-key=xkeysib-fixture-api-key",
                    "app.mail.logo-url=https://beautica.app/email/logo.png"));
            case LOCAL -> { }
        }
        return new SpringApplicationBuilder(MinimalConfig.class)
                .web(WebApplicationType.NONE)
                // Command-line args: application.yml binds the datasource to ${DATABASE_URL}, which beats
                // default properties but not arguments.
                .run(props.stream().map(p -> "--" + p).toArray(String[]::new));
    }

    private static List<String> stamps() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        return jdbc.queryForList("SELECT environment FROM deployment_environment", String.class);
    }

    @Test
    @DisplayName("first staging boot stamps the empty database with 'staging'")
    void should_stampDatabase_when_firstBoot() {
        try (ConfigurableApplicationContext ctx = boot(DeployEnvironment.STAGING)) {
            assertThat(ctx.isActive()).isTrue();
        }

        assertThat(stamps()).containsExactly("staging");
    }

    @Test
    @DisplayName("re-boot with the same environment passes and keeps the single stamp row")
    void should_pass_when_stampMatches() {
        try (ConfigurableApplicationContext first = boot(DeployEnvironment.STAGING)) {
            assertThat(first.isActive()).isTrue();
        }

        try (ConfigurableApplicationContext second = boot(DeployEnvironment.STAGING)) {
            assertThat(second.isActive()).isTrue();
        }

        assertThat(stamps()).containsExactly("staging");
    }

    @Test
    @DisplayName("a prod container on a staging-stamped database fails with fixed text (no values)")
    void should_failBoot_when_stampBelongsToOtherEnvironment() {
        try (ConfigurableApplicationContext first = boot(DeployEnvironment.STAGING)) {
            assertThat(first.isActive()).isTrue();
        }

        Throwable thrown = catchThrowable(() -> boot(DeployEnvironment.PROD).close());

        assertThat(thrown).isNotNull();
        Throwable root = thrown;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Deployment environment stamp mismatch")
                .message().doesNotContain("staging").doesNotContain("prod");
        assertThat(stamps()).containsExactly("staging");
    }

    @Test
    @DisplayName("local environment never stamps")
    void should_notStamp_when_environmentLocal() {
        try (ConfigurableApplicationContext ctx = boot(DeployEnvironment.LOCAL)) {
            assertThat(ctx.isActive()).isTrue();
        }

        assertThat(stamps()).isEmpty();
    }

    private static Throwable rootOf(Throwable thrown) {
        Throwable root = thrown;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    @Test
    @DisplayName("unstamped database and no BEAUTICA_DB_STAMP_INIT: boot fails with fixed text and nothing is stamped")
    void should_failBoot_when_unstampedAndNoInit() {
        Throwable thrown = catchThrowable(() -> boot(DeployEnvironment.STAGING, null).close());

        assertThat(thrown).isNotNull();
        assertThat(rootOf(thrown)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BEAUTICA_DB_STAMP_INIT");
        // Refused before Flyway ran: not even the stamp table was created.
        assertThat(jdbc().queryForObject("SELECT to_regclass('deployment_environment') IS NULL", Boolean.class))
                .isTrue();
    }

    @Test
    @DisplayName("unstamped database and BEAUTICA_DB_STAMP_INIT matching: stamps")
    void should_stamp_when_unstampedAndInitMatches() {
        try (ConfigurableApplicationContext ctx = boot(DeployEnvironment.PROD, "prod")) {
            assertThat(ctx.isActive()).isTrue();
        }

        assertThat(stamps()).containsExactly("prod");
    }

    @Test
    @DisplayName("unstamped database and BEAUTICA_DB_STAMP_INIT for the other environment: fails, nothing stamped")
    void should_failBoot_when_unstampedAndInitIsOtherEnvironment() {
        Throwable thrown = catchThrowable(() -> boot(DeployEnvironment.STAGING, "prod").close());

        assertThat(thrown).isNotNull();
        assertThat(rootOf(thrown)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BEAUTICA_DB_STAMP_INIT");
        // Refused before Flyway ran: not even the stamp table was created.
        assertThat(jdbc().queryForObject("SELECT to_regclass('deployment_environment') IS NULL", Boolean.class))
                .isTrue();
    }

    @Test
    @DisplayName("stamped database: BEAUTICA_DB_STAMP_INIT is ignored (even naming the other environment)")
    void should_ignoreInit_when_alreadyStamped() {
        try (ConfigurableApplicationContext first = boot(DeployEnvironment.STAGING)) {
            assertThat(first.isActive()).isTrue();
        }

        try (ConfigurableApplicationContext second = boot(DeployEnvironment.STAGING, "prod")) {
            assertThat(second.isActive()).isTrue();
        }

        assertThat(stamps()).containsExactly("staging");
    }

    @Test
    @DisplayName("mismatched stamp + pending migration: boot fails BEFORE the pending migration is applied")
    void should_notApplyPendingMigration_when_stampMismatch() {
        try (ConfigurableApplicationContext first = boot(DeployEnvironment.STAGING)) {
            assertThat(first.isActive()).isTrue();
        }
        jdbc().update("UPDATE deployment_environment SET environment = 'prod'");

        Throwable thrown = catchThrowable(() -> boot(DeployEnvironment.STAGING, "staging",
                "spring.flyway.locations=classpath:db/migration,classpath:db/pending-probe-368").close());

        assertThat(thrown).isNotNull();
        assertThat(rootOf(thrown)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Deployment environment stamp mismatch");
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '99999'", Integer.class)).isZero();
        assertThat(jdbc().queryForObject("SELECT to_regclass('pending_probe_368') IS NOT NULL", Boolean.class))
                .isFalse();
        assertThat(stamps()).containsExactly("prod");
    }

    @Test
    @DisplayName("pending migration on a stamped database: validates once and applies it")
    void should_applyPendingMigration_when_stampMatches() {
        try (ConfigurableApplicationContext first = boot(DeployEnvironment.STAGING)) {
            assertThat(first.isActive()).isTrue();
        }

        try (ConfigurableApplicationContext second = boot(DeployEnvironment.STAGING, "staging",
                "spring.flyway.locations=classpath:db/migration,classpath:db/pending-probe-368")) {
            assertThat(second.isActive()).isTrue();
        }

        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '99999'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("out-of-order lower-version migration added after a higher one was applied: boot FAILS, not silently skipped (N4)")
    void should_failBoot_when_outOfOrderMigrationAppears() {
        try (ConfigurableApplicationContext first = boot(DeployEnvironment.STAGING, "staging",
                "spring.flyway.locations=classpath:db/migration,classpath:db/pending-probe-368")) {
            assertThat(first.isActive()).isTrue();
        }

        Throwable thrown = catchThrowable(() -> boot(DeployEnvironment.STAGING, "staging",
                "spring.flyway.locations=classpath:db/migration,classpath:db/pending-probe-368,"
                        + "classpath:db/out-of-order-probe-368").close());

        assertThat(thrown).isNotNull();
        assertThat(rootOf(thrown).getMessage()).contains("Validate failed");
        assertThat(jdbc().queryForObject("SELECT to_regclass('out_of_order_probe_368') IS NOT NULL", Boolean.class))
                .isFalse();
    }
}
