package com.beautica.config;

import com.beautica.TestPostgresImage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Boots a minimal real Spring Boot application (DataSource + JdbcTemplate + Flyway +
 * {@link DatabaseServerVersionLogger}) with only a Railway-shaped {@code DATABASE_URL}
 * ({@code postgresql://...}) against Testcontainers PostgreSQL 18, and proves what the
 * post-processor unit tests cannot: the normalized URL actually reaches the pool, the connection
 * is really TLS ({@code pg_stat_ssl}), the version logger prints its line, and Flyway 11 applies all
 * migrations on PG 18 without the "newer than this version of Flyway" warning.
 *
 * <p>The host is {@code 127.0.0.2}: loopback at the OS level but NOT in the normalizer's loopback
 * allow-list, so the production non-loopback code path (default {@code sslmode=require}) is taken.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Railway-style DATABASE_URL — real boot against PostgreSQL 18")
class RailwayStyleDatabaseBootIT {

    private static final String NON_LOOPBACK_HOST = "127.0.0.2";
    private static final String CERT_DIR = "/opt/pgtls";

    @TempDir
    static Path tmp;

    static PostgreSQLContainer<?> tlsPg;
    static PostgreSQLContainer<?> plainPg;

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            FlywayAutoConfiguration.class})
    @Import(DatabaseServerVersionLogger.class)
    static class MinimalDbConfig {
    }

    @BeforeAll
    static void startContainers() throws Exception {
        Path key = tmp.resolve("server.key");
        Path crt = tmp.resolve("server.crt");
        Process p = new ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-keyout", key.toString(), "-out", crt.toString(), "-days", "2", "-subj", "/CN=localhost")
                .redirectErrorStream(true).start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("openssl finished").isTrue();
        assertThat(p.exitValue()).as("openssl exit").isZero();

        tlsPg = new PostgreSQLContainer<>(TestPostgresImage.IMAGE)
                .withCopyFileToContainer(MountableFile.forHostPath(key), "/tmp/server.key")
                .withCopyFileToContainer(MountableFile.forHostPath(crt), "/tmp/server.crt")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c",
                        "mkdir -p " + CERT_DIR + " && cp /tmp/server.key /tmp/server.crt " + CERT_DIR
                                + " && chown postgres:postgres " + CERT_DIR + "/server.* && chmod 600 "
                                + CERT_DIR + "/server.key && exec docker-entrypoint.sh postgres"
                                + " -c ssl=on -c ssl_cert_file=" + CERT_DIR + "/server.crt"
                                + " -c ssl_key_file=" + CERT_DIR + "/server.key"));
        plainPg = new PostgreSQLContainer<>(TestPostgresImage.IMAGE);
        tlsPg.start();
        plainPg.start();
    }

    @AfterAll
    static void stopContainers() {
        if (tlsPg != null) tlsPg.stop();
        if (plainPg != null) plainPg.stop();
    }

    private static String railwayStyleUrl(PostgreSQLContainer<?> pg, String query) {
        return "postgresql://" + pg.getUsername() + ":" + pg.getPassword() + "@" + NON_LOOPBACK_HOST + ":"
                + pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + pg.getDatabaseName() + query;
    }

    private static SpringApplicationBuilder app(String url, boolean flyway) {
        return new SpringApplicationBuilder(MinimalDbConfig.class)
                .web(WebApplicationType.NONE)
                .properties("DATABASE_URL=" + url, "spring.main.banner-mode=off",
                        "spring.flyway.enabled=" + flyway, "beautica.db.expected-major=18");
    }

    @Test
    @DisplayName("TLS server + URL without sslmode — connects over TLS, logs PG 18, Flyway applies cleanly with no version warning")
    void should_connectOverTlsAndMigrateCleanly_when_railwayStyleUrlHasNoSslmode(CapturedOutput output) {
        try (ConfigurableApplicationContext ctx = app(railwayStyleUrl(tlsPg, ""), true).run()) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url"))
                    .as("resolved url must carry the secure default")
                    .contains("sslmode=require").startsWith("jdbc:postgresql://" + NON_LOOPBACK_HOST);
            assertThat(jdbc.queryForObject("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()", Boolean.class))
                    .as("the application connection must be encrypted").isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                    .as("Flyway applied the migrations on PG 18").isGreaterThan(100);
            assertThat(output.getAll())
                    .contains("PostgreSQL server major version 18")
                    .doesNotContain("differs from expected")
                    .doesNotContainIgnoringCase("newer than this version of Flyway")
                    .doesNotContainIgnoringCase("Flyway upgrade recommended")
                    .contains("Successfully applied");
        }
    }

    @Test
    @DisplayName("non-TLS server + URL without sslmode — startup FAILS: require is enforced, no silent plaintext fallback")
    void should_refuseToConnect_when_serverHasNoTlsAndNoSslmodeGiven() {
        Throwable thrown = catchThrowable(() -> {
            try (ConfigurableApplicationContext ctx = app(railwayStyleUrl(plainPg, ""), false).run()) {
                ctx.getBean(JdbcTemplate.class).queryForObject("SELECT 1", Integer.class);
            }
        });

        assertThat(thrown).as("with pgjdbc's default 'prefer' this would have booted over plaintext").isNotNull();
        assertThat(rootMessages(thrown)).containsIgnoringCase("does not support SSL");
    }

    @Test
    @DisplayName("non-TLS server + explicit sslmode=disable — explicit opt-out is honoured (control for the refusal test)")
    void should_connectPlaintext_when_explicitSslmodeDisable() {
        try (ConfigurableApplicationContext ctx = app(railwayStyleUrl(plainPg, "?sslmode=disable"), false).run()) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

            assertThat(jdbc.queryForObject("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()", Boolean.class))
                    .isFalse();
        }
    }

    private static String rootMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
            if (c.getCause() == c) break;
        }
        return sb.toString();
    }
}
