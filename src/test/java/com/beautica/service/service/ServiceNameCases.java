package com.beautica.service.service;

import org.junit.jupiter.params.provider.Arguments;

import java.util.stream.Stream;

/**
 * Shared phase 464 name ALLOW-LIST cases for the create / bulk / PATCH service tests. Rejected
 * characters are embedded mid-word so {@code strip()} can never remove them.
 */
final class ServiceNameCases {

    static final String INVALID_NAME_MESSAGE = "Name contains invalid characters";

    private ServiceNameCases() {
    }

    /** {@code (label, name)} — every name must be rejected with 400 by {@code canonicalName}. */
    static Stream<Arguments> rejectedNames() {
        return Stream.of(
                "\uD83D\uDC85",   // emoji U+1F485 (So)
                "\u034F",         // combining grapheme joiner (Mn)
                "\uFE0F",         // variation selector 16 (Mn)
                "\u17B4",         // Khmer inherent vowel (Mn)
                "\uDB40\uDD00",   // VS17 U+E0100 (Mn, surrogate pair)
                "\u200B",         // zero-width space (Cf)
                "\u3164",         // Hangul filler (Lo) — NFKC → U+1160, still a filler
                "\uD834\uDD59",   // U+1D159 musical null notehead (So)
                "\u2028",         // line separator (Zl)
                "\u0085",         // NEL (Cc)
                "#", "@",
                "\u2605"          // black star (So)
        ).map(ch -> Arguments.of(String.format("U+%04X", ch.codePointAt(0)), "Мані" + ch + "кюр"));
    }

    /** {@code (input, storedName)} — accepted, stored in canonical form. */
    static Stream<Arguments> acceptedNames() {
        return Stream.of(
                Arguments.of("Манікюр", "Манікюр"),
                Arguments.of("Пам'ять", "Пам'ять"),
                Arguments.of("пам\u2019ять", "пам\u2019ять"),
                Arguments.of("пам\u02BCять", "пам\u02BCять"),
                Arguments.of("Стрижка + укладка", "Стрижка + укладка"),
                // № (U+2116) is protected from NFKC's fold to "No".
                Arguments.of("Масаж (60 хв) \u21162", "Масаж (60 хв) \u21162"),
                Arguments.of("\u2116\u00A0\uFF15 \u2116", "\u2116 5 \u2116"),
                Arguments.of("SPA-догляд", "SPA-догляд"),
                Arguments.of("Стрижка  +   укладка", "Стрижка + укладка"),
                Arguments.of("Маніи\u0306", "Маній"));
    }
}
