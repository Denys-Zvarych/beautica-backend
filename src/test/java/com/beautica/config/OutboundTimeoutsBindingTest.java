package com.beautica.config;

import com.beautica.notification.sms.OtpSmsSender;
import com.google.firebase.FirebaseApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 364 QA — the PRODUCTION {@code application.yml} timeout values reach the R2 / Turbosms /
 * Firebase wiring through real Spring property binding, and a zero timeout aborts context startup
 * (a unit-level throw alone would not prove the app refuses to boot). No database needed.
 */
@DisplayName("Outbound timeouts — production yml binding and fail-fast startup (Phase 364)")
class OutboundTimeoutsBindingTest {

    @Configuration
    @EnableConfigurationProperties(TurbosmsProperties.class)
    @Import(SmsConfig.class)
    static class SmsWiring {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    private static ApplicationContextRunner prodYml() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    @AfterEach
    void cleanFirebaseApps() {
        FirebaseApp.getApps().forEach(FirebaseApp::delete);
    }

    private static String serviceAccountBase64() throws Exception {
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(gen.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String json = "{\"type\":\"service_account\",\"project_id\":\"test-project\","
                + "\"private_key_id\":\"kid\",\"private_key\":\"" + pem.replace("\n", "\\n") + "\","
                + "\"client_email\":\"t@test-project.iam.gserviceaccount.com\",\"client_id\":\"1\","
                + "\"token_uri\":\"https://oauth2.googleapis.com/token\"}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("should_bindTurbosmsThreeAndFiveSeconds_when_productionYmlLoaded")
    void should_bindTurbosmsThreeAndFiveSeconds_when_productionYmlLoaded() {
        prodYml().withUserConfiguration(SmsWiring.class).run(ctx -> {
            TurbosmsProperties props = ctx.getBean(TurbosmsProperties.class);

            assertThat(ctx).hasNotFailed();
            assertThat(props.getConnectTimeout()).as("connect, actual=%s", props.getConnectTimeout())
                    .isEqualTo(Duration.ofSeconds(3));
            assertThat(props.getReadTimeout()).as("read, actual=%s", props.getReadTimeout())
                    .isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    @DisplayName("should_exposeR2AndFirebaseDefaultsInYml_when_productionYmlLoaded")
    void should_exposeR2AndFirebaseDefaultsInYml_when_productionYmlLoaded() {
        prodYml().run(ctx -> {
            var env = ctx.getEnvironment();

            assertThat(env.getProperty("app.cloudflare-r2.connection-timeout")).isEqualTo("5s");
            assertThat(env.getProperty("app.cloudflare-r2.socket-timeout")).isEqualTo("30s");
            assertThat(env.getProperty("app.firebase.connect-timeout")).isEqualTo("5s");
            assertThat(env.getProperty("app.firebase.read-timeout")).isEqualTo("10s");
        });
    }

    @Test
    @DisplayName("should_buildS3Client_when_productionYmlTimeoutsBound")
    void should_buildS3Client_when_productionYmlTimeoutsBound() {
        prodYml().withUserConfiguration(S3Config.class)
                .withPropertyValues(S3ConfigTestSupport.ENABLED_WITH_CREDENTIALS)
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(S3Client.class));
    }

    @Test
    @DisplayName("should_failStartup_when_r2SocketTimeoutIsZero")
    void should_failStartup_when_r2SocketTimeoutIsZero() {
        S3ConfigTestSupport.enabledRunner().withPropertyValues("app.cloudflare-r2.socket-timeout=0s")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("app.cloudflare-r2.socket-timeout"));
    }

    @Test
    @DisplayName("should_failStartup_when_r2ConnectionTimeoutIsNegative")
    void should_failStartup_when_r2ConnectionTimeoutIsNegative() {
        S3ConfigTestSupport.enabledRunner().withPropertyValues("app.cloudflare-r2.connection-timeout=-1s")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("app.cloudflare-r2.connection-timeout"));
    }

    @Test
    @DisplayName("should_failStartup_when_turbosmsReadTimeoutIsZero")
    void should_failStartup_when_turbosmsReadTimeoutIsZero() {
        // OtpSmsSender is unconditional, so the zero value aborts boot even with booking SMS off.
        new ApplicationContextRunner()
                .withUserConfiguration(SmsWiring.class)
                .withPropertyValues("app.sms.turbosms.read-timeout=0s")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("app.sms.turbosms.read-timeout"));
    }

    @Test
    @DisplayName("should_failStartup_when_turbosmsConnectTimeoutIsZero")
    void should_failStartup_when_turbosmsConnectTimeoutIsZero() {
        new ApplicationContextRunner()
                .withUserConfiguration(SmsWiring.class)
                .withPropertyValues("app.sms.turbosms.connect-timeout=0")
                .run(ctx -> {
                    assertThat(ctx).hasFailed().getFailure().rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("app.sms.turbosms.connect-timeout");
                    assertThat(ctx.getStartupFailure()).isNotNull();
                });
    }

    @Test
    @DisplayName("should_startWithOtpSender_when_turbosmsTimeoutsPositive")
    void should_startWithOtpSender_when_turbosmsTimeoutsPositive() {
        new ApplicationContextRunner()
                .withUserConfiguration(SmsWiring.class)
                .withPropertyValues("app.sms.turbosms.read-timeout=2s")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(OtpSmsSender.class));
    }

    @Test
    @DisplayName("should_failStartup_when_firebaseConnectTimeoutIsZero")
    void should_failStartup_when_firebaseConnectTimeoutIsZero() throws Exception {
        new ApplicationContextRunner()
                .withUserConfiguration(FirebaseConfig.class)
                .withPropertyValues(
                        "FIREBASE_ENABLED=true",
                        "app.firebase.service-account=" + serviceAccountBase64(),
                        "app.firebase.connect-timeout=0s")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("app.firebase.connect-timeout"));
    }

    // ---- Phase 374: Brevo HTTPS API transport timeouts ----

    @Configuration
    @Import(com.beautica.notification.mail.MailTransportConfig.class)
    static class BrevoApiWiring {
    }

    @Test
    @DisplayName("should_bindBrevoApiThreeAndTenSeconds_when_productionYmlLoaded")
    void should_bindBrevoApiThreeAndTenSeconds_when_productionYmlLoaded() {
        prodYml().withUserConfiguration(BrevoApiWiring.class).run(ctx -> {
            var props = ctx.getBean(com.beautica.notification.mail.BrevoApiProperties.class);

            assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    @DisplayName("should_failStartup_when_brevoApiReadOrConnectTimeoutIsZero")
    void should_failStartup_when_brevoApiReadOrConnectTimeoutIsZero() {
        for (String key : java.util.List.of("read-timeout", "connect-timeout")) {
            new ApplicationContextRunner()
                    .withUserConfiguration(BrevoApiWiring.class)
                    .withPropertyValues("app.mail.brevo-api." + key + "=0s")
                    .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("app.mail.brevo-api." + key));
        }
    }
}
