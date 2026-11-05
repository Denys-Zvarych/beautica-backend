package com.beautica.booking.crypto;

import com.beautica.booking.domain.GuestCancelToken;
import com.beautica.booking.crypto.GuestCancelTokenCipher.Row;
import com.beautica.notification.crypto.OutboxPayloadCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GuestCancelTokenCipher — phase 435 reversible guest cancel token (AES-256-GCM, dedicated key)")
class GuestCancelTokenCipherTest {

    private static final UUID TOKEN = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID ROW_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final Row ROW = Row.booking(ROW_ID);
    private static final String OTHER_KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";
    /** The development key committed in application-local.yml. */
    private static final String DEV_KEY = "XWr8dDi+di4G1u+XHkTbsBdNmx/OGugrJzLpKJ3T4+E=";
    /** The V230 format CHECK (audit-fix form). */
    private static final String V230_FORMAT = "^v2:[0-9a-f]{8}:[A-Za-z0-9+/]+={0,2}$";

    private final GuestCancelTokenCipher cipher = GuestCancelTokenCipherFixtures.testCipher();

    @Test
    @DisplayName("seal → open round-trips the token; the sealed form is v2:<kid>:, fits the V230 column and hides the token")
    void should_roundTripToken_when_sealedThenOpened() {
        // Act
        String sealed = cipher.seal(TOKEN, ROW);

        // Assert
        assertThat(cipher.open(sealed, ROW)).isEqualTo(GuestCancelToken.of(TOKEN));
        assertThat(sealed).matches("^v2:[0-9a-f]{8}:[A-Za-z0-9+/]+={0,2}$").matches(V230_FORMAT)
                .doesNotContain(TOKEN.toString());
        assertThat(sealed.length()).isLessThanOrEqualTo(128);
    }

    @Test
    @DisplayName("a fresh IV per call: sealing the same token twice gives different values")
    void should_produceDifferentCiphertexts_when_sameTokenSealedTwice() {
        // Act + Assert
        assertThat(cipher.seal(TOKEN, ROW)).isNotEqualTo(cipher.seal(TOKEN, ROW));
    }

