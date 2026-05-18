package com.beautica.auth;

import com.beautica.config.OtpPepperConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecureTokenGenerator — unit")
class SecureTokenGeneratorTest {

    // Fixed 32+ char test pepper — the real HMAC key path exercises misuse.
    private static final String TEST_PEPPER = "unit-test-otp-pepper-min-32-characters!";

    private SecureTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        tokenGenerator = new SecureTokenGenerator(new OtpPepperConfig(TEST_PEPPER));
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

    @Test
    @DisplayName("hashOtp is deterministic for the same OTP under the same pepper")
    void should_beDeterministic_when_hashOtpCalledWithSameInput() {
        var first = tokenGenerator.hashOtp("123456");
        var second = tokenGenerator.hashOtp("123456");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("hashOtp output is exactly 64 lowercase hex characters (HMAC-SHA256)")
    void should_return64HexChars_when_hashOtpCalled() {
        var hash = tokenGenerator.hashOtp("000000");

        assertThat(hash).hasSize(64);
        assertThat(hash)
                .as("HMAC-SHA256 hex must satisfy the V49 chk_verification_code_hash_format constraint")
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("hashOtp is keyed — output differs from the unkeyed hash() for the same input")
    void should_differFromUnkeyedHash_when_sameOtpInput() {
        var keyed = tokenGenerator.hashOtp("424242");
        var unkeyed = tokenGenerator.hash("424242");

        assertThat(keyed)
                .as("a keyed HMAC must not equal the bare SHA-256 of the same value")
                .isNotEqualTo(unkeyed);
    }

    @Test
    @DisplayName("hashOtp output depends on the pepper — different pepper yields a different digest")
    void should_produceDifferentDigest_when_pepperDiffers() {
        var otherGenerator = new SecureTokenGenerator(
                new OtpPepperConfig("a-completely-different-pepper-min-32-chars"));

        var digestA = tokenGenerator.hashOtp("987654");
        var digestB = otherGenerator.hashOtp("987654");

        assertThat(digestA)
                .as("a leaked digest must be useless without the exact server pepper")
                .isNotEqualTo(digestB);
    }

    @Test
    @DisplayName("generateOtp produces a 6-digit zero-padded numeric string")
    void should_generateSixDigitNumericCode_when_generateOtpCalled() {
        for (int i = 0; i < 100; i++) {
            var otp = tokenGenerator.generateOtp();
            assertThat(otp).matches("^\\d{6}$");
        }
    }

    @Test
    @DisplayName("generateOtp produces high distinctness across 1000 calls")
    void should_generateDifferentCodes_when_generateOtpCalledRepeatedly() {
        var codes = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(i -> tokenGenerator.generateOtp())
                .collect(java.util.stream.Collectors.toSet());
        // 1000 calls with 1M possible values — collision probability negligible
        assertThat(codes.size()).isGreaterThan(900);
    }
}
