package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
                        "BREVO_SMTP_LOGIN=login", "BREVO_SMTP_KEY=key", "JWT_SECRET=x", "FRONTEND_BASE_URL=https://x.invalid",
                        "R2_ENABLED=true", "CLOUDFLARE_R2_BUCKET=beautica-media-prod");
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
                .withPropertyValues("spring.profiles.active=prod,staging", "JWT_SECRET=x", "FRONTEND_BASE_URL=https://x.invalid")
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
}
