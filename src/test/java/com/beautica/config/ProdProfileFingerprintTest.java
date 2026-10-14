package com.beautica.config;

import com.beautica.support.TestSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 368 — the committed YAML, not hand-built properties: {@code prod} alone resolves to
 * environment PROD with the Brevo/turbosms wiring and passes the guard; adding {@code staging} flips the
 * environment to STAGING with capture wiring and also passes it. Config-only (no DB), so a slice runner.
 */
@DisplayName("prod vs prod,staging — environment identity from the real application-*.yml (Phase 368)")
class ProdProfileFingerprintTest {

    private static ApplicationContextRunner runner(String profiles) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profiles,
                        "BREVO_SMTP_LOGIN=login", "BREVO_SMTP_KEY=key", "JWT_SECRET=x", "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "FRONTEND_BASE_URL=https://app.beautica.ua",
                        "PUBLIC_BASE_URL=https://api.beautica.ua", "SUPPORT_EMAIL=help@beautica.ua",
                        "R2_ENABLED=true", "CLOUDFLARE_R2_BUCKET=beautica-media-prod",
                        "CLOUDFLARE_R2_PUBLIC_URL=https://media.beautica.ua");
    }

    private static DeploymentProperties bind(org.springframework.core.env.Environment env) {
        return Binder.get(env).bind("beautica", DeploymentProperties.class).get();
    }

    @Test
    @DisplayName("prod alone — PROD / turbosms / Brevo host, guard passes")
    void should_bePassingProd_when_onlyProdProfileActive() {
        runner("prod").run(ctx -> {
            DeploymentProperties props = bind(ctx.getEnvironment());

            assertThat(props.environment()).isEqualTo(DeployEnvironment.PROD);
            assertThat(props.sms().mode()).isEqualTo(SmsMode.TURBOSMS);
            assertThatCode(() -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment())).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("prod,staging — flips to STAGING / capture; no Brevo host; guard passes with the overlay alone")
    void should_flipToStaging_when_stagingProfileAdded() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod,staging", "JWT_SECRET=x", "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "FRONTEND_BASE_URL=https://x.invalid",
                        "PUBLIC_BASE_URL=https://api.staging.beautica.test", "SUPPORT_EMAIL=support@test.invalid")
                .run(ctx -> {
                    DeploymentProperties props = bind(ctx.getEnvironment());

                    assertThat(props.environment()).isEqualTo(DeployEnvironment.STAGING);
                    assertThat(props.sms().mode()).isEqualTo(SmsMode.CAPTURE);
                    assertThat(ctx.getEnvironment().getProperty("spring.mail.host")).doesNotContain("brevo");
                    assertThat(ctx.getEnvironment().getProperty("app.booking.sms.enabled")).isEqualTo("true");
                    assertThatCode(() -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment())).doesNotThrowAnyException();
                    assertThat(ctx.getEnvironment().getProperty("app.rate-limit.invite-capacity")).isEqualTo("1000");
                    assertThat(ctx.getEnvironment().getProperty("spring.mail.properties.mail.smtp.timeout")).isEqualTo("3000");
                    assertThat(ctx.getEnvironment().getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
                    assertThat(ctx.getEnvironment().getProperty("springdoc.api-docs.enabled")).isEqualTo("true");
                });
    }

    @Test
    @DisplayName("prod without PUBLIC_BASE_URL — unresolvable placeholder counts as unset and trips public-base-url-https")
    void should_failGuard_when_prodPublicBaseUrlMissing() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod",
                        "BREVO_SMTP_LOGIN=login", "BREVO_SMTP_KEY=key", "JWT_SECRET=x", "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "FRONTEND_BASE_URL=https://app.beautica.ua",
                        "SUPPORT_EMAIL=help@beautica.ua",
                        "R2_ENABLED=true", "CLOUDFLARE_R2_BUCKET=beautica-media-prod",
                        "CLOUDFLARE_R2_PUBLIC_URL=https://media.beautica.ua")
                .run(ctx -> assertThatThrownBy(() -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("public-base-url-https")
                        .hasMessageContaining("app.public-base-url"));
    }

    // ---- Railway staging regressions (fix/link-base-url-guard): the committed YAML with an env var missing ----

    private static ApplicationContextRunner stagingRunnerWith(String... envVars) {
        String[] props = new String[envVars.length + 4];
        props[0] = "spring.profiles.active=prod,staging";
        props[1] = "JWT_SECRET=x";
        props[2] = "FRONTEND_BASE_URL=https://x.invalid";
        props[3] = "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY;
        System.arraycopy(envVars, 0, props, 4, envVars.length);
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(props);
    }

    @Test
    @DisplayName("prod,staging without PUBLIC_BASE_URL (the Railway staging bug) — guard fails on public-base-url-https")
    void should_failGuard_when_stagingPublicBaseUrlMissing() {
        stagingRunnerWith("SUPPORT_EMAIL=support@test.invalid")
                .run(ctx -> assertThatThrownBy(() -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("public-base-url-https")
                        .hasMessageContaining("app.public-base-url")
                        .message().doesNotContain("localhost"));
    }

    @Test
    @DisplayName("application-prod.yml declares public-base-url with no default — the localhost base default never leaks into prod")
    void should_leavePublicBaseUrlUnresolvable_when_prodProfileAndEnvVarMissing() {
        stagingRunnerWith("SUPPORT_EMAIL=support@test.invalid")
                .run(ctx -> assertThatThrownBy(() -> ctx.getEnvironment().getProperty("app.public-base-url"))
                        .as("a resolved value here means a default (e.g. http://localhost:8080) is back in play")
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("PUBLIC_BASE_URL"));
    }

    @Test
    @DisplayName("prod,staging without SUPPORT_EMAIL (the support-contact 503 bug) — guard fails on staging-support-email")
    void should_failGuard_when_stagingSupportEmailMissing() {
        stagingRunnerWith("PUBLIC_BASE_URL=https://api.staging.beautica.test")
                .run(ctx -> assertThatThrownBy(() -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("staging-support-email")
                        .hasMessageContaining("app.support.email"));
    }

    @Test
    @DisplayName("prod without SUPPORT_EMAIL — guard fails on prod-support-email instead of booting into a 503 contact endpoint")
    void should_failGuard_when_prodSupportEmailMissing() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod",
                        "BREVO_SMTP_LOGIN=login", "BREVO_SMTP_KEY=key", "JWT_SECRET=x", "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "FRONTEND_BASE_URL=https://app.beautica.ua",
                        "PUBLIC_BASE_URL=https://api.beautica.ua",
                        "R2_ENABLED=true", "CLOUDFLARE_R2_BUCKET=beautica-media-prod",
                        "CLOUDFLARE_R2_PUBLIC_URL=https://media.beautica.ua")
                .run(ctx -> assertThatThrownBy(() -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("prod-support-email")
                        .hasMessageContaining("app.support.email"));
    }
}
