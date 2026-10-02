package com.beautica.notification.inapp.push;

import com.beautica.auth.Role;
import com.beautica.notification.inapp.dto.NotificationParams;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PushCopy — unit")
class PushCopyTest {

    // 2026-10-03T11:30:00Z == 14:30 in Europe/Kyiv (EEST, UTC+3), a Saturday.
    private static final Instant STARTS_AT = Instant.parse("2026-10-03T11:30:00Z");

    private static NotificationParams bookingParams(String counterpart, String service, int count) {
        return new NotificationParams(counterpart, service, count, STARTS_AT, "Salon", null, null);
    }

    @ParameterizedTest
    @EnumSource(InAppNotificationType.class)
    @DisplayName("every type has a non-blank title for both audiences")
    void should_haveTitle_when_anyType(InAppNotificationType type) {
        assertThat(PushCopy.title(type, true)).isNotBlank();
        assertThat(PushCopy.title(type, false)).isNotBlank();
    }

    @Test
    @DisplayName("declined and rescheduled titles differ by audience; the rest do not")
    void should_differByAudience_when_declinedOrRescheduled() {
        assertThat(PushCopy.title(InAppNotificationType.BOOKING_DECLINED, true))
                .isEqualTo("Ваш запис скасовано");
        assertThat(PushCopy.title(InAppNotificationType.BOOKING_DECLINED, false))
                .isEqualTo("Запис у вашому дні скасовано");
        assertThat(PushCopy.title(InAppNotificationType.BOOKING_RESCHEDULED, true))
                .isEqualTo("Ваш запис перенесено");
        assertThat(PushCopy.title(InAppNotificationType.BOOKING_RESCHEDULED, false))
                .isEqualTo("Запис перенесено");
        assertThat(PushCopy.title(InAppNotificationType.BOOKING_CREATED, true))
                .isEqualTo(PushCopy.title(InAppNotificationType.BOOKING_CREATED, false));
    }

    @Test
    @DisplayName("provider body leads with the counterpart: name — service, Kyiv date/time")
    void should_leadWithCounterpart_when_providerBody() {
        String body = PushCopy.body(InAppNotificationType.BOOKING_CREATED, false,
                bookingParams("Олена Коваленко", "Манікюр", 1));

        assertThat(body).isEqualTo("Олена Коваленко — Манікюр, сб, 3 жовтня, 14:30");
    }

    @Test
    @DisplayName("client body leads with the service and ends with the provider")
    void should_endWithCounterpart_when_clientBody() {
        String body = PushCopy.body(InAppNotificationType.BOOKING_DECLINED, true,
                bookingParams("Салон Оазис", "Манікюр", 1));

        assertThat(body).isEqualTo("Манікюр, сб, 3 жовтня, 14:30 — Салон Оазис");
    }

    @Test
    @DisplayName("a multi-service visit appends +N to the first service")
    void should_appendExtraServices_when_countAboveOne() {
        String body = PushCopy.body(InAppNotificationType.BOOKING_CREATED, false,
                bookingParams("Олена", "Манікюр", 3));

        assertThat(body).contains("Манікюр +2");
    }

    @Test
    @DisplayName("a rescheduled body says «новий час»")
    void should_sayNewTime_when_rescheduled() {
        String provider = PushCopy.body(InAppNotificationType.BOOKING_RESCHEDULED, false,
                bookingParams("Олена", "Манікюр", 1));
        String client = PushCopy.body(InAppNotificationType.BOOKING_RESCHEDULED, true,
                bookingParams("Салон", "Манікюр", 1));

        assertThat(provider).isEqualTo("Олена — Манікюр, новий час: сб, 3 жовтня, 14:30");
        assertThat(client).isEqualTo("Манікюр, новий час: сб, 3 жовтня, 14:30 — Салон");
    }

    @Test
    @DisplayName("the time is rendered in Europe/Kyiv, not UTC")
    void should_renderKyivTime_when_instantGiven() {
        // 2026-12-31T22:30:00Z is 00:30 on 1 January in Kyiv (EET, UTC+2) — crosses the day.
        String label = PushCopy.whenLabel(Instant.parse("2026-12-31T22:30:00Z"));

        assertThat(label).isEqualTo("пт, 1 січня, 00:30");
    }

