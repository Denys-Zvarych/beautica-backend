package com.beautica.user;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation unit guard for the locality contract on the CLIENT self-service
 * DTO {@link UpdateProfileRequest} (bound by {@code PATCH /api/v1/users/me}).
 *
 * <p>Defence-in-depth mirror of the provider DTO tests (Phase 10.6 reversal). The
 * reversal added {@code @NotBlank} to {@code street} + {@code buildingNo} on the
 * <em>provider</em> DTOs ({@code IndependentMasterUpdateRequest},
 * {@code Create/UpdateSalonRequest}) ONLY. This DTO — the client path — must stay
 * address-optional: a null (or absent) {@code street}/{@code buildingNo} must yield
 * <strong>no</strong> constraint violation. This test would fail the instant an
 * accidental {@code @NotBlank} leaked onto {@code UpdateProfileRequest.street}
 * or {@code buildingNo}, guarding the "client locality stays optional" invariant
 * at the DTO boundary itself (complementing the {@code UserControllerTest} slice).
 */
@DisplayName("UpdateProfileRequest — client locality-optional validation")
class UpdateProfileRequestLocalityValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static boolean hasViolationOn(Set<ConstraintViolation<UpdateProfileRequest>> violations,
                                          String field) {
        return violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    @Test
    @DisplayName("null street + null buildingNo yields NO violation (client address stays optional)")
    void should_haveNoViolation_when_streetAndBuildingNoAreNull() {
        // City + district set, structured address omitted — the client "set my city" save.
        var request = new UpdateProfileRequest(
                "Jane", "Doe", null,
                UUID.randomUUID(), UUID.randomUUID(),
                null, null, null,   // street, buildingNo, locationNote all null
                null, null);

        assertThat(validator.validate(request))
                .as("client UpdateProfileRequest with null street/buildingNo must not trip any @NotBlank")
                .isEmpty();
    }

    @Test
    @DisplayName("blank street + blank buildingNo yields NO violation (no @NotBlank on the client DTO)")
    void should_haveNoViolation_when_streetAndBuildingNoAreBlank() {
        // A blank string is exactly what @NotBlank rejects on the provider DTOs. Its
        // acceptance here proves @NotBlank is absent from the client path (the empty-string
        // arm of the @Pattern/@Size caps still permits "").
        var request = new UpdateProfileRequest(
                "Jane", "Doe", null,
                UUID.randomUUID(), null,
                "  ", "  ", null,
                null, null);

        var violations = validator.validate(request);

        assertThat(hasViolationOn(violations, "street"))
                .as("client DTO must NOT reject a blank street (no @NotBlank leak)")
                .isFalse();
        assertThat(hasViolationOn(violations, "buildingNo"))
                .as("client DTO must NOT reject a blank buildingNo (no @NotBlank leak)")
                .isFalse();
    }

    @Test
    @DisplayName("fully empty locality (all fields null) yields NO violation")
    void should_haveNoViolation_when_allLocalityFieldsNull() {
        // A pure name-only edit — no locality at all — must remain valid on the client DTO.
        var request = new UpdateProfileRequest(
                "Jane", "Doe", null,
                null, null, null, null, null,
                null, null);

        assertThat(validator.validate(request))
                .as("client UpdateProfileRequest with no locality fields must be violation-free")
                .isEmpty();
    }
}
