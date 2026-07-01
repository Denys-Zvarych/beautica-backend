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

    // ─── leading-zero OTP: fixed-width, no zero-strip (QA LOW: zero-padding seeded edges) ──

    @Test
    @DisplayName("hashOtp treats a leading-zero OTP as a 6-char string, NOT a stripped integer (000123 != 123)")
    void should_notStripLeadingZeros_when_hashOtpCalled() {
        // The bug this guards: if any layer coerced the OTP through Integer.parseInt
        // (or similar) before hashing, "000123" would collapse to "123" and the two
        // would hash identically — a zero-padded code generated by the server would
        // then verify against the stripped form a naive client might send.
        var padded = tokenGenerator.hashOtp("000123");
        var stripped = tokenGenerator.hashOtp("123");

        assertThat(padded)
                .as("a leading-zero OTP must be hashed as the literal 6-char string, never as a stripped int")
                .isNotEqualTo(stripped);
    }

    @Test
    @DisplayName("hashOtp is stable for all-zero (000000) and seeded leading-zero edges (001000, 000123)")
    void should_beDeterministicAndDistinct_when_leadingZeroOtps() {
        // Each seeded edge round-trips to itself (no width loss) and the three
        // distinct codes produce three distinct digests.
        assertThat(tokenGenerator.hashOtp("000000")).isEqualTo(tokenGenerator.hashOtp("000000"));
        assertThat(tokenGenerator.hashOtp("000123")).isEqualTo(tokenGenerator.hashOtp("000123"));
        assertThat(tokenGenerator.hashOtp("001000")).isEqualTo(tokenGenerator.hashOtp("001000"));

        assertThat(java.util.Set.of(
                        tokenGenerator.hashOtp("000000"),
                        tokenGenerator.hashOtp("000123"),
                        tokenGenerator.hashOtp("001000")))
                .as("three distinct leading-zero codes must yield three distinct digests")
                .hasSize(3);
    }

    @Test
    @DisplayName("generateOtp always emits exactly 6 chars including leading zeros (codes < 100000 stay zero-padded)")
    void should_alwaysEmitSixChars_when_generateOtpProducesSmallNumber() {
        // %06d is the only thing standing between a small SecureRandom draw (e.g. 123)
        // and a 3-char OTP. Drive enough samples to land in the low-magnitude band and
        // assert the width never collapses. We can't force SecureRandom, so we assert
        // the invariant holds for every sample and that small codes are observed padded.
        boolean sawZeroPadded = false;
        for (int i = 0; i < 5000; i++) {
            var otp = tokenGenerator.generateOtp();
            assertThat(otp)
                    .as("every generated OTP is a fixed-width 6-digit string, actual=%s", otp)
                    .hasSize(6)
                    .matches("^\\d{6}$");
            if (otp.charAt(0) == '0') {
                sawZeroPadded = true;
            }
        }
        // ~10% of the 0..999999 space starts with '0'; across 5000 draws this is
        // effectively certain, proving the padding path is actually exercised.
        assertThat(sawZeroPadded)
                .as("a zero-padded code (leading '0') must appear among 5000 draws")
                .isTrue();
    }

    @Test
    @DisplayName("generateOtp output round-trips through hashOtp and re-hashes to the same digest (no width loss)")
    void should_roundTripGeneratedOtp_when_hashed() {
        // End-to-end on the generator: whatever generateOtp emits, re-hashing the
        // exact emitted string reproduces the digest — proving the value carried
        // through hashOtp is the literal 6-char string, leading zeros included.
        for (int i = 0; i < 1000; i++) {
            var otp = tokenGenerator.generateOtp();
            assertThat(tokenGenerator.hashOtp(otp))
                    .as("re-hashing the exact generated OTP must reproduce its digest, otp=%s", otp)
                    .isEqualTo(tokenGenerator.hashOtp(otp));
            // And the stripped form (if it differs) must produce a different digest.
            var stripped = otp.replaceFirst("^0+", "");
            if (!stripped.equals(otp) && !stripped.isEmpty()) {
                assertThat(tokenGenerator.hashOtp(otp))
                        .as("zero-stripped form must not collide with the padded form, otp=%s", otp)
                        .isNotEqualTo(tokenGenerator.hashOtp(stripped));
            }
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

    // ─── ThreadLocal.remove() correctness ────────────────────────────────────

    @Test
    @DisplayName("hash can be called multiple times on the same thread without error (ThreadLocal is reset between calls)")
    void should_succeedOnRepeatedCalls_when_threadLocalIsCorrectlyReset() {
        // If SHA256.remove() were absent, the MessageDigest would be recycled correctly
        // via md.reset() — but the ThreadLocal entry would accumulate on the Tomcat
        // thread. This test verifies that repeated calls produce consistent results
        // and no exception is thrown, which would happen if the digest were
        // left in a corrupt state.
        var input = "test-token-input";

        var first = tokenGenerator.hash(input);
        var second = tokenGenerator.hash(input);
        var third = tokenGenerator.hash(input);

        assertThat(first).isEqualTo(second);
        assertThat(second).isEqualTo(third);
        assertThat(first).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("hash(empty string) returns the known SHA-256 digest for empty input")
    void should_returnKnownDigest_when_hashCalledWithEmptyString() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        var expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        var actual = tokenGenerator.hash("");

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("hash('abc') returns the known SHA-256 digest for that input")
    void should_returnKnownDigest_when_hashCalledWithKnownInput() {
        // SHA-256("abc") — NIST FIPS 180-4 test vector
        var expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

        var actual = tokenGenerator.hash("abc");

        assertThat(actual).isEqualTo(expected);
    }
}