    @Test
    @DisplayName("invite-accepted names the new teammate and role")
    void should_nameTeammateAndRole_when_inviteAccepted() {
        NotificationParams admin = new NotificationParams(
                null, null, 0, null, null, "Ірина Мельник", Role.SALON_ADMIN);
        NotificationParams master = new NotificationParams(
                null, null, 0, null, null, "Ірина Мельник", Role.SALON_MASTER);

        assertThat(PushCopy.body(InAppNotificationType.INVITE_ACCEPTED, false, admin))
                .isEqualTo("Ірина Мельник тепер у команді — адміністратор");
        assertThat(PushCopy.body(InAppNotificationType.INVITE_ACCEPTED, false, master))
                .isEqualTo("Ірина Мельник тепер у команді — майстер");
    }

    @ParameterizedTest
    @EnumSource(InAppNotificationType.class)
    @DisplayName("null params (lost access) yield the generic body for every type")
    void should_returnGenericBody_when_paramsNull(InAppNotificationType type) {
        assertThat(PushCopy.body(type, false, null)).isEqualTo(PushCopy.GENERIC_BODY);
        assertThat(PushCopy.body(type, true, null)).isEqualTo(PushCopy.GENERIC_BODY);
    }

    @Test
    @DisplayName("a missing service, counterpart or time yields the generic body, never a half sentence")
    void should_returnGenericBody_when_fieldMissing() {
        assertThat(PushCopy.body(InAppNotificationType.BOOKING_CREATED, false,
                bookingParams(null, "Манікюр", 1))).isEqualTo(PushCopy.GENERIC_BODY);
        assertThat(PushCopy.body(InAppNotificationType.BOOKING_CREATED, false,
                bookingParams("Олена", null, 1))).isEqualTo(PushCopy.GENERIC_BODY);
        assertThat(PushCopy.body(InAppNotificationType.BOOKING_CREATED, false,
                new NotificationParams("Олена", "Манікюр", 1, null, null, null, null)))
                .isEqualTo(PushCopy.GENERIC_BODY);
        assertThat(PushCopy.body(InAppNotificationType.INVITE_ACCEPTED, false,
                new NotificationParams(null, null, 0, null, null, null, null)))
                .isEqualTo(PushCopy.GENERIC_BODY);
    }

    @Test
    @DisplayName("control and bidi characters are stripped and an over-long name is capped")
    void should_sanitizeNames_when_controlOrOverlong() {
        String hostile = "Оле‮на\n\t Коваль​";
        String body = PushCopy.body(InAppNotificationType.BOOKING_CREATED, false,
                bookingParams(hostile, "Манікюр", 1));
        String capped = PushCopy.body(InAppNotificationType.BOOKING_CREATED, false,
                bookingParams("Я".repeat(500), "Манікюр", 1));

        assertThat(body).startsWith("Олена Коваль — ").doesNotContain("‮").doesNotContain("​");
        assertThat(capped.length()).isLessThan(120);
        assertThat(capped).contains("…");
    }

    private static String providerBodyFor(String counterpart) {
        return PushCopy.body(InAppNotificationType.BOOKING_CREATED, false, bookingParams(counterpart, "Манікюр", 1));
    }

