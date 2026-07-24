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
 * <p>Phase 25.9 (reverses Phase 25.2's "required, ≥10-char" decision, made and shipped earlier
 * the same day): {@code comment} is now OPTIONAL for all roles. {@code @NotBlank} and the
 * normalized-length floor ({@code @NormalizedSize(min = 10, ...)}, since deleted along with the
 * now-unused {@code NormalizedSize}/{@code NormalizedSizeValidator} classes) are gone. What
 * survives: the raw {@code @Size(max = 1000)} DoS-guard ceiling and the control-character
 * {@code @Pattern} guard — neither is about the "is a note required" question.
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
    @DisplayName("Phase 25.9: accepts a null comment — the note is optional for all roles")
    void should_acceptComment_whenNull() {
        var violations = validator.validate(withComment(null));

        assertThat(hasViolationOn(violations, "comment"))
                .as("a null comment must not trip any constraint, found=%s", violations)
                .isFalse();
    }

    @Test
    @DisplayName("Phase 25.9: accepts a blank (whitespace-only) comment — @NotBlank was removed")
    void should_acceptComment_whenBlank() {
        var violations = validator.validate(withComment("   "));

        assertThat(hasViolationOn(violations, "comment"))
                .as("a blank comment must not trip any constraint, found=%s", violations)
                .isFalse();
    }

    @Test
    @DisplayName("Phase 25.9: accepts a genuinely short comment (below the old 10-char floor) — the "
            + "floor was removed, not merely lowered")
    void should_acceptComment_whenShorterThanOldTenCharFloor() {
        var violations = validator.validate(withComment("No show"));

        assertThat(hasViolationOn(violations, "comment"))
                .as("a short comment must not trip any constraint, found=%s", violations)
                .isFalse();
    }

    @Test
    @DisplayName("accepts a genuinely meaningful long-form comment")
    void should_acceptComment_whenLongFormText() {
        var violations = validator.validate(withComment("Master is sick today, sorry for the trouble"));

        assertThat(hasViolationOn(violations, "comment"))
                .as("a real explanation must not trip any comment constraint, found=%s", violations)
                .isFalse();
    }

    @Test
    @DisplayName("rejects a comment over 1000 raw characters via the raw @Size ceiling (DoS guard survives)")
    void should_rejectComment_whenOverRawMaxLength() {
        var violations = validator.validate(withComment("a".repeat(1001)));

        assertThat(hasViolationOn(violations, "comment"))
                .as("an over-length comment must trip the raw @Size ceiling, found=%s", violations)
                .isTrue();
    }

    @Test
    @DisplayName("accepts exactly 1000 raw characters (the ceiling, inclusive)")
    void should_acceptComment_whenExactlyAtRawMaxLength() {
        var violations = validator.validate(withComment("a".repeat(1000)));

        assertThat(hasViolationOn(violations, "comment"))
                .as("exactly 1000 characters must clear the ceiling, found=%s", violations)
                .isFalse();
    }

    @Test
    @DisplayName("rejects a comment containing a forbidden control character (NUL byte) — the "
            + "@Pattern guard survives")
    void should_rejectComment_whenContainsControlCharacter() {
        var violations = validator.validate(withComment("legit comment\u0000embedded null"));

        assertThat(hasViolationOn(violations, "comment"))
                .as("an embedded NUL byte must trip the control-character @Pattern, found=%s", violations)
                .isTrue();
    }

    @Test
    @DisplayName("accepts a comment containing a newline or tab — only C0/C1 control chars are banned")
    void should_acceptComment_whenContainsNewlineAndTab() {
        var violations = validator.validate(withComment("line one\nline two\tindented"));

        assertThat(hasViolationOn(violations, "comment"))
                .as("newline/tab must not trip the control-character @Pattern, found=%s", violations)
                .isFalse();
    }
}
