package com.beautica.booking.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GuestCancelTokenKeyGuard — phase 435 audit-fix: guest cancel-token key independent of the outbox key")
class GuestCancelTokenKeyGuardTest {

    private static final String OUTBOX_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String GUEST_KEY = GuestCancelTokenCipherFixtures.TEST_KEY;

    @Test
    @DisplayName("boot fails when GUEST_CANCEL_TOKEN_KEY equals OUTBOX_PAYLOAD_KEY, without printing either")
    void should_failBoot_when_guestKeyEqualsOutboxKey() {
        // Act + Assert
        assertThatThrownBy(() -> GuestCancelTokenKeyGuard.assertIndependent(OUTBOX_KEY, null, OUTBOX_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GUEST_CANCEL_TOKEN_KEY must not equal OUTBOX_PAYLOAD_KEY")
                .hasMessageNotContaining(OUTBOX_KEY);
    }

    @Test
    @DisplayName("the comparison is on DECODED bytes: an unpadded spelling of the same key is still caught")
    void should_failBoot_when_sameKeyBytesSpelledDifferently() {
        // Arrange — the MIME-tolerant decoder is not used, but Base64 without padding decodes to the same 32 bytes
        String unpadded = OUTBOX_KEY.substring(0, OUTBOX_KEY.length() - 1);

        // Act + Assert
        assertThatThrownBy(() -> GuestCancelTokenKeyGuard.assertIndependent(unpadded, null, OUTBOX_KEY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("boot fails when the decrypt-only previous key equals OUTBOX_PAYLOAD_KEY")
    void should_failBoot_when_previousKeyEqualsOutboxKey() {
        // Act + Assert
        assertThatThrownBy(() -> GuestCancelTokenKeyGuard.assertIndependent(GUEST_KEY, OUTBOX_KEY, OUTBOX_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GUEST_CANCEL_TOKEN_KEY_PREVIOUS must not equal OUTBOX_PAYLOAD_KEY");
    }

    @Test
    @DisplayName("distinct keys (the test profile's pair) boot; a missing outbox key is left to its own cipher")
    void should_pass_when_keysDiffer() {
        // Act + Assert
        assertThatCode(() -> GuestCancelTokenKeyGuard.assertIndependent(GUEST_KEY, null, OUTBOX_KEY))
                .doesNotThrowAnyException();
        assertThatCode(() -> GuestCancelTokenKeyGuard.assertIndependent(GUEST_KEY, " ", null))
                .doesNotThrowAnyException();
    }
}
