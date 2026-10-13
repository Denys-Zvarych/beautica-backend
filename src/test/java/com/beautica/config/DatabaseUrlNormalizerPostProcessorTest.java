package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test for the boot-time {@code DATABASE_URL} normalizer.
 * <p>
 * Drives the {@link org.springframework.boot.env.EnvironmentPostProcessor} contract directly against a
 * real {@link StandardEnvironment} (a {@code ConfigurableEnvironment}) — no Spring context is started.
 * Each test seeds a {@code DATABASE_URL} property source, runs the post-processor, then asserts the
 * exact {@code spring.datasource.*} properties it injects. A regression here is a prod-only startup
 * failure, so the normalization contract is pinned field by field.
 */
@DisplayName("DatabaseUrlNormalizerPostProcessor — DATABASE_URL → JDBC normalization")
@ExtendWith(OutputCaptureExtension.class)
class DatabaseUrlNormalizerPostProcessorTest {

    private final DeferredLogs logs = new DeferredLogs();
    private final DatabaseUrlNormalizerPostProcessor processor = new DatabaseUrlNormalizerPostProcessor(logs);

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
    @DisplayName("happy path — postgresql:// URL")
    class HappyPath {

        @Test
        @DisplayName("postgresql://user:pass@host/db?sslmode=require → jdbc URL + split credentials")
        void should_normalizeUrl_when_credentialsAndQueryPresent() {
            StandardEnvironment env = environmentWith(
                    "postgresql://app_user:s3cr3t@db-host.example.com/beautica?sslmode=require");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .as("JDBC url: scheme rewritten, credentials stripped, sslmode kept")
                    .isEqualTo("jdbc:postgresql://db-host.example.com/beautica?sslmode=require");
            assertThat(env.getProperty("spring.datasource.username"))
                    .isEqualTo("app_user");
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
                    .isEqualTo("jdbc:postgresql://db.internal:6543/beautica?sslmode=require");
        }

        @Test
        @DisplayName("postgres:// scheme is accepted and rewritten the same way")
        void should_normalize_when_schemeIsPostgresShortForm() {
            StandardEnvironment env = environmentWith(
                    "postgres://user:pass@host.example.com/beautica?sslmode=require");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://host.example.com/beautica?sslmode=require");
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
                    "postgresql://user:pass@localhost/beautica?channel_binding=require");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://localhost/beautica");
        }

