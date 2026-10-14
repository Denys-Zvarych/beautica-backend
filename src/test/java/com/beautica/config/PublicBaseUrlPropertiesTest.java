package com.beautica.config;

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
 * Pins the fail-fast contract on {@link PublicBaseUrlProperties#getPublicBaseUrl()}.
 *
 * <p>{@code submitRequest} builds the admin approval link as
 * {@code publicBaseUrl + REVIEW_PATH + rawToken}. A null/blank base URL would email a
 * {@code "null/..."}-prefixed broken link. The property is {@code @NotBlank} on a
 * {@code @Validated @ConfigurationProperties} bean, so a blank value fails binding at
 * application startup rather than silently shipping a broken link. These tests assert
 * the {@code @NotBlank} constraint is actually enforced (the annotation is the guard).
 */
@DisplayName("PublicBaseUrlProperties — @NotBlank fail-fast")
class PublicBaseUrlPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private PublicBaseUrlProperties withUrl(String url) {
        var props = new PublicBaseUrlProperties();
        props.setPublicBaseUrl(url);
        return props;
    }

    @Test
    @DisplayName("rejects a null base URL")
    void should_failValidation_when_baseUrlNull() {
        Set<ConstraintViolation<PublicBaseUrlProperties>> violations = validator.validate(withUrl(null));

        assertThat(violations)
                .as("null publicBaseUrl must violate @NotBlank — startup must fail, not email a null link")
                .isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("publicBaseUrl"));
    }

    @Test
    @DisplayName("rejects a blank base URL")
    void should_failValidation_when_baseUrlBlank() {
        Set<ConstraintViolation<PublicBaseUrlProperties>> violations = validator.validate(withUrl("   "));

        assertThat(violations)
                .as("blank publicBaseUrl must violate @NotBlank")
                .isNotEmpty();
    }

    @Test
    @DisplayName("accepts a non-blank base URL")
    void should_passValidation_when_baseUrlPresent() {
        Set<ConstraintViolation<PublicBaseUrlProperties>> violations =
                validator.validate(withUrl("https://api.beautica.app"));

        assertThat(violations)
                .as("a valid base URL must produce no violations")
                .isEmpty();
    }

    @Test
    @DisplayName("rejects a path, query, fragment or trailing slash; accepts bare origins")
    void should_failBinding_when_publicBaseUrlHasPath() {
        for (String bad : new String[]{"https://api.beautica.app/api", "https://api.beautica.app?x=1",
                "https://api.beautica.app#frag", "https://api.beautica.app/", "ftp://api.beautica.app",
                "https://api.beautica.app/ x"}) {
            assertThat(validator.validate(withUrl(bad))).as("value=%s", bad).isNotEmpty();
        }
        for (String ok : new String[]{"https://api.beautica.app", "http://localhost:8080", "https://api.beautica.test"}) {
            assertThat(validator.validate(withUrl(ok))).as("value=%s", ok).isEmpty();
        }
    }
}
