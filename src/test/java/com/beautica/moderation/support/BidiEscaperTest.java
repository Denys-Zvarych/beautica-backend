package com.beautica.moderation.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BidiEscaper — invisible controls become visible \\uXXXX (phases 396 / 414)")
class BidiEscaperTest {

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {"061C", "200B", "200C", "200D", "200E", "200F", "2028", "2029", "202A", "202E", "2060",
            "2061", "2062", "2063", "2064", "2066", "2069", "FEFF"})
    @DisplayName("every bidi / separator / zero-width control is escaped")
    void should_escape_when_invisibleControl(String hex) {
        String control = String.valueOf((char) Integer.parseInt(hex, 16));

        String escaped = BidiEscaper.escape("a" + control + "b");

        assertThat(escaped).isEqualTo("a\\u" + hex + "b");
    }

    @Test
    @DisplayName("ordinary text (Cyrillic, emoji, NBSP) and null pass through unchanged")
    void should_keepText_when_noInvisibleControl() {
        assertThat(BidiEscaper.escape("Привіт 👋 світ")).isEqualTo("Привіт 👋 світ");
        assertThat(BidiEscaper.escape(null)).isNull();
    }
}
