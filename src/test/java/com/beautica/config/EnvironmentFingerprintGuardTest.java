package com.beautica.config;

import com.beautica.support.TestSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("EnvironmentFingerprintGuard — Phase 368 D2 / audit cycle 1")
class EnvironmentFingerprintGuardTest {

    private static final String SECRET = "tok-S3cr3t-value-9xQ";
    private static final String VALID_KEY = TestSecrets.INTERNAL_API_KEY;
    /** Cloudflare's published always-pass TEST secret. */
    private static final String TURNSTILE_TEST_SECRET = "1x0000000000000000000000000000000AA";
    /** Shape of a real secret (never a real one): 35 chars, not a test key. */
    private static final String TURNSTILE_REAL_SECRET = "0x4AAAAAAAfixtureNotARealSecretZZ9";

    private static MockEnvironment stagingEnv() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("RAILWAY_ENVIRONMENT_NAME", "staging")
                .withProperty("app.cloudflare-r2.enabled", "true")
                .withProperty("app.cloudflare-r2.bucket", "beautica-media-staging")
                .withProperty("spring.mail.host", "mailpit.railway.internal")
                .withProperty("app.public-base-url", "https://api.staging.beautica.test")
                .withProperty("app.frontend.base-url", "https://example.invalid")
                .withProperty("app.cloudflare-r2.public-url", "https://media.staging.beautica.test")
                .withProperty("app.support.email", "support@test.invalid")
                .withProperty("app.internal-api-key", VALID_KEY)
                // Phase 382: the staging overlay turns Turnstile on with Cloudflare's always-pass test secret.
                .withProperty("app.turnstile.enabled", "true")
                .withProperty("app.turnstile.secret-key", TURNSTILE_TEST_SECRET);
        env.setActiveProfiles("prod", "staging");
        return env;
    }

    private static MockEnvironment prodEnv() {
        return prodEnvWithoutAdminEmail().withProperty("ADMIN_EMAIL", "admin@beautica.ua");
    }

    private static MockEnvironment prodEnvWithoutAdminEmail() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("RAILWAY_ENVIRONMENT_NAME", "production")
                .withProperty("app.cloudflare-r2.enabled", "true")
                .withProperty("app.cloudflare-r2.bucket", "beautica-media-prod")
                .withProperty("spring.mail.host", "smtp-relay.brevo.com")
                .withProperty("app.public-base-url", "https://api.beautica.ua")
                .withProperty("app.frontend.base-url", "https://app.beautica.ua")
                .withProperty("app.cloudflare-r2.public-url", "https://media.beautica.ua")
                .withProperty("app.support.email", "help@beautica.ua")
                .withProperty("app.internal-api-key", VALID_KEY)
                .withProperty("sentry.dsn", EU_DSN)
                .withProperty("TURBOSMS_TOKEN", "turbosms-fixture-token")
                .withProperty("spring.mail.username", "brevo-login")
                .withProperty("spring.mail.password", "brevo-key")
                // Phase 374: prod must resolve the Brevo HTTPS API transport with its key and hosted logo.
                .withProperty("app.mail.transport", "brevo-api")
                .withProperty("app.mail.brevo-api.api-key", "xkeysib-guard-fixture")
                .withProperty("app.mail.brevo-api.base-url", "https://api.brevo.com")
                .withProperty("app.mail.logo-url", "https://beautica.app/email/logo.png")
                // Phase 382: prod must run Turnstile with a real (non-test) secret.
                .withProperty("app.turnstile.enabled", "true")
                .withProperty("app.turnstile.secret-key", TURNSTILE_REAL_SECRET);
        env.setActiveProfiles("prod");
        return env;
    }

    private static void run(MockEnvironment env, DeployEnvironment environment, SmsMode mode) {
        env.setProperty("beautica.environment", environment.name());
        env.setProperty("beautica.sms.mode", mode.name());
        EnvironmentFingerprintGuard.verify(env);
    }

    @Test
    @DisplayName("staging + Railway staging + bucket -staging + no Brevo/Turbosms + capture — passes")
    void should_pass_when_stagingIsFullyConsistent() {
        assertThatCode(() -> run(stagingEnv(), DeployEnvironment.STAGING, SmsMode.CAPTURE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod with prod-shaped properties — passes (Railway name production maps to prod)")
    void should_pass_when_prodIsFullyConsistent() {
        assertThatCode(() -> run(prodEnv(), DeployEnvironment.PROD, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("staging with TURBOSMS_TOKEN set — fails naming TURBOSMS_TOKEN, message carries no value")
    void should_failNamingTheVariable_when_stagingHasTurbosmsToken() {
        MockEnvironment env = stagingEnv().withProperty("TURBOSMS_TOKEN", SECRET)
                .withProperty("app.sms.turbosms.token", SECRET);

        assertThatThrownBy(() -> run(env, DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TURBOSMS_TOKEN")
                .hasMessageContaining("staging-turbosms-token-blank")
                .message().doesNotContain(SECRET);
    }

    @Test
    @DisplayName("staging with BREVO_SMTP_KEY set — fails naming BREVO_SMTP_KEY, no value")
    void should_fail_when_stagingHasBrevoKey() {
        MockEnvironment env = stagingEnv().withProperty("BREVO_SMTP_KEY", SECRET);

        assertThatThrownBy(() -> run(env, DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .hasMessageContaining("BREVO_SMTP_KEY").message().doesNotContain(SECRET);
    }

    @Test
    @DisplayName("staging with a Brevo mail host — fails")
    void should_fail_when_stagingMailHostIsBrevo() {
        MockEnvironment env = stagingEnv().withProperty("spring.mail.host", "smtp-relay.brevo.com");

        assertThatThrownBy(() -> run(env, DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .hasMessageContaining("staging-mail-host").hasMessageContaining("spring.mail.host")
                .message().doesNotContain("smtp-relay");
    }

    @Test
    @DisplayName("staging with sms.mode=turbosms — fails")
    void should_fail_when_stagingSmsModeIsNotCapture() {
        assertThatThrownBy(() -> run(stagingEnv(), DeployEnvironment.STAGING, SmsMode.TURBOSMS))
                .hasMessageContaining("staging-sms-mode").hasMessageContaining("beautica.sms.mode");
    }

    @Test
    @DisplayName("prod with bucket beautica-media-staging — fails naming the bucket property, no value")
    void should_fail_when_prodUsesStagingBucket() {
        MockEnvironment env = prodEnv().withProperty("app.cloudflare-r2.bucket", "beautica-media-staging");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("r2-bucket-name").hasMessageContaining("app.cloudflare-r2.bucket")
                .message().doesNotContain("beautica-media-staging");
    }

    @Test
    @DisplayName("R2 disabled — allowed in staging, a violation in prod")
    void should_allowDisabledR2InStagingButNotProd() {
        MockEnvironment staging = stagingEnv().withProperty("app.cloudflare-r2.enabled", "false")
                .withProperty("app.cloudflare-r2.bucket", "anything");
        MockEnvironment prod = prodEnv().withProperty("app.cloudflare-r2.enabled", "false");

        assertThatCode(() -> run(staging, DeployEnvironment.STAGING, SmsMode.CAPTURE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> run(prod, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("prod-r2-enabled");
    }

    @Test
    @DisplayName("bucket must be the exact name — suffix-only lookalikes fail in both environments")
    void should_requireExactBucketName() {
        for (String bucket : new String[]{"evil-beautica-media-staging", "beautica-media-staging-x", "x-prod"}) {
            assertThatThrownBy(() -> run(stagingEnv().withProperty("app.cloudflare-r2.bucket", bucket),
                    DeployEnvironment.STAGING, SmsMode.CAPTURE)).as(bucket).hasMessageContaining("r2-bucket-name");
        }
        assertThatThrownBy(() -> run(prodEnv().withProperty("app.cloudflare-r2.bucket", "evil-beautica-media-prod"),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).hasMessageContaining("r2-bucket-name");
    }

    @Test
    @DisplayName("prod with Railway env staging — fails")
    void should_fail_when_prodRunsInRailwayStaging() {
        MockEnvironment env = prodEnv().withProperty("RAILWAY_ENVIRONMENT_NAME", "staging");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("railway-environment-name").hasMessageContaining("RAILWAY_ENVIRONMENT_NAME");
    }

    @Test
    @DisplayName("staging with Railway env production — fails")
    void should_fail_when_stagingRunsInRailwayProduction() {
        MockEnvironment env = stagingEnv().withProperty("RAILWAY_ENVIRONMENT_NAME", "production");

        assertThatThrownBy(() -> run(env, DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .hasMessageContaining("railway-environment-name");
    }

    @Test
    @DisplayName("prod with sms.mode=capture — fails")
    void should_fail_when_prodSmsModeIsCapture() {
        assertThatThrownBy(() -> run(prodEnv(), DeployEnvironment.PROD, SmsMode.CAPTURE))
                .hasMessageContaining("prod-sms-mode");
    }

    @Test
    @DisplayName("prod with a mailpit or loopback mail host — fails")
    void should_fail_when_prodMailHostIsCaptureShaped() {
        for (String host : new String[]{"mailpit.railway.internal", "localhost", "127.0.0.1"}) {
            MockEnvironment env = prodEnv().withProperty("spring.mail.host", host);

            assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                    .as("host=%s", host).hasMessageContaining("prod-mail-host");
        }
    }

    @Test
    @DisplayName("Railway var absent — rule 1 skipped, the other rules still apply")
    void should_skipRailwayRuleButKeepOthers_when_railwayVarAbsent() {
        MockEnvironment noRailway = new MockEnvironment()
                .withProperty("spring.mail.host", "mailpit.railway.internal")
                .withProperty("app.public-base-url", "https://api.staging.beautica.test")
                .withProperty("app.frontend.base-url", "https://example.invalid")
                .withProperty("app.support.email", "support@test.invalid")
                .withProperty("app.internal-api-key", VALID_KEY)
                .withProperty("app.turnstile.enabled", "true")
                .withProperty("app.turnstile.secret-key", TURNSTILE_TEST_SECRET);
        noRailway.setActiveProfiles("prod", "staging");

        assertThatCode(() -> run(noRailway, DeployEnvironment.STAGING, SmsMode.CAPTURE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> run(noRailway.withProperty("TURBOSMS_TOKEN", SECRET),
                DeployEnvironment.STAGING, SmsMode.CAPTURE)).hasMessageContaining("TURBOSMS_TOKEN");
    }

    @Test
    @DisplayName("genuinely local (LOCAL, no prod/staging profile, no Railway var) is skipped")
    void should_skip_when_environmentIsLocal() {
        MockEnvironment env = new MockEnvironment().withProperty("TURBOSMS_TOKEN", SECRET)
                .withProperty("spring.mail.host", "smtp-relay.brevo.com");

        assertThatCode(() -> run(env, DeployEnvironment.LOCAL, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod or staging profile with environment LOCAL (BEAUTICA_ENVIRONMENT=local) — fails")
    void should_fail_when_prodOrStagingProfileButEnvironmentLocal() {
        assertThatThrownBy(() -> run(prodEnv(), DeployEnvironment.LOCAL, SmsMode.TURBOSMS))
                .hasMessageContaining("profile-environment");
        assertThatThrownBy(() -> run(stagingEnv(), DeployEnvironment.LOCAL, SmsMode.CAPTURE))
                .hasMessageContaining("profile-environment");
    }

    @Test
    @DisplayName("prod,local profile pair with environment LOCAL still triggers the guard")
    void should_notSkip_when_prodAndLocalProfilesCombined() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "local");

        assertThatThrownBy(() -> run(env, DeployEnvironment.LOCAL, SmsMode.TURBOSMS))
                .hasMessageContaining("profile-environment");
    }

    @Test
    @DisplayName("staging profile with environment not STAGING — fails")
    void should_fail_when_stagingProfileButEnvironmentProd() {
        assertThatThrownBy(() -> run(stagingEnv(), DeployEnvironment.PROD, SmsMode.CAPTURE))
                .hasMessageContaining("profile-environment");
    }

    @Test
    @DisplayName("prod profile without staging and environment not PROD — fails")
    void should_fail_when_prodProfileButEnvironmentStaging() {
        assertThatThrownBy(() -> run(prodEnv(), DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .hasMessageContaining("profile-environment");
    }

    @Test
    @DisplayName("RAILWAY_ENVIRONMENT_NAME set — the guard is never skipped, even for LOCAL with no profiles")
    void should_neverSkip_when_railwayVarSet() {
        MockEnvironment env = new MockEnvironment().withProperty("RAILWAY_ENVIRONMENT_NAME", "LOCAL");

        assertThatThrownBy(() -> run(env, DeployEnvironment.LOCAL, SmsMode.TURBOSMS))
                .hasMessageContaining("railway-environment-name");
    }

    @Test
    @DisplayName("relaxed binding: beautica.environment=local (lower case) is honoured and cross-checked")
    void should_bindEnvironmentRelaxed() {
        MockEnvironment env = prodEnv().withProperty("beautica.environment", "local");

        assertThatThrownBy(() -> EnvironmentFingerprintGuard.verify(env))
                .hasMessageContaining("profile-environment");
    }

    @Test
    @DisplayName("staging mail host is an allowlist: loopback, exact mailpit, *.railway.internal pass; anything else fails")
    void should_allowlistStagingMailHost() {
        for (String host : new String[]{"localhost", "127.0.0.1", "mailpit", "mailpit.railway.internal", "smtp.railway.internal",
                "MAILPIT.RAILWAY.INTERNAL."}) {
            assertThatCode(() -> run(stagingEnv().withProperty("spring.mail.host", host),
                    DeployEnvironment.STAGING, SmsMode.CAPTURE)).as(host).doesNotThrowAnyException();
        }
        for (String host : new String[]{"smtp.sendgrid.net", "smtp.gmail.com", "", "example.com", "mailpit.evil.com", "mailpit.railway.internal.evil.com"}) {
            assertThatThrownBy(() -> run(stagingEnv().withProperty("spring.mail.host", host),
                    DeployEnvironment.STAGING, SmsMode.CAPTURE)).as("host=%s", host).hasMessageContaining("staging-mail-host");
        }
    }

    @Test
    @DisplayName("several violations are reported together")
    void should_reportAllViolations_when_multipleRulesBroken() {
        MockEnvironment env = stagingEnv().withProperty("TURBOSMS_TOKEN", SECRET);

        assertThatThrownBy(() -> run(env, DeployEnvironment.STAGING, SmsMode.TURBOSMS))
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("staging-turbosms-token-blank").contains("staging-sms-mode"));
    }

    // ---- link-building base URLs (fail closed) ----

    private static void assertStagingPublicUrlRejected(String value, String rule) {
        MockEnvironment env = stagingEnv();
        if (value == null) {
            env = new MockEnvironment().withProperty("RAILWAY_ENVIRONMENT_NAME", "staging")
                    .withProperty("spring.mail.host", "mailpit.railway.internal")
                    .withProperty("app.frontend.base-url", "https://example.invalid");
            env.setActiveProfiles("prod", "staging");
        } else {
            env.setProperty("app.public-base-url", value);
        }
        MockEnvironment finalEnv = env;
        assertThatThrownBy(() -> run(finalEnv, DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .as("value=%s", value).hasMessageContaining(rule).hasMessageContaining("app.public-base-url")
                .message().satisfies(m -> {
                    if (value != null) {
                        assertThat(m).doesNotContain(value);
                    }
                });
    }

    @Test
    @DisplayName("public-base-url unset — fails")
    void should_fail_when_publicBaseUrlUnset() {
        assertStagingPublicUrlRejected(null, "public-base-url-https");
        assertStagingPublicUrlRejected("  ", "public-base-url-https");
    }

    @Test
    @DisplayName("public-base-url http, loopback or internal host — fails")
    void should_fail_when_publicBaseUrlNotHttpsOrInternal() {
        for (String v : new String[]{"http://api.staging.beautica.test", "https://localhost:8080",
                "https://127.0.0.1", "https://[::1]", "https://0.0.0.0", "https://10.0.2.2",
                "https://x.localhost", "https://backend.railway.internal", "not a url", "https://"}) {
            assertStagingPublicUrlRejected(v, "public-base-url-https");
        }
    }

    @Test
    @DisplayName("public-base-url trailing slash, query or fragment — fails")
    void should_fail_when_publicBaseUrlHasBadShape() {
        for (String v : new String[]{"https://api.staging.beautica.test/", "https://api.staging.beautica.test?x=1",
                "https://api.staging.beautica.test#f"}) {
            assertStagingPublicUrlRejected(v, "public-base-url-shape");
        }
    }

    @Test
    @DisplayName("staging public-base-url host without 'staging' — fails")
    void should_fail_when_stagingPublicHostLacksStaging() {
        assertStagingPublicUrlRejected("https://api.beautica.test", "staging-public-base-url-name");
    }

    @Test
    @DisplayName("prod link URL host containing staging or ending .invalid — fails")
    void should_fail_when_prodLinkUrlIsStagingOrInvalid() {
        assertThatThrownBy(() -> run(prodEnv().withProperty("app.public-base-url", "https://api.staging.beautica.test"),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).hasMessageContaining("prod-public-base-url-host");
        assertThatThrownBy(() -> run(prodEnv().withProperty("app.frontend.base-url", "https://example.invalid"),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).hasMessageContaining("prod-frontend-base-url-host");
        assertThatThrownBy(() -> run(prodEnv().withProperty("app.frontend.base-url", "https://staging.beautica.test"),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).hasMessageContaining("prod-frontend-base-url-host");
    }

    @Test
    @DisplayName("prod frontend URL http or loopback — fails with the frontend rule")
    void should_fail_when_prodFrontendUrlNotHttps() {
        assertThatThrownBy(() -> run(prodEnv().withProperty("app.frontend.base-url", "http://localhost:3000"),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).hasMessageContaining("frontend-base-url-https")
                .hasMessageContaining("app.frontend.base-url");
    }

    @Test
    @DisplayName("staging frontend example.invalid is accepted (rules 1-3 only), but staging frontend http is not")
    void should_acceptInvalidFrontend_inStaging() {
        assertThatCode(() -> run(stagingEnv().withProperty("app.frontend.base-url", "https://example.invalid"),
                DeployEnvironment.STAGING, SmsMode.CAPTURE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> run(stagingEnv().withProperty("app.frontend.base-url", "http://example.invalid"),
                DeployEnvironment.STAGING, SmsMode.CAPTURE)).hasMessageContaining("frontend-base-url-https");
    }

    @Test
    @DisplayName("R2 public URL http or loopback — fails; unset while R2 enabled — fails; R2 off — not checked")
    void should_fail_when_r2PublicUrlNotHttps() {
        for (String v : new String[]{"http://media.beautica.test", "https://localhost"}) {
            assertThatThrownBy(() -> run(prodEnv().withProperty("app.cloudflare-r2.public-url", v),
                    DeployEnvironment.PROD, SmsMode.TURBOSMS)).as(v).hasMessageContaining("r2-public-url-https");
        }
        assertThatThrownBy(() -> run(prodEnv().withProperty("app.cloudflare-r2.public-url", ""),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).hasMessageContaining("r2-public-url-https");
        assertThatCode(() -> run(stagingEnv().withProperty("app.cloudflare-r2.enabled", "false")
                        .withProperty("app.cloudflare-r2.public-url", ""),
                DeployEnvironment.STAGING, SmsMode.CAPTURE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("local profile is unaffected by link URL rules")
    void should_notCheckLinkUrls_when_local() {
        MockEnvironment env = new MockEnvironment().withProperty("app.public-base-url", "http://localhost:8080")
                .withProperty("app.frontend.base-url", "http://localhost:3000");

        assertThatCode(() -> run(env, DeployEnvironment.LOCAL, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod support email blank, without @ or ending .invalid — fails, no value in message")
    void should_fail_when_prodSupportEmailInvalid() {
        for (String v : new String[]{"", "   ", "support-at-example.com", "support@test.invalid"}) {
            assertThatThrownBy(() -> run(prodEnv().withProperty("app.support.email", v),
                    DeployEnvironment.PROD, SmsMode.TURBOSMS)).as("value=%s", v)
                    .hasMessageContaining("prod-support-email").hasMessageContaining("app.support.email")
                    .message().satisfies(m -> assertThat(m).doesNotContain("support-at").doesNotContain("test.invalid"));
        }
    }

    @Test
    @DisplayName("staging support email blank or a real domain — fails; .invalid passes")
    void should_failStagingSupportEmail_when_blankOrRealDomain() {
        for (String v : new String[]{"", "support@example.com"}) {
            assertThatThrownBy(() -> run(stagingEnv().withProperty("app.support.email", v),
                    DeployEnvironment.STAGING, SmsMode.CAPTURE)).as("value=%s", v)
                    .hasMessageContaining("staging-support-email").message().doesNotContain("example.com");
        }
        assertThatCode(() -> run(stagingEnv().withProperty("app.support.email", "support@test.invalid"),
                DeployEnvironment.STAGING, SmsMode.CAPTURE)).doesNotThrowAnyException();
        assertThatCode(() -> run(prodEnv().withProperty("app.support.email", "help@beautica.ua"),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    // ---- hardening (audit cycle 1) ----

    @Test
    @DisplayName("public-base-url numeric / mapped / private IP literal or single-label host — fails")
    void should_fail_when_publicBaseUrlIsNumericOrMappedLoopback() {
        for (String v : new String[]{"https://2130706433", "https://0177.0.0.1", "https://0x7f.1", "https://0",
                "https://[::ffff:127.0.0.1]", "https://[0:0:0:0:0:0:0:1]", "https://10.1.2.3", "https://192.168.1.5",
                "https://172.16.0.9", "https://169.254.169.254", "https://staging", "https://api"}) {
            assertStagingPublicUrlRejected(v, "public-base-url-https");
        }
    }

    @Test
    @DisplayName("link URL with userinfo — fails with the userinfo rule, value not echoed")
    void should_fail_when_linkUrlHasUserInfo() {
        assertThatThrownBy(() -> run(prodEnv().withProperty("app.public-base-url", "https://user:secret@api.beautica.ua"),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).hasMessageContaining("public-base-url-userinfo")
                .message().satisfies(m -> assertThat(m).doesNotContain("secret"));
        assertThatThrownBy(() -> run(prodEnv().withProperty("app.frontend.base-url", "https://a@app.beautica.ua"),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).hasMessageContaining("frontend-base-url-userinfo");
    }

    @Test
    @DisplayName("link URL with surrounding whitespace, and frontend query/fragment/trailing slash — fails the shape rule")
    void should_fail_when_linkUrlHasWhitespaceOrBadShape() {
        for (String v : new String[]{" https://api.beautica.ua", "https://api.beautica.ua ", "https://api.beautica.ua\n"}) {
            assertThatThrownBy(() -> run(prodEnv().withProperty("app.public-base-url", v),
                    DeployEnvironment.PROD, SmsMode.TURBOSMS)).as("value=[%s]", v)
                    .hasMessageContaining("public-base-url-shape");
        }
        for (String v : new String[]{"https://app.beautica.ua/", "https://app.beautica.ua?x=1", "https://app.beautica.ua#f",
                " https://app.beautica.ua"}) {
            assertThatThrownBy(() -> run(prodEnv().withProperty("app.frontend.base-url", v),
                    DeployEnvironment.PROD, SmsMode.TURBOSMS)).as("value=[%s]", v)
                    .hasMessageContaining("frontend-base-url-shape");
        }
    }

    @Test
    @DisplayName("uppercase scheme or host and trailing-dot localhost are normalised, not a bypass")
    void should_normaliseCaseAndTrailingDot_when_checkingLinkUrls() {
        assertStagingPublicUrlRejected("HTTPS://LOCALHOST:8080", "public-base-url-https");
        assertStagingPublicUrlRejected("https://localhost.", "public-base-url-https");
        assertStagingPublicUrlRejected("HTTP://API.STAGING.BEAUTICA.TEST", "public-base-url-https");
        assertThatCode(() -> run(stagingEnv().withProperty("app.public-base-url", "HTTPS://API.STAGING.BEAUTICA.TEST"),
                DeployEnvironment.STAGING, SmsMode.CAPTURE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> run(prodEnv().withProperty("app.public-base-url", "https://API.STAGING.beautica.test"),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).hasMessageContaining("prod-public-base-url-host");
    }

    @Test
    @DisplayName("staging public host must carry a whole 'staging' DNS label (staging, staging-*, *-staging)")
    void should_matchWholeStagingLabel_when_checkingStagingPublicHost() {
        for (String ok : new String[]{"https://api.staging.beautica.test", "https://staging.beautica.test",
                "https://staging-api.beautica.test", "https://beautica-backend-staging.up.railway.app"}) {
            assertThatCode(() -> run(stagingEnv().withProperty("app.public-base-url", ok),
                    DeployEnvironment.STAGING, SmsMode.CAPTURE)).as(ok).doesNotThrowAnyException();
        }
        for (String bad : new String[]{"https://notstaging-x.com", "https://stagingx.other-tld.com",
                "https://mystaging.beautica.test", "https://api.beautica.test"}) {
            assertStagingPublicUrlRejected(bad, "staging-public-base-url-name");
        }
    }

    @Test
    @DisplayName("prod support email: malformed, trailing-dot reserved domains, @-less — fails; no value echoed")
    void should_fail_when_prodSupportEmailMalformedOrReserved() {
        for (String v : new String[]{"@", "a@", "a@localhost", "a@b.invalid.", "a@b.test", "a@b.example", "a@b.localhost",
                "a b@c.com", "a@@c.com"}) {
            assertThatThrownBy(() -> run(prodEnv().withProperty("app.support.email", v),
                    DeployEnvironment.PROD, SmsMode.TURBOSMS)).as("value=%s", v)
                    .hasMessageContaining("prod-support-email");
        }
        assertThatCode(() -> run(prodEnv().withProperty("app.support.email", "Help@Beautica.UA."),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("staging support email without @ or malformed — fails")
    void should_failStagingSupportEmail_when_malformed() {
        for (String v : new String[]{"support.invalid", "@x.invalid", "a@.invalid", "a@localhost"}) {
            assertThatThrownBy(() -> run(stagingEnv().withProperty("app.support.email", v),
                    DeployEnvironment.STAGING, SmsMode.CAPTURE)).as("value=%s", v)
                    .hasMessageContaining("staging-support-email");
        }
        assertThatCode(() -> run(stagingEnv().withProperty("app.support.email", "a@b.invalid."),
                DeployEnvironment.STAGING, SmsMode.CAPTURE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod link URL and support email on a placeholder domain — fails for every reserved TLD and example domain")
    void should_fail_when_prodUsesPlaceholderDomains() {
        for (String host : new String[]{"api.beautica.test", "api.beautica.example", "api.beautica.localhost",
                "api.beautica.invalid", "api.beautica.local", "api.beautica.internal", "api.beautica.home.arpa",
                "example.com", "example.net", "example.org", "api.example.com", "a.b.example.org"}) {
            assertThatThrownBy(() -> run(prodEnv().withProperty("app.public-base-url", "https://" + host),
                    DeployEnvironment.PROD, SmsMode.TURBOSMS)).as("public %s", host)
                    .hasMessageContaining("public-base-url");
            assertThatThrownBy(() -> run(prodEnv().withProperty("app.frontend.base-url", "https://" + host),
                    DeployEnvironment.PROD, SmsMode.TURBOSMS)).as("frontend %s", host)
                    .hasMessageContaining("frontend-base-url");
            assertThatThrownBy(() -> run(prodEnv().withProperty("app.support.email", "help@" + host),
                    DeployEnvironment.PROD, SmsMode.TURBOSMS)).as("support %s", host)
                    .hasMessageContaining("prod-support-email");
        }
    }

    @Test
    @DisplayName("prod host merely containing 'example' (notexample.com, example.com.ua) is not a placeholder")
    void should_pass_when_prodHostOnlyResemblesPlaceholder() {
        assertThatCode(() -> run(prodEnv().withProperty("app.frontend.base-url", "https://notexample.com")
                        .withProperty("app.support.email", "help@example.com.ua"),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("public-base-url with a path — fails in prod and staging, since it can never match a CORS origin")
    void should_fail_when_publicBaseUrlHasPath() {
        assertThatThrownBy(() -> run(prodEnv().withProperty("app.public-base-url", "https://api.beautica.ua/api"),
                DeployEnvironment.PROD, SmsMode.TURBOSMS)).hasMessageContaining("public-base-url-path");
        assertStagingPublicUrlRejected("https://api.staging.beautica.test/api", "public-base-url-path");
    }

    // ---- internal-api-key rule (audit-fix cycle 1) ----

    @Test
    @DisplayName("prod with a blank internal API key — fails naming the rule and property")
    void should_fail_when_prodInternalApiKeyBlank() {
        MockEnvironment env = prodEnv().withProperty("app.internal-api-key", "");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("internal-api-key-blank").hasMessageContaining("app.internal-api-key");
    }

    @Test
    @DisplayName("prod with the dev default internal API key — fails, message carries no value")
    void should_fail_when_prodInternalApiKeyIsDevDefault() {
        MockEnvironment env = prodEnv().withProperty("app.internal-api-key", "changeme-dev-only");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("internal-api-key-default")
                .message().doesNotContain("changeme-dev-only");
    }

    @Test
    @DisplayName("prod with an internal API key under 32 chars — fails, message carries no value")
    void should_fail_when_prodInternalApiKeyTooShort() {
        String shortKey = "s".repeat(31);
        MockEnvironment env = prodEnv().withProperty("app.internal-api-key", shortKey);

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("internal-api-key-length").message().doesNotContain(shortKey);
    }

    @Test
    @DisplayName("prod with an unresolvable internal API key placeholder — counts as unset")
    void should_fail_when_prodInternalApiKeyMissing() {
        MockEnvironment env = prodEnv().withProperty("app.internal-api-key", "${INTERNAL_API_KEY_UNSET_FOR_TEST}");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("internal-api-key-blank");
    }

    @Test
    @DisplayName("staging with the dev default internal API key — fails")
    void should_fail_when_stagingInternalApiKeyIsDevDefault() {
        MockEnvironment env = stagingEnv().withProperty("app.internal-api-key", "changeme-dev-only");

        assertThatThrownBy(() -> run(env, DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .hasMessageContaining("internal-api-key-default");
    }

    @Test
    @DisplayName("prod with exactly 32 chars — passes")
    void should_pass_when_prodInternalApiKeyIsExactlyMinLength() {
        MockEnvironment env = prodEnv().withProperty("app.internal-api-key", "k".repeat(32));

        assertThatCode(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    // ---- Phase 371, D6: Sentry rules ----------------------------------------------------------------

    private static final String EU_DSN = "https://0123456789abcdef@o42.ingest.de.sentry.io/4500000000000001";
    private static final String US_DSN = "https://0123456789abcdef@o42.ingest.us.sentry.io/4500000000000001";

    private static MockEnvironment localEnv() {
        return new MockEnvironment();
    }

    @Test
    @DisplayName("sentry-local: LOCAL with sentry.enabled=true and a real DSN — fails, DSN not in message")
    void should_fail_when_localEnablesSentryWithRealDsn() {
        MockEnvironment env = localEnv().withProperty("sentry.enabled", "true").withProperty("sentry.dsn", EU_DSN)
                .withProperty("sentry.environment", "local");

        assertThatThrownBy(() -> run(env, DeployEnvironment.LOCAL, SmsMode.TURBOSMS))
                .hasMessageContaining("rule sentry-local").hasMessageContaining("sentry.enabled")
                .message().doesNotContain("0123456789abcdef").doesNotContain("sentry.io");
    }

    @Test
    @DisplayName("sentry-local: LOCAL with a real DSN but sentry.enabled=false (A3 default) — passes")
    void should_pass_when_localHasRealDsnButSentryDisabled() {
        MockEnvironment env = localEnv().withProperty("sentry.enabled", "false").withProperty("sentry.dsn", EU_DSN);

        assertThatCode(() -> run(env, DeployEnvironment.LOCAL, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sentry-local: LOCAL (test ITs) enabled with a loopback WireMock DSN — passes")
    void should_pass_when_localEnablesSentryWithLoopbackDsn() {
        MockEnvironment env = localEnv().withProperty("sentry.enabled", "true")
                .withProperty("sentry.dsn", "http://k@localhost:34567/1").withProperty("sentry.environment", "local");

        assertThatCode(() -> run(env, DeployEnvironment.LOCAL, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sentry-local: LOCAL enabled with an unparseable DSN — fails closed")
    void should_fail_when_localEnablesSentryWithUnparseableDsn() {
        MockEnvironment env = localEnv().withProperty("sentry.enabled", "true")
                .withProperty("sentry.dsn", "not a dsn").withProperty("sentry.environment", "local");

        assertThatThrownBy(() -> run(env, DeployEnvironment.LOCAL, SmsMode.TURBOSMS))
                .hasMessageContaining("rule sentry-local").message().doesNotContain("not a dsn");
    }

    @Test
    @DisplayName("sentry-environment: enabled with sentry.environment != beautica.environment — fails")
    void should_fail_when_sentryEnvironmentDiffers() {
        MockEnvironment env = prodEnv().withProperty("sentry.enabled", "true").withProperty("sentry.dsn", EU_DSN)
                .withProperty("sentry.environment", "staging");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule sentry-environment").hasMessageContaining("sentry.environment")
                .message().doesNotContain("0123456789abcdef");
    }

    @Test
    @DisplayName("sentry-environment: enabled with matching lower-case token — passes (prod and staging)")
    void should_pass_when_sentryEnvironmentMatches() {
        MockEnvironment prod = prodEnv().withProperty("sentry.enabled", "true").withProperty("sentry.dsn", EU_DSN)
                .withProperty("sentry.environment", "prod");
        MockEnvironment staging = stagingEnv().withProperty("sentry.enabled", "true")
                .withProperty("sentry.dsn", EU_DSN).withProperty("sentry.environment", "staging");

        assertThatCode(() -> run(prod, DeployEnvironment.PROD, SmsMode.TURBOSMS)).doesNotThrowAnyException();
        assertThatCode(() -> run(staging, DeployEnvironment.STAGING, SmsMode.CAPTURE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sentry-environment: LOCAL enabled with a stray SENTRY_ENVIRONMENT value — fails")
    void should_fail_when_localSentryEnvironmentOverridden() {
        MockEnvironment env = localEnv().withProperty("sentry.enabled", "true")
                .withProperty("sentry.dsn", "http://k@127.0.0.1:34567/1").withProperty("sentry.environment", "prod");

        assertThatThrownBy(() -> run(env, DeployEnvironment.LOCAL, SmsMode.TURBOSMS))
                .hasMessageContaining("rule sentry-environment");
    }

    @Test
    @DisplayName("sentry-region: prod and staging DSN on the US ingest — fails, DSN not in message")
    void should_fail_when_dsnIsNotEuRegion() {
        MockEnvironment prod = prodEnv().withProperty("sentry.enabled", "true").withProperty("sentry.dsn", US_DSN)
                .withProperty("sentry.environment", "prod");
        MockEnvironment staging = stagingEnv().withProperty("sentry.enabled", "true")
                .withProperty("sentry.dsn", US_DSN).withProperty("sentry.environment", "staging");

        assertThatThrownBy(() -> run(prod, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule sentry-region").hasMessageContaining("sentry.dsn")
                .message().doesNotContain("0123456789abcdef").doesNotContain("o42");
        assertThatThrownBy(() -> run(staging, DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .hasMessageContaining("rule sentry-region");
    }

    @Test
    @DisplayName("sentry-region: a look-alike host (de.sentry.io.evil.test) — fails")
    void should_fail_when_dsnHostOnlyContainsEuSuffix() {
        MockEnvironment env = prodEnv().withProperty("sentry.enabled", "true")
                .withProperty("sentry.dsn", "https://k@o1.ingest.de.sentry.io.evil.test/1")
                .withProperty("sentry.environment", "prod");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule sentry-region");
    }

    @Test
    @DisplayName("sentry-dsn-required: PROD with a blank DSN — fails naming the rule")
    void should_fail_when_prodDsnBlank() {
        MockEnvironment prod = prodEnv().withProperty("sentry.enabled", "true").withProperty("sentry.dsn", "")
                .withProperty("sentry.environment", "prod");

        assertThatThrownBy(() -> run(prod, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule sentry-dsn-required").hasMessageContaining("sentry.dsn");
    }

    @Test
    @DisplayName("blank SENTRY_DSN — staging still boots (Sentry off)")
    void should_pass_when_dsnBlankInStaging() {
        MockEnvironment staging = stagingEnv().withProperty("sentry.enabled", "true").withProperty("sentry.dsn", "")
                .withProperty("sentry.environment", "staging");

        assertThatCode(() -> run(staging, DeployEnvironment.STAGING, SmsMode.CAPTURE)).doesNotThrowAnyException();
    }

    // ---- Phase 367 D5/D9: prod required-env rules ----------------------------------------------------------

    @Test
    @DisplayName("prod-turbosms-token-blank: PROD without TURBOSMS_TOKEN — fails naming the rule")
    void should_fail_when_prodTurbosmsTokenBlank() {
        MockEnvironment env = prodEnv().withProperty("TURBOSMS_TOKEN", " ");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule prod-turbosms-token-blank").hasMessageContaining("TURBOSMS_TOKEN");
    }

    @Test
    @DisplayName("prod-turbosms-token-blank: the token never appears; a token set via app.sms.turbosms.token passes")
    void should_acceptTokenViaProperty_when_prodTurbosms() {
        MockEnvironment viaProperty = prodEnv().withProperty("TURBOSMS_TOKEN", "")
                .withProperty("app.sms.turbosms.token", SECRET);

        assertThatCode(() -> run(viaProperty, DeployEnvironment.PROD, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod-admin-email-unset: ADMIN_EMAIL absent (only the yml default would apply) — fails")
    void should_fail_when_prodAdminEmailUnset() {
        MockEnvironment unset = prodEnvWithoutAdminEmail().withProperty("app.admin-email", "admin@beautica.app");

        assertThatThrownBy(() -> run(unset, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule prod-admin-email-unset").hasMessageContaining("ADMIN_EMAIL");
    }

    @Test
    @DisplayName("prod-admin-email-unset: ADMIN_EMAIL not email-shaped — fails, value not echoed")
    void should_fail_when_prodAdminEmailMalformed() {
        MockEnvironment env = prodEnv().withProperty("ADMIN_EMAIL", "not-an-email-" + SECRET);

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule prod-admin-email-unset").message().doesNotContain(SECRET);
    }

    @Test
    @DisplayName("prod-brevo-unconfigured: on the (now forbidden) smtp transport, PENDING/blank creds still WARN once each, no values")
    void should_warnOnce_when_prodBrevoPendingOrBlank(CapturedOutput output) {
        for (MockEnvironment env : List.of(
                prodEnv().withProperty("spring.mail.username", "PENDING").withProperty("spring.mail.password", "PENDING"),
                prodEnv().withProperty("spring.mail.username", "pending"),
                prodEnv().withProperty("spring.mail.password", " "))) {
            env.setProperty("app.mail.transport", "smtp");
            assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                    .hasMessageContaining("rule prod-mail-transport");
        }

        assertThat(brevoWarnLines(output)).hasSize(3);
        assertThat(output.getAll()).doesNotContain("brevo-login").doesNotContain("brevo-key");
    }

    @Test
    @DisplayName("prod-brevo-unconfigured: real credentials — no WARN")
    void should_notWarn_when_prodBrevoConfigured(CapturedOutput output) {
        run(prodEnv(), DeployEnvironment.PROD, SmsMode.TURBOSMS);

        assertThat(brevoWarnLines(output)).isEmpty();
    }

    @Test
    @DisplayName("staging ignores every new PROD rule (no DSN, no admin e-mail, no Brevo creds) — passes, no WARN")
    void should_ignoreProdRules_when_staging(CapturedOutput output) {
        MockEnvironment staging = stagingEnv().withProperty("sentry.dsn", "");

        assertThatCode(() -> run(staging, DeployEnvironment.STAGING, SmsMode.CAPTURE)).doesNotThrowAnyException();
        assertThat(brevoWarnLines(output)).isEmpty();
    }

    /** WARN log lines only: the test-name logger line itself mentions the rule. */
    private static List<String> brevoWarnLines(CapturedOutput output) {
        return output.getAll().lines()
                .filter(l -> l.contains(" WARN ") && l.contains("rule prod-brevo-unconfigured")).toList();
    }

    // ---------------------------------------------------------------- Phase 374 (Brevo HTTPS API transport)

    /** Prod on the API transport with no SMTP credentials at all (the Railway prod shape after cutover). */
    private static MockEnvironment prodApiEnv(String apiKey) {
        MockEnvironment env = prodEnv().withProperty("app.mail.transport", "brevo-api");
        env.setProperty("spring.mail.username", "");
        env.setProperty("spring.mail.password", "");
        if (apiKey != null) {
            env.setProperty("app.mail.brevo-api.api-key", apiKey);
        } else {
            ((java.util.Map<?, ?>) env.getPropertySources().get(
                    org.springframework.mock.env.MockPropertySource.MOCK_PROPERTIES_PROPERTY_SOURCE_NAME).getSource())
                    .remove("app.mail.brevo-api.api-key");
        }
        return env;
    }

    @Test
    @DisplayName("Phase 374 D8 — prod + brevo-api + blank/missing/PENDING key — fails prod-brevo-api-key-missing, names the env var only")
    void should_fail_when_prodBrevoApiKeyBlankOrPending() {
        for (MockEnvironment env : List.of(prodApiEnv(null), prodApiEnv(" "), prodApiEnv("PENDING"), prodApiEnv("pending"))) {
            assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("rule prod-brevo-api-key-missing")
                    .hasMessageContaining("BREVO_API_KEY");
        }
    }

    @Test
    @DisplayName("Phase 374 — prod + brevo-api + real key and NO SMTP credentials — passes, no prod-brevo-unconfigured WARN")
    void should_pass_when_prodBrevoApiKeySetWithoutSmtpCredentials(CapturedOutput output) {
        assertThatCode(() -> run(prodApiEnv("xkeysib-" + SECRET), DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .doesNotThrowAnyException();

        assertThat(brevoWarnLines(output)).isEmpty();
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("Phase 374 audit — prod-mail-transport: prod on smtp fails (SMTP cannot leave Railway); no key rule")
    void should_failProdBoot_when_mailTransportIsSmtp(CapturedOutput output) {
        MockEnvironment env = prodEnv().withProperty("app.mail.transport", "smtp")
                .withProperty("spring.mail.password", "PENDING");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule prod-mail-transport: app.mail.transport must be brevo-api in prod")
                .message().doesNotContain("prod-brevo-api-key-missing");
        assertThat(brevoWarnLines(output)).hasSize(1);
    }

    @Test
    @DisplayName("Phase 374 audit — prod-brevo-api-base-url: any origin other than exactly https://api.brevo.com fails, no value echoed")
    void should_failProdBoot_when_brevoApiBaseUrlNotBrevoHttps() {
        for (String url : List.of("http://api.brevo.com", "https://api.brevo.com.evil-zq.example",
                "https://api.brevo.com/", "http://127.0.0.1:8089")) {
            MockEnvironment env = prodEnv().withProperty("app.mail.brevo-api.base-url", url);

            assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                    .hasMessageContaining("rule prod-brevo-api-base-url")
                    .message().doesNotContain("evil-zq").doesNotContain("127.0.0.1");
        }
    }

    @Test
    @DisplayName("Phase 374 audit — prod-brevo-api-base-url: a relaxed env-var spelling (APP_MAIL_BREVOAPI_BASEURL) "
            + "that getProperty would miss is still caught, because the guard reads what the bean binds")
    void should_failProdBoot_when_baseUrlOverriddenViaEnvVarSpelling() {
        MockEnvironment env = prodEnv();
        ((java.util.Map<?, ?>) env.getPropertySources().get(
                org.springframework.mock.env.MockPropertySource.MOCK_PROPERTIES_PROPERTY_SOURCE_NAME).getSource())
                .remove("app.mail.brevo-api.base-url");
        env.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                java.util.Map.of("APP_MAIL_BREVOAPI_BASEURL", "https://brevo-zq374.example.net")));
        assertThat(env.getProperty("app.mail.brevo-api.base-url"))
                .as("precondition: plain getProperty does not see this spelling").isNull();
        assertThat(org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("app.mail.brevo-api.base-url", String.class).orElse(null))
                .as("precondition: the binder (what BrevoApiProperties gets) does").isEqualTo("https://brevo-zq374.example.net");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule prod-brevo-api-base-url")
                .message().doesNotContain("brevo-zq374");
    }

    @Test
    @DisplayName("Phase 374 audit — prod-brevo-api-base-url: an unset base-url resolves to the Brevo default and passes")
    void should_pass_when_prodBrevoApiBaseUrlUnset() {
        MockEnvironment clean = prodEnv();
        ((java.util.Map<?, ?>) clean.getPropertySources().get(
                org.springframework.mock.env.MockPropertySource.MOCK_PROPERTIES_PROPERTY_SOURCE_NAME).getSource())
                .remove("app.mail.brevo-api.base-url");
        assertThat(clean.getProperty("app.mail.brevo-api.base-url")).as("precondition: base-url unset").isNull();

        assertThatCode(() -> run(clean, DeployEnvironment.PROD, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Phase 374 audit — prod-mail-logo-url: the logo must be served from https://beautica.app/ in prod")
    void should_failProdBoot_when_logoUrlHostNotBeautica() {
        for (String url : List.of("https://cdn.example.com/logo.png", "https://beautica.app.evil.example/logo.png",
                "http://beautica.app/email/logo.png", "")) {
            MockEnvironment env = prodEnv().withProperty("app.mail.logo-url", url);

            assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                    .hasMessageContaining("rule prod-mail-logo-url");
        }
    }

    @Test
    @DisplayName("Phase 374 — staging + brevo-api transport — fails staging-mail-transport")
    void should_fail_when_stagingTransportIsBrevoApi() {
        MockEnvironment env = stagingEnv().withProperty("app.mail.transport", "brevo-api");

        assertThatThrownBy(() -> run(env, DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .hasMessageContaining("rule staging-mail-transport");
    }

    @Test
    @DisplayName("Phase 374 — staging with BREVO_API_KEY (env or property) — fails staging-brevo-key-blank, no value")
    void should_fail_when_stagingHasBrevoApiKey() {
        for (MockEnvironment env : List.of(stagingEnv().withProperty("BREVO_API_KEY", SECRET),
                stagingEnv().withProperty("app.mail.brevo-api.api-key", SECRET))) {
            assertThatThrownBy(() -> run(env, DeployEnvironment.STAGING, SmsMode.CAPTURE))
                    .hasMessageContaining("rule staging-brevo-key-blank: BREVO_API_KEY must be blank in staging")
                    .message().doesNotContain(SECRET);
        }
    }

    @Test
    @DisplayName("Phase 374 — mailKind is brevo-api for the API transport (checked before the host), else unchanged")
    void should_reportBrevoApiMailKind_when_transportBrevoApi() {
        MockEnvironment api = new MockEnvironment().withProperty("spring.mail.host", "mailpit")
                .withProperty("app.mail.transport", "brevo-api");
        MockEnvironment smtp = new MockEnvironment().withProperty("spring.mail.host", "smtp-relay.brevo.com")
                .withProperty("app.mail.transport", "smtp");

        assertThat(EnvironmentFingerprintGuard.mailKind(api)).isEqualTo("brevo-api");
        assertThat(EnvironmentFingerprintGuard.mailKind(smtp)).isEqualTo("brevo");
    }

    @Test
    @DisplayName("Phase 374 — an unknown app.mail.transport value fails the guard instead of silently meaning smtp")
    void should_fail_when_transportValueUnknown() {
        MockEnvironment env = prodEnv().withProperty("app.mail.transport", "bogus");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }

    @Test
    @DisplayName("sentryState: on only with enabled + DSN; never returns the DSN")
    void should_reportSentryState_when_banner() {
        assertThat(EnvironmentFingerprintGuard.sentryState(new MockEnvironment()
                .withProperty("sentry.enabled", "true").withProperty("sentry.dsn", EU_DSN))).isEqualTo("on");
        assertThat(EnvironmentFingerprintGuard.sentryState(new MockEnvironment()
                .withProperty("sentry.enabled", "true").withProperty("sentry.dsn", " "))).isEqualTo("off");
        assertThat(EnvironmentFingerprintGuard.sentryState(new MockEnvironment()
                .withProperty("sentry.enabled", "false").withProperty("sentry.dsn", EU_DSN))).isEqualTo("off");
    }

    @Test
    @DisplayName("sentry-debug: SENTRY_DEBUG=true in prod or staging — fails naming the rule, never the DSN")
    void should_fail_when_sentryDebugOutsideLocal() {
        MockEnvironment prod = prodEnv().withProperty("sentry.enabled", "true").withProperty("sentry.dsn", EU_DSN)
                .withProperty("sentry.environment", "prod").withProperty("sentry.debug", "true");
        MockEnvironment staging = stagingEnv().withProperty("sentry.debug", "TRUE");

        assertThatThrownBy(() -> run(prod, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule sentry-debug").hasMessageContaining("sentry.debug")
                .message().doesNotContain("0123456789abcdef").doesNotContain("o42");
        assertThatThrownBy(() -> run(staging, DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .hasMessageContaining("rule sentry-debug");
    }

    @Test
    @DisplayName("sentry-send-default-pii: true (or garbage) in prod/staging — fails")
    void should_fail_when_sendDefaultPiiOutsideLocal() {
        MockEnvironment prod = prodEnv().withProperty("sentry.send-default-pii", "true");
        MockEnvironment staging = stagingEnv().withProperty("sentry.send-default-pii", "yes");

        assertThatThrownBy(() -> run(prod, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule sentry-send-default-pii").hasMessageContaining("sentry.send-default-pii");
        assertThatThrownBy(() -> run(staging, DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .hasMessageContaining("rule sentry-send-default-pii");
    }

    @Test
    @DisplayName("sentry.debug / send-default-pii explicitly false in prod — passes; true in LOCAL — not this rule's concern")
    void should_pass_when_debugAndPiiFalseOrLocal() {
        MockEnvironment prod = prodEnv().withProperty("sentry.debug", "false")
                .withProperty("sentry.send-default-pii", "false");
        MockEnvironment local = localEnv().withProperty("sentry.debug", "true")
                .withProperty("sentry.send-default-pii", "true");

        assertThatCode(() -> run(prod, DeployEnvironment.PROD, SmsMode.TURBOSMS)).doesNotThrowAnyException();
        assertThatCode(() -> run(local, DeployEnvironment.LOCAL, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod-turbosms-token-placeholder: PENDING/changeme/todo/xxx (any case) or a short token — fails, value not echoed")
    void should_fail_when_prodTurbosmsTokenIsPlaceholderOrShort() {
        for (String bad : List.of("PENDING", "ChangeMe", "todo", "XXX", "x", "short-token")) {
            MockEnvironment env = prodEnv().withProperty("TURBOSMS_TOKEN", bad);

            assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                    .hasMessageContaining("rule prod-turbosms-token-placeholder")
                    .message().doesNotContain("=" + bad);
        }
    }

    @Test
    @DisplayName("prod-admin-email: a reserved/example ADMIN_EMAIL, or an APP_ADMIN_EMAIL override to one, fails")
    void should_fail_when_prodAdminEmailIsPlaceholderOrOverriddenToOne() {
        MockEnvironment raw = prodEnv().withProperty("ADMIN_EMAIL", "admin@example.com");
        MockEnvironment overridden = prodEnv().withProperty("app.admin-email", "ops@mail.example.org");
        MockEnvironment overriddenMalformed = prodEnv().withProperty("app.admin-email", "not-an-email");

        assertThatThrownBy(() -> run(raw, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule prod-admin-email-placeholder");
        assertThatThrownBy(() -> run(overridden, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule prod-admin-email-effective");
        assertThatThrownBy(() -> run(overriddenMalformed, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .hasMessageContaining("rule prod-admin-email-effective");
    }

    @Test
    @DisplayName("prod-admin-email: a real override different from ADMIN_EMAIL passes")
    void should_pass_when_prodAdminEmailOverriddenToRealAddress() {
        MockEnvironment env = prodEnv().withProperty("app.admin-email", "ops@beautica.ua");

        assertThatCode(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("spring.factories instantiation of the guard takes the DeferredLogFactory constructor (deferred-log path)")
    void should_useDeferredLogPath_when_instantiatedBySpringFactoriesLoader() {
        org.springframework.boot.logging.DeferredLogFactory factory = org.mockito.Mockito.mock(
                org.springframework.boot.logging.DeferredLogFactory.class);
        org.apache.commons.logging.Log deferred = org.mockito.Mockito.mock(org.apache.commons.logging.Log.class);
        org.mockito.Mockito.when(factory.getLog(EnvironmentFingerprintGuard.class)).thenReturn(deferred);
        org.springframework.core.io.support.SpringFactoriesLoader loader =
                org.springframework.core.io.support.SpringFactoriesLoader.forDefaultResourceLocation(
                        getClass().getClassLoader());

        List<org.springframework.boot.env.EnvironmentPostProcessor> processors = loader.load(
                org.springframework.boot.env.EnvironmentPostProcessor.class,
                org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver.of(
                        org.springframework.boot.logging.DeferredLogFactory.class, factory));

        EnvironmentFingerprintGuard guard = processors.stream()
                .filter(EnvironmentFingerprintGuard.class::isInstance)
                .map(EnvironmentFingerprintGuard.class::cast).findFirst().orElseThrow();
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(guard, "log")).isSameAs(deferred);
        assertThat(EnvironmentFingerprintGuard.class.getConstructors()).hasSize(1);
        org.mockito.Mockito.verify(factory).getLog(EnvironmentFingerprintGuard.class);
    }

    // ── Phase 382 D7 — Turnstile rules ───────────────────────────────────────────────────────

    @Test
    @DisplayName("prod with Turnstile disabled fails naming prod-turnstile-disabled, not the secret")
    void should_fail_when_prodTurnstileDisabled() {
        MockEnvironment env = prodEnv().withProperty("app.turnstile.enabled", "false");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod-turnstile-disabled")
                .hasMessageNotContaining(TURNSTILE_REAL_SECRET);
    }

    @Test
    @DisplayName("prod with a blank Turnstile secret fails naming prod-turnstile-secret-blank")
    void should_fail_when_prodTurnstileSecretBlank() {
        MockEnvironment env = prodEnv().withProperty("app.turnstile.secret-key", "  ");

        assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod-turnstile-secret-blank");
    }

    @Test
    @DisplayName("prod with any Cloudflare test secret (1x/2x/3x) fails naming prod-turnstile-secret-test-key, never the value")
    void should_fail_when_prodTurnstileSecretIsTestKey() {
        for (String testKey : new String[] {"1x0000000000000000000000000000000AA",
                "2x0000000000000000000000000000000AA", "3x0000000000000000000000000000000AA"}) {
            MockEnvironment env = prodEnv().withProperty("app.turnstile.secret-key", testKey);

            assertThatThrownBy(() -> run(env, DeployEnvironment.PROD, SmsMode.TURBOSMS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("prod-turnstile-secret-test-key")
                    .hasMessageNotContaining(testKey);
        }
    }

    @Test
    @DisplayName("staging with a REAL Turnstile secret fails naming staging-turnstile-secret-not-test-key, never the value")
    void should_fail_when_stagingTurnstileSecretIsReal() {
        MockEnvironment env = stagingEnv().withProperty("app.turnstile.secret-key", TURNSTILE_REAL_SECRET);

        assertThatThrownBy(() -> run(env, DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staging-turnstile-secret-not-test-key")
                .hasMessageNotContaining(TURNSTILE_REAL_SECRET);
    }

    @Test
    @DisplayName("staging accepts each of the three Cloudflare test secrets")
    void should_pass_when_stagingTurnstileSecretIsAnyTestKey() {
        for (String testKey : new String[] {"1x0000000000000000000000000000000AA",
                "2x0000000000000000000000000000000AA", "3x0000000000000000000000000000000AA"}) {
            MockEnvironment env = stagingEnv().withProperty("app.turnstile.secret-key", testKey);

            assertThatCode(() -> run(env, DeployEnvironment.STAGING, SmsMode.CAPTURE)).doesNotThrowAnyException();
        }
    }
}
