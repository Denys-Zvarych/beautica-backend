package com.beautica.booking.dto;

import com.beautica.auth.phoneotp.PhoneOtpSendRequest;
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

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 381 D2 — the privacy-consent fields on both guest requests, validated through a real
 * {@link Validator}: {@code privacyAccepted} must be exactly {@code true}; the policy version is a
 * format-checked effective date ({@code 2026-10-20}, optional {@code .N} revision, at most 32 chars).
 */
@DisplayName("Guest requests — privacy consent validation (Phase 381)")
class GuestBookingRequestConsentValidationTest {

    private static final OffsetDateTime FUTURE = OffsetDateTime.parse("2099-06-10T12:00:00+03:00");
    private static final String VALID_PHONE = "+380671234567";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static GuestBookingRequest booking(Boolean accepted, String version) {
        return new GuestBookingRequest(UUID.randomUUID(), null, FUTURE, "Олена", "Коваль", accepted, version);
    }

    private static PhoneOtpSendRequest otp(Boolean accepted, String version) {
        return new PhoneOtpSendRequest(VALID_PHONE, accepted, version, null);
    }

    private static <T> boolean violates(Set<ConstraintViolation<T>> violations, String field) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    @Test
    @DisplayName("should_reject_when_privacyAcceptedIsNullOrFalse")
    void should_reject_when_privacyAcceptedIsNullOrFalse() {
        assertThat(violates(validator.validate(booking(null, "2026-10-20")), "privacyAccepted")).isTrue();
        assertThat(violates(validator.validate(booking(false, "2026-10-20")), "privacyAccepted")).isTrue();
        assertThat(violates(validator.validate(otp(null, "2026-10-20")), "privacyAccepted")).isTrue();
        assertThat(violates(validator.validate(otp(false, "2026-10-20")), "privacyAccepted")).isTrue();
    }

    @ParameterizedTest(name = "version [{0}] is rejected")
    @ValueSource(strings = {"", "  ", "v1", "2026-10-20; DROP", "2026-10-20\n", "2026-10-20.1234", "2026-1-20",
            "20261020", " 2026-10-20", "2026-10-20 "})
    @DisplayName("should_reject_when_policyVersionIsBlankOrMalformed")
    void should_reject_when_policyVersionIsBlankOrMalformed(String version) {
        assertThat(violates(validator.validate(booking(true, version)), "privacyPolicyVersion")).isTrue();
        assertThat(violates(validator.validate(otp(true, version)), "privacyPolicyVersion")).isTrue();
    }

    @Test
    @DisplayName("should_reject_when_policyVersionIsNullOrLongerThan32")
    void should_reject_when_policyVersionIsNullOrLongerThan32() {
        String tooLong = "2026-10-20." + "1".repeat(22);

        assertThat(tooLong).hasSize(33);
        assertThat(violates(validator.validate(booking(true, null)), "privacyPolicyVersion")).isTrue();
        assertThat(violates(validator.validate(booking(true, tooLong)), "privacyPolicyVersion")).isTrue();
        assertThat(violates(validator.validate(otp(true, tooLong)), "privacyPolicyVersion")).isTrue();
    }

    @ParameterizedTest(name = "version [{0}] is accepted")
    @ValueSource(strings = {"2026-10-20", "2026-10-20.2", "2026-10-20.123"})
    @DisplayName("should_accept_when_consentIsGivenAndVersionIsWellFormed")
    void should_accept_when_consentIsGivenAndVersionIsWellFormed(String version) {
        assertThat(validator.validate(booking(true, version))).isEmpty();
        assertThat(validator.validate(otp(true, version))).isEmpty();
    }

    @ParameterizedTest(name = "version [{0}] is rejected")
    @ValueSource(strings = {"9999-99-99", "2026-13-01", "2026-02-30", "2999-01-01"})
    @DisplayName("should_reject_when_dateIsImpossibleOrFarFuture")
    void should_reject_when_dateIsImpossibleOrFarFuture(String version) {
        assertThat(violates(validator.validate(booking(true, version)), "privacyPolicyVersion")).isTrue();
        assertThat(violates(validator.validate(otp(true, version)), "privacyPolicyVersion")).isTrue();
    }

    @Test
    @DisplayName("should_rejectTomorrowAndAcceptToday_when_kyivDateIsTheReference")
    void should_rejectTomorrowAndAcceptToday_when_kyivDateIsTheReference() {
        java.time.LocalDate today = java.time.LocalDate.now(com.beautica.common.TimeZones.KYIV);
        String tomorrow = today.plusDays(1).toString();

        assertThat(violates(validator.validate(booking(true, tomorrow)), "privacyPolicyVersion")).isTrue();
        assertThat(violates(validator.validate(otp(true, tomorrow + ".1")), "privacyPolicyVersion")).isTrue();
        assertThat(validator.validate(booking(true, today.toString()))).isEmpty();
        assertThat(validator.validate(otp(true, today.minusDays(1) + ".2"))).isEmpty();
    }

    @Test
    @DisplayName("should_failValidation_when_legacyFourArgConstructorUsed")
    void should_failValidation_when_legacyFourArgConstructorUsed() {
        var legacy = new GuestBookingRequest(UUID.randomUUID(), FUTURE, "Олена", "Коваль");

        Set<ConstraintViolation<GuestBookingRequest>> violations = validator.validate(legacy);

        assertThat(violates(violations, "privacyAccepted")).isTrue();
        assertThat(violates(violations, "privacyPolicyVersion")).isTrue();
    }
}