    @Test
    @DisplayName("audit-fix: a value moved to another row (other id, or other table with the same id) does not open")
    void should_failOpen_when_sealedValueMovedToOtherRow() {
        // Arrange
        String sealed = cipher.seal(TOKEN, ROW);

        // Act + Assert
        assertThatThrownBy(() -> cipher.open(sealed, Row.booking(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN.toString()).doesNotContain(sealed));
        assertThatThrownBy(() -> cipher.open(sealed, Row.appointment(ROW_ID)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("audit-fix: the row id must be assigned — sealing for an unsaved row is refused")
    void should_refuseSeal_when_rowIdMissing() {
        // Act + Assert
        assertThatThrownBy(() -> cipher.seal(TOKEN, Row.booking(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("row id must be assigned");
    }

    @Test
    @DisplayName("audit-fix rotation: a value sealed under the old key opens while it is configured as key-previous")
    void should_openOldValue_when_oldKeyIsPrevious() {
        // Arrange
        String sealedUnderOld = new GuestCancelTokenCipher(new GuestCancelTokenCipherProperties(OTHER_KEY))
                .seal(TOKEN, ROW);
        GuestCancelTokenCipher rotated = new GuestCancelTokenCipher(
                new GuestCancelTokenCipherProperties(GuestCancelTokenCipherFixtures.TEST_KEY, OTHER_KEY));

        // Act + Assert
        assertThat(rotated.open(sealedUnderOld, ROW)).isEqualTo(GuestCancelToken.of(TOKEN));
        assertThat(rotated.open(rotated.seal(TOKEN, ROW), ROW)).isEqualTo(GuestCancelToken.of(TOKEN));
        assertThat(rotated.seal(TOKEN, ROW)).as("new values use the CURRENT key's id")
                .startsWith(cipher.seal(TOKEN, ROW).substring(0, 12));
    }

    @Test
    @DisplayName("audit-fix rotation: key-previous is decrypt-only — the same key twice fails boot")
    void should_failFast_when_previousKeyEqualsCurrent() {
        // Act + Assert
        assertThatThrownBy(() -> new GuestCancelTokenCipher(new GuestCancelTokenCipherProperties(
                GuestCancelTokenCipherFixtures.TEST_KEY, GuestCancelTokenCipherFixtures.TEST_KEY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.guest-cancel-token.key-previous must differ");
    }

    @Test
    @DisplayName("audit-fix cycle 2: an unbound v1: value is rejected under the current and previous key and fails the V230 CHECK")
    void should_rejectV1_when_unboundValuePresented() {
        // Arrange
        String v1Current = OutboxPayloadCipher.withKey(GuestCancelTokenCipherFixtures.TEST_KEY, "k")
                .seal(TOKEN.toString());
        String v1Old = OutboxPayloadCipher.withKey(OTHER_KEY, "k").seal(TOKEN.toString());
        GuestCancelTokenCipher rotated = new GuestCancelTokenCipher(
                new GuestCancelTokenCipherProperties(GuestCancelTokenCipherFixtures.TEST_KEY, OTHER_KEY));

        // Act + Assert
        assertThat(v1Current).doesNotMatch(V230_FORMAT);
        assertThatThrownBy(() -> cipher.open(v1Current, ROW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unrecognised version prefix")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN.toString()).doesNotContain(v1Current));
        assertThatThrownBy(() -> rotated.open(v1Old, ROW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unrecognised version prefix");
    }

    @Test
    @DisplayName("a value sealed under another key does not open, and the message names neither token nor value")
    void should_throwWithoutLeaking_when_sealedUnderAnotherKey() {
        // Arrange
        String foreign = new GuestCancelTokenCipher(new GuestCancelTokenCipherProperties(OTHER_KEY)).seal(TOKEN, ROW);

        // Act + Assert
        assertThatThrownBy(() -> cipher.open(foreign, ROW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown key id")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN.toString()).doesNotContain(foreign));
    }

    @Test
    @DisplayName("a tampered body under a known key id does not open")
    void should_throw_when_bodyTampered() {
        // Arrange
        String sealed = cipher.seal(TOKEN, ROW);
        int at = 40; // inside the ciphertext, never a Base64 padding position
        String tampered = sealed.substring(0, at) + (sealed.charAt(at) == 'A' ? 'B' : 'A') + sealed.substring(at + 1);

        // Act + Assert
        assertThatThrownBy(() -> cipher.open(tampered, ROW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the outbox key does not open a guest-token value (dedicated key, no cross-use)")
    void should_notOpen_when_outboxCipherUsed() {
        // Arrange
        OutboxPayloadCipher outbox = OutboxPayloadCipher.withKey(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.outbox.payload-key");

        // Act + Assert
        assertThatThrownBy(() -> outbox.open(cipher.seal(TOKEN, ROW))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a non-UUID plaintext under the right key is rejected without echoing it")
    void should_throw_when_plaintextIsNotAToken() {
        // Arrange
        String sealedJunk = OutboxPayloadCipher.withKey(GuestCancelTokenCipherFixtures.TEST_KEY, "k").seal("not-a-token");

        // Act + Assert
        assertThatThrownBy(() -> cipher.open(sealedJunk, ROW))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("not-a-token"));
    }

    @Test
    @DisplayName("an unknown version prefix is refused")
    void should_throw_when_versionUnknown() {
        // Act + Assert
        assertThatThrownBy(() -> cipher.open("v9:abc", ROW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unrecognised version prefix");
    }

    @Test
    @DisplayName("boot fails fast on a blank key, naming the property")
    void should_failFast_when_keyBlank() {
        // Act + Assert
        assertThatThrownBy(() -> new GuestCancelTokenCipher(new GuestCancelTokenCipherProperties(" ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.guest-cancel-token.key must be provided");
    }

    @Test
    @DisplayName("boot fails fast on a key that is not 32 bytes")
    void should_failFast_when_keyWrongLength() {
        // Act + Assert
        assertThatThrownBy(() -> new GuestCancelTokenCipher(new GuestCancelTokenCipherProperties("AAAA")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.guest-cancel-token.key must decode to exactly 32 bytes");
    }

    @Test
    @DisplayName("boot fails fast on a malformed previous key, naming that property")
    void should_failFast_when_previousKeyMalformed() {
        // Act + Assert
        assertThatThrownBy(() -> new GuestCancelTokenCipher(
                new GuestCancelTokenCipherProperties(GuestCancelTokenCipherFixtures.TEST_KEY, "AAAA")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.guest-cancel-token.key-previous must decode to exactly 32 bytes");
    }

    @Test
    @DisplayName("boot fails fast on malformed Base64")
    void should_failFast_when_keyNotBase64() {
        // Act + Assert
        assertThatThrownBy(() -> new GuestCancelTokenCipher(new GuestCancelTokenCipherProperties("!!not base64!!")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be valid Base64");
    }

    @Test
    @DisplayName("the properties' toString never prints either key")
    void should_redactKey_when_propertiesPrinted() {
        // Act + Assert
        assertThat(new GuestCancelTokenCipherProperties(OTHER_KEY, GuestCancelTokenCipherFixtures.TEST_KEY).toString())
                .doesNotContain(OTHER_KEY).doesNotContain(GuestCancelTokenCipherFixtures.TEST_KEY);
    }

    @Test
    @DisplayName("the dev guard refuses the committed local dev key and accepts any other")
    void should_refuseDevKey_when_guardChecks() {
        // Act + Assert
        assertThatThrownBy(() -> GuestCancelTokenKeyDevGuard.assertNotDevKey(DEV_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GUEST_CANCEL_TOKEN_KEY is the development default");
        GuestCancelTokenKeyDevGuard.assertNotDevKey(OTHER_KEY);
    }

    @Test
    @DisplayName("audit-fix cycle 2: the dev guard also refuses the committed dev key as key-previous")
    void should_refuseDevKey_when_configuredAsPreviousKey() {
        // Arrange
        GuestCancelTokenKeyDevGuard guard = new GuestCancelTokenKeyDevGuard(
                new GuestCancelTokenCipherProperties(OTHER_KEY, DEV_KEY));
        GuestCancelTokenKeyDevGuard clean = new GuestCancelTokenKeyDevGuard(
                new GuestCancelTokenCipherProperties(OTHER_KEY, GuestCancelTokenCipherFixtures.TEST_KEY));

        // Act + Assert
        assertThatThrownBy(guard::assertNotDevKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GUEST_CANCEL_TOKEN_KEY_PREVIOUS is the development default")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(DEV_KEY));
        clean.assertNotDevKey();
    }
}
