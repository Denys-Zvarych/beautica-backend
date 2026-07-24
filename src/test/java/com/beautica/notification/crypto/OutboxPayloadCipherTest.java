package com.beautica.notification.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutboxPayloadCipher — unit")
class OutboxPayloadCipherTest {

    private static final String VERSION_PREFIX = "v1:";

    private OutboxPayloadCipher cipher;

    @BeforeEach
    void setUp() {
        // 32 random bytes → Base64. Fresh per test so no value leaks across cases.
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String b64Key = Base64.getEncoder().encodeToString(keyBytes);

        cipher = new OutboxPayloadCipher(new OutboxCipherProperties(b64Key));
    }

    @Test
    @DisplayName("should_roundTrip_when_plaintextIsAsciiUtf8UrlEmptyOrLarge")
    void should_roundTrip_when_plaintextIsAsciiUtf8UrlEmptyOrLarge() {
        String ascii = "hello-world";
        String utf8 = "Привіт, світе! 🌻";
        String url = "https://app.beautica.example/invites/accept?token=abcDEF123456";
        String empty = "";
        String large = "x".repeat(2048); // 2KB upper bound called out in the architect's plan

        assertThat(cipher.open(cipher.seal(ascii))).isEqualTo(ascii);
        assertThat(cipher.open(cipher.seal(utf8))).isEqualTo(utf8);
        assertThat(cipher.open(cipher.seal(url))).isEqualTo(url);
        assertThat(cipher.open(cipher.seal(empty))).isEqualTo(empty);
        assertThat(cipher.open(cipher.seal(large))).isEqualTo(large);
    }

    @Test
    @DisplayName("should_throwIllegalStateException_when_ciphertextIsTampered")
    void should_throwIllegalStateException_when_ciphertextIsTampered() {
        String sealed = cipher.seal("sensitive-invite-url");

        // Flip a single byte in the Base64 body to simulate tampering.
        String b64 = sealed.substring(VERSION_PREFIX.length());
        byte[] raw = Base64.getDecoder().decode(b64);
        // Modify a byte well inside the ciphertext (past the 12-byte IV).
        raw[raw.length - 1] = (byte) (raw[raw.length - 1] ^ 0x01);
        String tampered = VERSION_PREFIX + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.open(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt or tampered");
    }

    @Test
    @DisplayName("should_produceDifferentCiphertexts_when_sealingSamePlaintextTwice")
    void should_produceDifferentCiphertexts_when_sealingSamePlaintextTwice() {
        String plaintext = "https://app.beautica.example/invites/accept?token=stable";

        String first = cipher.seal(plaintext);
        String second = cipher.seal(plaintext);

        assertThat(first).isNotEqualTo(second);                 // IV randomness
        assertThat(cipher.open(first)).isEqualTo(plaintext);    // both still decrypt
        assertThat(cipher.open(second)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("should_keepCiphertextWithinBoundedOverhead_when_sealingPlaintext")
    void should_keepCiphertextWithinBoundedOverhead_when_sealingPlaintext() {
        // The architect's plan caps ciphertext length at ≤ 2.7× plaintext length.
        // For non-trivial plaintext (≥ a few hundred bytes), GCM adds IV+tag (28 bytes
        // raw) plus Base64 overhead (~4/3) plus the "v1:" prefix — well under 2.7×.
        String plaintext = "x".repeat(512);

        String sealed = cipher.seal(plaintext);

        assertThat(sealed.length()).isLessThanOrEqualTo((int) (plaintext.length() * 2.7));
        assertThat(sealed).startsWith(VERSION_PREFIX);
    }

    @Test
    @DisplayName("should_throwIllegalStateException_when_keyDecodesToWrongLength")
    void should_throwIllegalStateException_when_keyDecodesToWrongLength() {
        // 24 bytes → AES-192 territory; cipher demands AES-256.
        String shortKey = Base64.getEncoder().encodeToString(new byte[24]);

        assertThatThrownBy(() -> new OutboxPayloadCipher(new OutboxCipherProperties(shortKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    // ── E4: edge cases ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_throwIllegalStateException_when_openReceivesUnknownVersionPrefix")
    void should_throwIllegalStateException_when_openReceivesUnknownVersionPrefix() {
        // "v2:" prefix is unrecognised — open() must reject it.
        String unknownPrefix = "v2:" + Base64.getEncoder().encodeToString(new byte[32]);

        assertThatThrownBy(() -> cipher.open(unknownPrefix))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should_throwIllegalStateException_when_openReceivesMalformedBase64")
    void should_throwIllegalStateException_when_openReceivesMalformedBase64() {
        // Valid prefix but body contains characters illegal in standard Base64.
        String malformedBase64 = "v1:not-valid-base64@@@";

        assertThatThrownBy(() -> cipher.open(malformedBase64))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should_throwIllegalStateException_when_openReceivesTruncatedPayload")
    void should_throwIllegalStateException_when_openReceivesTruncatedPayload() {
        // 5 bytes is below IV_LENGTH_BYTES (12) + TAG_LENGTH_BYTES (16) = 28 minimum.
        String truncated = "v1:" + Base64.getEncoder().encodeToString(new byte[5]);

        assertThatThrownBy(() -> cipher.open(truncated))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should_throwIllegalStateException_when_openWithWrongKey")
    void should_throwIllegalStateException_when_openWithWrongKey() {
        // Seal with `cipher` (key A), try to open with `cipher2` (key B) → GCM tag fails.
        byte[] differentKeyBytes = new byte[32];
        // Flip every bit so the key is definitely different.
        new java.security.SecureRandom().nextBytes(differentKeyBytes);
        String differentB64Key = Base64.getEncoder().encodeToString(differentKeyBytes);
        OutboxPayloadCipher cipher2 = new OutboxPayloadCipher(new OutboxCipherProperties(differentB64Key));

        String sealed = cipher.seal("sensitive-data");

        assertThatThrownBy(() -> cipher2.open(sealed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("OutboxPayloadCipher constructor — throws IllegalStateException when key property is null")
    void should_throwWhenKeyIsNull() {
        assertThatThrownBy(() -> new OutboxPayloadCipher(new OutboxCipherProperties(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be provided");
    }

    @Test
    @DisplayName("OutboxPayloadCipher constructor — throws IllegalStateException when key is blank")
    void should_throwIllegalStateException_when_keyIsBlank() {
        assertThatThrownBy(() -> new OutboxPayloadCipher(new OutboxCipherProperties("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be provided");
    }

    @Test
    @DisplayName("should_throwIllegalStateException_when_sealReceivesNull (seal(null) → IllegalStateException)")
    void should_throwException_when_sealReceivesNull() {
        // seal(null) throws IllegalStateException("plaintext must not be null") per production code.
        assertThatThrownBy(() -> cipher.seal(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plaintext must not be null");
    }

    @Test
    @DisplayName("should_throwIllegalStateException_when_openReceivesNull (open(null) → IllegalStateException)")
    void should_throwException_when_openReceivesNull() {
        // open(null) fails the startsWith check → IllegalStateException("unrecognised version prefix").
        assertThatThrownBy(() -> cipher.open(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
