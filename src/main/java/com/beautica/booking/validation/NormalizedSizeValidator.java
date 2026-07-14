package com.beautica.booking.validation;

import com.beautica.booking.service.BookingComments;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementation backing {@link NormalizedSize}. Runs the exact same
 * {@link BookingComments#normalize(String)} transform the service layer applies before
 * persistence, then measures the RESULT — so padding characters normalize() would strip anyway
 * (zero-width spaces, bidi overrides, other Unicode {@code Cf} format characters) can never be
 * used to satisfy a {@code min} floor.
 *
 * <p>{@code null} values return {@code true} (valid) — Bean Validation skips them here so
 * {@code @NotBlank} owns the "required" message (§A of the Anti-Bug Playbook).
 */
public class NormalizedSizeValidator implements ConstraintValidator<NormalizedSize, String> {

    private int min;
    private int max;

    @Override
    public void initialize(NormalizedSize constraintAnnotation) {
        this.min = constraintAnnotation.min();
        this.max = constraintAnnotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Null handled by @NotBlank — skip here so messages stay distinct.
        if (value == null) {
            return true;
        }
        String normalized = BookingComments.normalize(value);
        int length = normalized == null ? 0 : normalized.length();
        return length >= min && length <= max;
    }
}
