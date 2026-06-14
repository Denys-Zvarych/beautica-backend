package com.beautica.common.validation;

import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.master.dto.MasterProfileUpdateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Jakarta Bean Validation coverage proving {@link NoDigits} is actually wired onto the
 * {@code firstName}/{@code lastName} fields of every DTO that carries it:
 * {@link RegisterRequest}, {@link InviteAcceptRequest},
 * {@link RegisterIndependentMasterRequest} and {@link MasterProfileUpdateRequest}.
 *
 * <p>The validator's own logic lives in {@link NoDigitsValidatorTest}; this class is the
 * <em>wiring</em> guard — it would fail if the annotation were dropped from any field or
 * applied to the wrong one. No Spring context / Testcontainers — a {@link Validator} fully
 * reproduces the constraint. Style mirrors {@link MasterProfileUpdateRequestValidationTest}
 * (same {@code @BeforeAll} validator + {@code hasViolationOn} path helper).
 *
 * <p>Each DTO contributes one digit-name → violation-on-the-name-field case and one
 * hyphen/apostrophe/space-name → no-name-violation case. {@link MasterProfileUpdateRequest}
 * additionally proves null first/last names still pass (the fields are optional there).
 */
@DisplayName("@NoDigits wiring on person-name DTO fields — Bean Validation")
class NoDigitsDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    /** True when at least one violation reports on the given property path. */
    private static boolean hasViolationOn(Set<? extends ConstraintViolation<?>> violations, String path) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(path));
    }

    private static final String STRONG_PASSWORD = "Str0ngP@ss1!";
    private static final String VALID_PHONE = "+380501234567";

    // ════════════════════════════════════════════════════════════════════════════
    // RegisterRequest
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("RegisterRequest")
    class Register {

        private RegisterRequest with(String firstName, String lastName) {
            return new RegisterRequest("new@beautica.test", STRONG_PASSWORD,
                    SelfRegistrationRole.CLIENT, firstName, lastName, VALID_PHONE, null);
        }

        @ParameterizedTest(name = "firstName \"{0}\" → violation on firstName")
        @ValueSource(strings = {"Taras2", "John123"})
        @DisplayName("rejects a digit-containing firstName via @NoDigits")
        void should_violateFirstName_when_firstNameHasDigit(String firstName) {
            var violations = validator.validate(with(firstName, "Shevchenko"));

            assertThat(hasViolationOn(violations, "firstName"))
                    .as("firstName=%s must trip @NoDigits, found=%s", firstName, violations)
                    .isTrue();
        }

        @Test
        @DisplayName("rejects a digit-containing lastName via @NoDigits")
        void should_violateLastName_when_lastNameHasDigit() {
            var violations = validator.validate(with("Taras", "Shevchenko9"));

            assertThat(hasViolationOn(violations, "lastName"))
                    .as("lastName with a digit must trip @NoDigits, found=%s", violations)
                    .isTrue();
        }

        @ParameterizedTest(name = "accepts firstName \"{0}\"")
        @ValueSource(strings = {"Анна-Марія", "О’Коннор", "Mary Jane", "Дар'я"})
        @DisplayName("accepts hyphen/apostrophe/space names with no name-field violation")
        void should_notViolateName_when_nameHasNoDigit(String firstName) {
            var violations = validator.validate(with(firstName, "Грицюк-Філатов"));

            assertThat(hasViolationOn(violations, "firstName"))
                    .as("firstName=%s must not trip @NoDigits, found=%s", firstName, violations)
                    .isFalse();
            assertThat(hasViolationOn(violations, "lastName"))
                    .as("hyphenated lastName must not trip @NoDigits, found=%s", violations)
                    .isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // InviteAcceptRequest
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("InviteAcceptRequest")
    class InviteAccept {

        private InviteAcceptRequest with(String firstName, String lastName) {
            return new InviteAcceptRequest("a".repeat(64), STRONG_PASSWORD,
                    firstName, lastName, VALID_PHONE);
        }

        @Test
        @DisplayName("rejects a digit-containing firstName via @NoDigits")
        void should_violateFirstName_when_firstNameHasDigit() {
            var violations = validator.validate(with("Olena1", "Kovalchuk"));

            assertThat(hasViolationOn(violations, "firstName"))
                    .as("firstName with a digit must trip @NoDigits, found=%s", violations)
                    .isTrue();
        }

        @Test
        @DisplayName("rejects a digit-containing lastName via @NoDigits")
        void should_violateLastName_when_lastNameHasDigit() {
            var violations = validator.validate(with("Olena", "Kovalchuk2"));

            assertThat(hasViolationOn(violations, "lastName"))
                    .as("lastName with a digit must trip @NoDigits, found=%s", violations)
                    .isTrue();
        }

        @Test
        @DisplayName("accepts hyphen/apostrophe names with no name-field violation")
        void should_notViolateName_when_nameHasNoDigit() {
            var violations = validator.validate(with("Анна-Марія", "О’Коннор"));

            assertThat(hasViolationOn(violations, "firstName"))
                    .as("hyphenated firstName must not trip @NoDigits, found=%s", violations)
                    .isFalse();
            assertThat(hasViolationOn(violations, "lastName"))
                    .as("apostrophe lastName must not trip @NoDigits, found=%s", violations)
                    .isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // RegisterIndependentMasterRequest
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("RegisterIndependentMasterRequest")
    class RegisterIndependentMaster {

        private RegisterIndependentMasterRequest with(String firstName, String lastName) {
            return new RegisterIndependentMasterRequest("master@beautica.test", STRONG_PASSWORD,
                    firstName, lastName, VALID_PHONE);
        }

        @Test
        @DisplayName("rejects a digit-containing firstName via @NoDigits")
        void should_violateFirstName_when_firstNameHasDigit() {
            var violations = validator.validate(with("Ivan3", "Franko"));

            assertThat(hasViolationOn(violations, "firstName"))
                    .as("firstName with a digit must trip @NoDigits, found=%s", violations)
                    .isTrue();
        }

        @Test
        @DisplayName("rejects a digit-containing lastName via @NoDigits")
        void should_violateLastName_when_lastNameHasDigit() {
            var violations = validator.validate(with("Ivan", "Franko4"));

            assertThat(hasViolationOn(violations, "lastName"))
                    .as("lastName with a digit must trip @NoDigits, found=%s", violations)
                    .isTrue();
        }

        @Test
        @DisplayName("accepts space/hyphen names with no name-field violation")
        void should_notViolateName_when_nameHasNoDigit() {
            var violations = validator.validate(with("Mary Jane", "Грицюк-Філатов"));

            assertThat(hasViolationOn(violations, "firstName"))
                    .as("space-separated firstName must not trip @NoDigits, found=%s", violations)
                    .isFalse();
            assertThat(hasViolationOn(violations, "lastName"))
                    .as("hyphenated lastName must not trip @NoDigits, found=%s", violations)
                    .isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // MasterProfileUpdateRequest — firstName/lastName are OPTIONAL here
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("MasterProfileUpdateRequest (optional first/last name)")
    class MasterProfileUpdate {

        private MasterProfileUpdateRequest with(String firstName, String lastName) {
            return new MasterProfileUpdateRequest(firstName, lastName, null, null, null);
        }

        @Test
        @DisplayName("rejects a digit-containing firstName via @NoDigits")
        void should_violateFirstName_when_firstNameHasDigit() {
            var violations = validator.validate(with("Olena5", null));

            assertThat(hasViolationOn(violations, "firstName"))
                    .as("firstName with a digit must trip @NoDigits, found=%s", violations)
                    .isTrue();
        }

        @Test
        @DisplayName("rejects a digit-containing lastName via @NoDigits")
        void should_violateLastName_when_lastNameHasDigit() {
            var violations = validator.validate(with(null, "Koval6"));

            assertThat(hasViolationOn(violations, "lastName"))
                    .as("lastName with a digit must trip @NoDigits, found=%s", violations)
                    .isTrue();
        }

        @Test
        @DisplayName("accepts hyphen/apostrophe names with no name-field violation")
        void should_notViolateName_when_nameHasNoDigit() {
            var violations = validator.validate(with("Анна-Марія", "О’Коннор"));

            assertThat(hasViolationOn(violations, "firstName"))
                    .as("hyphenated firstName must not trip @NoDigits, found=%s", violations)
                    .isFalse();
            assertThat(hasViolationOn(violations, "lastName"))
                    .as("apostrophe lastName must not trip @NoDigits, found=%s", violations)
                    .isFalse();
        }

        @Test
        @DisplayName("accepts null first AND last name — both fields are optional on this edit DTO")
        void should_haveNoViolations_when_bothNamesNull() {
            var violations = validator.validate(with(null, null));

            assertThat(violations)
                    .as("a fully-null name pair is a valid no-op update, found=%s", violations)
                    .isEmpty();
        }
    }
}
