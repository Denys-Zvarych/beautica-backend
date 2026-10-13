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

        new EnvironmentBannerRunner(env, props(DeployEnvironment.STAGING, SmsMode.CAPTURE))
                .run(new DefaultApplicationArguments());

        assertThat(output.getOut())
                .contains("beautica_environment=staging mail=capture sms=capture r2_bucket=beautica-media-staging");
    }

    @Test
    @DisplayName("prod — banner shows mail=brevo sms=turbosms and the prod bucket")
    void should_logProviderWiring_when_prod(CapturedOutput output) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.mail.host", "smtp-relay.brevo.com")
                .withProperty("app.cloudflare-r2.enabled", "true")
                .withProperty("app.cloudflare-r2.bucket", "beautica-media-prod");

        new EnvironmentBannerRunner(env, props(DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .run(new DefaultApplicationArguments());

        assertThat(output.getOut())
                .contains("beautica_environment=prod mail=brevo sms=turbosms r2_bucket=beautica-media-prod");
    }

    @Test
    @DisplayName("banner never prints the mail hostname or credentials")
    void should_notLeakHostOrSecrets_when_bannerLogged(CapturedOutput output) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.mail.host", "secret-host.example")
                .withProperty("spring.mail.password", "hunter2-secret")
                .withProperty("TURBOSMS_TOKEN", "tok-secret");

        new EnvironmentBannerRunner(env, props(DeployEnvironment.PROD, SmsMode.TURBOSMS))
                .run(new DefaultApplicationArguments());

        assertThat(output.getOut()).contains("beautica_environment=prod")
                .doesNotContain("secret-host.example").doesNotContain("hunter2-secret").doesNotContain("tok-secret");
    }
}
