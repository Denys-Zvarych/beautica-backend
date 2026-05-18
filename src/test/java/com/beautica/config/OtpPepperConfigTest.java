package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Fail-fast validation for the OTP pepper, mirroring {@code JwtConfig} — the
 * application must refuse to start with a missing or weak pepper rather than
 * silently fall back to a guessable keyed digest.
 */
@DisplayName("OtpPepperConfig — fail-fast validation")
class OtpPepperConfigTest {

    @Test
    @DisplayName("should_throw_when_pepperIsNull")
    void should_throw_when_pepperIsNull() {
        assertThatThrownBy(() -> new OtpPepperConfig(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("otp-pepper");
    }

    @Test
    @DisplayName("should_throw_when_pepperShorterThan32Chars")
    void should_throw_when_pepperShorterThan32Chars() {
        assertThatThrownBy(() -> new OtpPepperConfig("a".repeat(31)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    @DisplayName("should_accept_when_pepperIsExactly32Chars")
    void should_accept_when_pepperIsExactly32Chars() {
        assertThatCode(() -> new OtpPepperConfig("a".repeat(32)))
                .doesNotThrowAnyException();

        assertThat(new OtpPepperConfig("a".repeat(32)).otpPepper()).hasSize(32);
    }
}
