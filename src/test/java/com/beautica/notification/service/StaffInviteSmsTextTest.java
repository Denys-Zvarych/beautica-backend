package com.beautica.notification.service;

import com.beautica.booking.GuestMessageExpectedCopy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B520 (NTF-59) — the staff invite SMS copy. Expected text comes from the shared SMS fixture
 * ({@link GuestMessageExpectedCopy#staffInvite}), never a re-typed literal.
 */
@DisplayName("StaffInviteSmsText — salon name + join link, at most three UCS-2 segments (B520)")
class StaffInviteSmsTextTest {

    private static final String LINK = "https://beautica.app/invite/accept?token=" + "x".repeat(43);

    @Test
    @DisplayName("a normal salon name renders the fixed copy with the name and the link, untouched")
    void should_renderFixedCopy_when_salonNameFits() {
        String text = StaffInviteSmsText.render("Студія Аврора", LINK);

        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInvite("Студія Аврора", LINK));
        assertThat(text.length()).isLessThanOrEqualTo(GuestMessageExpectedCopy.STAFF_INVITE_MAX_CHARS);
    }

    @Test
    @DisplayName("a long salon name is cut with «…» so the SMS is exactly three segments; the link is never cut")
    void should_cutSalonName_when_textWouldExceedThreeSegments() {
        String longName = "Салон краси ".repeat(20);

        String text = StaffInviteSmsText.render(longName, LINK);

        assertThat(text).hasSize(GuestMessageExpectedCopy.STAFF_INVITE_MAX_CHARS);
        assertThat(text).endsWith(LINK);
        int budget = GuestMessageExpectedCopy.STAFF_INVITE_MAX_CHARS
                - GuestMessageExpectedCopy.staffInvite("", LINK).length();
        String cut = longName.substring(0, budget - 1) + "…";
        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInvite(cut, LINK));
    }

    @Test
    @DisplayName("the cut never splits an emoji (surrogate pair) in a salon name")
    void should_notSplitSurrogatePair_when_cutLandsInsideEmoji() {
        int budget = GuestMessageExpectedCopy.STAFF_INVITE_MAX_CHARS
                - GuestMessageExpectedCopy.staffInvite("", LINK).length();
        // The cut keeps budget - 1 chars; put an emoji's high surrogate exactly at the last kept index.
        String name = "a".repeat(budget - 2) + "💅" + "b".repeat(10);

        String text = StaffInviteSmsText.render(name, LINK);

        String kept = text.substring("Beautica: «".length(), text.indexOf("»"));
        assertThat(kept).isEqualTo("a".repeat(budget - 2) + "…");
        assertThat(Character.isHighSurrogate(kept.charAt(kept.length() - 2))).isFalse();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"evil.ua/x", "https://a.b", "www.c.com", "HTTP://X.Y/z", "1.2.3.4", "клік.укр",
            "evil。ua", "(pay.me)", "a@evil.ua"})
    @DisplayName("a URL / domain word in the salon name never reaches the SMS; the rest of the name stays")
    void should_dropLinkLikeWord_when_salonNameCarriesUrl(String link) {
        String text = StaffInviteSmsText.render("Студія " + link + " Аврора", LINK);

        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInvite("Студія Аврора", LINK));
        assertThat(text).doesNotContain(link);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"evil\u00AD.com", "evil.\u200Bcom", "evil\u0301.com", "evil\u2060.com"})
    @DisplayName("B520 audit-fix 2: a domain hidden by a soft hyphen, zero-width space, combining accent or word "
            + "joiner (all ignored by a phone's linkifier) is still dropped")
    void should_dropLinkLikeWord_when_domainHidesBehindInvisibleCharacter(String link) {
        String text = StaffInviteSmsText.render("Студія " + link + " Аврора", LINK);

        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInvite("Студія Аврора", LINK));
        assertThat(text).doesNotContain("evil").doesNotContain("com");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"044-123-45-67", "+380 44 123 45 67", "(044) 123 45 67", "0441234567", "тел.0441234567",
            "+380\u200B441234567", "044 \u200B 123 \u200B 4567"})
    @DisplayName("B520 audit-fix 3: a callback phone number in the salon name (one word or a run of number-ish words) "
            + "never reaches the SMS, where it would be tap-to-call; the rest of the name stays")
    void should_dropPhoneNumber_when_salonNameCarriesCallbackNumber(String phone) {
        String text = StaffInviteSmsText.render("Салон " + phone, LINK);

        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInvite("Салон", LINK));
        assertThat(text).doesNotContain("044").doesNotContain("123").doesNotContain("45").doesNotContain("67");
    }

    @Test
    @DisplayName("B520 audit-fix 3: a phone number spanning words between letter words is dropped as one unit")
    void should_dropWholeNumberRun_when_phoneSitsInsideSalonName() {
        String text = StaffInviteSmsText.render("Салон +380 44 123 45 67 Аврора", LINK);

        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInvite("Салон Аврора", LINK));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Salon 24", "Студія 1", "Nails 24/7", "Салон №5", "Beauty 2024", "Studio 12 34"})
    @DisplayName("B520 audit-fix 3: ordinary short numbers in a salon name stay")
    void should_keepShortNumbers_when_salonNameHasNoPhone(String name) {
        String text = StaffInviteSmsText.render(name, LINK);

        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInvite(name, LINK));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Салон 123 456", "Салон тел.123456", "Салон 12-34-56"})
    @DisplayName("B520 boundary: 6 digits in a run of number words, or in one word, are below the phone threshold and stay")
    void should_keepNumber_when_runHoldsSixDigits(String name) {
        String text = StaffInviteSmsText.render(name, LINK);

        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInvite(name, LINK));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Салон 123 4567", "Салон тел.1234567", "Салон 12-34-567"})
    @DisplayName("B520 boundary: exactly 7 digits in a run of number words, or in one word, is a phone and is dropped")
    void should_dropNumber_when_runHoldsSevenDigits(String name) {
        String text = StaffInviteSmsText.render(name, LINK);

        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInvite("Салон", LINK));
    }

    @Test
    @DisplayName("B520: digits of number runs split by a letter word are counted per run, not summed across the name")
    void should_keepBothShortRuns_when_letterWordSeparatesThem() {
        String name = "Салон 123 Аврора 4567";

        String text = StaffInviteSmsText.render(name, LINK);

        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInvite(name, LINK));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Салон0441 234567", "tel:+380441 23456"})
    @DisplayName("B520 extra pass: a callback number glued to a letter word and split across words is counted as one "
            + "letter-free stretch and dropped — no 7-digit sequence reaches the SMS")
    void should_dropNumber_when_phoneSplitsAcrossLetterWordBoundary(String name) {
        String safe = StaffInviteSmsText.withoutLinks(name);

        assertThat(safe).doesNotMatch(".*\\d{7}.*");
        assertThat(safe.replaceAll("\\D", "").length()).isLessThan(StaffInviteSmsText.PHONE_MIN_DIGITS);
    }

    @Test
    @DisplayName("B520 extra pass: phone punctuation libphonenumber accepts ('/') does not split a "
            + "number into short runs — every letter-free word counts")
    void should_dropNumber_when_phoneSeparatedBySlashes() {
        String safe = StaffInviteSmsText.withoutLinks("Salon 044 / 123 / 4567");

        assertThat(safe).isEqualTo("Salon");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Salon 044 ~ 123 ~ 4567", "Salon [044] 123 4567", "Salon 044 \u2053 123 \u223C 4567"})
    @DisplayName("B520 extra pass: other libphonenumber separators (~, [ ], U+2053, U+223C) are number-ish too")
    void should_dropNumber_when_phoneUsesOtherAcceptedSeparators(String name) {
        String safe = StaffInviteSmsText.withoutLinks(name);

        assertThat(safe).isEqualTo("Salon");
    }

    @Test
    @DisplayName("B520 extra pass: «Nails 24/7» keeps its short number")
    void should_keepShortSlashNumber_when_belowPhoneThreshold() {
        String safe = StaffInviteSmsText.withoutLinks("Nails 24/7");

        assertThat(safe).isEqualTo("Nails 24/7");
    }

    @Test
    @DisplayName("B520 audit-fix 3: a salon name that is only a phone number falls back to the copy without a name")
    void should_renderNamelessCopy_when_salonNameIsOnlyPhoneNumber() {
        String text = StaffInviteSmsText.render("044 123 45 67", LINK);

        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInviteWithoutSalonName(LINK));
    }

    @Test
    @DisplayName("a salon name made only of links falls back to the copy without a name")
    void should_renderNamelessCopy_when_nothingSafeIsLeftOfSalonName() {
        String text = StaffInviteSmsText.render("evil.ua/x https://a.b www.c.com", LINK);

        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInviteWithoutSalonName(LINK));
        assertThat(text).doesNotContain("evil").doesNotContain("a.b").doesNotContain("c.com");
    }

    @Test
    @DisplayName("ordinary punctuation (a trailing dot, «ім. Лесі», a hyphen) is not mistaken for a domain")
    void should_keepOrdinaryPunctuation_when_noDomainInSalonName() {
        String name = "Салон ім. Лесі — Nails-Studio.";

        String text = StaffInviteSmsText.render(name, LINK);

        assertThat(text).isEqualTo(GuestMessageExpectedCopy.staffInvite(name, LINK));
    }
}
