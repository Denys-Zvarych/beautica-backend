package com.beautica.notification.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OutboxKeyDevGuard#assertNotDevKey(String)}.
 *
 * <p>The method under test is called directly (bypassing Spring's {@code @Profile} wiring)
 * so no Spring context is needed here — this is a plain unit test.</p>
 */
@DisplayName("OutboxKeyDevGuard — unit")
class OutboxKeyDevGuardTest {

    /** The dev key committed to .env.local and the one the guard must reject. */
    private static final String DEV_KEY_B64 = "xqXxTzEL0+Xlq/iNHyBgKG/nRxIiKedVmGonbisH2KM=";

    @Test
    @DisplayName("should_throwIllegalStateException_when_keyIsKnownDevDefault")
    void should_throwIllegalStateException_when_keyIsKnownDevDefault() {
        assertThatThrownBy(() -> OutboxKeyDevGuard.assertNotDevKey(DEV_KEY_B64))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OUTBOX_PAYLOAD_KEY is the development default")
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    @DisplayName("should_notThrow_when_keyIsRandomAndDifferentFromDevDefault")
    void should_notThrow_when_keyIsRandomAndDifferentFromDevDefault() {
        byte[] randomKeyBytes = new byte[32];
        new SecureRandom().nextBytes(randomKeyBytes);
        String randomB64Key = Base64.getEncoder().encodeToString(randomKeyBytes);

        assertThatCode(() -> OutboxKeyDevGuard.assertNotDevKey(randomB64Key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should_notThrow_when_keyIsMalformedBase64")
    void should_notThrow_when_keyIsMalformedBase64() {
        // Malformed Base64 is rejected by OutboxPayloadCipher's constructor with a clearer
        // message — the guard must not throw here to avoid shadowing that error.
        assertThatCode(() -> OutboxKeyDevGuard.assertNotDevKey("not-valid-base64!!!"))
                .doesNotThrowAnyException();
    }
}
