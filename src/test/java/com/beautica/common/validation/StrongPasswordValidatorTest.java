package com.beautica.common.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StrongPasswordValidator — unit")
class StrongPasswordValidatorTest {

    private final StrongPasswordValidator validator = new StrongPasswordValidator();

    @Test
    @DisplayName("accepts a strong password within the length bound")
    void should_accept_when_passwordIsStrong() {
        boolean valid = validator.isValid("Str0ngP@ss1!", null);

        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("accepts null so @NotBlank owns the required message (Bean Validation skips null)")
    void should_accept_when_valueIsNull() {
        boolean valid = validator.isValid(null, null);

        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("accepts blank so @NotBlank owns the required message")
    void should_accept_when_valueIsBlank() {
        boolean valid = validator.isValid("   ", null);

        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("rejects a password shorter than the minimum length")
    void should_reject_when_passwordTooShort() {
        boolean valid = validator.isValid("short1!", null);

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("rejects a password longer than the maximum length")
    void should_reject_when_passwordTooLong() {
        String tooLong = "A".repeat(StrongPasswordValidator.MAX_LENGTH + 1);

        boolean valid = validator.isValid(tooLong, null);

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("rejects a denylisted common password")
    void should_reject_when_passwordIsCommon() {
        boolean valid = validator.isValid("password123", null);

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("rejects a denylisted common password case-insensitively")
    void should_reject_when_commonPasswordDiffersOnlyInCase() {
        boolean valid = validator.isValid("PassWord123", null);

        assertThat(valid).isFalse();
    }
}
