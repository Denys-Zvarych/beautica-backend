package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.config.BookingLinksProperties;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.salon.entity.Salon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GuestProviderCancellationSmsText — phase 390 guest provider-cancellation SMS copy")
class GuestProviderCancellationSmsTextTest {

    /** Kyiv 10:00 on Mon 26.10.2026 (EET, UTC+2 — the day after the DST switch). */
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2026-10-26T08:00:00Z");
    private static final String BODY = "Beautica: Ваш запис 26.10.2026 о 10:00 скасовано.";
    private static final String REBOOK = " Записатися знову: ";
    private static final int SINGLE_UCS2_SEGMENT = 70;

    private final GuestProviderCancellationSmsText text = new GuestProviderCancellationSmsText(links(true));

    @Test
    @DisplayName("salon closure: the body alone, exact, one UCS-2 segment (49 chars)")
    void should_renderBodyOnly_when_salonClosed() {
        String sms = text.forSalonClosed(STARTS_AT);

        assertThat(sms).isEqualTo(BODY);
        assertThat(sms).hasSize(49);
        assertThat(sms.length()).isLessThanOrEqualTo(SINGLE_UCS2_SEGMENT);
    }

    @Test
    @DisplayName("decline: rebook link to the master's own page when the master has a slug")
    void should_appendMasterLink_when_declinedMasterHasSlug() {
        Booking booking = booking(master(true, "anna-koval"), salon(true, "salon-x"));

        String sms = text.forDecline(booking, STARTS_AT);

        assertThat(sms).isEqualTo(BODY + REBOOK + "beautica.app/book/anna-koval");
        assertThat(sms).hasSize(96);
        assertThat(sms.length()).isLessThanOrEqualTo(GuestProviderCancellationSmsText.MAX_UCS2_CHARS);
    }

    @Test
    @DisplayName("decline: falls back to the salon page when the master has no slug")
    void should_appendSalonLink_when_masterHasNoSlug() {
        Booking booking = booking(master(true, null), salon(true, "salon-x"));

        String sms = text.forDecline(booking, STARTS_AT);

        assertThat(sms).isEqualTo(BODY + REBOOK + "beautica.app/book/s/salon-x");
    }

    @Test
    @DisplayName("decline of a SALON_MASTER: salon link only — a salon master never gets a link of their own")
    void should_appendSalonLink_when_declinedMasterIsSalonMaster() {
        Booking booking = booking(master(MasterType.SALON_MASTER, true, "olena-shevchenko"), salon(true, "salon-x"));

        String sms = text.forDecline(booking, STARTS_AT);

        assertThat(sms).isEqualTo(BODY + REBOOK + "beautica.app/book/s/salon-x");
        assertThat(sms).doesNotContain("olena-shevchenko");
    }

    @Test
    @DisplayName("decline of a SALON_OWNER master: salon link only — an owner shares only the salon page")
    void should_appendSalonLink_when_declinedMasterIsSalonOwner() {
        Booking booking = booking(master(MasterType.SALON_OWNER, true, "iryna-owner"), salon(true, "salon-x"));

        String sms = text.forDecline(booking, STARTS_AT);

        assertThat(sms).isEqualTo(BODY + REBOOK + "beautica.app/book/s/salon-x");
        assertThat(sms).doesNotContain("iryna-owner");
    }

    @Test
    @DisplayName("decline of a salon master with no salon slug: body only, still never the master's slug")
    void should_renderBodyOnly_when_salonMasterAndSalonHasNoSlug() {
        Booking booking = booking(master(MasterType.SALON_MASTER, true, "olena-shevchenko"), salon(true, null));

        assertThat(text.forDecline(booking, STARTS_AT)).isEqualTo(BODY);
    }

    @Test
    @DisplayName("decline: an inactive master's slug is never offered")
    void should_skipMasterLink_when_masterInactive() {
        Booking booking = booking(master(false, "anna-koval"), null);

        String sms = text.forDecline(booking, STARTS_AT);

        assertThat(sms).isEqualTo(BODY);
    }

    @Test
    @DisplayName("decline: no slug anywhere — body only")
    void should_renderBodyOnly_when_noSlugAnywhere() {
        Booking booking = booking(master(true, null), salon(true, null));

        assertThat(text.forDecline(booking, STARTS_AT)).isEqualTo(BODY);
    }

