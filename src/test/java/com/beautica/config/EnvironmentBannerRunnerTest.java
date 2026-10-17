package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("EnvironmentBannerRunner — startup wiring line (Phase 368 D5)")
class EnvironmentBannerRunnerTest {

    /** Banner lines only: the test-name logger line can mention the same tokens. */
    private static java.util.List<String> bannerLines(CapturedOutput output) {
        return output.getOut().lines().filter(l -> l.contains("beautica_environment=")
                && l.contains("EnvironmentBannerRunner - ")).toList();
    }

    /** Probe stub: reports the relay as reachable (or not) without touching the network; counts calls. */
    private static final class StubProbe extends BrevoConnectionProbe {
        private final boolean unreachable;
        private int calls;

        StubProbe(boolean unreachable) {
            super(null);
            this.unreachable = unreachable;
        }

        @Override
        public boolean isUnreachable() {
            calls++;
            return unreachable;
        }
    }

    private static EnvironmentBannerRunner runner(MockEnvironment env, DeploymentProperties props) {
        return new EnvironmentBannerRunner(env, props, new StubProbe(false));
    }

    private static DeploymentProperties props(DeployEnvironment env, SmsMode mode) {
        return new DeploymentProperties(env, new DeploymentProperties.Sms(mode));
    }

    @Test
    @DisplayName("staging — banner shows mail=capture sms=capture and the staging bucket")
    void should_logCaptureWiring_when_staging(CapturedOutput output) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.mail.host", "mailpit.railway.internal")
                .withProperty("app.cloudflare-r2.enabled", "true")
                .withProperty("app.cloudflare-r2.bucket", "beautica-media-staging");

