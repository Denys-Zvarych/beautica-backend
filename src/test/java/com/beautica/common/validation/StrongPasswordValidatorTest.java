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
        String input = "Str0ngP@ss1!";

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("password=%s", input).isTrue();
    }

    @Test
    @DisplayName("accepts null so @NotBlank owns the required message (Bean Validation skips null)")
    void should_accept_when_valueIsNull() {
        boolean valid = validator.isValid(null, null);

        assertThat(valid).as("password=%s", (Object) null).isTrue();
    }

    @Test
    @DisplayName("accepts blank so @NotBlank owns the required message")
    void should_accept_when_valueIsBlank() {
        String input = "   ";

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("password=%s", input).isTrue();
    }

    @Test
    @DisplayName("accepts a password of exactly the minimum length (length is the sole passing dimension)")
    void should_accept_when_passwordExactly8Chars() {
        String input = "Abcdef1g";

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("password=%s", input).isTrue();
    }

    @Test
    @DisplayName("rejects a password one char below the minimum length (length is the sole failing dimension)")
    void should_reject_when_passwordExactly7Chars() {
        String input = "Abcde1f";

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("password=%s", input).isFalse();
    }

    @Test
    @DisplayName("accepts a password of exactly the maximum length (length is the sole passing dimension)")
    void should_accept_when_passwordExactly128Chars() {
        String input = "A1" + "a".repeat(StrongPasswordValidator.MAX_LENGTH - 2);

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("password.length=%d", input.length()).isTrue();
    }

    @Test
    @DisplayName("rejects a password one char above the maximum length (length is the sole failing dimension)")
    void should_reject_when_passwordExactly129Chars() {
        String input = "A1" + "a".repeat(StrongPasswordValidator.MAX_LENGTH - 1);

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("password.length=%d", input.length()).isFalse();
    }

    @Test
    @DisplayName("rejects a password shorter than the minimum length")
    void should_reject_when_passwordTooShort() {
        String input = "short1!";

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("password=%s", input).isFalse();
    }

    @Test
    @DisplayName("rejects a password longer than the maximum length (length-only failure: has digit + uppercase)")
    void should_reject_when_passwordTooLong() {
        String tooLong = "A1" + "a".repeat(StrongPasswordValidator.MAX_LENGTH);

        boolean valid = validator.isValid(tooLong, null);

        assertThat(valid).as("password.length=%d", tooLong.length()).isFalse();
    }

    @Test
    @DisplayName("rejects a password with no digit")
    void should_reject_when_passwordHasNoDigit() {
        String input = "Strongpass!";

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("password=%s", input).isFalse();
    }

    @Test
    @DisplayName("rejects a password with no uppercase letter")
    void should_reject_when_passwordHasNoUppercase() {
        String input = "str0ngp@ss1";

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("password=%s", input).isFalse();
    }

    @Test
    @DisplayName("rejects a denylisted common password")
    void should_reject_when_passwordIsCommon() {
        String input = "password123";

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("password=%s", input).isFalse();
    }

    @Test
    @DisplayName("rejects a denylisted common password case-insensitively")
    void should_reject_when_commonPasswordDiffersOnlyInCase() {
        String input = "PassWord123";

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("password=%s", input).isFalse();
    }

    @Test
    @DisplayName("accepts when the only digit/uppercase sits after a newline (find scans across lines, unlike a .* full-match)")
    void should_accept_when_digitAndUppercaseAppearOnlyAfterNewline() {
        // Both the uppercase letter and the digit live on the line *after* the embedded
        // newline. A presence check that anchors to a non-DOTALL ".*X.*" full-match would
        // reject this (the '.' run cannot cross '\n'); Matcher.find() on a bare [0-9]/[A-Z]
        // class scans the whole string and accepts it. Pins the documented find() contract.
        String input = "abcdefg\nH1";

        boolean valid = validator.isValid(input, null);

        assertThat(valid).as("password=%s", input).isTrue();
    }
}
