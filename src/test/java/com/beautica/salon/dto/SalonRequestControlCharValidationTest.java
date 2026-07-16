package com.beautica.salon.dto;

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
 * Bean Validation unit guards for the {@code description} field on the salon
 * create/update DTOs. The {@code description} @Pattern was loosened to permit
 * line breaks and tabs (TAB / LF / CR) for long-form copy, while still
 * rejecting NUL and other C0 / DEL control characters
 * (regex {@code ^[^...]*$}, banning 0x00-0x08, 0x0B, 0x0C, 0x0E-0x1F, 0x7F).
 */
@DisplayName("Salon request DTOs - description control-char validation")
class SalonRequestControlCharValidationTest {

    private static final String NUL = "\u0000";
    private static final String VTAB = "\u000B";
    private static final String DEL = "\u007F";

    // Phase 10.6 reversal: street + buildingNo are now @NotBlank on both DTOs. The
    // builders below supply a valid pair so validator.validate(...) surfaces ONLY the
    // description violation under test — otherwise the .isEmpty() positive assertions
    // would fail on unrelated street/buildingNo violations (de-vacuuming).
    private static final String VALID_STREET = "вул. Хрещатик";
    private static final String VALID_BUILDING_NO = "1";

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static CreateSalonRequest createWithDescription(String description) {
        return new CreateSalonRequest(
                "My Salon", description, null, null, null, null, null,
                null, null, VALID_STREET, VALID_BUILDING_NO, null);
    }

    private static UpdateSalonRequest updateWithDescription(String description) {
        return new UpdateSalonRequest(
                "My Salon", description, null, null, null,
                null, null, VALID_STREET, VALID_BUILDING_NO, null, null, null);
    }

    private static boolean hasDescriptionViolation(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("description"));
    }

    // -- positive: newline + tab now accepted (the bug fix) -------------------

    @Test
    @DisplayName("CreateSalonRequest accepts description containing a newline and a tab")
    void should_acceptCreateDescription_when_containsNewlineAndTab() {
        var description = "First paragraph.\nSecond paragraph.\r\n\tIndented note.";

        assertThat(validator.validate(createWithDescription(description)))
                .as("create description with newline/tab must be accepted, actual=%s", description)
                .isEmpty();
    }

    @Test
    @DisplayName("UpdateSalonRequest accepts description containing a newline and a tab")
    void should_acceptUpdateDescription_when_containsNewlineAndTab() {
        var description = "Line A\nLine B\tindented";

        assertThat(validator.validate(updateWithDescription(description)))
                .as("update description with newline/tab must be accepted, actual=%s", description)
                .isEmpty();
    }

    // -- negative: NUL/VT/DEL still rejected (over-loosen guard) --------------

    @Test
    @DisplayName("CreateSalonRequest rejects description containing an embedded NUL byte")
    void should_rejectCreateDescription_when_containsNul() {
        var description = "Good" + NUL + "bad";

        assertThat(hasDescriptionViolation(validator.validate(createWithDescription(description))))
                .as("create description with embedded NUL must fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("UpdateSalonRequest rejects description containing an embedded NUL byte")
    void should_rejectUpdateDescription_when_containsNul() {
        var description = "Good" + NUL + "bad";

        assertThat(hasDescriptionViolation(validator.validate(updateWithDescription(description))))
                .as("update description with embedded NUL must fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("CreateSalonRequest rejects description containing a vertical tab (0x0B, non-newline C0)")
    void should_rejectCreateDescription_when_containsVerticalTab() {
        var description = "Good" + VTAB + "bad";

        assertThat(hasDescriptionViolation(validator.validate(createWithDescription(description))))
                .as("create description with VT (0x0B) must still fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("UpdateSalonRequest rejects description containing a DEL char (0x7F)")
    void should_rejectUpdateDescription_when_containsDel() {
        var description = "Good" + DEL + "bad";

        assertThat(hasDescriptionViolation(validator.validate(updateWithDescription(description))))
                .as("update description with DEL (0x7F) must still fail @Pattern validation")
                .isTrue();
    }
}
