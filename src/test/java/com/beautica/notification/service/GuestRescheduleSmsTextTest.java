package com.beautica.notification.service;

import com.beautica.booking.crypto.GuestCancelTokenCipher;
import com.beautica.booking.crypto.GuestCancelTokenCipherFixtures;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.service.GuestBookingPageLinks;
import com.beautica.booking.service.GuestCancelUrlBuilder;
import com.beautica.config.BookingSmsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("GuestRescheduleSmsText — phase 431/435 guest reschedule SMS copy")
class GuestRescheduleSmsTextTest {

    /** One UCS-2 segment. */
    private static final int ONE_SEGMENT = 70;

    @Test
    @DisplayName("the default renders the NEW start in Kyiv wall-clock time, in one UCS-2 segment, with no «Деталі» line when no guest-page link exists (pre-V230 row)")
    void should_renderKyivDateTimeInOneSegment_when_defaultTemplate() {
        GuestRescheduleSmsText text = new GuestRescheduleSmsText(new BookingSmsProperties(), links());

        String sms = text.render(startingAt("2026-10-26T08:00:00Z"));

        assertThat(sms).isEqualTo("Beautica: Ваш запис 26.10.2026 о 10:00 перенесено.");
        assertThat(sms).hasSize(50);
        assertThat(sms.length()).isLessThanOrEqualTo(ONE_SEGMENT);
    }

    @Test
    @DisplayName("summer time: a UTC instant renders as EEST (UTC+3) wall clock")
    void should_renderEestWallClock_when_summerInstant() {
        GuestRescheduleSmsText text = new GuestRescheduleSmsText(new BookingSmsProperties(), links());

        String sms = text.render(startingAt("2026-07-01T21:30:00Z"));

        assertThat(sms).isEqualTo("Beautica: Ваш запис 02.07.2026 о 00:30 перенесено.");
    }

    @Test
    @DisplayName("a valid override is used verbatim")
    void should_useOverride_when_overrideIsValid() {
        BookingSmsProperties properties = new BookingSmsProperties();
        properties.getSms().setReschedule("Запис перенесено: {date} {time}");

        String sms = new GuestRescheduleSmsText(properties, links()).render(startingAt("2026-10-26T08:00:00Z"));

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

        assertThatThrownBy(() -> new GuestRescheduleSmsText(properties, links()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.booking.sms.reschedule");
    }

    @Test
    @DisplayName("an override rendering past two UCS-2 segments (134 chars) fails at construction (boot)")
    void should_failBoot_when_renderedTextExceedsTwoSegments() {
        BookingSmsProperties properties = new BookingSmsProperties();
        properties.getSms().setReschedule("ж".repeat(120) + " {date} {time}");

        assertThatThrownBy(() -> new GuestRescheduleSmsText(properties, links()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("134");
    }

    @Test
    @DisplayName("an override rendering to exactly 134 chars is accepted")
    void should_accept_when_renderedTextIsExactlyTwoSegments() {
        BookingSmsProperties properties = new BookingSmsProperties();
        // 117 + 1 + 10 + 1 + 5 = 134
        properties.getSms().setReschedule("ж".repeat(117) + " {date} {time}");

        String sms = new GuestRescheduleSmsText(properties, links()).render(startingAt("2026-10-26T08:00:00Z"));

        assertThat(sms).hasSize(134);
    }

    @Test
    @DisplayName("phase 435: «Beautica:» title first and no rebook link — the «Деталі» paragraph needs the guest-page link")
    void should_startWithTitleAndCarryNoLink_when_defaultTemplate() {
        // Arrange
        GuestRescheduleSmsText text = new GuestRescheduleSmsText(
                new BookingSmsProperties(), links());

        // Act
        String sms = text.render(startingAt("2026-10-26T08:00:00Z"));

        // Assert
        assertThat(sms).startsWith("Beautica: ").doesNotContain("Деталі").doesNotContain("http");
    }

    @Test
    @DisplayName("phase 435: a sealed-token booking gets the exact text WITH the guest's own page")
    void should_renderExactTextWithGuestPage_when_tokenSealed() {
        // Arrange
        UUID token = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Booking booking = Booking.builder().startsAt(OffsetDateTime.parse("2026-10-26T08:00:00Z"))
                .id(ROW_ID).cancelToken(token).cancelTokenSealed(CIPHER.seal(token, com.beautica.booking.crypto.GuestCancelTokenCipher.Row.booking(ROW_ID))).build();
        GuestRescheduleSmsText text = new GuestRescheduleSmsText(new BookingSmsProperties(), links());

        // Act
        String sms = text.render(booking);

        // Assert
        assertThat(sms).isEqualTo("Beautica: Ваш запис 26.10.2026 о 10:00 перенесено.\n\n"
                + "Деталі або записатись знову https://beautica.app/book/cancel/123e4567-e89b-12d3-a456-426614174000");
    }

    private static final GuestCancelTokenCipher CIPHER = GuestCancelTokenCipherFixtures.testCipher();
    private static final UUID ROW_ID = UUID.fromString("0b0b0b0b-0000-4000-8000-000000000002");

    private static GuestProviderCancellationSmsText links() {
        return new GuestProviderCancellationSmsText(new GuestBookingPageLinks(
                CIPHER, new GuestCancelUrlBuilder("https://beautica.app"), mock(AppointmentRepository.class)));
    }

    private static Booking startingAt(String instant) {
        return Booking.builder().startsAt(OffsetDateTime.parse(instant)).build();
    }
}
