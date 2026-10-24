package com.beautica.config;

import com.beautica.support.TestSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 375 (D5/D6) — the kill-switch default per committed profile (prod OFF; local / staging ON)
 * and the boot-time validation of {@code app.booking-links.web-base-url}. Config-only, no DB.
 */
@DisplayName("app.booking-links — kill-switch per profile + web-base-url validation (Phase 375)")
class BookingLinksPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(BookingLinksProperties.class)
    static class Props {
    }

    private static ApplicationContextRunner runner(String profiles, String... extra) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
                .withUserConfiguration(Props.class)
                .withPropertyValues("spring.profiles.active=" + profiles, "JWT_SECRET=x",
                        "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "PUBLIC_BASE_URL=https://api.beautica.ua",
                        "OUTBOX_PAYLOAD_KEY=x")
                .withPropertyValues(extra);
    }

    @Test
    @DisplayName("prod profile, env unset → sharing disabled (safe default)")
    void should_disableSharing_when_prodProfileAndEnvUnset() {
        runner("prod").run(ctx -> {
            BookingLinksProperties props = ctx.getBean(BookingLinksProperties.class);

            assertThat(props.isShareEnabled()).isFalse();
            assertThat(props.getWebBaseUrl()).isEqualTo("https://beautica.app");
        });
    }

    @Test
    @DisplayName("prod profile, BOOKING_LINKS_SHARE_ENABLED=true → sharing enabled")
    void should_enableSharing_when_prodEnvFlipped() {
        runner("prod", "BOOKING_LINKS_SHARE_ENABLED=true")
                .run(ctx -> assertThat(ctx.getBean(BookingLinksProperties.class).isShareEnabled()).isTrue());
    }

    @Test
    @DisplayName("staging overlay → sharing enabled")
    void should_enableSharing_when_stagingOverlay() {
        runner("prod,staging")
                .run(ctx -> assertThat(ctx.getBean(BookingLinksProperties.class).isShareEnabled()).isTrue());
    }

    @Test
    @DisplayName("local profile → sharing enabled")
    void should_enableSharing_when_localProfile() {
        runner("local")
                .run(ctx -> assertThat(ctx.getBean(BookingLinksProperties.class).isShareEnabled()).isTrue());
    }

    @Test
    @DisplayName("web-base-url over http → binding fails at boot")
    void should_failBinding_when_webBaseUrlNotHttps() {
        runner("prod", "app.booking-links.web-base-url=http://beautica.app")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("web-base-url with trailing slash → binding fails at boot")
    void should_failBinding_when_webBaseUrlHasTrailingSlash() {
        runner("prod", "app.booking-links.web-base-url=https://beautica.app/")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    // 375 audit (LOW security) — host-only origin: no userinfo, no port.

    @Test
    @DisplayName("web-base-url with userinfo → binding fails at boot")
    void should_failBinding_when_webBaseUrlHasUserinfo() {
        runner("prod", "app.booking-links.web-base-url=https://beautica.app@evil.tld")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("web-base-url with explicit port → binding fails at boot")
    void should_failBinding_when_webBaseUrlHasPort() {
        runner("prod", "app.booking-links.web-base-url=https://x:8443")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("host-only https subdomain origin → binds")
    void should_bindSubdomainOrigin_when_webBaseUrlIsHostOnlyHttps() {
        runner("prod", "app.booking-links.web-base-url=https://staging.beautica.app")
                .run(ctx -> assertThat(ctx.getBean(BookingLinksProperties.class).getWebBaseUrl())
                        .isEqualTo("https://staging.beautica.app"));
    }
}
