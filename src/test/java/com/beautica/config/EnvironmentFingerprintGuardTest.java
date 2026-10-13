package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EnvironmentFingerprintGuard — Phase 368 D2 / audit cycle 1")
class EnvironmentFingerprintGuardTest {

    private static final String SECRET = "tok-S3cr3t-value-9xQ";

    private static MockEnvironment stagingEnv() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("RAILWAY_ENVIRONMENT_NAME", "staging")
                .withProperty("app.cloudflare-r2.enabled", "true")
                .withProperty("app.cloudflare-r2.bucket", "beautica-media-staging")
                .withProperty("spring.mail.host", "mailpit.railway.internal");
        env.setActiveProfiles("prod", "staging");
        return env;
    }

    private static MockEnvironment prodEnv() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("RAILWAY_ENVIRONMENT_NAME", "production")
                .withProperty("app.cloudflare-r2.enabled", "true")
                .withProperty("app.cloudflare-r2.bucket", "beautica-media-prod")
                .withProperty("spring.mail.host", "smtp-relay.brevo.com");
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
                .withProperty("spring.mail.host", "mailpit.railway.internal");
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
}
