package com.beautica.service.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean-validation unit tests for {@link CreateCategoryRequestRequest}, focused on the
 * OPTIONAL {@code initialServiceName} field added in Phase 16.x.
 *
 * <p>Pure JSR-380 validation — no Spring context. A single {@link Validator} is built
 * once and reused; {@code name}/{@code displayName} are always supplied valid so the
 * only violations surfaced are the ones the {@code initialServiceName} constraints
 * produce. Guards behavior #1: null/absent valid, blank valid (treated as null),
 * length &gt; 255 rejected, control char rejected.
 */
@DisplayName("CreateCategoryRequestRequest — initialServiceName validation")
class CreateCategoryRequestRequestValidationTest {

    private static final String VALID_NAME = "NAIL_ART";
    private static final String VALID_DISPLAY = "Нейл-арт";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private Set<ConstraintViolation<CreateCategoryRequestRequest>> validate(String initialServiceName) {
        return validator.validate(
                new CreateCategoryRequestRequest(VALID_NAME, VALID_DISPLAY, initialServiceName));
    }

    private boolean hasInitialServiceNameViolation(
            Set<ConstraintViolation<CreateCategoryRequestRequest>> violations) {
        return violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("initialServiceName"));
    }

    @Test
    @DisplayName("null initialServiceName is valid (field is optional)")
    void should_accept_when_initialServiceNameNull() {
        assertThat(validate(null))
                .as("null initialServiceName must produce no violation")
                .isEmpty();
    }

    @Test
    @DisplayName("blank initialServiceName is valid (no @NotBlank; normalized later)")
    void should_accept_when_initialServiceNameBlank() {
        assertThat(validate("   "))
                .as("blank initialServiceName must pass validation (treated as null at service layer)")
                .noneMatch(v -> v.getPropertyPath().toString().equals("initialServiceName"));
    }

    @Test
    @DisplayName("initialServiceName at exactly 255 chars is valid (boundary)")
    void should_accept_when_initialServiceNameAtMaxLength() {
        assertThat(validate("a".repeat(255)))
                .as("255 chars is the inclusive max — must be accepted")
                .isEmpty();
    }

    @Test
    @DisplayName("initialServiceName at 256 chars is rejected (boundary + 1)")
    void should_reject_when_initialServiceNameExceedsMaxLength() {
        Set<ConstraintViolation<CreateCategoryRequestRequest>> violations = validate("a".repeat(256));

        assertThat(violations)
                .as("256 chars exceeds @Size(max = 255)")
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("initialServiceName");
                    assertThat(v.getMessage()).contains("at most 255 characters");
                });
    }

    @Test
    @DisplayName("initialServiceName containing a newline control char is rejected")
    void should_reject_when_initialServiceNameHasControlChar() {
        Set<ConstraintViolation<CreateCategoryRequestRequest>> violations = validate("Manicure\nDeluxe");

        assertThat(violations)
                .as("control characters must be rejected by @Pattern(^[^\\p{Cntrl}]*$)")
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("initialServiceName");
                    assertThat(v.getMessage()).contains("control characters");
                });
    }

    @Test
    @DisplayName("ordinary multi-word initialServiceName is valid")
    void should_accept_when_initialServiceNameOrdinaryText() {
        assertThat(validate("Класичний манікюр"))
                .as("a normal printable name must not trigger the control-char or size guard")
                .isEmpty();
    }
}
