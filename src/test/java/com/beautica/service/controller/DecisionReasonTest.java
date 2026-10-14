package com.beautica.service.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DecisionReason — reject-form reason normalisation (phase 372 D4)")
class DecisionReasonTest {

    @Test
    @DisplayName("raw input over the 4x hard cap is TOO_LONG before any regex runs, even if it would clean to empty")
    void should_returnTooLong_when_rawExceedsHardCap_withoutRegexWork() {
        // 1201 chars of pure control chars would clean to empty (NONE) if regexes ran; TOO_LONG proves the early exit.
        String raw = "\u0007".repeat(4 * DecisionReason.MAX_LENGTH + 1);

        DecisionReason.Parsed parsed = DecisionReason.parse(raw);

        assertThat(parsed).isSameAs(DecisionReason.Parsed.TOO_LONG);
    }

    @Test
    @DisplayName("raw input exactly at the hard cap is still cleaned (control-only collapses to no reason)")
    void should_stillClean_when_rawAtHardCap() {
        String raw = "\u0007".repeat(4 * DecisionReason.MAX_LENGTH);

        DecisionReason.Parsed parsed = DecisionReason.parse(raw);

        assertThat(parsed).isSameAs(DecisionReason.Parsed.NONE);
    }

    @ParameterizedTest(name = "[{index}] blank/absent → no reason: \"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "   ", "\n\t\r", "​­", " \u0000 "})
    @DisplayName("null, blank, whitespace-only and control/format-only input all mean 'no reason'")
    void should_returnNone_when_inputBlankOrOnlyInvisible(String raw) {
        DecisionReason.Parsed parsed = DecisionReason.parse(raw);

        assertThat(parsed).isSameAs(DecisionReason.Parsed.NONE);
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed and the cleaned value kept")
    void should_trim_when_reasonHasSurroundingWhitespace() {
        DecisionReason.Parsed parsed = DecisionReason.parse("  Дублікат  ");

        assertThat(parsed.value()).isEqualTo("Дублікат");
        assertThat(parsed.tooLong()).isFalse();
    }

    @Test
    @DisplayName("a run of CR/LF/TAB folds to ONE space; other control and zero-width format chars are removed")
    void should_foldLineBreaksAndStripControlAndFormatChars_when_present() {
        DecisionReason.Parsed parsed = DecisionReason.parse("Рядок1\r\n\tРядок2\u0007​кінець");

        assertThat(parsed.value()).isEqualTo("Рядок1 Рядок2кінець");
    }

    @Test
    @DisplayName("markup is NOT escaped here — escaping belongs to th:text at render time")
    void should_keepMarkupVerbatim_when_reasonContainsHtml() {
        DecisionReason.Parsed parsed = DecisionReason.parse("<b>x</b> & \"q\"");

        assertThat(parsed.value()).isEqualTo("<b>x</b> & \"q\"");
    }

    @Test
    @DisplayName("exactly 300 code points is accepted; 301 is TOO_LONG (Q8 boundary)")
    void should_acceptExactly300_and_rejectOver_when_atBoundary() {
        assertThat(DecisionReason.parse("ж".repeat(300)).value()).hasSize(300);
        assertThat(DecisionReason.parse("ж".repeat(301))).isSameAs(DecisionReason.Parsed.TOO_LONG);
    }

    @Test
    @DisplayName("the 300 cap counts code points, not UTF-16 chars — 300 emoji (600 chars) are accepted, 301 are not")
    void should_countCodePoints_when_reasonHasSurrogatePairs() {
        String emoji = "😀";

        assertThat(DecisionReason.parse(emoji.repeat(300)).value()).isEqualTo(emoji.repeat(300));
        assertThat(DecisionReason.parse(emoji.repeat(301))).isSameAs(DecisionReason.Parsed.TOO_LONG);
    }

    @Test
    @DisplayName("the 300 cap applies AFTER cleaning — 300 visible chars padded with whitespace are accepted")
    void should_measureLengthAfterCleaning_when_paddedWithWhitespace() {
        String raw = "   " + "ж".repeat(300) + "\n\n   ";

        assertThat(DecisionReason.parse(raw).value()).isEqualTo("ж".repeat(300));
    }
}
