package com.beautica.config;

import com.beautica.support.TestSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Proves {@link DatabaseUrlNormalizerPostProcessor} is wired through {@code META-INF/spring.factories}
 * and runs inside a real {@link org.springframework.boot.SpringApplication} startup, and that its
 * prod fail-fast is not swallowed by Boot. Unit tests call {@code postProcessEnvironment} directly and
 * cannot prove either. No database is contacted: the context holds one empty configuration class.
 */
@DisplayName("DatabaseUrlNormalizerPostProcessor — inside a real Spring Boot startup")
class DatabaseUrlNormalizerBootStartupTest {

    private static final String PASSWORD = "S3cretPw-9xQ";

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {
    }

    private static SpringApplicationBuilder app(String databaseUrl, String... profiles) {
        return new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.NONE)
                .profiles(profiles)
                .properties("DATABASE_URL=" + databaseUrl, "spring.main.banner-mode=off",
                        // EnvironmentFingerprintGuard (Phase 368): prod requires R2 on with the exact prod bucket.
                        "R2_ENABLED=true", "CLOUDFLARE_R2_BUCKET=beautica-media-prod",
                        "CLOUDFLARE_R2_PUBLIC_URL=https://media.beautica.ua", "FRONTEND_BASE_URL=https://app.beautica.ua",
                        "PUBLIC_BASE_URL=https://api.beautica.ua", "SUPPORT_EMAIL=help@beautica.ua",
                        "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY,
                        // Phase 367 prod required-env rules.
                        "SENTRY_DSN=https://0123456789abcdef@o42.ingest.de.sentry.io/4500000000000001",
                        "TURBOSMS_TOKEN=turbosms-fixture-token", "ADMIN_EMAIL=admin@beautica.ua");
    }

    private static List<Throwable> chain(Throwable t) {
        List<Throwable> all = new ArrayList<>();
        for (Throwable c = t; c != null && !all.contains(c); c = c.getCause()) {
            all.add(c);
        }
        return all;
    }

    @Test
    @DisplayName("prod + Railway public proxy host — boot FAILS with ProdTransportViolationException and leaks no password")
    void should_failStartup_when_prodProfileAndRailwayPublicProxyUrl() {
        Throwable thrown = catchThrowable(() -> app(
                "postgresql://postgres:" + PASSWORD + "@shuttle.proxy.rlwy.net:41234/railway", "prod").run());

        assertThat(thrown).as("startup must abort, not boot with the proxy URL").isNotNull();
        Throwable violation = chain(thrown).stream()
                .filter(t -> t.getClass().getSimpleName().equals("ProdTransportViolationException"))
                .findFirst().orElse(null);
        assertThat(violation)
                .as("ProdTransportViolationException must be in the cause chain, actual chain=%s", chain(thrown))
                .isNotNull();
        assertThat(violation.getMessage()).contains("Railway public proxy").doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("prod + public non-Railway host — boot FAILS (allowlist) and leaks no password")
    void should_failStartup_when_prodProfileAndPublicNonRailwayHost() {
        Throwable thrown = catchThrowable(() -> app(
                "postgresql://postgres:" + PASSWORD + "@db.example.com:5432/railway", "prod").run());

        assertThat(thrown).as("startup must abort").isNotNull();
        Throwable violation = chain(thrown).stream()
                .filter(t -> t.getClass().getSimpleName().equals("ProdTransportViolationException"))
                .findFirst().orElse(null);
        assertThat(violation).isNotNull();
        assertThat(violation.getMessage()).contains("private Railway host").doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("prod + raw jdbc: URL — boot FAILS (fail closed on pgjdbc host grammar)")
    void should_failStartup_when_prodProfileAndRawJdbcUrl() {
        Throwable thrown = catchThrowable(() -> app(
                "jdbc:postgresql://db.railway.internal:5432/railway?user=u&password=" + PASSWORD, "prod").run());

        assertThat(chain(thrown))
                .as("chain=%s", chain(thrown))
                .anyMatch(t -> t.getClass().getSimpleName().equals("ProdTransportViolationException"));
        assertThat(chain(thrown)).noneMatch(t -> String.valueOf(t.getMessage()).contains(PASSWORD));
    }

    @Test
    @DisplayName("prod + host-override query key — boot FAILS (cannot smuggle the proxy host past the authority check)")
    void should_failStartup_when_prodProfileAndHostOverrideQueryKey() {
        Throwable thrown = catchThrowable(() -> app(
                "postgresql://u:" + PASSWORD + "@db.railway.internal:5432/railway?PGHOST=shuttle.proxy.rlwy.net",
                "prod").run());

        assertThat(chain(thrown))
                .as("chain=%s", chain(thrown))
                .anyMatch(t -> t.getClass().getSimpleName().equals("ProdTransportViolationException"));
    }

    @Test
    @DisplayName("prod + Railway private URL — boots and resolves a JDBC url with sslmode=require and split credentials")
    void should_resolveSecureJdbcUrl_when_prodProfileAndRailwayPrivateUrl() {
        try (ConfigurableApplicationContext ctx = app(
                "postgresql://postgres:" + PASSWORD + "@postgres.railway.internal:5432/railway", "prod").run()) {
            var env = ctx.getEnvironment();

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://postgres.railway.internal:5432/railway?sslmode=require&logServerErrorDetail=false");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("postgres");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo(PASSWORD);
        }
    }

    @Test
    @DisplayName("non-prod + Railway public proxy host — boots (WARN only) and still forces sslmode=require")
    void should_bootWithSslRequire_when_nonProdAndRailwayPublicProxyUrl() {
        try (ConfigurableApplicationContext ctx = app(
                "postgresql://postgres:" + PASSWORD + "@shuttle.proxy.rlwy.net:41234/railway").run()) {

            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://shuttle.proxy.rlwy.net:41234/railway?sslmode=require&logServerErrorDetail=false");
        }
    }

    @Test
    @DisplayName("non-prod + loopback host — no sslmode injected (local Docker stays plaintext)")
    void should_notInjectSslmode_when_loopbackHost() {
        try (ConfigurableApplicationContext ctx = app("postgresql://u:p@localhost:5432/beautica").run()) {

            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://localhost:5432/beautica?logServerErrorDetail=false");
        }
    }
}
