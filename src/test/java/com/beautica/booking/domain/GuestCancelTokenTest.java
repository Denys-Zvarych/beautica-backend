package com.beautica.booking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GuestCancelToken — phase 435 follow-up: short base62 tokens, legacy UUID tokens still accepted")
class GuestCancelTokenTest {

    private static final String LEGACY = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    @DisplayName("a generated token is 16 base62 chars and never repeats over 10k draws")
    void should_generateSixteenBase62Chars_when_generated() {
        // Arrange
        Set<String> seen = new HashSet<>();

        // Act
        for (int i = 0; i < 10_000; i++) {
            seen.add(GuestCancelToken.generate().value());
        }

        // Assert
        assertThat(seen).hasSize(10_000).allMatch(value -> value.matches("[0-9A-Za-z]{16}"));
    }

    @Test
    @DisplayName("generated tokens use the WHOLE base62 alphabet, near-uniformly (no shrunk alphabet, no modulo bias)")
    void should_drawEveryBase62CharUniformly_when_generatedManyTimes() {
        // Arrange
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        int[] counts = new int[128];
        int draws = 10_000;

        // Act
        for (int i = 0; i < draws; i++) {
            for (char c : GuestCancelToken.generate().value().toCharArray()) {
                counts[c]++;
            }
        }

        // Assert — 160k chars → ~2581 per symbol, binomial sd ≈ 50; the band is ±~10 sd
        int expected = draws * GuestCancelToken.SHORT_LENGTH / alphabet.length();
        for (char c : alphabet.toCharArray()) {
            assertThat(counts[c]).as("count of '%s' (expected ≈ %d)", c, expected)
                    .isBetween(expected - 500, expected + 500);
        }
    }

    @Test
    @DisplayName("a generated token is short-form, not legacy")
    void should_notBeLegacy_when_generated() {
        // Act + Assert
        assertThat(GuestCancelToken.generate().isLegacy()).isFalse();
    }

    @Test
    @DisplayName("a short token parses exactly as given (case-sensitive)")
    void should_keepValue_when_shortTokenParsed() {
        // Act
        GuestCancelToken token = GuestCancelToken.valueOf("Ab3dE5gH7jK9mN1p");

        // Assert
        assertThat(token.value()).isEqualTo("Ab3dE5gH7jK9mN1p");
        assertThat(token).isNotEqualTo(GuestCancelToken.valueOf("ab3dE5gH7jK9mN1p"));
    }

    @Test
    @DisplayName("a legacy UUID parses to its canonical lower-case form, so its stored hash still matches")
    void should_canonicaliseUuid_when_legacyTokenParsed() {
        // Act
        GuestCancelToken token = GuestCancelToken.valueOf(LEGACY.toUpperCase());

        // Assert
        assertThat(token.value()).isEqualTo(LEGACY);
        assertThat(token.isLegacy()).isTrue();
        assertThat(CancelTokenHasher.hash(token)).isEqualTo(CancelTokenHasher.hash(UUID.fromString(LEGACY)));
    }

    @Test
    @DisplayName("the short-token hash is SHA-256 of its raw 16 chars")
    void should_hashRawString_when_shortToken() {
        // Act
        String hash = CancelTokenHasher.hash(GuestCancelToken.valueOf("Ab3dE5gH7jK9mN1p"));

        // Assert — the preimage is the raw string, nothing else
        assertThat(hash).hasSize(64).isEqualTo(sha256Hex("Ab3dE5gH7jK9mN1p"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "Ab3dE5gH7jK9mN1", "Ab3dE5gH7jK9mN1pq", "Ab3dE5gH7jK9mN1_",
            "Ab3dE5gH7jK9mN1-", "not-a-uuid", "123e4567-e89b-12d3-a456-4266141740000", "../../etc/passwd"})
    @DisplayName("anything else is rejected without echoing the value")
    void should_reject_when_malformed(String raw) {
        // Act + Assert
        assertThat(GuestCancelToken.parse(raw)).isEmpty();
        assertThatThrownBy(() -> GuestCancelToken.valueOf(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Malformed cancel token");
    }

    @Test
    @DisplayName("toString never shows the raw token")
    void should_redact_when_toString() {
        // Act + Assert
        assertThat(GuestCancelToken.valueOf("Ab3dE5gH7jK9mN1p").toString()).doesNotContain("Ab3dE5gH7jK9mN1p");
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