        @Test
        @DisplayName("URL with no query string yields a bare JDBC url")
        void should_produceBareUrl_when_noQueryPresent() {
            StandardEnvironment env = environmentWith(
                    "postgresql://user:pass@localhost/beautica");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://localhost/beautica");
        }
    }

    @Nested
    @DisplayName("credential edge cases")
    class Credentials {

        @Test
        @DisplayName("userinfo without ':' maps to username only, no password property")
        void should_setUsernameOnly_when_userInfoHasNoPassword() {
            StandardEnvironment env = environmentWith(
                    "postgresql://lonely_user@localhost/beautica");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("lonely_user");
            assertThat(env.getProperty("spring.datasource.password")).isNull();
            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://localhost/beautica");
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
        @DisplayName("an already-jdbc URL (non-postgresql driver) is left completely untouched")
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
        @DisplayName("a malformed URL injects nothing, boot proceeds, and a WARN is logged without the password or URL")
        void should_warnWithoutLeaking_when_urlIsMalformed(CapturedOutput output) {
            // A bare space is illegal in a URI and makes URI.create throw — exercises the catch branch.
            StandardEnvironment env = environmentWith("postgresql://user:Sup3rS3cret@bad host/beautica");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(env.getPropertySources().contains("normalizedDatabaseUrl")).isFalse();
            assertThat(env.getProperty("spring.datasource.url")).isNull();
            assertThat(output.getAll())
                    .contains("DATABASE_URL could not be normalized: IllegalArgumentException")
                    .doesNotContain("Sup3rS3cret")
                    .doesNotContain("bad host")
                    .doesNotContain("postgresql://");
        }
    }

    @Nested
    @DisplayName("Railway shapes and secure-by-default sslmode")
    class RailwayAndSslMode {

        @Test
        @DisplayName("Railway private URL → sslmode=require appended, credentials split")
        void should_appendSslModeRequire_when_railwayPrivateUrl() {
            StandardEnvironment env = environmentWith(
                    "postgresql://postgres:AbC123xyz@postgres.railway.internal:5432/railway");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://postgres.railway.internal:5432/railway?sslmode=require");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("postgres");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("AbC123xyz");
        }

        @Test
        @DisplayName("Railway public proxy URL → port kept, sslmode=require appended")
        void should_keepPortAndAppendSslMode_when_railwayPublicProxyUrl() {
            StandardEnvironment env = environmentWith(
                    "postgresql://postgres:pw@shuttle.proxy.rlwy.net:41234/railway");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://shuttle.proxy.rlwy.net:41234/railway?sslmode=require");
        }

        @Test
        @DisplayName("explicit sslmode=disable is kept, never overridden")
        void should_keepSslModeDisable_when_explicit() {
            StandardEnvironment env = environmentWith("postgresql://u:p@db.example.com/beautica?sslmode=disable");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.example.com/beautica?sslmode=disable");
        }

        @Test
        @DisplayName("explicit sslmode=verify-full is kept, never overridden")
        void should_keepSslModeVerifyFull_when_explicit() {
            StandardEnvironment env = environmentWith("postgresql://u:p@db.example.com/beautica?sslmode=verify-full");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.example.com/beautica?sslmode=verify-full");
        }

        @ParameterizedTest
        @ValueSource(strings = {"localhost", "127.0.0.1", "[::1]"})
        @DisplayName("loopback hosts get no sslmode")
        void should_notAddSslMode_when_hostIsLoopback(String host) {
            StandardEnvironment env = environmentWith("postgresql://u:p@" + host + ":5432/beautica");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://" + host + ":5432/beautica");
        }

        @Test
        @DisplayName("percent-encoded password is decoded")
        void should_decodePassword_when_percentEncoded() {
            StandardEnvironment env = environmentWith("postgresql://u:p%40ss%3Aw@db.example.com/beautica");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("p@ss:w");
        }

        @Test
        @DisplayName("existing query is preserved and sslmode appended with '&'")
        void should_appendWithAmpersand_when_queryExistsWithoutSslMode() {
            StandardEnvironment env = environmentWith("postgresql://u:p@db.example.com/beautica?connectTimeout=10");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.example.com/beautica?connectTimeout=10&sslmode=require");
        }

        @Test
        @DisplayName("channel_binding is dropped and sslmode defaulted when only libpq-only params present")
        void should_dropChannelBindingAndDefaultSslMode_when_onlyLibpqParams() {
            StandardEnvironment env = environmentWith("postgresql://u:p@db.example.com/beautica?channel_binding=require");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.example.com/beautica?sslmode=require");
        }

        @Test
        @DisplayName("a hostless/unparseable host (underscore) injects nothing and WARNs without host, password or URL")
        void should_warnWithoutLeaking_when_hostIsNotParseable(CapturedOutput output) {
            StandardEnvironment env = environmentWith("postgresql://user:Sup3rS3cret@my_db_host/beautica");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(env.getPropertySources().contains("normalizedDatabaseUrl")).isFalse();
            assertThat(env.getProperty("spring.datasource.url")).isNull();
            assertThat(output.getAll())
                    .contains("DATABASE_URL could not be normalized: no parseable host")
                    .doesNotContain("Sup3rS3cret")
                    .doesNotContain("my_db_host")
                    .doesNotContain("postgresql://");
        }

        @ParameterizedTest
        @ValueSource(strings = {"disable", "allow", "prefer", "DISABLE", "Prefer"})
        @DisplayName("weak explicit sslmode on a non-loopback host is kept but WARNs with the mode only")
        void should_warnWithoutLeaking_when_weakSslModeOnRemoteHost(String mode, CapturedOutput output) {
            StandardEnvironment env = environmentWith(
                    "postgresql://u:Sup3rS3cret@secret-host.example.com/beautica?sslmode=" + mode);

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://secret-host.example.com/beautica?sslmode=" + mode);
            assertThat(output.getAll())
                    .contains("DATABASE_URL sslmode=" + mode.toLowerCase() + " on a non-loopback host")
                    .doesNotContain("Sup3rS3cret")
                    .doesNotContain("secret-host.example.com");
        }

        @Test
        @DisplayName("weak sslmode on a loopback host does not WARN")
        void should_notWarn_when_weakSslModeOnLoopback(CapturedOutput output) {
            StandardEnvironment env = environmentWith("postgresql://u:p@localhost:5432/beautica?sslmode=disable");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(output.getAll()).doesNotContain("non-loopback host");
        }

        @Test
        @DisplayName("strong explicit sslmode does not WARN")
        void should_notWarn_when_strongSslModeOnRemoteHost(CapturedOutput output) {
            StandardEnvironment env = environmentWith("postgresql://u:p@db.example.com/beautica?sslmode=verify-full");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(output.getAll()).doesNotContain("non-loopback host");
        }

        @Test
        @DisplayName("Railway public proxy host WARNs about the private URL, without host or password")
        void should_warnWithoutLeaking_when_hostIsRailwayPublicProxy(CapturedOutput output) {
            StandardEnvironment env = environmentWith(
                    "postgresql://postgres:Sup3rS3cret@shuttle.proxy.rlwy.net:41234/railway");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(output.getAll())
                    .contains("Railway public TCP proxy")
                    .doesNotContain("Sup3rS3cret")
                    .doesNotContain("shuttle.proxy");
        }

        @Test
        @DisplayName("Railway private host does not WARN")
        void should_notWarn_when_hostIsRailwayPrivate(CapturedOutput output) {
            StandardEnvironment env = environmentWith("postgresql://postgres:p@postgres.railway.internal:5432/railway");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(output.getAll()).doesNotContain("Railway public TCP proxy");
        }

        @Test
        @DisplayName("prod profile + Railway public proxy host fails startup with fixed text (no host, password)")
        void should_failStartup_when_prodProfileAndPublicProxyHost() {
            StandardEnvironment env = environmentWith(
                    "postgresql://postgres:Sup3rS3cret@shuttle.proxy.rlwy.net:41234/railway");
            env.setActiveProfiles("prod");

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("private *.railway.internal")
                    .message().doesNotContain("Sup3rS3cret").doesNotContain("shuttle").doesNotContain("postgresql://");
        }

        @Test
        @DisplayName("prod via spring.profiles.active property (SPRING_PROFILES_ACTIVE) also fails startup")
        void should_failStartup_when_prodProfileSetOnlyAsProperty() {
            StandardEnvironment env = environmentWith("postgresql://u:p@shuttle.proxy.rlwy.net:41234/railway");
            env.getPropertySources().addLast(
                    new MapPropertySource("profiles", Map.of("spring.profiles.active", "prod,staging")));

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("prod profile + private railway.internal host boots")
        void should_boot_when_prodProfileAndPrivateHost() {
            StandardEnvironment env = environmentWith("postgresql://u:p@postgres.railway.internal:5432/railway");
            env.setActiveProfiles("prod");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://postgres.railway.internal:5432/railway?sslmode=require");
        }

        @ParameterizedTest
        @ValueSource(strings = {"db.example.com", "127.0.0.2", "localhost", "127.0.0.1", "db.internal"})
        @DisplayName("prod + any host outside *.railway.internal fails startup with fixed text (allowlist)")
        void should_failStartup_when_prodAndHostNotRailwayInternal(String host) {
            StandardEnvironment env = environmentWith("postgresql://u:Sup3rS3cret@" + host + ":5432/railway");
            env.setActiveProfiles("prod");

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("private Railway host (*.railway.internal)")
                    .message().doesNotContain("Sup3rS3cret").doesNotContain(host).doesNotContain("postgresql://");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "postgresql://u:Sup3rS3cret@a.railway.internal,b.railway.internal:5432/railway",
                "postgresql://u:Sup3rS3cret@evil.com@x.railway.internal:5432/railway",
                "postgresql://u:Sup3rS3cret@db_1.railway.internal:5432/railway"})
        @DisplayName("prod + unparseable/multi-host postgresql:// URL fails startup with fixed text")
        void should_failStartup_when_prodAndHostUnparseable(String url) {
            StandardEnvironment env = environmentWith(url);
            env.setActiveProfiles("prod");

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("parseable single-host")
                    .message().doesNotContain("Sup3rS3cret").doesNotContain("evil");
        }

        @Test
        @DisplayName("prod + malformed URI (parse exception) fails startup, not soft-WARN")
        void should_failStartup_when_prodAndUriSyntaxInvalid() {
            StandardEnvironment env = environmentWith("postgresql://u:Sup3rS3cret@bad host/db");
            env.setActiveProfiles("prod");

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .message().doesNotContain("Sup3rS3cret");
        }

        @Test
        @DisplayName("non-prod + multi-host postgresql:// URL only WARNs and boots with the raw value")
        void should_onlyWarn_when_nonProdAndHostUnparseable() {
            StandardEnvironment env = environmentWith("postgresql://u:p@a.example.com,b.example.com:5432/db");
            env.setActiveProfiles("local");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getPropertySources().contains("normalizedDatabaseUrl")).isFalse();
        }

        @Test
        @DisplayName("non-prod profile + public proxy host only WARNs, no throw")
        void should_onlyWarn_when_nonProdProfileAndPublicProxyHost(CapturedOutput output) {
            StandardEnvironment env = environmentWith("postgresql://u:p@shuttle.proxy.rlwy.net:41234/railway");
            env.setActiveProfiles("local");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(env.getProperty("spring.datasource.url")).isNotNull();
            assertThat(output.getAll()).contains("Railway public TCP proxy");
        }

        @ParameterizedTest
        @ValueSource(strings = {"shuttle.proxy.rlwy.net.", "SHUTTLE.PROXY.RLWY.NET", "Shuttle.Proxy.Rlwy.Net."})
        @DisplayName("prod + proxy host with trailing dot or uppercase still fails startup")
        void should_failStartup_when_prodAndProxyHostHasTrailingDot(String host) {
            StandardEnvironment env = environmentWith("postgresql://u:p@" + host + ":41234/railway");
            env.setActiveProfiles("prod");

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("private *.railway.internal");
        }

        @Test
        @DisplayName("loopback host with trailing dot gets no sslmode")
        void should_notAddSslMode_when_loopbackHostHasTrailingDot() {
            StandardEnvironment env = environmentWith("postgresql://u:p@localhost.:5432/beautica");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://localhost.:5432/beautica");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://postgres.railway.internal:5432/railway?password=Sup3rS3cret",
                "jdbc:postgresql://x.rlwy.net,a@b/db?password=Sup3rS3cret",
                "jdbc:mysql://shuttle.proxy.rlwy.net/db"})
        @DisplayName("prod + jdbc: URL is refused with fixed text that leaks nothing")
        void should_failStartup_when_prodAndJdbcUrl(String url) {
            StandardEnvironment env = environmentWith(url);
            env.setActiveProfiles("prod");

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("raw jdbc: URLs are refused")
                    .message().doesNotContain("Sup3rS3cret").doesNotContain("rlwy").doesNotContain("railway.internal");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://shuttle.proxy.rlwy.net:41234/railway?sslmode=disable&password=Sup3rS3cret",
                "jdbc:postgresql://a,b/db?host=x.rlwy.net"})
        @DisplayName("non-prod + jdbc: URL passes through unchanged, no checks, no WARN")
        void should_passThroughUnchanged_when_nonProdAndJdbcUrl(String url, CapturedOutput output) {
            StandardEnvironment env = environmentWith(url);
            env.setActiveProfiles("local");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(env.getPropertySources().contains("normalizedDatabaseUrl")).isFalse();
            assertThat(env.getProperty("DATABASE_URL")).isEqualTo(url);
            assertThat(output.getAll()).doesNotContain("DATABASE_URL");
        }

        @ParameterizedTest
        @ValueSource(strings = {"host=x.rlwy.net", "PGHOST=x.rlwy.net", "hostaddr=1.2.3.4", "PgHostAddr=1.2.3.4",
                "sslmode=require&Host=x.rlwy.net"})
        @DisplayName("prod + host-override query key fails startup with fixed text")
        void should_failStartup_when_prodAndHostOverrideQueryKey(String param) {
            StandardEnvironment env = environmentWith("postgresql://u:Sup3rS3cret@postgres.railway.internal/db?" + param);
            env.setActiveProfiles("prod");

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("host-override")
                    .message().doesNotContain("Sup3rS3cret").doesNotContain("rlwy").doesNotContain("1.2.3.4");
        }

        @Test
        @DisplayName("non-prod + PGHOST query key is dropped with a fixed-text WARN")
        void should_dropHostOverrideAndWarn_when_nonProdAndPgHostParam(CapturedOutput output) {
            StandardEnvironment env = environmentWith("postgresql://u:p@db.example.com/beautica?PGHOST=evil.rlwy.net&application_name=x");
            env.setActiveProfiles("local");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.example.com/beautica?application_name=x&sslmode=require");
            assertThat(output.getAll())
                    .contains("host-override query parameter dropped")
                    .doesNotContain("evil");
        }

        @Test
        @DisplayName("prod delivered via a profile group/include (already expanded into active profiles) fails startup")
        void should_failStartup_when_prodArrivesAsExpandedGroupMember() {
            StandardEnvironment env = environmentWith("postgresql://u:p@shuttle.proxy.rlwy.net:41234/railway");
            // Boot expands spring.profiles.group.* / spring.profiles.include into setActiveProfiles before
            // this runs, so the processor only ever sees the flattened list.
            env.setActiveProfiles("staging", "prod");

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"sslmode=", "sslmode=%20", "sslmode"})
        @DisplayName("empty/blank sslmode counts as absent: dropped and sslmode=require injected")
        void should_injectSslModeRequire_when_sslModeValueEmpty(String param) {
            StandardEnvironment env = environmentWith("postgresql://u:p@db.example.com/beautica?" + param);

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.example.com/beautica?sslmode=require");
        }

        @ParameterizedTest
        @ValueSource(strings = {"db%3Fhost%3Devil.com", "db%3Fsslmode%3Ddisable", "db%3fhost%3dx", "db%2Fx", "db%26a%3Db",
                "db;x", "db%23x", "db%253Fhost%3Devil", "db/", "db%3Bx"})
        @DisplayName("prod + percent-encoded database name fails startup without leaking the URL")
        void should_failStartup_when_prodAndPathPercentEncoded(String dbName) {
            StandardEnvironment env = environmentWith("postgresql://u:Sup3rS3cret@postgres.railway.internal:5432/" + dbName);
            env.setActiveProfiles("prod");

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("prod requires a plain database name in DATABASE_URL")
                    .message().doesNotContain("Sup3rS3cret").doesNotContain("evil").doesNotContain("sslmode");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "..", ".", "railway"})
        @DisplayName("prod + harmless plain path shapes (/, /.., /., /railway) boot")
        void should_boot_when_prodAndHarmlessPlainPath(String dbName) {
            StandardEnvironment env = environmentWith("postgresql://u:p@postgres.railway.internal:5432/" + dbName);
            env.setActiveProfiles("prod");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://postgres.railway.internal:5432/" + dbName + "?sslmode=require");
        }

        @ParameterizedTest
        @ValueSource(strings = {"db;x", "db%23x", "db/"})
        @DisplayName("non-prod + non-plain database name: fixed WARN, DATABASE_URL left untouched")
        void should_warnAndNotNormalize_when_nonProdAndPathNotPlain(String dbName, CapturedOutput output) {
            StandardEnvironment env = environmentWith("postgresql://u:Sup3rS3cret@db.example.com/" + dbName);

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(env.getProperty("spring.datasource.url")).isNull();
            assertThat(output.getAll()).contains("database name is not plain").doesNotContain("Sup3rS3cret");
        }

        @Test
        @DisplayName("double-encoded sslmode=%2564isable on non-loopback host gets the weak-mode WARN")
        void should_warn_when_sslModeDoubleEncodedDisable(CapturedOutput output) {
            StandardEnvironment env = environmentWith("postgresql://u:p@db.example.com/beautica?sslmode=%2564isable");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.example.com/beautica?sslmode=%2564isable");
            assertThat(output.getAll()).contains("sslmode=disable on a non-loopback host");
        }

        @Test
        @DisplayName("a %26 inside a value stays one parameter, passed through raw")
        void should_keepSingleParam_when_valueContainsEncodedAmpersand() {
            StandardEnvironment env = environmentWith(
                    "postgresql://u:p@db.example.com/beautica?ApplicationName=a%26host%3Devil&sslmode=require");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.example.com/beautica?ApplicationName=a%26host%3Devil&sslmode=require");
        }

        @Test
        @DisplayName("prod + host-override key encoded as %68ost fails startup")
        void should_failStartup_when_prodAndHostOverrideKeyEncoded() {
            StandardEnvironment env = environmentWith(
                    "postgresql://u:p@postgres.railway.internal:5432/railway?%68ost=evil.com");
            env.setActiveProfiles("prod");

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .message().contains("host-override").doesNotContain("evil");
        }

        @Test
        @DisplayName("username with %3A and password are split on the literal ':' then decoded separately")
        void should_splitOnLiteralColon_when_usernameContainsEncodedColon() {
            StandardEnvironment env = environmentWith("postgresql://a%3Ab:p%40ss@localhost/beautica");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("a:b");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("p@ss");
        }

        @Test
        @DisplayName("prod + Railway's real URL shape (/railway) boots")
        void should_boot_when_prodAndPlainRailwayDbName() {
            StandardEnvironment env = environmentWith("postgresql://postgres:pw@postgres.railway.internal:5432/railway");
            env.setActiveProfiles("prod");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://postgres.railway.internal:5432/railway?sslmode=require");
        }

        @Test
        @DisplayName("prod + empty path is accepted (unchanged behaviour): no database segment")
        void should_boot_when_prodAndEmptyPath() {
            StandardEnvironment env = environmentWith("postgresql://u:p@postgres.railway.internal:5432");
            env.setActiveProfiles("prod");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://postgres.railway.internal:5432?sslmode=require");
        }

        @Test
        @DisplayName("non-prod + percent-encoded database name: fixed WARN, DATABASE_URL left untouched")
        void should_warnAndNotNormalize_when_nonProdAndPathPercentEncoded(CapturedOutput output) {
            StandardEnvironment env = environmentWith("postgresql://u:Sup3rS3cret@db.example.com/db%3Fhost%3Devil.com");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(env.getProperty("spring.datasource.url")).isNull();
            assertThat(env.getProperty("DATABASE_URL")).startsWith("postgresql://");
            assertThat(output.getAll())
                    .contains("database name is not plain")
                    .doesNotContain("Sup3rS3cret").doesNotContain("evil");
        }
    }

    @Nested
    @DisplayName("sslmode key hygiene and prod transport floor")
    class SslModeConvergence {

        private static final String PROD_HOST = "postgresql://u:Sup3rS3cret@postgres.railway.internal:5432/railway";
        private static final String REMOTE_HOST = "postgresql://u:Sup3rS3cret@db.example.com/beautica";

        private StandardEnvironment prodEnv(String query) {
            StandardEnvironment env = environmentWith(PROD_HOST + query);
            env.setActiveProfiles("prod");
            return env;
        }

        @ParameterizedTest
        @ValueSource(strings = {"%73slmode=disable", "SSLMODE=disable", "%53slMode=require"})
        @DisplayName("prod + encoded/case-variant sslmode key fails startup with fixed text")
        void should_failStartup_when_prodAndEncodedSslModeKey(String param) {
            StandardEnvironment env = prodEnv("?" + param);

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("DATABASE_URL carries an encoded or case-variant sslmode key; refusing to start in prod");
        }

        @Test
        @DisplayName("non-prod + %73slmode=disable is dropped with WARN and sslmode=require is injected")
        void should_dropEncodedKeyAndInjectRequire_when_nonProdAndEncodedSslModeKey(CapturedOutput output) {
            StandardEnvironment env = environmentWith(REMOTE_HOST + "?%73slmode=disable");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.example.com/beautica?sslmode=require");
            assertThat(output.getAll())
                    .contains("DATABASE_URL encoded or case-variant sslmode parameter dropped")
                    .doesNotContain("Sup3rS3cret");
        }

        @Test
        @DisplayName("prod + duplicate sslmode keys fails startup with fixed text")
        void should_failStartup_when_prodAndDuplicateSslMode() {
            StandardEnvironment env = prodEnv("?sslmode=require&sslmode=disable");

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("DATABASE_URL carries more than one sslmode parameter; refusing to start in prod");
        }

        @Test
        @DisplayName("non-prod + duplicate sslmode: LAST wins, duplicate WARN and weak-mode WARN fire")
        void should_keepLastSslMode_when_nonProdAndDuplicateSslMode(CapturedOutput output) {
            StandardEnvironment env = environmentWith(REMOTE_HOST + "?sslmode=require&sslmode=disable");

            processor.postProcessEnvironment(env, null);
            logs.switchOverAll();

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.example.com/beautica?sslmode=disable");
            assertThat(output.getAll())
                    .contains("DATABASE_URL carries more than one sslmode parameter; the last one is used")
                    .contains("DATABASE_URL sslmode=disable on a non-loopback host")
                    .doesNotContain("Sup3rS3cret");
        }

        @ParameterizedTest
        @ValueSource(strings = {"disable", "allow", "prefer", "DISABLE", "REQUIRE", "Verify-Full"})
        @DisplayName("prod + explicit weak or non-lowercase sslmode fails startup with fixed text (pgjdbc is case-sensitive)")
        void should_failStartup_when_prodAndWeakExplicitSslMode(String mode) {
            StandardEnvironment env = prodEnv("?sslmode=" + mode);

            assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("prod requires sslmode=require or stronger");
        }

        @ParameterizedTest
        @ValueSource(strings = {"require", "verify-ca", "verify-full"})
        @DisplayName("prod + explicit strong sslmode boots unchanged")
        void should_boot_when_prodAndStrongExplicitSslMode(String mode) {
            StandardEnvironment env = prodEnv("?sslmode=" + mode);

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://postgres.railway.internal:5432/railway?sslmode=" + mode);
        }

        @Test
        @DisplayName("no exact sslmode key: default injected (prod and non-prod)")
        void should_injectDefault_when_noExactSslModeKey() {
            StandardEnvironment env = prodEnv("?application_name=x");

            processor.postProcessEnvironment(env, null);

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://postgres.railway.internal:5432/railway?application_name=x&sslmode=require");
        }
    }
}
