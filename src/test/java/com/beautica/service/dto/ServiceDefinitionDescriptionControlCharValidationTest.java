package com.beautica.service.dto;

import com.beautica.service.entity.PriceType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation unit guards for the {@code description} field on the
 * service-definition create/update DTOs. The {@code description} @Pattern was
 * loosened to permit line breaks and tabs (TAB / LF / CR) for long-form copy,
 * while still rejecting NUL and other C0 / DEL control characters.
 */
@DisplayName("ServiceDefinition request DTOs - description control-char validation")
class ServiceDefinitionDescriptionControlCharValidationTest {

    private static final String NUL = "\u0000";
    private static final String VTAB = "\u000B";
    private static final String DEL = "\u007F";

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static CreateServiceDefinitionRequest createWithDescription(String description) {
        return new CreateServiceDefinitionRequest(
                "Classic Manicure", description, "MANICURE", 60, 0,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, null);
    }

    private static UpdateServiceDefinitionRequest updateWithDescription(String description) {
        return new UpdateServiceDefinitionRequest(
                "Classic Manicure", description, null, null, null,
                null, null, null, null, null);
    }

    private static boolean hasDescriptionViolation(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("description"));
    }

    // -- positive: newline + tab now accepted (the bug fix) -------------------

    @Test
    @DisplayName("CreateServiceDefinitionRequest accepts description with a newline and a tab")
    void should_acceptCreateDescription_when_containsNewlineAndTab() {
        var description = "What is included:\n- step one\n- step two\twith detail";

        assertThat(validator.validate(createWithDescription(description)))
                .as("create description with newline/tab must be accepted, actual=%s", description)
                .isEmpty();
    }

    @Test
    @DisplayName("UpdateServiceDefinitionRequest accepts description with a newline and a tab")
    void should_acceptUpdateDescription_when_containsNewlineAndTab() {
        var description = "Updated copy\nsecond line\tindented";

        assertThat(validator.validate(updateWithDescription(description)))
                .as("update description with newline/tab must be accepted, actual=%s", description)
                .isEmpty();
    }

    // -- negative: NUL/VT/DEL still rejected (over-loosen guard) --------------

    @Test
    @DisplayName("CreateServiceDefinitionRequest rejects description with an embedded NUL byte")
    void should_rejectCreateDescription_when_containsNul() {
        var description = "Good" + NUL + "bad";

        assertThat(hasDescriptionViolation(validator.validate(createWithDescription(description))))
                .as("create description with embedded NUL must fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("UpdateServiceDefinitionRequest rejects description with an embedded NUL byte")
    void should_rejectUpdateDescription_when_containsNul() {
        var description = "Good" + NUL + "bad";

        assertThat(hasDescriptionViolation(validator.validate(updateWithDescription(description))))
                .as("update description with embedded NUL must fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("CreateServiceDefinitionRequest rejects description with a vertical tab (0x0B)")
    void should_rejectCreateDescription_when_containsVerticalTab() {
        var description = "Good" + VTAB + "bad";

        assertThat(hasDescriptionViolation(validator.validate(createWithDescription(description))))
                .as("create description with VT (0x0B) must still fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("UpdateServiceDefinitionRequest rejects description with a DEL char (0x7F)")
    void should_rejectUpdateDescription_when_containsDel() {
        var description = "Good" + DEL + "bad";

        assertThat(hasDescriptionViolation(validator.validate(updateWithDescription(description))))
                .as("update description with DEL (0x7F) must still fail @Pattern validation")
                .isTrue();
    }
}
