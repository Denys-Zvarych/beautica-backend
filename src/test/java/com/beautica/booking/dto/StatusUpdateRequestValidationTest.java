package com.beautica.booking.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Jakarta Bean Validation coverage for {@link StatusUpdateRequest#comment()} — the shared
 * request body for {@code PATCH /decline} and {@code PATCH /not-complete}.
 *
 * <p>{@link #should_rejectComment_paddedWithZeroWidthChars()} is the regression guard for the
 * LOW finding this class exists to close: a plain {@code @Size(min = 10)} on the RAW string ran
 * before {@code BookingComments.normalize()} stripped Unicode {@code Cf} (zero-width/bidi)
 * padding in the service layer, so {@code "ok"} padded with 8 zero-width spaces (raw length 10)
 * used to clear the floor and then normalize back down to the 2-character degenerate case the
 * floor exists to reject. {@link com.beautica.booking.validation.NormalizedSize} closes the gap
 * by validating the NORMALIZED length instead.
 *
 * <p>No Spring context / Testcontainers — a {@link Validator} fully reproduces the constraint,
 * mirroring {@link com.beautica.common.validation.NoDigitsDtoValidationTest}.
 */
@DisplayName("StatusUpdateRequest.comment — Bean Validation")
class StatusUpdateRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static boolean hasViolationOn(Set<? extends ConstraintViolation<?>> violations, String path) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(path));
    }

    private static StatusUpdateRequest withComment(String comment) {
        return new StatusUpdateRequest(null, comment);
    }

    @Test
    @DisplayName("rejects a comment padded with zero-width spaces past the raw 10-char floor — "
            + "the normalized length (2 meaningful chars) is what actually gets checked")
    void should_rejectComment_paddedWithZeroWidthChars() {
        // "ok" (2 meaningful chars) + 8 zero-width spaces (U+200B) = raw length 10 — would have
        // cleared a naive @Size(min = 10) on the raw string.
        String padded = "ok" + "​".repeat(8);
        assertThat(padded.length()).as("raw length must clear a naive @Size(min=10)").isEqualTo(10);

        var violations = validator.validate(withComment(padded));

        assertThat(hasViolationOn(violations, "comment"))
                .as("zero-width-padded comment must still trip the (normalized) length floor, found=%s",
                        violations)
                .isTrue();
    }

    @Test
    @DisplayName("accepts a genuinely meaningful comment of at least 10 normalized characters")
    void should_acceptComment_whenNormalizedLengthClearsFloor() {
        var violations = validator.validate(withComment("Master is sick today, sorry for the trouble"));

        assertThat(hasViolationOn(violations, "comment"))
                .as("a real explanation must not trip any comment constraint, found=%s", violations)
                .isFalse();
    }

    @Test
    @DisplayName("rejects a blank comment via @NotBlank")
    void should_rejectComment_whenBlank() {
        var violations = validator.validate(withComment("   "));

        assertThat(hasViolationOn(violations, "comment"))
                .as("blank comment must trip @NotBlank, found=%s", violations)
                .isTrue();
    }

    @Test
    @DisplayName("rejects a comment over 1000 raw characters via the raw @Size ceiling")
    void should_rejectComment_whenOverRawMaxLength() {
        var violations = validator.validate(withComment("a".repeat(1001)));

        assertThat(hasViolationOn(violations, "comment"))
                .as("an over-length comment must trip the raw @Size ceiling, found=%s", violations)
                .isTrue();
    }

    @Test
    @DisplayName("accepts exactly 10 genuinely meaningful characters (the floor, inclusive)")
    void should_acceptComment_whenExactlyAtNormalizedFloor() {
        String exactlyTenChars = "No shows!!";
        assertThat(exactlyTenChars.strip().length()).isEqualTo(10);

        var violations = validator.validate(withComment(exactlyTenChars));

        assertThat(hasViolationOn(violations, "comment"))
                .as("exactly 10 normalized characters must clear the floor, found=%s", violations)
                .isFalse();
    }
}
