package com.beautica.service.dto;

import com.beautica.service.entity.PriceType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

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

    private static final String NAME_CONTROL_CHAR_MESSAGE = ServiceNameConstraints.NAME_REGEX_MESSAGE;

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static CreateServiceDefinitionRequest createWithDescription(String description) {
        // serviceTypeId is @NotNull since V111; supply one so the positive cases isolate the
        // description @Pattern under test rather than tripping the mandatory-type constraint.
        return new CreateServiceDefinitionRequest(
                "Classic Manicure", description, "MANICURE", 60, 0,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, java.util.UUID.randomUUID());
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

    // -- phase 464: name ALLOW-LIST at the DTO boundary (fast 400) --------------------------
    // Combining marks (U+034F, U+FE0F) deliberately pass the raw-input pattern so a
    // decomposed й reaches NFKC; ServiceCatalogService.canonicalName rejects them (service tests).

    static Stream<Arguments> disallowedNameCharsPerNameDto() {
        String[] rejected = {"\uD83D\uDC85", "\u200B", "\u3164", "\u2028", "\u0085", "#", "@", "\u2605",
                "\u202E", "\uFEFF", "\u115F", "\u1160", "\uFFA0", "\u2800", "\u009F"};
        return Stream.of(rejected).flatMap(ch -> namePerDto(
                String.format("U+%04X", ch.codePointAt(0)), "Мані" + ch + "кюр"));
    }

    static Stream<Arguments> allowedNamesPerNameDto() {
        String[] accepted = {"Манікюр", "Пам'ять", "Пам\u2019ять", "пам\u02BCять", "Стрижка + укладка",
                "Масаж (60 хв) \u21162", "SPA-догляд", "Стрижка  +  укладка", "Мані\u0438\u0306",
                "\u00A0Манікюр\u00A0", "«Люкс» — 100%: так! ні? 2/3 & 4, 5.6 \"А\" – б"};
        return Stream.of(accepted).flatMap(name -> namePerDto(name, name));
    }

    private static Stream<Arguments> namePerDto(String label, String name) {
        return Stream.of(
                Arguments.of(label, "create", new CreateServiceDefinitionRequest(
                        name, null, "MANICURE", 60, 0,
                        PriceType.FIXED, new BigDecimal("350.00"), null, null, UUID.randomUUID())),
                Arguments.of(label, "update", new UpdateServiceDefinitionRequest(
                        name, null, null, null, null, null, null, null, null, null)),
                Arguments.of(label, "bulk", new BulkServiceItemRequest(
                        UUID.randomUUID(), 60, PriceType.FIXED, new BigDecimal("350.00"),
                        null, null, name)));
    }

    @ParameterizedTest(name = "{1} name with {0} -> name violation")
    @MethodSource("disallowedNameCharsPerNameDto")
    @DisplayName("name DTOs reject any character outside the allow-list")
    void should_rejectName_when_containsCharOutsideAllowList(String codePoint, String dto, Object request) {
        var violations = validator.validate(request);

        assertNameControlCharViolation(violations,
                dto + " name with " + codePoint + " must fail @Pattern validation");
    }

    @ParameterizedTest(name = "{1} name \"{0}\" -> no name violation")
    @MethodSource("allowedNamesPerNameDto")
    @DisplayName("name DTOs accept allow-listed names (Ukrainian apostrophes, punctuation, raw spacing)")
    void should_acceptName_when_onlyAllowListedChars(String name, String dto, Object request) {
        var violations = validator.validate(request);

        assertThat(violations)
                .as(dto + " name \"" + name + "\" must pass validation")
                .noneMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    private static void assertNameControlCharViolation(
            Set<? extends ConstraintViolation<?>> violations, String description) {
        assertThat(violations)
                .as(description)
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("name");
                    assertThat(v.getMessage()).isEqualTo(NAME_CONTROL_CHAR_MESSAGE);
                });
    }
}
