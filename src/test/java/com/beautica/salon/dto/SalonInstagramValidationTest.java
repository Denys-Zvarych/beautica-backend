package com.beautica.salon.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation unit guards for the widened {@code instagramUrl} {@code @Pattern} on the
 * salon create/update DTOs (Phase 20.x). The pattern was widened from URL-only to
 * {@code ^$|^@?[A-Za-z0-9._]{1,30}$|^https://(www\.)?instagram\.com/[A-Za-z0-9._]+/?$} — mirroring
 * {@code MasterProfileUpdateRequest.instagram} — so a bare handle (with or without a leading
 * {@code @}) is now accepted alongside the pre-existing full instagram.com URL.
 *
 * <p>Before this test class, no test anywhere in {@code com.beautica.salon} ever exercised
 * {@code instagramUrl} with a non-null value: the DB-level {@code V105SalonInstagramConstraintMigrationTest}
 * covers only the raw-JDBC CHECK constraint, bypassing {@code @Valid} entirely. This class closes
 * that gap at the boundary where the widen actually takes effect for API callers.
 */
@DisplayName("Salon request DTOs - instagramUrl widened validation")
class SalonInstagramValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static CreateSalonRequest createWithInstagram(String instagramUrl) {
        // Field order: name, description, city, region, address, phone, instagramUrl,
        //              cityId, districtId, street, buildingNo, locationNote
        return new CreateSalonRequest(
                "My Salon", null, null, null, null, null, instagramUrl,
                null, null, null, null, null);
    }

    private static UpdateSalonRequest updateWithInstagram(String instagramUrl) {
        // Field order: name, description, city, region, address, cityId, districtId,
        //              street, buildingNo, locationNote, phone, instagramUrl
        return new UpdateSalonRequest(
                "My Salon", null, null, null, null,
                null, null, null, null, null, null, instagramUrl);
    }

    private static boolean hasInstagramViolation(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("instagramUrl"));
    }

    // -- positive: this is the actual feature under review — bare handles now accepted --------

    @Test
    @DisplayName("CreateSalonRequest accepts a bare at-prefixed instagram handle")
    void should_acceptCreateInstagram_when_atPrefixedHandle() {
        assertThat(hasInstagramViolation(validator.validate(createWithInstagram("@some.handle"))))
                .as("a bare @handle must now be accepted (the feature under review)")
                .isFalse();
    }

    @Test
    @DisplayName("UpdateSalonRequest accepts a bare at-prefixed instagram handle")
    void should_acceptUpdateInstagram_when_atPrefixedHandle() {
        assertThat(hasInstagramViolation(validator.validate(updateWithInstagram("@some.handle"))))
                .as("a bare @handle must now be accepted (the feature under review)")
                .isFalse();
    }

    @Test
    @DisplayName("CreateSalonRequest accepts an instagram handle without a leading at-sign")
    void should_acceptCreateInstagram_when_handleWithoutAtSign() {
        assertThat(hasInstagramViolation(validator.validate(createWithInstagram("some_handle"))))
                .as("an @-less handle must be accepted")
                .isFalse();
    }

    @Test
    @DisplayName("CreateSalonRequest accepts a full instagram.com URL (regression guard for pre-widen behavior)")
    void should_acceptCreateInstagram_when_fullInstagramUrl() {
        assertThat(hasInstagramViolation(validator.validate(createWithInstagram("https://instagram.com/some.handle"))))
                .as("the pre-existing full-URL form must still be accepted")
                .isFalse();
    }

    @Test
    @DisplayName("UpdateSalonRequest accepts a full www.instagram.com URL with trailing slash (regression guard)")
    void should_acceptUpdateInstagram_when_fullWwwInstagramUrlWithTrailingSlash() {
        assertThat(hasInstagramViolation(validator.validate(updateWithInstagram("https://www.instagram.com/some.handle/"))))
                .as("the pre-existing full-URL form (www + trailing slash) must still be accepted")
                .isFalse();
    }

    @Test
    @DisplayName("CreateSalonRequest accepts a blank instagramUrl (field is optional)")
    void should_acceptCreateInstagram_when_blank() {
        assertThat(hasInstagramViolation(validator.validate(createWithInstagram(""))))
                .as("an empty string must be accepted — instagramUrl is optional")
                .isFalse();
    }

    @Test
    @DisplayName("CreateSalonRequest accepts a 30-character handle (upper length boundary)")
    void should_acceptCreateInstagram_when_handleIsExactly30Chars() {
        String handle = "a".repeat(30);

        assertThat(hasInstagramViolation(validator.validate(createWithInstagram(handle))))
                .as("a 30-char handle is exactly at the {1,30} boundary and must be accepted")
                .isFalse();
    }

    // -- negative: neither handle nor URL pattern, or the old over-permissive gap --------------

    @Test
    @DisplayName("CreateSalonRequest rejects a value that matches neither the handle nor the URL pattern")
    void should_rejectCreateInstagram_when_neitherHandleNorUrlPattern() {
        assertThat(hasInstagramViolation(validator.validate(createWithInstagram("not a real value!!"))))
                .as("a value with spaces/punctuation outside [A-Za-z0-9._] must still be rejected")
                .isTrue();
    }

    @Test
    @DisplayName("UpdateSalonRequest rejects a plain http:// instagram URL (only https is accepted)")
    void should_rejectUpdateInstagram_when_httpUrlNotHttps() {
        assertThat(hasInstagramViolation(validator.validate(updateWithInstagram("http://instagram.com/some.handle"))))
                .as("http:// (not https://) must still be rejected, same as before the widen")
                .isTrue();
    }

    @Test
    @DisplayName("CreateSalonRequest rejects a handle longer than 30 characters (upper length boundary)")
    void should_rejectCreateInstagram_when_handleExceeds30Chars() {
        String tooLong = "a".repeat(31);

        assertThat(hasInstagramViolation(validator.validate(createWithInstagram(tooLong))))
                .as("a 31-char handle exceeds the {1,30} boundary and must be rejected")
                .isTrue();
    }

    @Test
    @DisplayName("UpdateSalonRequest rejects a handle containing a space and an exclamation mark")
    void should_rejectUpdateInstagram_when_handleContainsInvalidChars() {
        assertThat(hasInstagramViolation(validator.validate(updateWithInstagram("some handle!"))))
                .as("space and ! are outside [A-Za-z0-9._] and must be rejected")
                .isTrue();
    }
}
