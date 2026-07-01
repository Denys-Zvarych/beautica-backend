package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for the boot-time {@code DATABASE_URL} normalizer.
 * <p>
 * Drives the {@link org.springframework.boot.env.EnvironmentPostProcessor} contract directly against a
 * real {@link StandardEnvironment} (a {@code ConfigurableEnvironment}) — no Spring context is started.
 * Each test seeds a {@code DATABASE_URL} property source, runs the post-processor, then asserts the
 * exact {@code spring.datasource.*} properties it injects. A regression here is a prod-only startup
 * failure, so the normalization contract is pinned field by field.
 */
@DisplayName("DatabaseUrlNormalizerPostProcessor — Neon DATABASE_URL → JDBC normalization")
class DatabaseUrlNormalizerPostProcessorTest {

    private final DatabaseUrlNormalizerPostProcessor processor = new DatabaseUrlNormalizerPostProcessor();

    private static StandardEnvironment environmentWith(String databaseUrl) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> source = new HashMap<>();
        if (databaseUrl != null) {
            source.put("DATABASE_URL", databaseUrl);
        }
        // addLast so the normalizer's addFirst("normalizedDatabaseUrl") still wins for resolution.
        environment.getPropertySources().addLast(new MapPropertySource("rawEnv", source));
        return environment;
    }

    @Nested
    @DisplayName("happy path — Neon-style postgresql:// URL")
    class HappyPath {

        @Test
        @DisplayName("postgresql://user:pass@host/db?sslmode=require → jdbc URL + split credentials")
        void should_normalizeNeonUrl_when_credentialsAndQueryPresent() {
            StandardEnvironment env = environmentWith(
                    "postgresql://neon_user:s3cr3t@ep-cool-host.eu-central-1.aws.neon.tech/beautica?sslmode=require");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .as("JDBC url: scheme rewritten, credentials stripped, sslmode kept")
                    .isEqualTo("jdbc:postgresql://ep-cool-host.eu-central-1.aws.neon.tech/beautica?sslmode=require");
            assertThat(env.getProperty("spring.datasource.username"))
                    .isEqualTo("neon_user");
            assertThat(env.getProperty("spring.datasource.password"))
                    .isEqualTo("s3cr3t");
        }

        @Test
        @DisplayName("explicit host:port is preserved in the JDBC url")
        void should_preservePort_when_portIsPresent() {
            StandardEnvironment env = environmentWith(
                    "postgresql://user:pass@db.internal:6543/beautica");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.internal:6543/beautica");
        }

        @Test
        @DisplayName("postgres:// scheme is accepted and rewritten the same way")
        void should_normalize_when_schemeIsPostgresShortForm() {
            StandardEnvironment env = environmentWith(
                    "postgres://user:pass@host.neon.tech/beautica?sslmode=require");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://host.neon.tech/beautica?sslmode=require");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("user");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("pass");
        }
    }

    @Nested
    @DisplayName("query filtering — libpq-only params dropped")
    class QueryFiltering {

        @Test
        @DisplayName("channel_binding and sslnegotiation are stripped, sslmode retained")
        void should_dropLibpqOnlyParams_when_queryMixesJdbcAndLibpqOptions() {
            StandardEnvironment env = environmentWith(
                    "postgresql://user:pass@host/beautica"
                            + "?sslmode=require&channel_binding=require&sslnegotiation=direct");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .as("only the JDBC-understood sslmode survives the filter")
                    .isEqualTo("jdbc:postgresql://host/beautica?sslmode=require");
        }

        @Test
        @DisplayName("no '?' is appended when every query param is filtered out")
        void should_omitQueryString_when_allParamsAreLibpqOnly() {
            StandardEnvironment env = environmentWith(
                    "postgresql://user:pass@host/beautica?channel_binding=require");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://host/beautica");
        }

        @Test
        @DisplayName("URL with no query string yields a bare JDBC url")
        void should_produceBareUrl_when_noQueryPresent() {
            StandardEnvironment env = environmentWith(
                    "postgresql://user:pass@host/beautica");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://host/beautica");
        }
    }

    @Nested
    @DisplayName("credential edge cases")
    class Credentials {

        @Test
        @DisplayName("userinfo without ':' maps to username only, no password property")
        void should_setUsernameOnly_when_userInfoHasNoPassword() {
            StandardEnvironment env = environmentWith(
                    "postgresql://lonely_user@host/beautica");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("lonely_user");
            assertThat(env.getProperty("spring.datasource.password")).isNull();
            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://host/beautica");
        }

        @Test
        @DisplayName("URL without userinfo injects url but no credential properties")
        void should_injectUrlOnly_when_noCredentialsInUrl() {
            StandardEnvironment env = environmentWith(
                    "postgresql://host/beautica?sslmode=require");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://host/beautica?sslmode=require");
            assertThat(env.getProperty("spring.datasource.username")).isNull();
            assertThat(env.getProperty("spring.datasource.password")).isNull();
        }
    }

    @Nested
    @DisplayName("no-op / passthrough cases")
    class NoOp {

        @Test
        @DisplayName("an already-jdbc URL is left completely untouched")
        void should_doNothing_when_urlAlreadyHasJdbcPrefix() {
            StandardEnvironment env = environmentWith(
                    "jdbc:postgresql://host:5432/beautica?sslmode=require");

            processor.postProcessEnvironment(env, null);

            // DATABASE_URL resolves to the original (no normalizedDatabaseUrl source added).
            assertThat(env.getPropertySources().contains("normalizedDatabaseUrl")).isFalse();
            assertThat(env.getProperty("spring.datasource.url")).isNull();
            assertThat(env.getProperty("DATABASE_URL"))
                    .isEqualTo("jdbc:postgresql://host:5432/beautica?sslmode=require");
        }

        @Test
        @DisplayName("absent DATABASE_URL is a no-op — no properties injected")
        void should_doNothing_when_databaseUrlIsAbsent() {
            StandardEnvironment env = environmentWith(null);

            processor.postProcessEnvironment(env, null);

            assertThat(env.getPropertySources().contains("normalizedDatabaseUrl")).isFalse();
            assertThat(env.getProperty("spring.datasource.url")).isNull();
        }

        @Test
        @DisplayName("a malformed URL is swallowed — no properties injected, startup proceeds")
        void should_swallowFailure_when_urlIsMalformed() {
            // A bare space is illegal in a URI and makes URI.create throw — exercises the catch branch.
            StandardEnvironment env = environmentWith("postgresql://bad host/beautica");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getPropertySources().contains("normalizedDatabaseUrl")).isFalse();
            assertThat(env.getProperty("spring.datasource.url")).isNull();
        }
    }
}
