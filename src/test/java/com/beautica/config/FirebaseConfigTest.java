package com.beautica.config;

import com.beautica.config.FirebaseConfig.FirebaseSender;
import com.google.firebase.messaging.Message;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the AS-BUILT {@link FirebaseConfig#firebaseSender()} contract
 * (QA LOW — config/*ConfigTest row: no-op when disabled + non-local fail-fast).
 *
 * <p>The bean has three documented branches, all exercised here WITHOUT a real
 * Firebase credential and WITHOUT any network call:
 * <ul>
 *   <li><b>Disabled</b> ({@code FIREBASE_ENABLED=false}) → returns a no-op
 *       sender that yields {@code "no-op"}; no {@code FirebaseApp} init, no throw.
 *       Holds regardless of active profile.</li>
 *   <li><b>Enabled + blank credential + non-local profile</b> → fail-fast with
 *       {@link IllegalStateException} so the app refuses to start mis-configured.</li>
 *   <li><b>Enabled + blank credential + default profile</b> → fail-fast (Railway runs the default
 *       profile; no "prod" profile exists). Only the {@code local} profile degrades to the no-op.</li>
 *   <li><b>Enabled + non-blank but invalid credential</b> → fail-fast on the
 *       Base64/credential-parse seam (no network reached).</li>
 * </ul>
 *
 * The config is instantiated directly and its {@code @Value} fields are set via
 * reflection — deliberately NOT a {@code @SpringBootTest}, which would be wasteful
 * for a single conditionally-created bean.
 */
@DisplayName("FirebaseConfig — disabled no-op + non-local fail-fast contract")
class FirebaseConfigTest {

    private FirebaseConfig newConfig(boolean enabled, String serviceAccountJson, String... activeProfiles) {
        FirebaseConfig config = new FirebaseConfig();
        ReflectionTestUtils.setField(config, "firebaseEnabled", enabled);
        ReflectionTestUtils.setField(config, "serviceAccountJson", serviceAccountJson);

        MockEnvironment environment = new MockEnvironment();
        if (activeProfiles.length > 0) {
            environment.setActiveProfiles(activeProfiles);
        }
        ReflectionTestUtils.setField(config, "environment", environment);
        return config;
    }

    @Test
    @DisplayName("disabled → returns a no-op sender that yields \"no-op\" and never throws")
    void should_returnNoOpSender_when_firebaseDisabled() throws Exception {
        FirebaseConfig config = newConfig(false, "", "prod");

        FirebaseSender sender = config.firebaseSender();

        assertThat(sender)
                .as("disabled config must still expose a sender bean")
                .isNotNull();
        assertThat(sender.send(Message.builder().setToken("device-token").build()))
                .as("no-op sender must report the sentinel result, not attempt delivery")
                .isEqualTo("no-op");
    }

    @Test
    @DisplayName("enabled + blank credential + non-local profile → fail-fast IllegalStateException")
    void should_failFast_when_enabledButCredentialBlankInProd() {
        FirebaseConfig config = newConfig(true, "  ", "prod");

        assertThatThrownBy(config::firebaseSender)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIREBASE_SERVICE_ACCOUNT is not configured");
    }

    @Test
    @DisplayName("enabled + blank credential + local profile → degrades to no-op, no throw")
    void should_returnNoOpSender_when_enabledButCredentialBlankOutsideProd() throws Exception {
        FirebaseConfig config = newConfig(true, "", "local");

        FirebaseSender sender = config.firebaseSender();

        assertThat(sender.send(Message.builder().setToken("device-token").build()))
                .as("local profile must degrade gracefully rather than fail-fast")
                .isEqualTo("no-op");
    }

    @Test
    @DisplayName("enabled + invalid credential → fail-fast on credential parse, no network")
    void should_failFast_when_enabledWithInvalidCredential() {
        // Non-blank but not a valid Base64-encoded service-account JSON: must blow
        // up at the Base64/GoogleCredentials parse seam before any Firebase init.
        FirebaseConfig config = newConfig(true, "this-is-not-a-service-account-json", "prod");

        assertThatThrownBy(config::firebaseSender)
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("enabled + malformed credential + local profile → no-op sender, boot not blocked")
    void should_returnNoOpSender_when_credentialMalformedInLocalProfile() throws Exception {
        FirebaseConfig config = newConfig(true, "this-is-not-a-service-account-json", "local");

        FirebaseSender sender = config.firebaseSender();

        assertThat(sender.send(Message.builder().setToken("device-token").build()))
                .as("local profile must degrade to the no-op sender on an unparsable credential")
                .isEqualTo("no-op");
    }

    @Test
    @DisplayName("enabled + valid base64 but non-JSON credential + no profile (default) → fails hard")
    void should_failFast_when_credentialMalformedInDefaultProfile() {
        String notJson = java.util.Base64.getEncoder().encodeToString("{not json".getBytes());
        FirebaseConfig config = newConfig(true, notJson);

        assertThatThrownBy(config::firebaseSender).isInstanceOf(Exception.class);
    }

    @AfterEach
    void cleanFirebaseApps() {
        FirebaseApp.getApps().forEach(FirebaseApp::delete);
    }

    @Test
    @DisplayName("enabled + blank credential + default profile → fail-fast (Railway activates no profile)")
    void should_failFast_when_enabledAndCredentialBlankInDefaultProfile() {
        FirebaseConfig config = newConfig(true, "  ");

        assertThatThrownBy(config::firebaseSender)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIREBASE_SERVICE_ACCOUNT is not configured");
    }

    @Test
    @DisplayName("disabled + default profile → no-op, never reaches the credential check")
    void should_returnNoOpSender_when_disabledInDefaultProfile() throws Exception {
        FirebaseConfig config = newConfig(false, "", "test");

        assertThat(config.firebaseSender().send(Message.builder().setToken("t").build())).isEqualTo("no-op");
    }

    @Test
    @DisplayName("enabled + valid credential + FirebaseApp init failure + default profile → fails hard")
    void should_failFast_when_initializeAppFailsInDefaultProfile() throws Exception {
        FirebaseConfig config = failingInitConfig();
        ReflectionTestUtils.setField(config, "serviceAccountJson", serviceAccountBase64());

        assertThatThrownBy(config::firebaseSender).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("enabled + valid credential + FirebaseApp init failure + local profile → no-op")
    void should_returnNoOpSender_when_initializeAppFailsInLocalProfile() throws Exception {
        FirebaseConfig config = failingInitConfig();
        ReflectionTestUtils.setField(config, "serviceAccountJson", serviceAccountBase64());
        ((MockEnvironment) ReflectionTestUtils.getField(config, "environment")).setActiveProfiles("local");

        assertThat(config.firebaseSender().send(Message.builder().setToken("t").build())).isEqualTo("no-op");
    }

    @Test
    @DisplayName("destroy() deletes the FirebaseApp this config created")
    void should_deleteOwnedFirebaseApp_when_contextClosed() throws Exception {
        FirebaseConfig config = newConfig(true, serviceAccountBase64());
        config.firebaseSender();
        assertThat(FirebaseApp.getApps()).hasSize(1);

        config.destroy();

        assertThat(FirebaseApp.getApps()).isEmpty();
    }

    @Test
    @DisplayName("destroy() leaves a pre-existing FirebaseApp it only reused")
    void should_keepForeignFirebaseApp_when_contextClosed() throws Exception {
        FirebaseApp.initializeApp(FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.newBuilder().build())
                .setProjectId("test-project")
                .build());
        FirebaseConfig config = newConfig(true, serviceAccountBase64());
        config.firebaseSender();

        config.destroy();

        assertThat(FirebaseApp.getApps()).hasSize(1);
    }

    private FirebaseConfig failingInitConfig() {
        FirebaseConfig config = new FirebaseConfig() {
            @Override
            FirebaseApp initializeApp(GoogleCredentials credentials) {
                throw new IllegalStateException("boom");
            }
        };
        ReflectionTestUtils.setField(config, "firebaseEnabled", true);
        ReflectionTestUtils.setField(config, "environment", new MockEnvironment());
        return config;
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
        return Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
