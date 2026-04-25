package com.beautica.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecureTokenGenerator — unit")
class SecureTokenGeneratorTest {

    private SecureTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        tokenGenerator = new SecureTokenGenerator();
    }

    @Test
    @DisplayName("generateToken produces a non-null, non-blank value")
    void should_returnNonBlankToken_when_generated() {
        var token = tokenGenerator.generateToken();

        assertThat(token).isNotNull();
        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("generateToken produces unique values across successive calls")
    void should_returnUniqueTokens_when_calledMultipleTimes() {
        var first = tokenGenerator.generateToken();
        var second = tokenGenerator.generateToken();
        var third = tokenGenerator.generateToken();

        assertThat(first).isNotEqualTo(second);
        assertThat(first).isNotEqualTo(third);
        assertThat(second).isNotEqualTo(third);
    }

    @Test
    @DisplayName("generateToken output contains only base64url-safe characters (no +, /, or =)")
    void should_containOnlyBase64UrlChars_when_generated() {
        var token = tokenGenerator.generateToken();

        assertThat(token).doesNotContain("+", "/", "=");
    }

    @Test
    @DisplayName("hash produces the same output for the same input")
    void should_beDeterministic_when_hashCalledWithSameInput() {
        var raw = "some-raw-token-value";

        var first = tokenGenerator.hash(raw);
        var second = tokenGenerator.hash(raw);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("hash produces different outputs for different inputs")
    void should_produceDifferentHashes_when_inputsDiffer() {
        var hashA = tokenGenerator.hash("token-alpha");
        var hashB = tokenGenerator.hash("token-beta");

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("hash output is exactly 64 lowercase hex characters (SHA-256)")
    void should_return64HexChars_when_hashed() {
        var hash = tokenGenerator.hash("any-input-value");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
