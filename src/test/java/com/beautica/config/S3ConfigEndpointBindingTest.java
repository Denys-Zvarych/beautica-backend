package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 341 D4 — the endpoint property must reach the built {@link S3Client} through real Spring
 * property binding (not only through the package-private {@code configure} seam).
 */
@DisplayName("S3Config — endpoint override binding through a real Spring context (Phase 341)")
class S3ConfigEndpointBindingTest {

    private ApplicationContextRunner runner() {
        return S3ConfigTestSupport.enabledRunner();
    }

    private static URI endpointOf(S3Client client) {
        return client.serviceClientConfiguration().endpointOverride().orElseThrow();
    }

    @Test
    @DisplayName("built client targets the bound https endpoint override")
    void should_buildClientWithBoundEndpoint_when_overrideSet() {
        runner().withPropertyValues("app.cloudflare-r2.endpoint=https://acct.eu.r2.cloudflarestorage.com")
                .run(ctx -> assertThat(endpointOf(ctx.getBean(S3Client.class)))
                        .isEqualTo(URI.create("https://acct.eu.r2.cloudflarestorage.com")));
    }

    @Test
    @DisplayName("built client falls back to the account-id template when the endpoint property is blank")
    void should_buildClientWithTemplateEndpoint_when_overrideBlank() {
        runner().withPropertyValues("app.cloudflare-r2.endpoint=")
                .run(ctx -> assertThat(endpointOf(ctx.getBean(S3Client.class)))
                        .isEqualTo(URI.create("https://acct.r2.cloudflarestorage.com")));
    }

    @Test
    @DisplayName("local-profile yml resolves CLOUDFLARE_R2_ENDPOINT and enabled flag from plain properties with no yml edits")
    void should_honourEnvStyleProperties_when_localProfileActive() {
        new ApplicationContextRunner()
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withUserConfiguration(S3Config.class)
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "R2_ENABLED=true",
                        "CLOUDFLARE_R2_ACCOUNT_ID=acct",
                        "CLOUDFLARE_R2_ACCESS_KEY_ID=key",
                        "CLOUDFLARE_R2_SECRET_ACCESS_KEY=secret",
                        "CLOUDFLARE_R2_ENDPOINT=https://acct.eu.r2.cloudflarestorage.com")
                .run(ctx -> assertThat(endpointOf(ctx.getBean(S3Client.class)))
                        .isEqualTo(URI.create("https://acct.eu.r2.cloudflarestorage.com")));
    }

    @Test
    @DisplayName("local profile with no R2 env registers no S3Client (storage stays off by default)")
    void should_registerNoClient_when_localProfileAndNoR2Env() {
        new ApplicationContextRunner()
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withUserConfiguration(S3Config.class)
                .withPropertyValues("spring.profiles.active=local")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(S3Client.class));
    }

    @Test
    @DisplayName("custom host is accepted through real binding when allow-custom-endpoint=true")
    void should_buildClientWithCustomHost_when_allowCustomEndpointBound() {
        runner().withPropertyValues(
                        "app.cloudflare-r2.endpoint=https://minio.internal.example",
                        "app.cloudflare-r2.allow-custom-endpoint=true")
                .run(ctx -> assertThat(endpointOf(ctx.getBean(S3Client.class)))
                        .isEqualTo(URI.create("https://minio.internal.example")));
    }

    @Test
    @DisplayName("custom host fails startup through real binding when allow-custom-endpoint is unset")
    void should_failStartup_when_customHostAndOptOutUnset() {
        runner().withPropertyValues("app.cloudflare-r2.endpoint=https://minio.internal.example")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(".r2.cloudflarestorage.com"));
    }
}
