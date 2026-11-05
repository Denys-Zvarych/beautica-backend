package com.beautica.booking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.beautica.common.util.Placeholders;
import com.beautica.config.BookingSmsProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GuestSmsProviderText — phase 435 audit-fix: provider text in branded guest SMS")
class GuestSmsProviderTextTest {

    private static final String SALON_TEMPLATE = "Beautica: X\n\n{salonName} | {masterName}\n\nАдреса: {address}\n\nEnd";
    private static final String MASTER_TEMPLATE = "Beautica: X\n\n{masterName}\n\nАдреса: {address}\n\nEnd";

    @Test
    @DisplayName("a salon name over 40 chars is cut to 40 with «…»")
    void should_truncateSalonName_when_over40() {
        // Arrange
        String raw = "Студія краси та догляду Лілія на Подолі біля метро";

        // Act
        String name = GuestSmsProviderText.salonName(raw).orElseThrow();

        // Assert
        assertThat(name).hasSize(40).endsWith("…").startsWith("Студія краси та догляду Лілія на Подолі");
    }

    @Test
    @DisplayName("truncation never splits a surrogate pair")
    void should_notSplitSurrogatePair_when_truncating() {
        // Arrange — 38 letters, then an emoji (2 UTF-16 units) straddling the cut at 39
        String raw = "a".repeat(38) + "💅" + "bbbb";

        // Act
        String cut = GuestSmsProviderText.truncate(raw, 40);

        // Assert
        assertThat(cut).isEqualTo("a".repeat(38) + "…");
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 2))).isFalse();
    }

    @Test
    @DisplayName("a value that fits is returned unchanged; an address is capped at 60")
    void should_keepShortValues_and_capAddressAt60() {
        // Act + Assert
        assertThat(GuestSmsProviderText.salonName("Beauty Room")).contains("Beauty Room");
        assertThat(GuestSmsProviderText.address("вул. " + "Х".repeat(100)).orElseThrow()).hasSize(60).endsWith("…");
        assertThat(GuestSmsProviderText.personName("М".repeat(80))).hasSize(40);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example", "Салон http://x", "www.beauty", "Знижки на beauty-deals.com", "Салон лілія.укр",
            "evil.COM/promo", "ftp:files", "tg:resolve", "javascript:alert(1)", "10.0.0.1", "evil。com", "Lilia.studio"})
    @DisplayName("URL / domain-like values are dropped")
    void should_dropUrlLikeSalonName(String raw) {
        // Act + Assert
        assertThat(GuestSmsProviderText.salonName(raw)).isEmpty();
        assertThat(GuestSmsProviderText.address(raw)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Хрещатик 10", "вул. Шевченка, 5Б", "просп.Перемоги 12", "м.Київ, вул.Хрещатик", "буд.5, кв.12",
            "Beauty Room", "Hotel: Premier", "ТРЦ Ocean Plaza, 2 поверх", "10.5"})
    @DisplayName("ordinary names / addresses are kept")
    void should_keepOrdinaryText(String raw) {
        // Act + Assert
        assertThat(GuestSmsProviderText.address(raw)).contains(raw);
    }

    @Test
    @DisplayName("line breaks are flattened before anything else")
    void should_flattenLineBreaks() {
        // Act + Assert
        assertThat(GuestSmsProviderText.salonName("Beauty\nRoom\r\n")).contains("Beauty Room");
    }

    @Test
    @DisplayName("render drops the address line first, then the salon, to fit the cap")
    void should_dropAddressThenSalon_when_overCap() {
        // Arrange
        Map<String, String> values = new HashMap<>();
        values.put("salonName", "S".repeat(40));
        values.put("masterName", "M".repeat(40));
        values.put("address", "A".repeat(60));
        String bigSalon = SALON_TEMPLATE + "\n" + "x".repeat(GuestSmsProviderText.MAX_SMS_CHARS - 110);

        // Act
        String full = GuestSmsProviderText.render(SALON_TEMPLATE, MASTER_TEMPLATE, values, Set.of(), Set.of("address"));
        String noAddress = GuestSmsProviderText.render(bigSalon, MASTER_TEMPLATE, values, Set.of(), Set.of("address"));
        String noSalon = GuestSmsProviderText.render(
                bigSalon + "x".repeat(100), MASTER_TEMPLATE, values, Set.of(), Set.of("address"));

        // Assert
        assertThat(full).contains("Адреса: " + "A".repeat(60)).contains("S".repeat(40));
        assertThat(noAddress).doesNotContain("Адреса").contains("S".repeat(40));
        assertThat(noAddress.length()).isLessThanOrEqualTo(GuestSmsProviderText.MAX_SMS_CHARS);
        assertThat(noSalon).isEqualTo("Beautica: X\n\n" + "M".repeat(40) + "\n\nEnd");
    }

    private static final String NOTE_SALON = "Beautica: X\n\n{salonName} | {masterName}\nАдреса: {address} ({locationNote})\nEnd";
    private static final String NOTE_MASTER = "Beautica: X\n\n{masterName}\nАдреса: {address} ({locationNote})\nEnd";

    @Test
    @DisplayName("a location note follows the address rules: one line, capped at 60, dropped when URL-like")
    void should_sanitiseLocationNote_when_provided() {
        // Act + Assert
        assertThat(GuestSmsProviderText.locationNote("вхід\nз двору")).contains("вхід з двору");
        assertThat(GuestSmsProviderText.locationNote("Н".repeat(100)).orElseThrow()).hasSize(60).endsWith("…");
        assertThat(GuestSmsProviderText.locationNote("пишіть t.me/promo")).isEmpty();
        assertThat(GuestSmsProviderText.locationNote(" ")).isEmpty();
    }

    @Test
    @DisplayName("render keeps « (note)» after the address, and drops the brackets when there is no note")
    void should_dropBrackets_when_noLocationNote() {
        // Arrange
        Map<String, String> withNote = new HashMap<>(Map.of("masterName", "Марія", "address", "Хрещатик 10",
                GuestSmsProviderText.LOCATION_NOTE, "вхід з двору"));
        Map<String, String> noNote = new HashMap<>(Map.of("masterName", "Марія", "address", "Хрещатик 10"));

        // Act
        String noted = GuestSmsProviderText.render(null, NOTE_MASTER, withNote, Set.of(), Set.of("address"));
        String plain = GuestSmsProviderText.render(null, NOTE_MASTER, noNote, Set.of(), Set.of("address"));

        // Assert
        assertThat(noted).isEqualTo("Beautica: X\n\nМарія\nАдреса: Хрещатик 10 (вхід з двору)\nEnd");
        assertThat(plain).isEqualTo("Beautica: X\n\nМарія\nАдреса: Хрещатик 10\nEnd");
    }

    @Test
    @DisplayName("render never shows the note without an address — the whole «Адреса:» line goes")
    void should_dropNoteWithAddress_when_noAddress() {
        // Arrange
        Map<String, String> values = new HashMap<>(Map.of("masterName", "Марія",
                GuestSmsProviderText.LOCATION_NOTE, "вхід з двору"));

        // Act + Assert
        assertThat(GuestSmsProviderText.render(null, NOTE_MASTER, values, Set.of(), Set.of("address")))
                .isEqualTo("Beautica: X\n\nМарія\nEnd");
    }

    @Test
    @DisplayName("render drops the note first, then the address, then the salon, to fit the cap")
    void should_dropNoteThenAddressThenSalon_when_overCap() {
        // Arrange
        Map<String, String> values = new HashMap<>();
        values.put("salonName", "S".repeat(40));
        values.put("masterName", "M".repeat(40));
        values.put("address", "A".repeat(60));
        values.put(GuestSmsProviderText.LOCATION_NOTE, "N".repeat(60));
        String fullLength = GuestSmsProviderText.render(NOTE_SALON, NOTE_MASTER, values, Set.of(), Set.of("address"));
        // pad so the full text is 1 over the cap, but the text without the 63-char « (N…)» fits
        String tight = NOTE_SALON + "x".repeat(GuestSmsProviderText.MAX_SMS_CHARS - fullLength.length() + 1);
        // pad so even without the note it does not fit, but without the address line it does
        String tighter = NOTE_SALON + "x".repeat(GuestSmsProviderText.MAX_SMS_CHARS - fullLength.length() + 64);
        // pad so no salon variant fits
        String tightest = NOTE_SALON + "x".repeat(GuestSmsProviderText.MAX_SMS_CHARS);

        // Act
        String noNote = GuestSmsProviderText.render(tight, NOTE_MASTER, values, Set.of(), Set.of("address"));
        String noAddress = GuestSmsProviderText.render(tighter, NOTE_MASTER, values, Set.of(), Set.of("address"));
        String noSalon = GuestSmsProviderText.render(tightest, NOTE_MASTER, values, Set.of(), Set.of("address"));

        // Assert
        assertThat(fullLength).contains("Адреса: " + "A".repeat(60) + " (" + "N".repeat(60) + ")");
        assertThat(noNote).contains("Адреса: " + "A".repeat(60) + "\n").doesNotContain("N".repeat(60))
                .contains("S".repeat(40));
        assertThat(noAddress).doesNotContain("Адреса").contains("S".repeat(40));
        assertThat(noSalon).isEqualTo("Beautica: X\n\n" + "M".repeat(40) + "\nEnd");
    }

    @Test
    @DisplayName("render uses the master template when the salon name was dropped")
    void should_useMasterTemplate_when_noSalonName() {
        // Arrange
        Map<String, String> values = new HashMap<>(Map.of("masterName", "Марія"));

        // Act + Assert
        assertThat(GuestSmsProviderText.render(SALON_TEMPLATE, MASTER_TEMPLATE, values, Set.of(), Set.of("address")))
                .isEqualTo("Beautica: X\n\nМарія\n\nEnd");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "t\u2024me/promo",            // U+2024 one-dot leader
            "t\uFE52me/promo",            // U+FE52 small full stop
            "\uFF54\uFF0E\uFF4D\uFF45",  // full-width «ｔ．ｍｅ»
            "bit.l\u0443",                // Cyrillic «у» TLD look-alike
            "t.m\u0435",                  // Cyrillic «е»
            "promo.\u0441\u043Em",        // Cyrillic «со» + Latin m
            "t\u200B.me",                 // zero-width space before the dot
            "t.\u00ADme",                 // soft hyphen after the dot
            "t\u2060.\uFEFFme",           // word joiner + BOM (\\p{Cf}) around the dot
            "t . me/promo",               // spaced dots
            "bit .ly/sale",
            "www . beauty"})
    @DisplayName("audit-fix cycle 2: look-alike dots, full-width, Cyrillic TLDs, \\p{Cf} and spaced dots are still URL-like")
    void should_detectUrl_when_bypassVariantUsed(String raw) {
        // Act + Assert
        assertThat(GuestSmsProviderText.looksLikeUrl(raw)).isTrue();
        assertThat(GuestSmsProviderText.salonName(raw)).isEmpty();
        assertThat(GuestSmsProviderText.masterName(raw)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Марія Левченко", "Олена Ковальчук-Сидоренко", "Anna Smith", "Сом Олег", "вул. Сонячна, 5"})
    @DisplayName("audit-fix cycle 2: ordinary names (incl. Cyrillic words of look-alike letters) are not URL-like")
    void should_keepOrdinaryName_when_noUrl(String raw) {
        // Act + Assert
        assertThat(GuestSmsProviderText.masterName(raw)).contains(raw);
    }

    @Test
    @DisplayName("audit-fix cycle 2: singleLine strips \\p{Cf} so a U+202E override never reaches an SMS")
    void should_stripFormatChars_when_flattened() {
        // Act + Assert
        assertThat(Placeholders.singleLine("Beauty\u202ERoom\u200B\u00AD")).isEqualTo("BeautyRoom");
    }

    @Test
    @DisplayName("audit-fix cycle 2: a URL-like master name is dropped from all four live templates")
    void should_omitMasterName_when_masterNameIsUrlLike() {
        // Arrange
        BookingSmsProperties.Sms sms = new BookingSmsProperties().getSms();
        Map<String, String> values = new HashMap<>(Map.of(
                "salonName", "Beauty Room", "date", "10.06.2026", "time", "12:00", "serviceName", "Манікюр",
                "cancelUrl", "https://beautica.app/c/x"));
        GuestSmsProviderText.masterName("t.me/promo").ifPresent(name -> values.put("masterName", name));

        // Act
        String confirmationSalon = GuestSmsProviderText.render(
                sms.getWalkInConfirmationSalon(), sms.getWalkInConfirmation(), values, Set.of(), Set.of("address"));
        String confirmation = GuestSmsProviderText.render(
                null, sms.getWalkInConfirmation(), values, Set.of(), Set.of("address"));
        String reminderSalon = GuestSmsProviderText.render(
                sms.getReminderSalon(), sms.getReminder(), values, Set.of("address", "salonAddress"), Set.of());
        String reminder = GuestSmsProviderText.render(
                null, sms.getReminder(), values, Set.of("address", "salonAddress"), Set.of());

        // Assert
        assertThat(values).doesNotContainKey("masterName");
        assertThat(confirmationSalon).isEqualTo("Beautica: Ваш запис підтверджено!\n\n10.06.2026 о 12:00\n"
                + "Салон Beauty Room.\nСкасувати: https://beautica.app/c/x");
        assertThat(confirmation).isEqualTo("Beautica: Ваш запис підтверджено!\n\n10.06.2026 о 12:00\n"
                + "Скасувати: https://beautica.app/c/x");
        assertThat(reminderSalon).contains("\n\nBeauty Room | Манікюр о 12:00\n").doesNotContain("{masterName}");
        assertThat(reminder).contains("\n\nМанікюр о 12:00\n").doesNotContain("{masterName}").doesNotContain("|");
    }

    @ParameterizedTest
    @ValueSource(strings = {"буд. № 5", "просп. Миру, буд. № 12", "вул. Сумська, №5", "буд.№ 7"})
    @DisplayName("audit-fix cycle 3: «№» (NFKC «No») does not make an address URL-like")
    void should_keepAddress_when_containsNumeroSign(String raw) {
        // Act + Assert
        assertThat(GuestSmsProviderText.address(raw)).contains(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"vul. Khreshchatyk 22", "Kyiv, str. Lesi Ukrainky 5", "prosp. Peremohy 12, bud. 3"})
    @DisplayName("audit-fix cycle 3: a Latin abbreviation dot followed by a space keeps the address")
    void should_keepAddress_when_latinAbbreviationFollowedBySpace(String raw) {
        // Act + Assert
        assertThat(GuestSmsProviderText.address(raw)).contains(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Nails. Studio", "Beauty. Pro", "Anna K. Smith", "Hair & Co. Info"})
    @DisplayName("audit-fix cycle 3: a Latin word, dot, space keeps the salon / master name")
    void should_keepName_when_latinWordDotSpace(String raw) {
        // Act + Assert
        assertThat(GuestSmsProviderText.salonName(raw)).contains(raw);
        assertThat(GuestSmsProviderText.masterName(raw)).contains(raw);
    }
}
