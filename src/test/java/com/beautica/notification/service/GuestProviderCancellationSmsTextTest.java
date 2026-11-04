package com.beautica.notification.service;

import com.beautica.booking.crypto.GuestCancelTokenCipher;
import com.beautica.booking.crypto.GuestCancelTokenCipherFixtures;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.service.GuestBookingPageLinks;
import com.beautica.booking.service.GuestCancelUrlBuilder;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.salon.entity.Salon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("GuestProviderCancellationSmsText — phase 390/435 guest provider-cancellation SMS copy")
class GuestProviderCancellationSmsTextTest {

    /** Kyiv 10:00 on Mon 26.10.2026 (EET, UTC+2 — the day after the DST switch). */
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2026-10-26T08:00:00Z");
    private static final String BODY = "Beautica: Ваш запис 26.10.2026 о 10:00 скасовано.";
    private static final String DETAILS = "\n\nДеталі або записатись знову ";
    private static final int SINGLE_UCS2_SEGMENT = 70;
    /** A guest booking page as it would be built (phase 435 follow-up): https origin + /c/ + a legacy UUID token (59 chars). */
    private static final String GUEST_PAGE =
            "https://beautica.app/c/123e4567-e89b-12d3-a456-426614174000";

    private static final UUID TOKEN = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final GuestCancelTokenCipher CIPHER = GuestCancelTokenCipherFixtures.testCipher();
    private static final UUID ROW_ID = UUID.fromString("0b0b0b0b-0000-4000-8000-000000000001");

    private final GuestProviderCancellationSmsText text = new GuestProviderCancellationSmsText(
            new GuestBookingPageLinks(CIPHER, new GuestCancelUrlBuilder("https://beautica.app"),
                    mock(AppointmentRepository.class)));

    @Test
    @DisplayName("salon closure: the body alone, exact, one UCS-2 segment (49 chars)")
    void should_renderBodyOnly_when_salonClosed() {
        String sms = text.forSalonClosed(STARTS_AT);

        assertThat(sms).isEqualTo(BODY);
        assertThat(sms).hasSize(49);
        assertThat(sms.length()).isLessThanOrEqualTo(SINGLE_UCS2_SEGMENT);
    }

    @Test
    @DisplayName("phase 435: decline sends NO rebook link — only the guest's own page, absent on a pre-V230 row")
    void should_renderBodyOnly_when_declinedEvenWithMasterAndSalonSlugs() {
        // Arrange — slugs that the phase 390 copy would have linked
        Booking booking = booking(
                Master.builder().masterType(MasterType.INDEPENDENT_MASTER).isActive(true).bookingSlug("anna-koval").build(),
                Salon.builder().isActive(true).bookingSlug("salon-x").build());

        // Act
        String sms = text.forDecline(booking, STARTS_AT);

        // Assert
        assertThat(sms).isEqualTo(BODY).doesNotContain("anna-koval").doesNotContain("salon-x");
    }

    @Test
    @DisplayName("phase 435: master removal sends NO rebook link either")
    void should_renderBodyOnly_when_masterRemoved() {
        Booking booking = booking(
                Master.builder().masterType(MasterType.SALON_MASTER).isActive(true).build(),
                Salon.builder().isActive(true).bookingSlug("salon-x").build());

        assertThat(text.forMasterRemoved(booking, STARTS_AT)).isEqualTo(BODY);
    }

    @Test
    @DisplayName("the guest-page link source is empty for a row with only the hash (created before V230)")
    void should_returnNoGuestPageLink_when_onlyTheTokenHashIsStored() {
        assertThat(text.guestBookingPageLink(Booking.builder().cancelToken(TOKEN).build())).isEmpty();
    }

    @Test
    @DisplayName("phase 435: decline of a sealed-token booking renders the exact text WITH the guest's own page")
    void should_renderExactDeclineWithGuestPage_when_tokenSealed() {
        // Arrange
        Booking booking = Booking.builder().id(ROW_ID).cancelToken(TOKEN)
                .cancelTokenSealed(CIPHER.seal(TOKEN, com.beautica.booking.crypto.GuestCancelTokenCipher.Row.booking(ROW_ID))).build();

        // Act
        String sms = text.forDecline(booking, STARTS_AT);

        // Assert
        assertThat(sms).isEqualTo(BODY + DETAILS + GUEST_PAGE);
        assertThat(sms).hasSize(138);
    }

    @Test
    @DisplayName("phase 435: salon closure with the lead booking links the guest's own page too")
    void should_renderSalonClosureWithGuestPage_when_tokenSealed() {
        // Arrange
        Booking lead = Booking.builder().id(ROW_ID).cancelToken(TOKEN)
                .cancelTokenSealed(CIPHER.seal(TOKEN, com.beautica.booking.crypto.GuestCancelTokenCipher.Row.booking(ROW_ID))).build();

        // Act
        String sms = text.forSalonClosed(lead, STARTS_AT);

        // Assert
        assertThat(sms).isEqualTo(BODY + DETAILS + GUEST_PAGE);
    }

    @Test
    @DisplayName("phase 435: a visit item links the VISIT header's token, not its own")
    void should_linkHeaderToken_when_bookingBelongsToVisit() {
        // Arrange
        Appointment header = Appointment.builder().id(ROW_ID).cancelToken(TOKEN)
                .cancelTokenSealed(CIPHER.seal(TOKEN, com.beautica.booking.crypto.GuestCancelTokenCipher.Row.appointment(ROW_ID))).build();
        Booking item = Booking.builder().cancelToken(UUID.randomUUID()).appointment(header).build();

        // Act
        String sms = text.forMasterRemoved(item, STARTS_AT);

        // Assert
        assertThat(sms).isEqualTo(BODY + DETAILS + GUEST_PAGE);
    }

    @Test
    @DisplayName("with a link, the «Деталі» paragraph follows a blank line (138 chars, three segments)")
    void should_appendDetailsParagraph_when_linkPresent() {
        String sms = text.render(STARTS_AT, Optional.of(GUEST_PAGE));

        assertThat(sms).isEqualTo(BODY + DETAILS + GUEST_PAGE);
        assertThat(sms).hasSize(138);
        assertThat(sms.length()).isLessThanOrEqualTo(GuestProviderCancellationSmsText.MAX_UCS2_CHARS);
    }

    @Test
    @DisplayName("a link filling exactly three UCS-2 segments (201 chars) is kept")
    void should_keepLink_when_lengthIsExactlyThreeSegments() {
        String url = "https://" + "a".repeat(201 - 49 - DETAILS.length() - 8);

        assertThat(text.render(STARTS_AT, Optional.of(url))).hasSize(GuestProviderCancellationSmsText.MAX_UCS2_CHARS);
    }

    @Test
    @DisplayName("a link that would need a fourth segment drops the whole paragraph, the body stays")
    void should_dropParagraph_when_itWouldExceedThreeSegments() {
        String url = "https://" + "a".repeat(201 - 49 - DETAILS.length() - 8 + 1);

        assertThat(text.render(STARTS_AT, Optional.of(url))).isEqualTo(BODY);
    }

    @Test
    @DisplayName("the time is the Kyiv wall clock, not the stored UTC instant (summer, UTC+3)")
    void should_renderKyivWallClock_when_startsAtIsUtc() {
        String sms = text.forSalonClosed(OffsetDateTime.parse("2026-07-01T06:30:00Z"));

        assertThat(sms).isEqualTo("Beautica: Ваш запис 01.07.2026 о 09:30 скасовано.");
    }

    private static Booking booking(Master master, Salon salon) {
        return Booking.builder().master(master).salon(salon).build();
    }
}
