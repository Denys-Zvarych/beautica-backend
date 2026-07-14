package com.beautica.booking.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit coverage for {@link NormalizedSizeValidator}, the implementation backing
 * {@link NormalizedSize} on {@code StatusUpdateRequest.comment} (LOW finding fix).
 *
 * <p>Contract pinned here:
 * <ul>
 *   <li>{@code null} → valid ({@code @NotBlank} owns the "required" message, §A).</li>
 *   <li>the length actually measured is the length AFTER
 *       {@code BookingComments.normalize()} — not the raw string length — so padding characters
 *       normalize() strips anyway (zero-width spaces, bidi overrides, other {@code Cf} format
 *       characters) cannot be used to clear a {@code min} floor.</li>
 *   <li>a normalized length outside {@code [min, max]} is invalid; inside (inclusive) is valid.</li>
 * </ul>
 *
 * <p>Style mirrors {@link com.beautica.common.validation.NoDigitsValidatorTest} — no Spring
 * context, the validator is instantiated directly, {@code initialize()} is called explicitly to
 * set {@code min}/{@code max} (mirroring how the Bean Validation runtime invokes it), and
 * {@code isValid(value, null)} passes a {@code null} context (the validator never touches it).
 */
@DisplayName("NormalizedSizeValidator — unit")
class NormalizedSizeValidatorTest {

    private NormalizedSizeValidator validatorWith(int min, int max) {
        NormalizedSizeValidator validator = new NormalizedSizeValidator();
        validator.initialize(new NormalizedSize() {
            @Override
            public int min() {
                return min;
            }

            @Override
            public int max() {
                return max;
            }

            @Override
            public String message() {
                return "";
            }

            @Override
            public Class<?>[] groups() {
                return new Class<?>[0];
            }

            @Override
            public Class<? extends jakarta.validation.Payload>[] payload() {
                return new Class[0];
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return NormalizedSize.class;
            }
        });
        return validator;
    }

    @Test
    @DisplayName("accepts null so @NotBlank owns the required message (Bean Validation skips null)")
    void should_accept_when_valueIsNull() {
        NormalizedSizeValidator validator = validatorWith(10, 1000);

        boolean valid = validator.isValid(null, null);

        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("rejects a raw string that clears the min floor ONLY via zero-width padding — "
            + "the exact bypass this validator exists to close")
    void should_reject_when_rawLengthClearsFloorOnlyViaZeroWidthPadding() {
        // "ok" (2 meaningful chars) + 8 zero-width spaces (U+200B) = raw length 10.
        String padded = "ok" + "​".repeat(8);
        NormalizedSizeValidator validator = validatorWith(10, 1000);

        boolean valid = validator.isValid(padded, null);

        assertThat(padded.length()).as("raw length must clear a naive @Size(min=10)").isEqualTo(10);
        assertThat(valid)
                .as("normalized length is 2 (\"ok\"), below the min=10 floor")
                .isFalse();
    }

    @Test
    @DisplayName("accepts a genuinely meaningful comment whose normalized length clears the floor")
    void should_accept_when_normalizedLengthClearsFloor() {
        NormalizedSizeValidator validator = validatorWith(10, 1000);

        boolean valid = validator.isValid("Master is sick today", null);

        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("rejects a normalized length above max")
    void should_reject_when_normalizedLengthExceedsMax() {
        NormalizedSizeValidator validator = validatorWith(0, 5);

        boolean valid = validator.isValid("this is definitely longer than five", null);

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("rejects a comment that normalizes to null (blank-to-null) when min > 0")
    void should_reject_when_valueNormalizesToNull() {
        // Whitespace + zero-width chars only — BookingComments.normalize() returns null.
        NormalizedSizeValidator validator = validatorWith(10, 1000);

        boolean valid = validator.isValid("   ​​  ", null);

        assertThat(valid).isFalse();
    }
}
