package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 417 — {@code app.cloudflare-cdn} binding: blank zone/token bind (purge disabled, boot succeeds), defaults
 * apply, and a non-default {@code api-base-url} without {@code allow-custom-api-base-url=true} fails fast
 * (SSRF guard). Config-only, no DB.
 */
@DisplayName("app.cloudflare-cdn — binding, defaults, api-base-url guard (Phase 417)")
class CloudflareCdnPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(CloudflareCdnProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Props.class);

    @Test
    @DisplayName("blank zone-id / purge-token bind, context boots, purge not configured, defaults applied")
    void should_bindBlankValuesAndBoot_when_zoneAndTokenBlank() {
        runner.withPropertyValues("app.cloudflare-cdn.zone-id=", "app.cloudflare-cdn.purge-token=")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    CloudflareCdnProperties props = ctx.getBean(CloudflareCdnProperties.class);

                    assertThat(props.purgeConfigured()).isFalse();
                    assertThat(props.apiBaseUrl()).isEqualTo(CloudflareCdnProperties.DEFAULT_API_BASE_URL);
                    assertThat(props.allowCustomApiBaseUrl()).isFalse();
                    assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    @DisplayName("zone-id + purge-token set → purge configured")
    void should_reportConfigured_when_zoneAndTokenSet() {
        runner.withPropertyValues("app.cloudflare-cdn.zone-id=zone", "app.cloudflare-cdn.purge-token=tok")
                .run(ctx -> assertThat(ctx.getBean(CloudflareCdnProperties.class).purgeConfigured()).isTrue());
    }

    @Test
    @DisplayName("non-default api-base-url without the opt-in flag → boot fails")
    void should_failFast_when_customApiBaseUrlWithoutFlag() {
        runner.withPropertyValues("app.cloudflare-cdn.api-base-url=https://evil.example/client/v4")
                .run(ctx -> assertThat(ctx).getFailure().rootCause()
                        .hasMessageContaining("allow-custom-api-base-url=true to override"));
    }

    @Test
    @DisplayName("non-default api-base-url WITH allow-custom-api-base-url=true → binds")
    void should_bindCustomApiBaseUrl_when_flagSet() {
        runner.withPropertyValues("app.cloudflare-cdn.api-base-url=http://localhost:9999/client/v4",
                        "app.cloudflare-cdn.allow-custom-api-base-url=true")
                .run(ctx -> assertThat(ctx.getBean(CloudflareCdnProperties.class).apiBaseUrl())
                        .isEqualTo("http://localhost:9999/client/v4"));
    }

    @Test
    @DisplayName("zero read-timeout → boot fails")
    void should_failFast_when_readTimeoutZero() {
        runner.withPropertyValues("app.cloudflare-cdn.read-timeout=0s")
                .run(ctx -> assertThat(ctx).getFailure().rootCause()
                        .hasMessageContaining("app.cloudflare-cdn.read-timeout must be a positive duration"));
    }

    @Test
    @DisplayName("toString never prints the purge token, shows [redacted] instead")
    void should_redactPurgeToken_when_toString() {
        String token = "cf-purge-token-must-not-leak";
        CloudflareCdnProperties props = new CloudflareCdnProperties("zone", token,
                CloudflareCdnProperties.DEFAULT_API_BASE_URL, false, Duration.ofSeconds(2), Duration.ofSeconds(5));

        String rendered = props.toString();

        assertThat(rendered).doesNotContain(token).contains("[redacted]");
    }
}
