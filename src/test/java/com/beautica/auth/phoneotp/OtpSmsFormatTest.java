package com.beautica.auth.phoneotp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 440 — lenient {@code smsFormat} parsing: never a 400, unknown falls back to DOMAIN_BOUND. */
@DisplayName("OtpSmsFormat — lenient request parsing (Phase 440)")
class OtpSmsFormatTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(null, OtpSmsFormat.DOMAIN_BOUND, false),
                Arguments.of("", OtpSmsFormat.DOMAIN_BOUND, false),
                Arguments.of("  ", OtpSmsFormat.DOMAIN_BOUND, false),
                Arguments.of("FOO", OtpSmsFormat.DOMAIN_BOUND, true),
                Arguments.of("plain", OtpSmsFormat.PLAIN, false),
                Arguments.of(" PLAIN ", OtpSmsFormat.PLAIN, false),
                Arguments.of("DOMAIN_BOUND", OtpSmsFormat.DOMAIN_BOUND, false),
                Arguments.of("P".repeat(33), OtpSmsFormat.DOMAIN_BOUND, true));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}, unrecognised={2}")
    @MethodSource("cases")
    @DisplayName("should_resolveFormatAndFallbackFlag_when_rawValueGiven")
    void should_resolveFormatAndFallbackFlag_when_rawValueGiven(String raw, OtpSmsFormat expected,
                                                                boolean unrecognised) {
        OtpSmsFormat format = OtpSmsFormat.fromRequest(raw);
        boolean flagged = OtpSmsFormat.isUnrecognised(raw);

        assertThat(format).isEqualTo(expected);
        assertThat(flagged).isEqualTo(unrecognised);
    }
}
