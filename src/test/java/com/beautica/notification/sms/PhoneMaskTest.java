package com.beautica.notification.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 22.7 — the one masking rule, asserted once.
 *
 * <p>{@link PhoneMask} was extracted from {@link TurbosmsService} so that class and
 * {@link NoOpSmsService} could not drift into two definitions of "masked". Extraction moved the rule
 * but left it covered only INCIDENTALLY — {@code TurbosmsServiceTest} pins one well-formed number,
 * {@code NoOpSmsServiceTest} pins another and asserts only that null/blank do not throw. The
 * defensive branches (null, blank, shorter-than-four) had no assertion on what they actually
 * RETURN, and a drifted mask is not a cosmetic bug: it is a PII leak (Anti-Bug §I-3).
 */
@DisplayName("PhoneMask — the shared recipient-masking rule")
class PhoneMaskTest {

    @Test
    @DisplayName("a Ukrainian E.164 number keeps only its last four digits")
    void should_keepOnlyTheLastFourDigits_when_thePhoneIsWellFormed() {
        assertThat(PhoneMask.mask("+380671234567")).isEqualTo("+380***4567");
    }

    @ParameterizedTest(name = "\"{0}\" is not enough to dial")
    @ValueSource(strings = {"+380671234567", "+380501234567", "+380931112233"})
    @DisplayName("the masked form never contains the subscriber digits it is meant to hide")
    void should_hideTheSubscriberDigits_when_masking(String phone) {
        String masked = PhoneMask.mask(phone);

        assertThat(masked).doesNotContain(phone);
        assertThat(masked)
                .as("everything between the country code and the last four must be gone")
                .doesNotContain(phone.substring(4, phone.length() - 4));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("a null or blank recipient renders a placeholder rather than throwing")
    void should_renderThePlaceholder_when_thePhoneIsNullOrBlank(String phone) {
        // Callers log through this inside a catch block, so a throw here would replace a provider
        // failure with a masking failure and lose the original entirely.
        assertThat(PhoneMask.mask(phone)).isEqualTo("+380***????");
    }

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @CsvSource({"1234, +380***1234", "12, +380***12", "'+', +380***+"})
    @DisplayName("a recipient of four characters or fewer is emitted whole, never substring-clipped")
    void should_emitTheWholeValue_when_itIsNoLongerThanTheVisibleTail(String phone, String expected) {
        // The guard exists so substring(length - 4) cannot raise StringIndexOutOfBounds on a
        // malformed value. Nothing is leaked: a value this short is not a dialable number.
        assertThat(PhoneMask.mask(phone)).isEqualTo(expected);
    }
}
