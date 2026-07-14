package com.beautica.booking.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BookingComments.normalize — table-driven (Phase 25.1)")
class BookingCommentsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void should_normalizeAsExpected(String caseName, String input, String expected) {
        assertThat(BookingComments.normalize(input)).isEqualTo(expected);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("null input stays null", null, null),
                Arguments.of("blank-only input becomes null", "   ", null),
                Arguments.of("whitespace-only tabs/newlines become null", "\t\n  \n\t", null),
                Arguments.of("CRLF collapses to LF", "a\r\nb", "a\nb"),
                Arguments.of("lone CR collapses to LF", "a\rb", "a\nb"),
                Arguments.of("three CRLF runs collapse to one blank line",
                        "a\r\n\r\n\r\nb", "a\n\nb"),
                Arguments.of("four+ LF runs collapse to one blank line",
                        "a\n\n\n\nb", "a\n\nb"),
                Arguments.of("leading/trailing whitespace is stripped", "  hello  ", "hello"),
                Arguments.of("bidi override characters are stripped",
                        "please ‮cancel‬ this", "please cancel this"),
                Arguments.of("bidi isolate characters are stripped",
                        "⁦right to left⁩ text", "right to left text"),
                Arguments.of("zero-width characters are stripped",
                        "zero​width‌space‍joiner", "zerowidthspacejoiner"),
                Arguments.of("BOM is stripped", "﻿text with BOM", "text with BOM"),
                Arguments.of("left-to-right mark (U+200E) is stripped",
                        "price‎: 500", "price: 500"),
                Arguments.of("right-to-left mark (U+200F) is stripped",
                        "price‏: 500", "price: 500"),
                Arguments.of("Arabic letter mark (U+061C) is stripped",
                        "price؜: 500", "price: 500"),
                Arguments.of("word joiner (U+2060) is stripped",
                        "zero⁠width⁠joiner", "zerowidthjoiner"),
                Arguments.of("soft hyphen (U+00AD) is stripped (Cf, category-wide strip)",
                        "hy­phen", "hyphen"),
                Arguments.of("a normal two-line comment is preserved verbatim",
                        "Please arrive early.\nParking is limited.",
                        "Please arrive early.\nParking is limited."),
                Arguments.of("a 1000-char input survives unchanged", "a".repeat(1000), "a".repeat(1000))
        );
    }
}
