package com.beautica.common.validation;

import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.config.AuthConsentProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;
import java.util.stream.Collectors;

import static com.beautica.auth.TermsConsentExpectedCopy.PRIVACY_VERSION_REQUIRED;
import static com.beautica.auth.TermsConsentExpectedCopy.TERMS_REQUIRED;
import static com.beautica.auth.TermsConsentExpectedCopy.TERMS_VERSION_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 442 — the {@link TermsConsentComplete} matrix without Spring: a plain Hibernate Validator in tolerant
 * mode (the no-arg constructor) and one whose factory hands out a strict-mode validator.
 */
@DisplayName("@TermsConsentComplete — consent triple matrix")
class TermsConsentCompleteValidatorTest {

    private static final String TERMS = "2026-11-07.2";
    private static final String PRIVACY = "2026-10-20";

    private static ValidatorFactory tolerantFactory;
    private static ValidatorFactory strictFactory;
    private static Validator tolerant;
    private static Validator strict;

    @BeforeAll
    static void setUp() {
        tolerantFactory = Validation.buildDefaultValidatorFactory();
        tolerant = tolerantFactory.getValidator();
        ConstraintValidatorFactory delegate = tolerantFactory.getConstraintValidatorFactory();
        strictFactory = Validation.byDefaultProvider().configure()
                .constraintValidatorFactory(new ConstraintValidatorFactory() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                        return key == TermsConsentCompleteValidator.class
                                ? (T) new TermsConsentCompleteValidator(true)
                                : delegate.getInstance(key);
                    }

                    @Override
                    public void releaseInstance(ConstraintValidator<?, ?> instance) {
                        // stateless
                    }
                })
                .buildValidatorFactory();
        strict = strictFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        strictFactory.close();
        tolerantFactory.close();
    }

    @Test
    @DisplayName("tolerant mode — no consent fields at all is valid (legacy build)")
    void should_pass_when_consentAbsentInTolerantMode() {
        assertThat(paths(tolerant, register(null, null, null))).isEmpty();
    }

    @Test
    @DisplayName("strict mode — absent consent fails on all three fields")
    void should_failOnAllThreeFields_when_consentAbsentInStrictMode() {
        assertThat(paths(strict, register(null, null, null)))
                .containsExactlyInAnyOrder("termsAccepted", "termsVersion", "privacyPolicyVersion");
    }

    @Test
    @DisplayName("full triple is valid in both modes")
    void should_pass_when_fullConsentInBothModes() {
        assertThat(paths(tolerant, register(true, TERMS, PRIVACY))).isEmpty();
        assertThat(paths(strict, register(true, TERMS, PRIVACY))).isEmpty();
    }

    @Test
    @DisplayName("termsAccepted=false fails in both modes, with the terms copy")
    void should_failOnTermsAccepted_when_falseInBothModes() {
        assertThat(paths(tolerant, register(false, TERMS, PRIVACY))).containsOnly("termsAccepted");
        assertThat(paths(strict, register(false, TERMS, PRIVACY))).containsOnly("termsAccepted");
        assertThat(messages(tolerant, register(false, TERMS, PRIVACY))).containsOnly(TERMS_REQUIRED);
    }

    @Test
    @DisplayName("only termsAccepted=false sent — tolerant window closes, all three fields fail")
    void should_failOnTermsAccepted_when_onlyFalseSent() {
        assertThat(paths(tolerant, register(false, null, null)))
                .containsExactlyInAnyOrder("termsAccepted", "termsVersion", "privacyPolicyVersion");
    }

    @Test
    @DisplayName("versions without termsAccepted — fails on termsAccepted")
    void should_failOnTermsAccepted_when_missingWithVersionsPresent() {
        assertThat(paths(tolerant, register(null, TERMS, PRIVACY))).containsOnly("termsAccepted");
    }

    @Test
    @DisplayName("accepted with one version missing — fails on that version with the required copy")
    void should_failOnMissingVersion_when_termsAcceptedTrue() {
        assertThat(paths(tolerant, register(true, null, PRIVACY))).containsOnly("termsVersion");
        assertThat(paths(tolerant, register(true, TERMS, null))).containsOnly("privacyPolicyVersion");
        assertThat(messages(tolerant, register(true, null, PRIVACY))).containsOnly(TERMS_VERSION_REQUIRED);
        assertThat(messages(tolerant, register(true, TERMS, null))).containsOnly(PRIVACY_VERSION_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.8", "2026-13-01", "2026-11-07.1234", "", "2999-01-01"})
    @DisplayName("malformed or future version fails on that field only")
    void should_failOnVersion_when_formatOrDateInvalid(String bad) {
        assertThat(paths(tolerant, register(true, bad, PRIVACY))).containsOnly("termsVersion");
        assertThat(paths(tolerant, register(true, TERMS, bad))).containsOnly("privacyPolicyVersion");
    }

    @Test
    @DisplayName("33-char version fails on termsVersion")
    void should_failOnVersion_when_longerThan32Chars() {
        String tooLong = "2026-11-07." + "1".repeat(22);

        assertThat(tooLong).hasSize(33);
        assertThat(paths(tolerant, register(true, tooLong, PRIVACY))).containsOnly("termsVersion");
    }

    @Test
    @DisplayName("the same rule binds RegisterIndependentMasterRequest and InviteAcceptRequest")
    void should_applySameRule_when_independentMasterOrInviteAccept() {
        var master = new RegisterIndependentMasterRequest("a@b.test", "Str0ngP@ss1!", "Anna", "Koval",
                "+380501234567", true, null, PRIVACY);
        var invite = new InviteAcceptRequest("tok", "Str0ngP@ss1!", "Anna", "Koval", "+380501234567",
                null, null, null);

        assertThat(paths(tolerant, master)).containsOnly("termsVersion");
        assertThat(paths(strict, invite)).contains("termsAccepted");
    }

    @Test
    @DisplayName("Spring constructor reads the flag; missing bean falls back to tolerant")
    void should_readFlagFromProperties_when_springConstructorUsed() {
        var props = new AuthConsentProperties();
        props.setRequireTermsConsent(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthConsentProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(props);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthConsentProperties> empty = mock(ObjectProvider.class);

        var strictValidator = new TermsConsentCompleteValidator(provider);
        var fallbackValidator = new TermsConsentCompleteValidator(empty);

        var context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);

        assertThat(strictValidator.isValid(register(null, null, null), context)).isFalse();
        assertThat(fallbackValidator.isValid(register(null, null, null), context)).isTrue();
    }

    private static RegisterRequest register(Boolean accepted, String terms, String privacy) {
        return new RegisterRequest("client@beautica.test", "Str0ngP@ss1!", SelfRegistrationRole.CLIENT,
                "Olena", "Shevchenko", "+380501234567", null, accepted, terms, privacy);
    }

    private static Set<String> messages(Validator validator, Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    private static Set<String> paths(Validator validator, Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }
}
