package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-fast validation for {@link JwtConfig}.
 *
 * <p>The compact constructor validates the UTF-8 byte length of the secret, not the
 * Java {@code char} count.  A secret composed of multi-byte characters may have
 * {@code secret.length() >= 32} while encoding to fewer than 32 bytes, which would
 * produce a weak HS-256 key.  These tests verify that the byte-count check catches
 * such inputs and that legitimate secrets are accepted.
 */
@DisplayName("JwtConfig — fail-fast secret validation")
class JwtConfigTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a JwtConfig with placeholder expiration values — only the secret
     * is under test here.
     */
    private static JwtConfig jwtConfig(String secret) {
        return new JwtConfig(secret, 3_600_000L, 86_400_000L);
    }

    // ── null guard ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_throw_when_secretIsNull")
    void should_throw_when_secretIsNull() {
        assertThatThrownBy(() -> jwtConfig(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwt.secret");
    }

    // ── byte-length checks ────────────────────────────────────────────────────

    @Test
    @DisplayName("should_throw_when_secretHasFewerThan32AsciiBytes")
    void should_throw_when_secretHasFewerThan32AsciiBytes() {
        // 10 ASCII chars → 10 UTF-8 bytes — well below the 32-byte minimum
        String shortSecret = "a".repeat(10);

        assertThat(shortSecret.getBytes(StandardCharsets.UTF_8)).hasSize(10);

        assertThatThrownBy(() -> jwtConfig(shortSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes")
                .hasMessageContaining("10 bytes");
    }

    @Test
    @DisplayName("should_throw_when_secretHas31AsciiBytes")
    void should_throw_when_secretHas31AsciiBytes() {
        // boundary: one byte short
        String secret31 = "a".repeat(31);

        assertThatThrownBy(() -> jwtConfig(secret31))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes")
                .hasMessageContaining("31 bytes");
    }

    /**
     * Key security case: a string whose {@code char} count meets the old threshold
     * of 32 but whose UTF-8 byte count falls below 32 because every character is a
     * 4-byte emoji (U+1F600, "😀" — encodes to 4 bytes in UTF-8).
     *
     * <p>32 chars × 4 bytes/char = 128 bytes, so emoji alone do not produce the
     * under-32-byte case by themselves.  The realistic attack surface is characters
     * in the 2-byte range (e.g. Cyrillic or CJK) where a 16-char string passes the
     * old {@code length() < 32} guard while encoding to only 32 UTF-8 bytes — the
     * boundary is tight.  We test it explicitly: 15 Cyrillic chars × 2 bytes = 30 bytes,
     * {@code length()} returns 15, old guard would wrongly pass length < 32, new guard catches it.
     */
    @Test
    @DisplayName("should_throw_when_multiByteCyrillicSecretIsUnder32Bytes")
    void should_throw_when_multiByteCyrillicSecretIsUnder32Bytes() {
        // Each Cyrillic char is 2 UTF-8 bytes.
        // 15 chars → 15 chars (passes old char-count guard of < 32 — bug!)
        //            30 bytes (fails the correct byte-count guard — fixed!)
        String cyrillicSecret = "п".repeat(15);

        assertThat(cyrillicSecret.length()).isEqualTo(15);
        assertThat(cyrillicSecret.getBytes(StandardCharsets.UTF_8)).hasSize(30);

        assertThatThrownBy(() -> jwtConfig(cyrillicSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes")
                .hasMessageContaining("30 bytes");
    }

    // ── acceptance ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_accept_when_secretIsExactly32AsciiBytesLong")
    void should_accept_when_secretIsExactly32AsciiBytesLong() {
        // 32 ASCII chars → 32 UTF-8 bytes: exactly at the boundary
        String secret32 = "a".repeat(32);

        assertThat(secret32.getBytes(StandardCharsets.UTF_8)).hasSize(32);

        assertThatCode(() -> jwtConfig(secret32))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should_accept_when_secretIsLongerThan32Bytes")
    void should_accept_when_secretIsLongerThan32Bytes() {
        // A realistic production-grade secret: 64 base64 chars, all ASCII, 64 bytes
        String strongSecret = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

        assertThat(strongSecret.getBytes(StandardCharsets.UTF_8)).hasSize(64);

        assertThatCode(() -> jwtConfig(strongSecret))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should_accept_when_multiByteCyrillicSecretMeets32ByteThreshold")
    void should_accept_when_multiByteCyrillicSecretMeets32ByteThreshold() {
        // 16 Cyrillic chars × 2 bytes = 32 UTF-8 bytes — exactly at the boundary
        String cyrillicSecret = "п".repeat(16);

        assertThat(cyrillicSecret.length()).isEqualTo(16);
        assertThat(cyrillicSecret.getBytes(StandardCharsets.UTF_8)).hasSize(32);

        assertThatCode(() -> jwtConfig(cyrillicSecret))
                .doesNotThrowAnyException();
    }

    // ── placeholder guard ─────────────────────────────────────────────────────

    @Test
    @DisplayName("should_throw_when_secretStartsWithReplaceMePlaceholder")
    void should_throw_when_secretStartsWithReplaceMePlaceholder() {
        // Long enough in bytes but still a placeholder
        String placeholder = "REPLACE_ME_with_a_real_secret_that_is_long_enough";

        assertThatThrownBy(() -> jwtConfig(placeholder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }
}
