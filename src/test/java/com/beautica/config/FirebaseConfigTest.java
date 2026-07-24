package com.beautica.config;

import com.beautica.config.FirebaseConfig.FirebaseSender;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the AS-BUILT {@link FirebaseConfig#firebaseSender()} contract
 * (QA LOW — config/*ConfigTest row: no-op when disabled + prod fail-fast).
 *
 * <p>The bean has three documented branches, all exercised here WITHOUT a real
 * Firebase credential and WITHOUT any network call:
 * <ul>
 *   <li><b>Disabled</b> ({@code FIREBASE_ENABLED=false}) → returns a no-op
 *       sender that yields {@code "no-op"}; no {@code FirebaseApp} init, no throw.
 *       Holds regardless of active profile.</li>
 *   <li><b>Enabled + blank credential + prod profile</b> → fail-fast with
 *       {@link IllegalStateException} so the app refuses to start mis-configured.</li>
 *   <li><b>Enabled + blank credential + non-prod profile</b> → degrades to the
 *       no-op sender (the {@code firebaseEnabled && isProd} guard is false).</li>
 *   <li><b>Enabled + non-blank but invalid credential</b> → fail-fast on the
 *       Base64/credential-parse seam (no network reached).</li>
 * </ul>
 *
 * The config is instantiated directly and its {@code @Value} fields are set via
 * reflection — deliberately NOT a {@code @SpringBootTest}, which would be wasteful
 * for a single conditionally-created bean.
 */
@DisplayName("FirebaseConfig — disabled no-op + prod fail-fast contract")
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
    @DisplayName("enabled + blank credential + prod profile → fail-fast IllegalStateException")
    void should_failFast_when_enabledButCredentialBlankInProd() {
        FirebaseConfig config = newConfig(true, "  ", "prod");

        assertThatThrownBy(config::firebaseSender)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIREBASE_SERVICE_ACCOUNT is not configured");
    }

    @Test
    @DisplayName("enabled + blank credential + non-prod profile → degrades to no-op, no throw")
    void should_returnNoOpSender_when_enabledButCredentialBlankOutsideProd() throws Exception {
        FirebaseConfig config = newConfig(true, "", "local");

        FirebaseSender sender = config.firebaseSender();

        assertThat(sender.send(Message.builder().setToken("device-token").build()))
                .as("non-prod must degrade gracefully rather than fail-fast")
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
}