    @Test
    @DisplayName("a URL planted in a counterpart name is removed from the lock-screen body")
    void should_stripUrl_when_counterpartNameContainsLink() {
        String body = providerBodyFor("Олена https://evil.example/pay");

        assertThat(body).startsWith("Олена — Манікюр").doesNotContain("evil").doesNotContain("://");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Олена www.evil.xyz", "Олена evil.com", "Олена EVIL.COM/pay", "Олена (evil.ua)",
            "Олена bit.ly.top", "Олена ｗｗｗ．evil．ｃｏｍ", "Олена evil​.com"})
    @DisplayName("www and bare-domain variants (any case, full-width, zero-width-split) are removed")
    void should_stripDomain_when_nameCarriesWwwOrBareDomain(String hostile) {
        String body = providerBodyFor(hostile);

        assertThat(body).startsWith("Олена — Манікюр").doesNotContainIgnoringCase("evil");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Олена evil.app", "Олена bit.ly", "Олена x.shop", "Олена сайт.укр", "Олена evil。com",
            "Олена evil．com", "Олена evil｡com", "Олена evil . com", "Олена evil .com",
            "Олена 192.168.0.1", "Олена evil.dev/pay"})
    @DisplayName("TLD-agnostic: any word + dot-like char + 2+ letters (any script, spaced, CJK/full-width dot) is removed")
    void should_stripHost_when_dotShapeEvenOnUnlistedTld(String hostile) {
        String body = providerBodyFor(hostile);

        assertThat(body).startsWith("Олена — Манікюр")
                .doesNotContainIgnoringCase("evil").doesNotContainIgnoringCase("bit")
                .doesNotContain("shop").doesNotContain("укр").doesNotContain("192");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Олена evil·com", "Олена evil‧com"})
    @DisplayName("a tight middle dot (U+00B7 / U+2027) counts as a host dot and is removed")
    void should_stripHost_when_middleDotUsedAsDot(String hostile) {
        assertThat(providerBodyFor(hostile)).startsWith("Олена — Манікюр").doesNotContain("evil");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Анна · Марія", "Анна ‧ Марія"})
    @DisplayName("a SPACED middle dot is a separator, not a host: the real name survives unchanged")
    void should_keepName_when_middleDotIsSpacedSeparator(String name) {
        assertThat(providerBodyFor(name)).startsWith(name + " — Манікюр");
    }

    @Test
    @DisplayName("an enormous raw name is bounded before normalisation and still capped at 40 code points + ellipsis")
    void should_boundRawInput_when_nameIsHuge() {
        String body = providerBodyFor("Я".repeat(100_000));
        String name = body.substring(0, body.indexOf(" — "));

        assertThat(name.codePointCount(0, name.length())).isEqualTo(41);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Олена Коваленко", "Анна-Марія", "O'Neil", "Олена К.", "Олена К. Коваленко", "St. John",
            "Іван Т.", "Ольга...", "Марія т.д."})
    @DisplayName("regression: legitimate names, a trailing initial and a dot followed by a space survive unchanged")
    void should_keepName_when_initialOrAbbreviationDot(String name) {
        assertThat(providerBodyFor(name)).startsWith(name + " — Манікюр");
    }

    @Test
    @DisplayName("documented trade-off: an initial glued to a surname without a space reads as a host and is removed")
    void should_stripGluedInitial_when_noSpaceAfterDot() {
        assertThat(providerBodyFor("О.Коваленко")).isEqualTo(PushCopy.GENERIC_BODY);
    }

    @Test
    @DisplayName("a name that is only a URL leaves nothing visible, so the generic body is used")
    void should_returnGenericBody_when_nameIsOnlyUrl() {
        assertThat(providerBodyFor("https://evil.example")).isEqualTo(PushCopy.GENERIC_BODY);
        assertThat(providerBodyFor("www.evil.com")).isEqualTo(PushCopy.GENERIC_BODY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Олена Коваленко", "Іван Петро-Мельник", "Оксана О'Нілл-Шевченко", "Марія 💅🏽"})
    @DisplayName("a legitimate Cyrillic name (hyphen, apostrophe, emoji) is left unchanged")
    void should_keepName_when_legitimateCyrillic(String name) {
        assertThat(providerBodyFor(name)).startsWith(name + " — Манікюр");
    }

    @Test
    @DisplayName("NBSP, LINE SEPARATOR and PARAGRAPH SEPARATOR collapse like ordinary spaces")
    void should_collapseUnicodeWhitespace_when_nameHasNbspOrSeparators() {
        assertThat(providerBodyFor("Олена  Коваль  Іванівна"))
                .startsWith("Олена Коваль Іванівна — ");
    }

    @Test
    @DisplayName("ZWJ and VS16 survive so a multi-codepoint emoji is not broken; other format chars go")
    void should_keepZwjAndVs16_when_emojiSequence() {
        String family = "👩‍💻"; // woman technologist (ZWJ sequence)
        String heart = "❤️";

        assertThat(providerBodyFor("Олена " + family + heart + "‮​"))
                .startsWith("Олена " + family + heart + " — ");
    }

    @Test
    @DisplayName("every name is capped at 40 code points, never splitting a surrogate pair")
    void should_capNamesAtForty_when_nameOverlong() {
        String body = providerBodyFor("💅".repeat(100));
        String name = body.substring(0, body.indexOf(" — "));

        assertThat(name.codePointCount(0, name.length())).isEqualTo(41); // 40 + the ellipsis
        assertThat(name).endsWith("…");
    }

    @Test
    @DisplayName("a whitespace-only name counts as missing")
    void should_returnGenericBody_when_nameBlankAfterSanitising() {
        assertThat(PushCopy.body(InAppNotificationType.BOOKING_CREATED, false,
                bookingParams(" ​\n ", "Манікюр", 1))).isEqualTo(PushCopy.GENERIC_BODY);
    }

    @Test
    @DisplayName("NotificationParams has no field a note, phone or e-mail could travel in")
    void should_carryNoNoteOrContactField_when_paramsShapeInspected() {
        // PushCopy reads ONLY this record. If someone adds a note/phone/email component to it, this
        // fails and forces a deliberate decision about the lock screen (locked rule: never).
        assertThat(java.util.Arrays.stream(NotificationParams.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("counterpartName", "serviceName", "serviceCount", "startsAt",
                        "salonName", "subjectName", "subjectRole");
    }
}
