package com.beautica.common.format;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Parity table with {@code beautica-mobile/lib/shared/formatters/duration_minutes.dart}. */
class DurationLabelTest {

    @ParameterizedTest(name = "{0} min -> {1}")
    @CsvSource({
            "0, 0 хв",
            "45, 45 хв",
            "59, 59 хв",
            "60, 1 год",
            "90, 1 год 30 хв",
            "150, 2 год 30 хв",
            "600, 10 год"
    })
    void should_formatAppStyle_when_minutesGiven(int minutes, String expected) {
        assertThat(DurationLabel.format(minutes)).isEqualTo(expected);
    }
}
