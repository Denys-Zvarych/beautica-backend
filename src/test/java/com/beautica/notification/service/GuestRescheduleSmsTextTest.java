package com.beautica.notification.service;

import com.beautica.config.BookingSmsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GuestRescheduleSmsText — phase 431 guest reschedule SMS copy")
class GuestRescheduleSmsTextTest {

    /** One UCS-2 segment. */
    private static final int ONE_SEGMENT = 70;

    @Test
    @DisplayName("the default renders the NEW start in Kyiv wall-clock time, in one UCS-2 segment")
    void should_renderKyivDateTimeInOneSegment_when_defaultTemplate() {
        GuestRescheduleSmsText text = new GuestRescheduleSmsText(new BookingSmsProperties());

        String sms = text.render(OffsetDateTime.parse("2026-10-26T08:00:00Z"));

        assertThat(sms).isEqualTo("Beautica: Ваш запис перенесено на 26.10.2026 о 10:00.");
        assertThat(sms).hasSize(53);
        assertThat(sms.length()).isLessThanOrEqualTo(ONE_SEGMENT);
    }

    @Test
    @DisplayName("summer time: a UTC instant renders as EEST (UTC+3) wall clock")
    void should_renderEestWallClock_when_summerInstant() {
        GuestRescheduleSmsText text = new GuestRescheduleSmsText(new BookingSmsProperties());

        String sms = text.render(OffsetDateTime.parse("2026-07-01T21:30:00Z"));

        assertThat(sms).isEqualTo("Beautica: Ваш запис перенесено на 02.07.2026 о 00:30.");
    }

    @Test
    @DisplayName("a valid override is used verbatim")
    void should_useOverride_when_overrideIsValid() {
        BookingSmsProperties properties = new BookingSmsProperties();
        properties.getSms().setReschedule("Запис перенесено: {date} {time}");

        String sms = new GuestRescheduleSmsText(properties).render(OffsetDateTime.parse("2026-10-26T08:00:00Z"));

        assertThat(sms).isEqualTo("Запис перенесено: 26.10.2026 10:00");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "Ваш запис перенесено",
            "Ваш запис перенесено на {date}",
            "Ваш запис перенесено о {time}",
            "{date} {date} {time}",
            "{date} {time} {masterName}"
    })
    @DisplayName("an override missing a placeholder, repeating one or adding another fails at construction (boot)")
    void should_failBoot_when_placeholdersAreWrong(String template) {
        BookingSmsProperties properties = new BookingSmsProperties();
        properties.getSms().setReschedule(template);

        assertThatThrownBy(() -> new GuestRescheduleSmsText(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.booking.sms.reschedule");
    }

    @Test
    @DisplayName("an override rendering past two UCS-2 segments (134 chars) fails at construction (boot)")
    void should_failBoot_when_renderedTextExceedsTwoSegments() {
        BookingSmsProperties properties = new BookingSmsProperties();
        properties.getSms().setReschedule("ж".repeat(120) + " {date} {time}");

        assertThatThrownBy(() -> new GuestRescheduleSmsText(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("134");
    }

    @Test
    @DisplayName("an override rendering to exactly 134 chars is accepted")
    void should_accept_when_renderedTextIsExactlyTwoSegments() {
        BookingSmsProperties properties = new BookingSmsProperties();
        // 117 + 1 + 10 + 1 + 5 = 134
        properties.getSms().setReschedule("ж".repeat(117) + " {date} {time}");

        String sms = new GuestRescheduleSmsText(properties).render(OffsetDateTime.parse("2026-10-26T08:00:00Z"));

        assertThat(sms).hasSize(134);
    }
}
