package com.beautica.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UkrainianPlurals — unit")
class UkrainianPluralsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("should_agreeWithTheNumeral_when_renderingTheServicesPhrase")
    @CsvSource({
            // few (2–4): the form a 2–4 service visit uses
            "2, 2 послуги",
            "3, 3 послуги",
            "4, 4 послуги",
            // many (5–10): the rest of the reachable visit range (MAX_SERVICES_PER_VISIT = 10)
            "5, 5 послуг",
            "9, 9 послуг",
            "10, 10 послуг",
            // the 11–14 exception — always "many", never "few", despite ending in 1/2/3/4
            "11, 11 послуг",
            "12, 12 послуг",
            "14, 14 послуг",
            // back to the digit rule above 14
            "21, 21 послуга",
            "22, 22 послуги",
            "25, 25 послуг",
            // not reachable in production (every call site is behind a multi-service branch), but
            // must still be well-formed rather than throwing inside a notification
            "1, 1 послуга",
            "0, 0 послуг"
    })
    void should_agreeWithTheNumeral_when_renderingTheServicesPhrase(int count, String expected) {
        assertThat(UkrainianPlurals.servicesPhrase(count)).isEqualTo(expected);
    }

    @Test
    @DisplayName("should_applyTheSameLastDigitRule_when_theCountIsIntegerMinValue")
    void should_applyTheSameLastDigitRule_when_theCountIsIntegerMinValue() {
        // Math.abs(Integer.MIN_VALUE) overflows back to Integer.MIN_VALUE, so the previous
        // abs()-then-% form produced a NEGATIVE remainder that matched no `case` and reached the
        // "many" form only by falling off the end of the switch — the one int that escaped the
        // documented rule entirely. Integer.MIN_VALUE ends in 2, so under floorMod it agrees like
        // any other ...2, which is what this pins.
        assertThat(UkrainianPlurals.servicesNoun(Integer.MIN_VALUE))
                .isEqualTo(UkrainianPlurals.servicesNoun(2));
    }

    @ParameterizedTest(name = "count={0}")
    @DisplayName("should_returnAWellFormedNoun_when_theCountIsNegative")
    @ValueSource(ints = {-1, -2, -11, -14, -21, Integer.MIN_VALUE})
    void should_returnAWellFormedNoun_when_theCountIsNegative(int count) {
        // Unreachable in production (every call site sits behind a multi-service branch), but a
        // notification must never fail on copy — no exception, no empty string, for any int.
        assertThat(UkrainianPlurals.servicesNoun(count)).isNotBlank();
    }
}
