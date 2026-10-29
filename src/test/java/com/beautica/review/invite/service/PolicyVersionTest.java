package com.beautica.review.invite.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PolicyVersion — phase 400 D2 ordering")
class PolicyVersionTest {

    @ParameterizedTest(name = "{0} >= {1} is {2}")
    @DisplayName("versions order by date, then by revision suffix")
    @CsvSource({
            "2026-10-20, 2026-10-20, true",
            "2026-10-21, 2026-10-20, true",
            "2026-10-19, 2026-10-20, false",
            "2026-10-20.1, 2026-10-20, true",
            "2026-10-20, 2026-10-20.1, false",
            "2026-10-20.10, 2026-10-20.9, true",
            "2027-01-01, 2026-12-31.999, true"
    })
    void should_orderByDateThenRevision_when_compared(String accepted, String minimum, boolean expected) {
        PolicyVersion a = PolicyVersion.parse(accepted).orElseThrow();
        PolicyVersion min = PolicyVersion.parse(minimum).orElseThrow();

        boolean result = a.isAtLeast(min);

        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("a blank, malformed or impossible-date version parses to empty")
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "v1", "2026-13-01", "2026-02-30", "2026-10-20.1234", "20261020"})
    void should_returnEmpty_when_versionInvalid(String raw) {
        assertThat(PolicyVersion.parse(raw)).isEmpty();
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated")
    void should_parse_when_versionHasSurroundingWhitespace() {
        assertThat(PolicyVersion.parse(" 2026-10-20 ")).isPresent();
    }
}
