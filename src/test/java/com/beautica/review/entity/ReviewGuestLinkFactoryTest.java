package com.beautica.review.entity;

import com.beautica.booking.entity.Booking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Phase 399 audit — {@link Review#guestLinkReview} builds the public «Ім'я П.» label itself
 * ({@link GuestDisplayName}): never throws on a booking-valid guest name (≤ 100 chars), never publishes a full surname
 * or a phone fragment, always fits {@code VARCHAR(64)} and the V212 no-digits CHECK.
 */
@DisplayName("Review.guestLinkReview — guest display name rule (unit)")
class ReviewGuestLinkFactoryTest {

    private static final int DB_COLUMN_MAX = 64;
    private static final String FALLBACK = "Гість";

    @Test
    @DisplayName("name + surname → «Ім'я П.»")
    void should_renderNameAndInitial_when_nameAndSurnamePresent() {
        String label = displayName("Олена", "Кравець");

        assertThat(label).isEqualTo("Олена К.");
    }

    @Test
    @DisplayName("blank or absent surname → name only")
    void should_renderNameOnly_when_surnameBlankOrNull() {
        String blank = displayName("Олена", "   ");
        String absent = displayName("Олена", null);

        assertThat(blank).isEqualTo("Олена");
        assertThat(absent).isEqualTo("Олена");
    }

    @Test
    @DisplayName("a 100-char name (GuestClientDto max) is cut to 60 code points, never throws, fits VARCHAR(64)")
    void should_truncate_when_guestNameIs100Chars() {
        String hundred = "Я".repeat(100);

        String label = displayName(hundred, "Кравець");

        assertThat(label).isEqualTo("Я".repeat(60) + " К.");
        assertThat(label.codePointCount(0, label.length())).isLessThanOrEqualTo(DB_COLUMN_MAX);
    }

    @Test
    @DisplayName("truncation never splits a surrogate pair")
    void should_truncateByCodePoint_when_nameHoldsSupplementaryLetters() {
        String gothic = "𐌰".repeat(70);

        String label = displayName(gothic, null);

        assertThat(label.codePointCount(0, label.length())).isEqualTo(60);
        assertThat(label).isEqualTo("𐌰".repeat(60));
    }

    @Test
    @DisplayName("a two-word name field («Олена Кравець») never publishes the second word")
    void should_notRenderFullSurname_when_nameFieldHoldsTwoWords() {
        String label = displayName("Олена Кравець", null);

        assertThat(label).isEqualTo("Олена").doesNotContain("Кравець");
    }

    @ParameterizedTest(name = "[{index}] name=«{0}»")
    @MethodSource("nonAsciiSeparatedNames")
    @DisplayName("NBSP / zero-width space / dot / plus separate words — the surname never glues onto the name")
    void should_notRenderFullSurname_when_wordsSeparatedByNbspOrZwspOrDot(String name) {
        String label = displayName(name, null);

        assertThat(label).isEqualTo("Олена");
    }

    static Stream<Arguments> nonAsciiSeparatedNames() {
        return Stream.of(
                Arguments.of("Олена\u00A0Кравець"),
                Arguments.of("Олена\u200BКравець"),
                Arguments.of("Олена.Кравець"),
                Arguments.of("Олена+Кравець"),
                Arguments.of("Олена\u3164Кравець"));
    }

    @Test
    @DisplayName("a Hangul-filler-only name renders as nothing, so it falls back to «Гість» with no initial")
    void should_fallBackToGuestLabel_when_nameIsHangulFiller() {
        String halfwidthAndJamo = displayName("\uFFA0\u115F\u1160", "Кравець");

        String label = displayName("\u3164", "Кравець");

        assertThat(label).isEqualTo(FALLBACK);
        assertThat(halfwidthAndJamo).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("a Hangul-filler-only surname yields no initial; a filler before a letter is skipped")
    void should_omitInitial_when_surnameIsHangulFiller() {
        String fillerOnly = displayName("Олена", "\u3164");
        String fillerFirst = displayName("Олена", "\u3164Кравець");

        assertThat(fillerOnly).isEqualTo("Олена");
        assertThat(fillerFirst).isEqualTo("Олена К.");
    }

    @Test
    @DisplayName("at most two combining marks are kept after a base letter (Zalgo stacking bounded)")
    void should_dropExcessCombiningMarks_when_moreThanTwoFollowBaseCharacter() {
        String zalgo = "О" + "\u0301\u0300\u0302\u0303\u0304" + "лена";

        String label = displayName(zalgo, null);

        assertThat(label).isEqualTo("О\u0301\u0300лена");
    }

    @Test
    @DisplayName("a legitimate single combining mark (й as и + U+0306) is kept")
    void should_keepCombiningMark_when_withinBound() {
        String decomposed = "Андри\u0306";

        String label = displayName(decomposed, null);

        assertThat(label).isEqualTo(decomposed);
    }

    @Test
    @DisplayName("a supplementary-plane surname initial is one whole code point, never a lone surrogate")
    void should_renderWholeCodePointInitial_when_surnameStartsWithSupplementaryLetter() {
        String label = displayName("Олена", "𐌰𐌱");

        assertThat(label).isEqualTo("Олена 𐌰.");
    }

    @Test
    @DisplayName("digits and phone-like characters are stripped from the name; the surname initial skips digits")
    void should_stripDigits_when_nameOrSurnameHoldsPhone() {
        String glued = displayName("Олена+380501234567", "0501234567Кравець");
        String leadingPhone = displayName("+380 50 123 45 67 Олена", null);

        assertThat(glued).isEqualTo("Олена К.");
        assertThat(leadingPhone).isEqualTo("Олена");
        assertThat(glued + leadingPhone).doesNotContainPattern("[0-9]");
    }

    @ParameterizedTest(name = "[{index}] name=«{0}»")
    @MethodSource("unusableNames")
    @DisplayName("nothing name-like left → neutral «Гість», no initial")
    void should_fallBackToGuestLabel_when_nameHasNoLetters(String name) {
        String label = displayName(name, "Кравець");

        assertThat(label).isEqualTo(FALLBACK);
    }

    static Stream<Arguments> unusableNames() {
        return Stream.of(
                Arguments.of("+380 50 123 45 67"),
                Arguments.of("0501234567"),
                Arguments.of("   "),
                Arguments.of("-- ''"),
                Arguments.of((Object) null));
    }

    @Test
    @DisplayName("apostrophes and hyphens inside a name survive; edge punctuation is trimmed")
    void should_keepInnerApostropheAndHyphen_when_namePunctuated() {
        String apostrophe = displayName("Мар'яна", "Ющенко");
        String hyphen = displayName("-Анна-Марія-", null);

        assertThat(apostrophe).isEqualTo("Мар'яна Ю.");
        assertThat(hyphen).isEqualTo("Анна-Марія");
    }

    @Test
    @DisplayName("the factory stores the built label on a GUEST_LINK review with no account")
    void should_storeBuiltLabel_when_factoryUsed() {
        Booking booking = mock(Booking.class);

        Review review = Review.guestLinkReview(booking, (short) 5, null, "Олена Кравець", "Кравець");

        assertThat(review.getGuestDisplayName()).isEqualTo("Олена К.");
        assertThat(review.getSource()).isEqualTo(ReviewSource.GUEST_LINK);
        assertThat(review.getClient()).isNull();
    }

    @ParameterizedTest(name = "[{index}] name=«{0}» surname=«{1}»")
    @MethodSource("factoryInputs")
    @DisplayName("every factory-built label passes the Review lifecycle guard (re-normalisation fixed point)")
    void should_passLifecycleGuard_when_labelBuiltByFactory(String name, String surname) {
        Review review = Review.guestLinkReview(mock(Booking.class), (short) 5, null, name, surname);

        boolean canonical = GuestDisplayName.isCanonical(review.getGuestDisplayName());

        assertThat(canonical).isTrue();
        assertThatCode(review::enforceGuestDisplayNameShape).doesNotThrowAnyException();
    }

    /** Every (name, surname) input used elsewhere in this class. */
    static Stream<Arguments> factoryInputs() {
        Stream<Arguments> explicit = Stream.of(
                Arguments.of("Олена", "Кравець"),
                Arguments.of("Олена", "   "),
                Arguments.of("Олена", null),
                Arguments.of("Я".repeat(100), "Кравець"),
                Arguments.of("𐌰".repeat(70), null),
                Arguments.of("Олена Кравець", null),
                Arguments.of("Олена Кравець", "Кравець"),
                Arguments.of("\uFFA0\u115F\u1160", "Кравець"),
                Arguments.of("\u3164", "Кравець"),
                Arguments.of("Олена", "\u3164"),
                Arguments.of("Олена", "\u3164Кравець"),
                Arguments.of("О" + "\u0301\u0300\u0302\u0303\u0304" + "лена", null),
                Arguments.of("Андри\u0306", null),
                Arguments.of("Олена", "𐌰𐌱"),
                Arguments.of("Олена+380501234567", "0501234567Кравець"),
                Arguments.of("+380 50 123 45 67 Олена", null),
                Arguments.of("Мар'яна", "Ющенко"),
                Arguments.of("-Анна-Марія-", null));
        Stream<Arguments> separated = nonAsciiSeparatedNames().map(args -> Arguments.of(args.get()[0], null));
        Stream<Arguments> unusable = unusableNames().map(args -> Arguments.of(args.get()[0], "Кравець"));
        return Stream.of(explicit, separated, unusable).flatMap(s -> s);
    }

    @ParameterizedTest(name = "[{index}] label=«{0}»")
    @MethodSource("nonCanonicalLabels")
    @DisplayName("a builder-written label the factory could never produce is rejected by the lifecycle guard")
    void should_rejectLabel_when_notFactoryShaped(String label) {
        Review review = Review.builder().source(ReviewSource.GUEST_LINK).guestDisplayName(label).build();

        assertThatThrownBy(review::enforceGuestDisplayNameShape)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GUEST_LINK review display name is not a normalised guest label"); // never echoes the label
    }

    static Stream<Arguments> nonCanonicalLabels() {
        return Stream.of(
                Arguments.of("Олена Кравець"),
                Arguments.of("0 5 0 1"),
                Arguments.of("Олена К"),
                Arguments.of("Олена К. Б."),
                Arguments.of("Олена 0."),
                Arguments.of("Олена\u00A0К."),
                Arguments.of("Олена\u3164"),
                Arguments.of("-Олена"),
                Arguments.of("О" + "\u0301\u0300\u0302" + "лена"),
                Arguments.of("Я".repeat(61)),
                Arguments.of(""),
                Arguments.of((Object) null));
    }

    @Test
    @DisplayName("an APP review is not subject to the guest label guard")
    void should_skipGuard_when_sourceApp() {
        Review review = Review.builder().guestDisplayName(null).build();

        assertThatCode(review::enforceGuestDisplayNameShape).doesNotThrowAnyException();
    }

    private static String displayName(String name, String surname) {
        return Review.guestLinkReview(mock(Booking.class), (short) 5, null, name, surname).getGuestDisplayName();
    }
}
