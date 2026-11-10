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
 * Bean-validation unit tests for {@link SuggestServiceTypeRequest#name()}: the suggested service
 * name carries the phase-464 raw-input allow-list ({@link ServiceNameConstraints#NAME_REGEX}).
 * {@code categoryName}/{@code description} are always supplied valid so only {@code name}
 * violations surface. Pure JSR-380 — no Spring context.
 */
@DisplayName("SuggestServiceTypeRequest — name validation")
class SuggestServiceTypeRequestValidationTest {

    private static final String VALID_CATEGORY = "NAIL_SERVICE";
    private static final String VALID_DESCRIPTION = "Опис процедури";

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

    private Set<ConstraintViolation<SuggestServiceTypeRequest>> validate(String name) {
        return validator.validate(new SuggestServiceTypeRequest(name, VALID_CATEGORY, VALID_DESCRIPTION));
    }

    @Test
    @DisplayName("ordinary Cyrillic name with allowed punctuation is valid")
    void should_accept_when_nameIsOrdinaryText() {
        Set<ConstraintViolation<SuggestServiceTypeRequest>> violations = validate("Масаж спини (класичний) №1");

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("NBSP-padded name passes the raw-input pattern (folded later by canonicalisation)")
    void should_accept_when_nameHasNbspPadding() {
        Set<ConstraintViolation<SuggestServiceTypeRequest>> violations = validate(" Манікюр ");

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("a name of ONLY \\p{Zs} separators passes the DTO — the service-layer canonical check is load-bearing")
    void should_passDto_when_nameIsOnlySpaceSeparators() {
        Set<ConstraintViolation<SuggestServiceTypeRequest>> violations = validate("  　");

        // Pinned so nobody drops ServiceCatalogService#canonicalSuggestedName believing the DTO
        // already rejects this — it does not (@NotBlank only strips Character.isWhitespace).
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("a control character in name is rejected with the allow-list message")
    void should_reject_when_nameHasControlChar() {
        Set<ConstraintViolation<SuggestServiceTypeRequest>> violations = validate("Manicure\u0000Deluxe");

        assertThat(violations).anySatisfy(v -> {
            assertThat(v.getPropertyPath().toString()).isEqualTo("name");
            assertThat(v.getMessage()).isEqualTo(ServiceNameConstraints.NAME_REGEX_MESSAGE);
        });
    }

    @Test
    @DisplayName("a symbol/emoji outside the allow-list in name is rejected")
    void should_reject_when_nameHasDisallowedSymbol() {
        Set<ConstraintViolation<SuggestServiceTypeRequest>> violations = validate("Манікюр <b>💅</b>");

        assertThat(violations).anySatisfy(v -> {
            assertThat(v.getPropertyPath().toString()).isEqualTo("name");
            assertThat(v.getMessage()).isEqualTo(ServiceNameConstraints.NAME_REGEX_MESSAGE);
        });
    }

    @Test
    @DisplayName("description keeps its own control-char rule (non-name field unchanged)")
    void should_keepDescriptionRule_when_descriptionHasSymbols() {
        Set<ConstraintViolation<SuggestServiceTypeRequest>> violations = validator.validate(
                new SuggestServiceTypeRequest("Манікюр", VALID_CATEGORY, "Опис <b>💅</b> #1"));

        assertThat(violations).isEmpty();
    }
}
