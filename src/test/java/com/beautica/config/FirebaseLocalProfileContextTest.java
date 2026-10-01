package com.beautica.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.config.FirebaseConfig.FirebaseSender;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.Message;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

/**
 * Phase 340 — proves the {@code local} profile wires the exported {@code FIREBASE_SERVICE_ACCOUNT} into
 * {@link FirebaseConfig} end to end: real {@code application.yml} + {@code application-local.yml}
 * property resolution (not a hand-set reflection field) feeding the real bean method.
 *
 * <p>The credential is a throwaway RSA key generated in-memory per class run; nothing real is used and
 * nothing is sent (the sender is never invoked on the real path, so no network is reached). The env var
 * is simulated with a system property, which Spring resolves for {@code ${FIREBASE_SERVICE_ACCOUNT:}}
 * exactly as it resolves an OS environment variable.
 *
 * <p>Driven with {@link ApplicationContextRunner} + {@link ConfigDataApplicationContextInitializer}: a
 * one-bean context that still loads the real YAML files (Anti-Bug §M-1 — no Tomcat / Postgres).
 */
@DisplayName("FirebaseConfig — local profile resolves FIREBASE_SERVICE_ACCOUNT from the environment")
@ExtendWith(OutputCaptureExtension.class)
class FirebaseLocalProfileContextTest {

    private static final String FAKE_PROJECT_ID = "fake-local-project";
    private static String fakeServiceAccountBase64;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(FirebaseConfig.class);

    @BeforeAll
    static void generateThrowawayCredential() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(gen.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String json = "{\"type\":\"service_account\",\"project_id\":\"" + FAKE_PROJECT_ID + "\","
                + "\"private_key_id\":\"kid\",\"private_key\":\"" + pem.replace("\n", "\\n") + "\","
                + "\"client_email\":\"t@" + FAKE_PROJECT_ID + ".iam.gserviceaccount.com\",\"client_id\":\"1\","
                + "\"token_uri\":\"https://oauth2.googleapis.com/token\"}";
        fakeServiceAccountBase64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void cleanFirebaseApps() {
        FirebaseApp.getApps().forEach(FirebaseApp::delete);
    }

    @Test
    @DisplayName("local + FIREBASE_ENABLED + valid-shaped credential → real FirebaseApp created, project id logged, key not logged")
    void should_createRealFirebaseApp_when_localProfileHasEnabledFlagAndCredential(CapturedOutput output) {
        runner.withSystemProperties("spring.profiles.active=local", "FIREBASE_ENABLED=true",
                        "FIREBASE_SERVICE_ACCOUNT=" + fakeServiceAccountBase64)
                .run(context -> {
                    assertThat(context).hasSingleBean(FirebaseSender.class);
                    assertThat(FirebaseApp.getApps())
                            .as("the real path must initialise exactly one FirebaseApp")
                            .hasSize(1);
                    assertThat(output.getAll())
                            .as("project id comes from the exported service account")
                            .contains("Firebase push enabled for project " + FAKE_PROJECT_ID)
                            .as("the credential itself must never be logged")
                            .doesNotContain(fakeServiceAccountBase64.substring(0, 40));
                });
    }

    @Test
    @DisplayName("local + FIREBASE_ENABLED + blank FIREBASE_SERVICE_ACCOUNT → no-op sender, no FirebaseApp, boot succeeds")
    void should_fallBackToNoOpSender_when_localProfileHasBlankCredential() {
        runner.withSystemProperties("spring.profiles.active=local", "FIREBASE_ENABLED=true",
                        "FIREBASE_SERVICE_ACCOUNT=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(FirebaseApp.getApps()).as("no app may be initialised without a credential").isEmpty();
                    assertThat(context.getBean(FirebaseSender.class)
                            .send(Message.builder().setToken("device-token").build()))
                            .isEqualTo("no-op");
                });
    }

    @Test
    @DisplayName("local profile — app.firebase.service-account binds the FIREBASE_SERVICE_ACCOUNT variable")
    void should_bindServiceAccountFromEnv_when_localProfileActive() {
        runner.withSystemProperties("spring.profiles.active=local", "FIREBASE_SERVICE_ACCOUNT=abc123")
                .run(context -> assertThat(context.getBean(Environment.class)
                        .getProperty("app.firebase.service-account"))
                        .isEqualTo("abc123"));
    }

    @Test
    @DisplayName("local profile — app.firebase.service-account is empty when the variable is unset (push stays dark)")
    void should_defaultServiceAccountToEmpty_when_localProfileAndVariableUnset() {
        runner.withSystemProperties("spring.profiles.active=local")
                .run(context -> assertThat(context.getBean(Environment.class)
                        .getProperty("app.firebase.service-account", "<missing>"))
                        .isEmpty());
    }

    @Test
    @DisplayName("test profile — application-test.yml still pins app.firebase.service-account=test, ignoring the variable")
    void should_keepTestProfileServiceAccountUnchanged_when_variableSet() {
        runner.withSystemProperties("spring.profiles.active=test", "FIREBASE_SERVICE_ACCOUNT=abc123")
                .run(context -> assertThat(context.getBean(Environment.class)
                        .getProperty("app.firebase.service-account"))
                        .isEqualTo("test"));
    }

    @Test
    @DisplayName("default profile — application.yml still maps app.firebase.service-account to the variable")
    void should_bindServiceAccountFromEnv_when_noProfileActive() {
        runner.withSystemProperties("FIREBASE_SERVICE_ACCOUNT=abc123")
                .run(context -> assertThat(context.getBean(Environment.class)
                        .getProperty("app.firebase.service-account"))
                        .isEqualTo("abc123"));
    }
}
