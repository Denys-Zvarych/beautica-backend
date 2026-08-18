package com.beautica.booking.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>LOW-FIX REGRESSION NET (Phase 13.3): control-char guard on guest name/surname.</b>
 *
 * <p>{@code GuestBookingRequest.name} / {@code surname} are free-text fields persisted to
 * {@code VARCHAR(100)} and rendered into the confirmation SMS. They currently carry only
 * {@code @NotBlank @Size(max = 100)} — an attacker can embed NUL / C0 / DEL control characters
 * (header/SMS-injection surface, log-poisoning). The fix adds a control-char {@code @Pattern}
 * to both fields (mirroring the booking-comment guard, but single-line: TAB / LF / CR are also
 * rejected since a name is never multi-line).
 *
 * <p><b>The negative tests are EXPECTED-RED until backend-dev adds the {@code @Pattern}.</b>
 * The positive test (a clean Ukrainian name is accepted) must stay GREEN before and after the
 * fix — it pins that the new pattern does not reject legitimate Cyrillic / hyphen / apostrophe
 * names.
 */
@DisplayName("GuestBookingRequest — name/surname control-char validation (LOW-fix regression, negatives expected-red until fix lands)")
class GuestBookingRequestControlCharValidationTest {

    private static final String NUL = "\u0000";
    private static final String VTAB = "\u000B";
    private static final String DEL = "\u007F";
    private static final String TAB = "\t";
    private static final String LF = "\n";

    private static final OffsetDateTime FUTURE = OffsetDateTime.parse("2099-06-10T12:00:00+03:00");

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static GuestBookingRequest withName(String name) {
        return new GuestBookingRequest(UUID.randomUUID(), FUTURE, name, "Коваль");
    }

    private static GuestBookingRequest withSurname(String surname) {
        return new GuestBookingRequest(UUID.randomUUID(), FUTURE, "Олена", surname);
    }

    private static boolean hasFieldViolation(Set<ConstraintViolation<GuestBookingRequest>> violations,
                                             String field) {
        return violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    // ── positive: legitimate Cyrillic names accepted (must stay GREEN) ─────────

    @Test
    @DisplayName("accepts a clean Cyrillic name with hyphen and apostrophe")
    void should_accept_when_nameIsCleanCyrillic() {
        var req = new GuestBookingRequest(UUID.randomUUID(), FUTURE, "Марія-Анна", "О'Коваль");

        assertThat(validator.validate(req))
                .as("a clean Cyrillic name with hyphen/apostrophe must be accepted")
                .isEmpty();
    }

    // ── negative: control chars rejected (EXPECTED-RED until @Pattern added) ───

    @Test
    @DisplayName("should_reject_when_guestNameContainsControlChars (NUL)")
    void should_reject_when_guestNameContainsControlChars() {
        var req = withName("Оле" + NUL + "на");

        assertThat(hasFieldViolation(validator.validate(req), "name"))
                .as("name with embedded NUL must fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("should reject name with a vertical tab (0x0B)")
    void should_reject_when_nameContainsVerticalTab() {
        var req = withName("Оле" + VTAB + "на");

        assertThat(hasFieldViolation(validator.validate(req), "name"))
                .as("name with VT (0x0B) must fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("should reject name with a DEL char (0x7F)")
    void should_reject_when_nameContainsDel() {
        var req = withName("Оле" + DEL + "на");

        assertThat(hasFieldViolation(validator.validate(req), "name"))
                .as("name with DEL (0x7F) must fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("should reject name with a newline (single-line field)")
    void should_reject_when_nameContainsNewline() {
        var req = withName("Олена" + LF + "Коваль");

        assertThat(hasFieldViolation(validator.validate(req), "name"))
                .as("a name is single-line — an embedded LF must fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("should reject surname with an embedded NUL byte")
    void should_reject_when_surnameContainsNul() {
        var req = withSurname("Ко" + NUL + "валь");

        assertThat(hasFieldViolation(validator.validate(req), "surname"))
                .as("surname with embedded NUL must fail @Pattern validation")
                .isTrue();
    }

    @Test
    @DisplayName("should reject surname with a tab (single-line field)")
    void should_reject_when_surnameContainsTab() {
        var req = withSurname("Ко" + TAB + "валь");

        assertThat(hasFieldViolation(validator.validate(req), "surname"))
                .as("a surname is single-line — an embedded TAB must fail @Pattern validation")
                .isTrue();
    }

    // ── phone-from-JWT-only pin ────────────────────────────────────────────────
    // The guest phone is the OTP-verified `sub` claim of the guest JWT, extracted server-side
    // in GuestBookingService — it must NEVER be a request-body field, or a caller could book on
    // behalf of an arbitrary phone. This structural pin breaks the moment someone adds a phone
    // (or msisdn/tel/...) component to the record.

    @Test
    @DisplayName("GuestBookingRequest exposes no phone field — phone is taken only from the guest JWT")
    void should_haveNoPhoneComponent_when_inspectingRecord() {
        var componentNames = java.util.Arrays.stream(GuestBookingRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(componentNames)
                .as("phone must NOT be a request-body field; it comes from the guest JWT sub claim")
                // masterServiceIds added in BE-7 (multi-service guest visit); phone still absent.
                .containsExactlyInAnyOrder("serviceId", "masterServiceIds", "startsAt", "name", "surname")
                .noneMatch(n -> n.toLowerCase().contains("phone")
                        || n.toLowerCase().contains("msisdn")
                        || n.toLowerCase().equals("tel"));
    }
}