        runner(env, props(DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .run(new DefaultApplicationArguments());

        assertThat(output.getOut())
                .contains("beautica_environment=staging mail=capture sms=capture r2_bucket=beautica-media-staging");
    }

    @Test
    @DisplayName("prod — banner shows mail=brevo sms=turbosms and the prod bucket")
    void should_logProviderWiring_when_prod(CapturedOutput output) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.mail.host", "smtp-relay.brevo.com")
                .withProperty("spring.mail.username", "brevo-login")
                .withProperty("spring.mail.password", "brevo-key")
                .withProperty("app.cloudflare-r2.enabled", "true")
                .withProperty("app.cloudflare-r2.bucket", "beautica-media-prod");

        runner(env, props(DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .run(new DefaultApplicationArguments());

        assertThat(output.getOut())
                .contains("beautica_environment=prod mail=brevo sms=turbosms r2_bucket=beautica-media-prod sentry=off push=off");
    }

    @Test
    @DisplayName("Phase 367 D9 — prod with PENDING or blank Brevo credentials shows mail=brevo-pending")
    void should_logBrevoPending_when_prodCredentialsPendingOrBlank(CapturedOutput output) {
        MockEnvironment pending = new MockEnvironment().withProperty("spring.mail.host", "smtp-relay.brevo.com")
                .withProperty("spring.mail.username", "PENDING").withProperty("spring.mail.password", "PENDING");
        MockEnvironment blank = new MockEnvironment().withProperty("spring.mail.host", "smtp-relay.brevo.com");

        runner(pending, props(DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .run(new DefaultApplicationArguments());
        runner(blank, props(DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .run(new DefaultApplicationArguments());

        assertThat(bannerLines(output)).hasSize(2).allMatch(l -> l.contains("mail=brevo-pending sms="));
    }

    @Test
    @DisplayName("prod with real-looking Brevo credentials but a failing relay probe shows mail=brevo-unreachable")
    void should_logBrevoUnreachable_when_prodProbeFails(CapturedOutput output) {
        MockEnvironment env = new MockEnvironment().withProperty("spring.mail.host", "smtp-relay.brevo.com")
                .withProperty("spring.mail.username", "brevo-login").withProperty("spring.mail.password", "brevo-key");

        new EnvironmentBannerRunner(env, props(DeployEnvironment.PROD, SmsMode.TURBOSMS), new StubProbe(true))
                .run(new DefaultApplicationArguments());

        assertThat(bannerLines(output)).singleElement().asString().contains("mail=brevo-unreachable sms=");
    }

    @Test
    @DisplayName("the relay probe is skipped when credentials are pending, and in staging")
    void should_notProbe_when_credentialsPendingOrNotProd() {
        StubProbe probe = new StubProbe(true);
        MockEnvironment pending = new MockEnvironment().withProperty("spring.mail.host", "smtp-relay.brevo.com")
                .withProperty("spring.mail.username", "PENDING").withProperty("spring.mail.password", "PENDING");
        MockEnvironment staging = new MockEnvironment().withProperty("spring.mail.host", "mailpit.railway.internal");

        new EnvironmentBannerRunner(pending, props(DeployEnvironment.PROD, SmsMode.TURBOSMS), probe)
                .run(new DefaultApplicationArguments());
        new EnvironmentBannerRunner(staging, props(DeployEnvironment.STAGING, SmsMode.CAPTURE), probe)
                .run(new DefaultApplicationArguments());

        assertThat(probe.calls).isZero();
    }

    @Test
    @DisplayName("Phase 367 D10 — push=on when FIREBASE_ENABLED resolves true (as its consumers read it), else push=off")
    void should_logPushState_when_firebaseEnabledFlag(CapturedOutput output) {
        runner(new MockEnvironment().withProperty("FIREBASE_ENABLED", "true"),
                props(DeployEnvironment.PROD, SmsMode.TURBOSMS)).run(new DefaultApplicationArguments());
        runner(new MockEnvironment().withProperty("FIREBASE_ENABLED", "false"),
                props(DeployEnvironment.PROD, SmsMode.TURBOSMS)).run(new DefaultApplicationArguments());
        runner(new MockEnvironment(), props(DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .run(new DefaultApplicationArguments());

        runner(new MockEnvironment().withProperty("FIREBASE_ENABLED", "1"),
                props(DeployEnvironment.PROD, SmsMode.TURBOSMS)).run(new DefaultApplicationArguments());

        assertThat(bannerLines(output)).hasSize(4);
        assertThat(bannerLines(output).get(3)).as("Spring's boolean conversion accepts 1, as @Value does").endsWith("push=on");
        assertThat(bannerLines(output).get(0)).endsWith("push=on");
        assertThat(bannerLines(output).get(1)).endsWith("push=off");
        assertThat(bannerLines(output).get(2)).endsWith("push=off");
    }

    @Test
    @DisplayName("Phase 367 — staging never shows brevo-pending (capture host, no Brevo creds)")
    void should_notShowBrevoPending_when_staging(CapturedOutput output) {
        MockEnvironment env = new MockEnvironment().withProperty("spring.mail.host", "mailpit.railway.internal");

        runner(env, props(DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .run(new DefaultApplicationArguments());

        assertThat(bannerLines(output)).singleElement().asString()
                .contains("mail=capture").doesNotContain("brevo-pending");
    }

    @Test
    @DisplayName("banner never prints the mail hostname or credentials")
    void should_notLeakHostOrSecrets_when_bannerLogged(CapturedOutput output) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.mail.host", "secret-host.example")
                .withProperty("spring.mail.password", "hunter2-secret")
                .withProperty("TURBOSMS_TOKEN", "tok-secret");

        runner(env, props(DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .run(new DefaultApplicationArguments());

        assertThat(output.getOut()).contains("beautica_environment=prod")
                .doesNotContain("secret-host.example").doesNotContain("hunter2-secret").doesNotContain("tok-secret");
    }

    @Test
    @DisplayName("Phase 371 — sentry=on when enabled with a DSN; the DSN itself is never printed")
    void should_logSentryOn_when_enabledWithDsn(CapturedOutput output) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("sentry.enabled", "true")
                .withProperty("sentry.dsn", "https://dsn-key-secret@o42.ingest.de.sentry.io/45");

        runner(env, props(DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .run(new DefaultApplicationArguments());

        assertThat(output.getOut()).contains("sentry=on")
                .doesNotContain("dsn-key-secret").doesNotContain("sentry.io");
    }

    @Test
    @DisplayName("Phase 371 — sentry=off when the DSN is blank or Sentry is disabled")
    void should_logSentryOff_when_dsnBlankOrDisabled(CapturedOutput output) {
        MockEnvironment blankDsn = new MockEnvironment()
                .withProperty("sentry.enabled", "true").withProperty("sentry.dsn", "");
        MockEnvironment disabled = new MockEnvironment()
                .withProperty("sentry.enabled", "false")
                .withProperty("sentry.dsn", "https://dsn-key-secret@o42.ingest.de.sentry.io/45");

        runner(blankDsn, props(DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .run(new DefaultApplicationArguments());
        runner(disabled, props(DeployEnvironment.LOCAL, SmsMode.TURBOSMS))
                .run(new DefaultApplicationArguments());

        assertThat(output.getOut()).contains("beautica_environment=staging").contains("sentry=off")
                .doesNotContain("sentry=on").doesNotContain("dsn-key-secret");
    }
}
