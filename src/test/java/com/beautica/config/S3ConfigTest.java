package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.mockito.Mockito;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Slice tests for {@link S3Config} — verifies the {@link S3Client} bean activation policy.
 *
 * <p>Three scenarios are exercised:
 * <ol>
 *   <li>Enabled + all credentials present → bean is registered.</li>
 *   <li>Disabled → bean is absent.</li>
 *   <li>Enabled + blank {@code account-id} → context refresh fails fast.</li>
 * </ol>
 *
 * <p>Scenarios 1 and 2 use {@link Nested} {@link SpringBootTest} classes so each property
 * set produces its own context — this avoids {@code @DirtiesContext}, which is banned by
 * project conventions. The runtime cost is two extra context starts; this is acceptable
 * because no DB, no web environment, and no auto-configuration sweep are loaded — only
 * {@link S3Config} itself.
 *
 * <p>Scenario 3 uses {@link ApplicationContextRunner} so the {@link IllegalStateException}
 * thrown inside the bean factory is captured as {@code context.getStartupFailure()} instead
 * of aborting the JUnit harness — {@code @SpringBootTest} would abort the test before the
 * test body runs when the context refresh blows up.
 */
@DisplayName("S3Config — S3Client bean activation policy")
class S3ConfigTest {

    @Nested
    @DisplayName("when R2 is enabled with all credentials present")
    @SpringBootTest(
            classes = S3Config.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                    "app.cloudflare-r2.enabled=true",
                    "app.cloudflare-r2.account-id=test-account",
                    "app.cloudflare-r2.access-key-id=test-key",
                    "app.cloudflare-r2.secret-access-key=test-secret"
            }
    )
    class EnabledWithCredentials {

        @Autowired
        private Optional<S3Client> s3Client;

        @Test
        @DisplayName("registers S3Client bean when R2 is enabled and all credentials are present")
        void should_registerS3ClientBean_when_r2EnabledAndAllCredentialsPresent() {
            assertThat(s3Client)
                    .as("S3Client bean must be registered when feature flag is on and credentials are configured")
                    .isPresent()
                    .get()
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("when R2 is disabled")
    @SpringBootTest(
            classes = S3Config.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                    "app.cloudflare-r2.enabled=false"
            }
    )
    class Disabled {

        @Autowired
        private Optional<S3Client> s3Client;

        @Test
        @DisplayName("does not register S3Client bean when R2 is disabled")
        void should_notRegisterS3ClientBean_when_r2Disabled() {
            assertThat(s3Client)
                    .as("S3Client bean must be absent so R2StorageService injects Optional.empty()")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("fails startup with IllegalStateException naming the missing property when R2 enabled and account-id is blank")
    void should_failStartup_when_r2EnabledAndAccountIdBlank() {
        // account-id intentionally blank — overrides the helper's bound value
        ApplicationContextRunner contextRunner = S3ConfigTestSupport.enabledRunner()
                .withPropertyValues("app.cloudflare-r2.account-id=");

        contextRunner.run(context -> assertThat(context)
                .as("Bean factory must surface the misconfiguration as a startup failure")
                .hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.cloudflare-r2.account-id"));
    }

    // ── Phase 341 — checksum policy, endpoint override, message text ──────────

    private static S3ClientBuilder recordingBuilder() {
        return mock(S3ClientBuilder.class, Mockito.RETURNS_SELF);
    }

    @Test
    @DisplayName("configures WHEN_REQUIRED request and response checksum policy for Cloudflare R2")
    void should_setWhenRequiredChecksums_when_configuringBuilder() {
        S3ClientBuilder builder = recordingBuilder();

        S3Config.configure(builder, "acct", "key", "secret", "");

        verify(builder).requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED);
        verify(builder).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
    }

    @Test
    @DisplayName("uses the account-id template endpoint when the override is blank")
    void should_useTemplateEndpoint_when_overrideBlank() {
        S3ClientBuilder builder = recordingBuilder();

        S3Config.configure(builder, "acct123", "key", "secret", "  ");

        verify(builder).endpointOverride(URI.create("https://acct123.r2.cloudflarestorage.com"));
    }

    @Test
    @DisplayName("honours a non-blank https endpoint override")
    void should_useOverrideEndpoint_when_overrideSet() {
        S3ClientBuilder builder = recordingBuilder();

        S3Config.configure(builder, "acct123", "key", "secret",
                "https://acct123.eu.r2.cloudflarestorage.com");

        verify(builder).endpointOverride(URI.create("https://acct123.eu.r2.cloudflarestorage.com"));
    }

    @Test
    @DisplayName("rejects a non-https endpoint override with IllegalStateException")
    void should_throwIllegalState_when_overrideNotHttps() {
        assertThatThrownBy(() -> S3Config.configure(recordingBuilder(), "acct", "key", "secret",
                "http://localhost:9000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.cloudflare-r2.endpoint");
    }

    @Test
    @DisplayName("binds app.cloudflare-r2.endpoint into the bean factory and fails startup on a non-https value")
    void should_failStartup_when_boundEndpointOverrideNotHttps() {
        S3ConfigTestSupport.enabledRunner()
                .withPropertyValues("app.cloudflare-r2.endpoint=http://localhost:9000")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("app.cloudflare-r2.endpoint"));
    }

    @Test
    @DisplayName("registers the bean when a valid https endpoint override is bound")
    void should_registerBean_when_boundEndpointOverrideIsHttps() {
        S3ConfigTestSupport.enabledRunner()
                .withPropertyValues("app.cloudflare-r2.endpoint=https://acct.eu.r2.cloudflarestorage.com")
                .run(context -> assertThat(context).hasSingleBean(S3Client.class));
    }

    // ── Endpoint host rule + opt-out ──────────────────────────────────────────

    @Test
    @DisplayName("rejects a bare https:// endpoint with a clear IllegalStateException (not a raw IAE)")
    void should_throwIllegalStateWithHostMessage_when_overrideHasNoHost() {
        assertThatThrownBy(() -> S3Config.configure(recordingBuilder(), "acct", "key", "secret", "https://"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("with a host");
        assertThatThrownBy(() -> S3Config.configure(recordingBuilder(), "acct", "key", "secret", "https:///path"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must include a host");
    }

    @Test
    @DisplayName("rejects an unparsable endpoint with IllegalStateException")
    void should_throwIllegalState_when_overrideNotAUri() {
        assertThatThrownBy(() -> S3Config.configure(recordingBuilder(), "acct", "key", "secret",
                "https://bad host/x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.cloudflare-r2.endpoint");
    }

    @Test
    @DisplayName("rejects a non-R2 https host by default")
    void should_throwIllegalState_when_hostNotR2AndOptOutOff() {
        assertThatThrownBy(() -> S3Config.configure(recordingBuilder(), "acct", "key", "secret",
                "https://evil.example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".r2.cloudflarestorage.com");
    }

    @Test
    @DisplayName("rejects a look-alike host that merely contains the R2 suffix mid-name")
    void should_throwIllegalState_when_hostOnlyContainsR2Suffix() {
        assertThatThrownBy(() -> S3Config.configure(recordingBuilder(), "acct", "key", "secret",
                "https://acct.r2.cloudflarestorage.com.evil.example"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("accepts a non-R2 https host when allow-custom-endpoint is true")
    void should_acceptCustomHost_when_optOutEnabled() {
        S3ClientBuilder builder = recordingBuilder();

        S3Config.configure(builder, "acct", "key", "secret", "https://minio.internal.example", true);

        verify(builder).endpointOverride(URI.create("https://minio.internal.example"));
    }

    @Test
    @DisplayName("still requires a host and https when allow-custom-endpoint is true")
    void should_stillRejectBareHttps_when_optOutEnabled() {
        assertThatThrownBy(() -> S3Config.configure(recordingBuilder(), "acct", "key", "secret",
                "https://", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> S3Config.configure(recordingBuilder(), "acct", "key", "secret",
                "http://minio.internal.example", true))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── HTTP client timeouts ──────────────────────────────────────────────────

    @Test
    @DisplayName("installs the Apache HTTP client builder on the S3 client builder")
    void should_installApacheHttpClientBuilder_when_configuringBuilder() {
        S3ClientBuilder builder = recordingBuilder();

        S3Config.configure(builder, "acct", "key", "secret", "");

        org.mockito.ArgumentCaptor<software.amazon.awssdk.http.SdkHttpClient.Builder<?>> http =
                org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.http.SdkHttpClient.Builder.class);
        verify(builder).httpClientBuilder(http.capture());
        assertThat(http.getValue()).isInstanceOf(software.amazon.awssdk.http.apache.ApacheHttpClient.Builder.class);
    }

    @Test
    @DisplayName("explicitly sets the pinned socket/connection timeouts and pool size on the HTTP client builder")
    void should_setPinnedTimeoutsExplicitly_when_usingR2HttpClientBuilder() throws Exception {
        // Read the builder's EXPLICIT options (before SDK defaults are merged): the SDK default READ_TIMEOUT
        // is also 30s, so asserting on a defaults-resolved client would stay green if the call were deleted.
        Object apacheBuilder = S3Config.r2HttpClientBuilder();
        java.lang.reflect.Field f = apacheBuilder.getClass().getDeclaredField("standardOptions");
        f.setAccessible(true);
        software.amazon.awssdk.utils.AttributeMap options =
                ((software.amazon.awssdk.utils.AttributeMap.Builder) f.get(apacheBuilder)).build();

        assertThat(options.get(software.amazon.awssdk.http.SdkHttpConfigurationOption.READ_TIMEOUT))
                .isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(options.get(software.amazon.awssdk.http.SdkHttpConfigurationOption.CONNECTION_TIMEOUT))
                .isEqualTo(java.time.Duration.ofSeconds(5));
        assertThat(options.get(software.amazon.awssdk.http.SdkHttpConfigurationOption.MAX_CONNECTIONS))
                .isEqualTo(50);
    }
}
