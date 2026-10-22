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

    /** Shape of a real Turnstile secret (never a real one): not a Cloudflare test key. */
    private static final String TURNSTILE_REAL_SECRET = "0x4AAAAAAAfixtureNotARealSecretZZ9";

    private static ApplicationContextRunner runner(String profiles) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profiles,
                        "SENTRY_DSN=https://0123456789abcdef@o42.ingest.de.sentry.io/4500000000000001", "TURBOSMS_TOKEN=turbosms-fixture-token", "ADMIN_EMAIL=admin@beautica.ua",
                        "BREVO_API_KEY=xkeysib-fixture-api-key", "TURNSTILE_SECRET_KEY=" + TURNSTILE_REAL_SECRET,
                        "JWT_SECRET=x", "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "FRONTEND_BASE_URL=https://app.beautica.ua",
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
                    // Phase 374: staging overrides prod's brevo-api transport and hosted logo back to SMTP + CID.
                    assertThat(ctx.getEnvironment().getProperty("app.mail.transport")).isEqualTo("smtp");
                    assertThat(ctx.getEnvironment().getProperty("app.mail.logo-url")).isEmpty();
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
                        "SENTRY_DSN=https://0123456789abcdef@o42.ingest.de.sentry.io/4500000000000001", "TURBOSMS_TOKEN=turbosms-fixture-token", "ADMIN_EMAIL=admin@beautica.ua",
                        "BREVO_API_KEY=xkeysib-fixture-api-key",
                        "JWT_SECRET=x", "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "FRONTEND_BASE_URL=https://app.beautica.ua",
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
                        "SENTRY_DSN=https://0123456789abcdef@o42.ingest.de.sentry.io/4500000000000001", "TURBOSMS_TOKEN=turbosms-fixture-token", "ADMIN_EMAIL=admin@beautica.ua",
                        "BREVO_API_KEY=xkeysib-fixture-api-key",
                        "JWT_SECRET=x", "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "FRONTEND_BASE_URL=https://app.beautica.ua",
                        "PUBLIC_BASE_URL=https://api.beautica.ua",
                        "R2_ENABLED=true", "CLOUDFLARE_R2_BUCKET=beautica-media-prod",
                        "CLOUDFLARE_R2_PUBLIC_URL=https://media.beautica.ua")
                .run(ctx -> assertThatThrownBy(() -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("prod-support-email")
                        .hasMessageContaining("app.support.email"));
    }

    // ---- Phase 367 D5: each new required variable, removed alone from a passing prod set, fails boot on its own rule ----

    private static final java.util.Map<String, String> PROD_REQUIRED = java.util.Map.of(
            "SENTRY_DSN", "https://0123456789abcdef@o42.ingest.de.sentry.io/4500000000000001",
            "TURBOSMS_TOKEN", "turbosms-fixture-token",
            "ADMIN_EMAIL", "admin@beautica.ua",
            "BREVO_API_KEY", "xkeysib-fixture-api-key",
            "TURNSTILE_SECRET_KEY", TURNSTILE_REAL_SECRET);

    private static ApplicationContextRunner prodRunnerWithout(String missing) {
        java.util.List<String> props = new java.util.ArrayList<>(java.util.List.of("spring.profiles.active=prod",
                "JWT_SECRET=x",
                "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "FRONTEND_BASE_URL=https://app.beautica.ua",
                "PUBLIC_BASE_URL=https://api.beautica.ua", "SUPPORT_EMAIL=help@beautica.ua", "R2_ENABLED=true",
                "CLOUDFLARE_R2_BUCKET=beautica-media-prod", "CLOUDFLARE_R2_PUBLIC_URL=https://media.beautica.ua"));
        PROD_REQUIRED.forEach((k, v) -> {
            if (!k.equals(missing)) {
                props.add(k + "=" + v);
            }
        });
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(props.toArray(String[]::new));
    }

    @Test
    @DisplayName("prod with all three new required variables — guard passes (control for the falsification cases)")
    void should_passGuard_when_prodHasSentryTurbosmsAndAdminEmail() {
        prodRunnerWithout("").run(ctx -> assertThatCode(() -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                .doesNotThrowAnyException());
    }

    @Test
    @DisplayName("prod without SENTRY_DSN — guard fails on sentry-dsn-required, DSN-free message")
    void should_failGuard_when_prodSentryDsnMissing() {
        prodRunnerWithout("SENTRY_DSN").run(ctx -> assertThatThrownBy(
                        () -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                .hasMessageContaining("rule sentry-dsn-required").message().doesNotContain("sentry.io"));
    }

    @Test
    @DisplayName("prod without TURBOSMS_TOKEN — guard fails on prod-turbosms-token-blank")
    void should_failGuard_when_prodTurbosmsTokenMissing() {
        prodRunnerWithout("TURBOSMS_TOKEN").run(ctx -> assertThatThrownBy(
                        () -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                .hasMessageContaining("rule prod-turbosms-token-blank"));
    }

    @Test
    @DisplayName("prod without ADMIN_EMAIL (yml default admin@beautica.app would apply) — guard fails on prod-admin-email-unset")
    void should_failGuard_when_prodAdminEmailMissing() {
        prodRunnerWithout("ADMIN_EMAIL").run(ctx -> {
            assertThat(ctx.getEnvironment().getProperty("app.admin-email")).isEqualTo("admin@beautica.app");
            assertThatThrownBy(() -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                    .hasMessageContaining("rule prod-admin-email-unset");
        });
    }

    @Test
    @DisplayName("Phase 374 D8 — prod without BREVO_API_KEY — guard fails on prod-brevo-api-key-missing")
    void should_failGuard_when_prodBrevoApiKeyMissing() {
        prodRunnerWithout("BREVO_API_KEY").run(ctx -> assertThatThrownBy(
                        () -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                .hasMessageContaining("rule prod-brevo-api-key-missing"));
    }

    @Test
    @DisplayName("Phase 374 D2/D4/D6 — prod resolves transport=brevo-api + hosted logo, and needs no BREVO_SMTP_* at all")
    void should_resolveBrevoApiTransport_when_prodProfile() {
        prodRunnerWithout("").run(ctx -> {
            assertThat(ctx.getEnvironment().getProperty("app.mail.transport")).isEqualTo("brevo-api");
            assertThat(ctx.getEnvironment().getProperty("app.mail.logo-url"))
                    .isEqualTo("https://beautica.app/email/logo.png");
            assertThat(ctx.getEnvironment().getProperty("spring.mail.username")).isEmpty();
            assertThat(ctx.getEnvironment().getProperty("spring.mail.password")).isEmpty();
        });
    }

    // ---- Phase 382 D7: Turnstile rules against the real application-prod.yml / application-staging.yml ----

    @Test
    @DisplayName("Phase 382 — prod resolves Turnstile ON with the beautica.app hostnames and the otp_send action")
    void should_resolveTurnstileOn_when_prodProfile() {
        prodRunnerWithout("").run(ctx -> {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("app.turnstile.enabled")).isEqualTo("true");
            assertThat(env.getProperty("app.turnstile.expected-hostnames[0]")).isEqualTo("beautica.app");
            assertThat(env.getProperty("app.turnstile.expected-hostnames[1]")).isEqualTo("www.beautica.app");
            assertThat(env.getProperty("app.turnstile.expected-action")).isEqualTo("otp_send");
        });
    }

    @Test
    @DisplayName("Phase 382 — prod without TURNSTILE_SECRET_KEY fails boot on prod-turnstile-secret-blank")
    void should_failGuard_when_prodTurnstileSecretMissing() {
        prodRunnerWithout("TURNSTILE_SECRET_KEY").run(ctx -> assertThatThrownBy(
                        () -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                .hasMessageContaining("rule prod-turnstile-secret-blank"));
    }

    @Test
    @DisplayName("Phase 382 — prod with a Cloudflare TEST secret fails boot on prod-turnstile-secret-test-key, value never printed")
    void should_failGuard_when_prodTurnstileSecretIsTestKey() {
        String testKey = "1x0000000000000000000000000000000AA";
        prodRunnerWithout("TURNSTILE_SECRET_KEY").withPropertyValues("TURNSTILE_SECRET_KEY=" + testKey)
                .run(ctx -> assertThatThrownBy(() -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                        .hasMessageContaining("rule prod-turnstile-secret-test-key")
                        .message().doesNotContain(testKey));
    }

    @Test
    @DisplayName("Phase 382 — an env override cannot switch Turnstile off in prod: prod-turnstile-disabled")
    void should_failGuard_when_prodTurnstileDisabledByEnvOverride() {
        prodRunnerWithout("").withPropertyValues("app.turnstile.enabled=false").run(ctx -> assertThatThrownBy(
                        () -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                .hasMessageContaining("rule prod-turnstile-disabled"));
    }

    @Test
    @DisplayName("Phase 382 — staging resolves Turnstile ON with Cloudflare's always-pass test secret by default")
    void should_resolveTestSecretDefault_when_stagingProfile() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod,staging")
                .run(ctx -> {
                    var env = ctx.getEnvironment();
                    assertThat(env.getProperty("app.turnstile.enabled")).isEqualTo("true");
                    assertThat(env.getProperty("app.turnstile.secret-key"))
                            .isEqualTo("1x0000000000000000000000000000000AA");
                    assertThat(env.getProperty("app.turnstile.expected-action")).isEmpty();
                });
    }

    @Test
    @DisplayName("Phase 382 — staging with a REAL TURNSTILE_SECRET_KEY fails boot on staging-turnstile-secret-not-test-key")
    void should_failGuard_when_stagingTurnstileSecretIsReal() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod,staging", "JWT_SECRET=x",
                        "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "FRONTEND_BASE_URL=https://x.invalid",
                        "PUBLIC_BASE_URL=https://api.staging.beautica.test", "SUPPORT_EMAIL=support@test.invalid",
                        "TURNSTILE_SECRET_KEY=" + TURNSTILE_REAL_SECRET)
                .run(ctx -> assertThatThrownBy(() -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                        .hasMessageContaining("rule staging-turnstile-secret-not-test-key")
                        .message().doesNotContain(TURNSTILE_REAL_SECRET));
    }

    @Test
    @DisplayName("Phase 382 audit — an env override of the siteverify URL fails prod boot on prod-turnstile-siteverify-url")
    void should_failGuard_when_prodSiteverifyUrlOverridden() {
        prodRunnerWithout("").withPropertyValues("app.turnstile.siteverify-url=https://evil.example/siteverify")
                .run(ctx -> assertThatThrownBy(() -> EnvironmentFingerprintGuard.verify(ctx.getEnvironment()))
                        .hasMessageContaining("rule prod-turnstile-siteverify-url"));
    }
}