    @Test
    @DisplayName("booking-link sharing switched off — no link, ever")
    void should_dropLink_when_shareDisabled() {
        GuestProviderCancellationSmsText disabled = new GuestProviderCancellationSmsText(links(false));
        Booking booking = booking(master(true, "anna-koval"), salon(true, "salon-x"));

        assertThat(disabled.forDecline(booking, STARTS_AT)).isEqualTo(BODY);
        assertThat(disabled.forMasterRemoved(booking, STARTS_AT)).isEqualTo(BODY);
    }

    @Test
    @DisplayName("master removal: only the salon page, never the removed master's own page")
    void should_appendOnlySalonLink_when_masterRemoved() {
        Booking booking = booking(master(true, "anna-koval"), salon(true, "salon-x"));

        assertThat(text.forMasterRemoved(booking, STARTS_AT))
                .isEqualTo(BODY + REBOOK + "beautica.app/book/s/salon-x");
    }

    @Test
    @DisplayName("master removal of an independent master (self-delete) — no salon, no link")
    void should_renderBodyOnly_when_masterRemovedWithoutSalon() {
        Booking booking = booking(master(true, "anna-koval"), null);

        assertThat(text.forMasterRemoved(booking, STARTS_AT)).isEqualTo(BODY);
    }

    @Test
    @DisplayName("a 48-char slug fills exactly two UCS-2 segments (134 chars) and keeps the link")
    void should_keepLink_when_lengthIsExactlyTwoSegments() {
        String slug = "a".repeat(48);
        Booking booking = booking(master(true, slug), null);

        String sms = text.forDecline(booking, STARTS_AT);

        assertThat(sms).isEqualTo(BODY + REBOOK + "beautica.app/book/" + slug);
        assertThat(sms).hasSize(GuestProviderCancellationSmsText.MAX_UCS2_CHARS);
    }

    @Test
    @DisplayName("a 49-char slug would need a third segment — the link is dropped, the body stays")
    void should_dropLink_when_itWouldExceedTwoSegments() {
        Booking booking = booking(master(true, "a".repeat(49)), null);

        assertThat(text.forDecline(booking, STARTS_AT)).isEqualTo(BODY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Anna", "anna koval", "-anna", "anna-", "anna/../x", "a"})
    @DisplayName("a slug outside the V86 shape is never put into an SMS")
    void should_dropLink_when_slugMalformed(String slug) {
        Booking booking = booking(master(true, slug), null);

        assertThat(text.forDecline(booking, STARTS_AT)).isEqualTo(BODY);
    }

    @Test
    @DisplayName("the time is the Kyiv wall clock, not the stored UTC instant (summer, UTC+3)")
    void should_renderKyivWallClock_when_startsAtIsUtc() {
        String sms = text.forSalonClosed(OffsetDateTime.parse("2026-07-01T06:30:00Z"));

        assertThat(sms).isEqualTo("Beautica: Ваш запис 01.07.2026 о 09:30 скасовано.");
    }

    @Test
    @DisplayName("the link host is the configured web origin without its scheme")
    void should_useBareHost_when_webBaseUrlIsStaging() {
        BookingLinksProperties staging = links(true);
        staging.setWebBaseUrl("https://stg.beautica.app");
        GuestProviderCancellationSmsText stagingText = new GuestProviderCancellationSmsText(staging);
        Booking booking = booking(master(true, "anna-koval"), null);

        assertThat(stagingText.forDecline(booking, STARTS_AT))
                .isEqualTo(BODY + REBOOK + "stg.beautica.app/book/anna-koval");
    }

    private static BookingLinksProperties links(boolean shareEnabled) {
        BookingLinksProperties properties = new BookingLinksProperties();
        properties.setShareEnabled(shareEnabled);
        return properties;
    }

    private static Master master(boolean active, String slug) {
        return master(MasterType.INDEPENDENT_MASTER, active, slug);
    }

    private static Master master(MasterType type, boolean active, String slug) {
        return Master.builder().masterType(type).isActive(active).bookingSlug(slug).build();
    }

    private static Salon salon(boolean active, String slug) {
        return Salon.builder().isActive(active).bookingSlug(slug).build();
    }

    private static Booking booking(Master master, Salon salon) {
        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);
        org.mockito.Mockito.lenient().when(booking.getSalon()).thenReturn(salon);
        return booking;
    }
}
