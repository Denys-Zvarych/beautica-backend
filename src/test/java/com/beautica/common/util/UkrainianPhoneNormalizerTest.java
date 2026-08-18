package com.beautica.common.util;

import com.beautica.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 22.2 — the RAW-INPUT → E.164 conversion that stands between a staff-typed phone and
 * {@code chk_bookings_guest_phone_format} (V89).
 *
 * <p>Every accepted case below is asserted against {@link #DB_CHECK}, a literal transcription of
 * that database CHECK, so this suite fails the moment the normaliser could emit something the
 * insert would reject — the whole reason it exists.
 */
@DisplayName("UkrainianPhoneNormalizer — staff-typed phone → E.164")
class UkrainianPhoneNormalizerTest {

    /** {@code chk_bookings_guest_phone_format} (V89), transcribed literally. */
    private static final Pattern DB_CHECK = Pattern.compile("^\\+[0-9]{6,18}$");

    private static final String EXPECTED = "+380501234567";

    @ParameterizedTest(name = "\"{0}\" → +380501234567")
    @ValueSource(strings = {
            "+380501234567",        // already E.164 — the only shape a pre-22.2 producer ever emitted
            "380501234567",         // country code without the plus
            "00380501234567",       // ITU international-access prefix
            "0501234567",           // national trunk form — the one a human actually types
            "501234567",            // bare subscriber number
            "050 123 45 67",        // spaces, exactly as the phase doc's example spells it
            "+38 (050) 123-45-67",  // the printed form on a salon business card
            "  0 5 0 1 2 3 4 5 6 7  ",
            "050.123.45.67",
            "+380-50-123-45-67",
    })
    @DisplayName("normalises every accepted spelling to the same E.164 number")
    void should_normaliseToE164_when_inputIsAnyAcceptedUkrainianSpelling(String raw) {
        String normalised = UkrainianPhoneNormalizer.toE164(raw);

        assertThat(normalised).isEqualTo(EXPECTED);
        assertThat(DB_CHECK.matcher(normalised).matches())
                .as("output must satisfy chk_bookings_guest_phone_format, or the insert 500s")
                .isTrue();
    }

    @Test
    @DisplayName("the raw spacing form the phase doc names is NOT already E.164 — the conversion is real")
    void should_actuallyConvert_when_inputIsTheRawSpacedForm() {
        String raw = "050 123 45 67";

        assertThat(DB_CHECK.matcher(raw).matches())
                .as("guard against a vacuous suite: the raw input must FAIL the DB check")
                .isFalse();
        assertThat(UkrainianPhoneNormalizer.toE164(raw)).isNotEqualTo(raw);
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @ValueSource(strings = {
            "",
            "   ",
            "05012345",             // 8 digits — too short
            "05012345678",          // 11 digits — too long
            "+12025550142",         // valid E.164, but not Ukrainian (documented limitation)
            "+380501234",           // UA country code, wrong subscriber length
            "+0501234567",          // trunk 0 written as a country code
            "+501234567",           // bare subscriber written as a country code
            "050ABC4567",
            "050-123-45-67 ext 12",
            "+380501234567890123456789012345678901234567890",   // over the raw-length ceiling
    })
    @DisplayName("rejects anything that is not a Ukrainian number, with one generic 400")
    void should_reject_when_inputIsNotAUkrainianNumber(String raw) {
        assertThatThrownBy(() -> UkrainianPhoneNormalizer.toE164(raw))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid phone format")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("rejects null without dereferencing it")
    void should_reject_when_inputIsNull() {
        assertThatThrownBy(() -> UkrainianPhoneNormalizer.toE164(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid phone format");
    }
}
