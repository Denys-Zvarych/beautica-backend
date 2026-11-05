package com.beautica.booking.service;

import com.beautica.booking.crypto.GuestCancelTokenCipher;
import com.beautica.booking.crypto.GuestCancelTokenCipherFixtures;
import com.beautica.booking.domain.GuestCancelToken;
import com.beautica.booking.domain.GuestConfirmationSmsText;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.BookingSmsProperties;
import com.beautica.config.PhoneHashPepperConfig;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain mocks (no strict-stubs extension): each booking fixture stubs every getter the renderer may read.
 *
 * <p>Phase 435 follow-up — the Telegram details ARE the confirmation SMS: every content test asserts the exact text and
 * that it equals {@link GuestConfirmationSmsText#render} over the SMS templates the guest's SMS used.
 */
@DisplayName("GuestBookingDetailsText — Telegram booking details after linking")
class GuestBookingDetailsTextTest {

    private static final Instant NOW = Instant.parse("2026-10-31T08:00:00Z");
    /** 10:00 Kyiv (UTC+2 after the 25.10 DST switch). */
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2026-11-03T08:00:00Z");
    private static final UUID BOOKING_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID APPOINTMENT_ID = UUID.fromString("99999999-2222-3333-4444-555555555555");
    private static final String GUEST_PHONE = "+380501234567";
    private static final PhoneHasher PHONE_HASHER =
            new PhoneHasher(new PhoneHashPepperConfig("unit-test-pepper-unit-test-pepper-0123456789"));
    private static final byte[] PROVEN = PHONE_HASHER.hash(GUEST_PHONE).hash();
    private static final String FRONTEND = "https://beautica.app";
    private static final GuestCancelToken TOKEN = GuestCancelToken.valueOf("Ab3dE5gH7jK9mN1p");
    private static final String LINK = FRONTEND + "/c/" + TOKEN.value();
    private static final GuestCancelTokenCipher CIPHER = GuestCancelTokenCipherFixtures.testCipher();
    private static final BookingSmsProperties.Sms SMS = new BookingSmsProperties().getSms();

    private BookingRepository bookingRepository;
    private GuestBookingDetailsText detailsText;
    private Master master;
    private Salon salon;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        detailsText = new GuestBookingDetailsText(bookingRepository, PHONE_HASHER, Clock.fixed(NOW, ZoneOffset.UTC),
                new BookingSmsProperties(), GuestCancelTokenCipherFixtures.testPageLinks(FRONTEND));
        master = mock(Master.class);
        when(master.displayFirstName()).thenReturn(" Марія ");
        when(master.displayLastName()).thenReturn("Левченко");
        salon = mock(Salon.class);
        when(salon.getName()).thenReturn("Beauty Room");
        when(salon.getStreet()).thenReturn("вул. Хрещатик");
        when(salon.getBuildingNo()).thenReturn("1");
        when(salon.getLocationNote()).thenReturn("вхід з двору");
    }

    private Booking booking(UUID id, String service, OffsetDateTime startsAt, BookingStatus status,
                            String price, String priceMax) {
        ServiceDefinition definition = mock(ServiceDefinition.class);
        when(definition.getName()).thenReturn(service);
        MasterServiceAssignment assignment = mock(MasterServiceAssignment.class);
        when(assignment.getServiceDefinition()).thenReturn(definition);
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(id);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getSalon()).thenReturn(salon);
        when(booking.getMasterService()).thenReturn(assignment);
        when(booking.getBookingSource()).thenReturn(BookingSource.LINK);
        when(booking.getStatus()).thenReturn(status);
        when(booking.getStartsAt()).thenReturn(startsAt);
        when(booking.getGuestPhone()).thenReturn(GUEST_PHONE);
        when(booking.getPriceAtBooking()).thenReturn(new BigDecimal(price));
        when(booking.getPriceMaxAtBooking()).thenReturn(priceMax == null ? null : new BigDecimal(priceMax));
        when(booking.getCancelTokenSealed()).thenReturn(
                CIPHER.seal(TOKEN, GuestCancelTokenCipher.Row.booking(id)));
        return booking;
    }

    private void found(Booking... rows) {
        when(bookingRepository.findWithVisitForGuestDetails(BOOKING_ID)).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("render exactly the salon confirmation SMS — salon, master, address with note, own page link")
    void should_renderSalonConfirmationSms_when_singleSalonBooking() {
        Booking lead = booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.00", null);
        found(lead);

        Optional<String> text = detailsText.render(BOOKING_ID, PROVEN);

        assertThat(text).contains("""
                Beautica: Ваш запис підтверджено!

                03.11.2026 о 10:00
                Салон Beauty Room, майстер Марія Левченко.
                Адреса: вул. Хрещатик 1 (вхід з двору)
                Скасувати: https://beautica.app/c/Ab3dE5gH7jK9mN1p""");
        assertThat(text).as("Telegram text == the LINK confirmation SMS text")
                .contains(GuestConfirmationSmsText.render(SMS.getConfirmationSalon(), SMS.getConfirmation(), lead, LINK));
    }

    @Test
    @DisplayName("link the VISIT header's page and use the first CONFIRMED item's time for a visit")
    void should_linkVisitHeaderPage_when_visit() {
        Booking lead = booking(BOOKING_ID, "Стрижка", STARTS_AT, BookingStatus.CONFIRMED, "300.00", "500.00");
        Appointment appointment = mock(Appointment.class);
        when(appointment.getId()).thenReturn(APPOINTMENT_ID);
        when(appointment.getCancelTokenSealed()).thenReturn(
                CIPHER.seal(TOKEN, GuestCancelTokenCipher.Row.appointment(APPOINTMENT_ID)));
        when(lead.getAppointment()).thenReturn(appointment);
        Booking second = booking(UUID.randomUUID(), "Укладка", STARTS_AT.plusHours(1), BookingStatus.CONFIRMED,
                "200.00", null);
        when(second.getAppointment()).thenReturn(appointment);
        found(second, lead);

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).contains("\n03.11.2026 о 10:00\n").endsWith("\nСкасувати: " + LINK)
                .doesNotContain("Стрижка").doesNotContain("Ціна");
    }

    @Test
    @DisplayName("render the independent-master SMS with the master's own address and note")
    void should_renderMasterConfirmationSms_when_independentMaster() {
        Booking lead = booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.50", null);
        when(lead.getSalon()).thenReturn(null);
        User masterUser = mock(User.class);
        when(masterUser.getStreet()).thenReturn("вул. Січових Стрільців");
        when(masterUser.getBuildingNo()).thenReturn(null);
        when(masterUser.getLocationNote()).thenReturn("2 поверх");
        when(master.getUser()).thenReturn(masterUser);
        found(lead);

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).isEqualTo("""
                Beautica: Ваш запис підтверджено!

                03.11.2026 о 10:00
                Майстер Марія Левченко.
                Адреса: вул. Січових Стрільців (2 поверх)
                Скасувати: https://beautica.app/c/Ab3dE5gH7jK9mN1p""");
        assertThat(text).isEqualTo(
                GuestConfirmationSmsText.render(SMS.getConfirmationSalon(), SMS.getConfirmation(), lead, LINK));
    }

    @Test
    @DisplayName("omit the address line (and its note) when the salon has no address")
    void should_omitAddressLine_when_salonHasNoAddress() {
        when(salon.getStreet()).thenReturn(" ");
        when(salon.getBuildingNo()).thenReturn(null);
        found(booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.00", null));

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).doesNotContain("Адреса:").doesNotContain("вхід з двору");
    }

    @Test
    @DisplayName("sanitise provider text like the SMS — a URL-like salon name and note are dropped")
    void should_dropUrlLikeProviderText_when_rendered() {
        when(salon.getName()).thenReturn("Знижки на beauty-deals.com");
        when(salon.getLocationNote()).thenReturn("t.me/promo");
        found(booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.00", null));

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).contains("\nМайстер Марія Левченко.\nАдреса: вул. Хрещатик 1\n")
                .doesNotContain("beauty-deals").doesNotContain("t.me");
    }

    @Test
    @DisplayName("drop the «Скасувати:» line when the row has no sealed token (created before V230)")
    void should_dropCancelLine_when_noSealedToken() {
        Booking lead = booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.00", null);
        when(lead.getCancelTokenSealed()).thenReturn(null);
        found(lead);

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).endsWith("(вхід з двору)").doesNotContain("Скасувати");
    }

    @Test
    @DisplayName("render nothing when the booking is no longer CONFIRMED")
    void should_renderNothing_when_bookingCancelled() {
        found(booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CANCELLED, "350.00", null));

        assertThat(detailsText.render(BOOKING_ID, PROVEN)).isEmpty();
    }

    @Test
    @DisplayName("render nothing when the booking has already started")
    void should_renderNothing_when_bookingStarted() {
        found(booking(BOOKING_ID, "Манікюр", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), BookingStatus.CONFIRMED,
                "350.00", null));

        assertThat(detailsText.render(BOOKING_ID, PROVEN)).isEmpty();
    }

    @Test
    @DisplayName("render nothing for an account (non-guest) booking")
    void should_renderNothing_when_accountBooking() {
        Booking lead = booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.00", null);
        when(lead.getClient()).thenReturn(mock(User.class));
        found(lead);

        assertThat(detailsText.render(BOOKING_ID, PROVEN)).isEmpty();
    }

    @Test
    @DisplayName("render nothing when the booking does not exist")
    void should_renderNothing_when_bookingMissing() {
        when(bookingRepository.findWithVisitForGuestDetails(BOOKING_ID)).thenReturn(List.of());

        assertThat(detailsText.render(BOOKING_ID, PROVEN)).isEmpty();
    }

    @Test
    @DisplayName("send nothing when the booking's guest phone no longer matches the proven phone")
    void should_sendNothing_when_bookingPhoneNoLongerMatchesProof() {
        Booking lead = booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.00", null);
        when(lead.getGuestPhone()).thenReturn("+380671112233");
        found(lead);

        assertThat(detailsText.render(BOOKING_ID, PROVEN)).isEmpty();
    }

    @Test
    @DisplayName("send nothing when no proven phone hash is supplied")
    void should_sendNothing_when_provenPhoneHashMissing() {
        found(booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.00", null));

        assertThat(detailsText.render(BOOKING_ID, null)).isEmpty();
    }

    @Test
    @DisplayName("render the walk-in confirmation SMS for a STAFF visit, loading the whole visit in one query")
    void should_renderWalkInSmsInOneQuery_when_walkInVisit() {
        Booking lead = walkIn(booking(BOOKING_ID, "Мій манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.00", null),
                "Манікюр");
        Booking second = walkIn(booking(UUID.randomUUID(), "Мій педикюр", STARTS_AT.plusHours(1),
                BookingStatus.CONFIRMED, "400.00", null), "Педикюр");
        Appointment appointment = mock(Appointment.class);
        when(appointment.getId()).thenReturn(APPOINTMENT_ID);
        when(appointment.getCancelTokenSealed()).thenReturn(
                CIPHER.seal(TOKEN, GuestCancelTokenCipher.Row.appointment(APPOINTMENT_ID)));
        when(lead.getAppointment()).thenReturn(appointment);
        when(second.getAppointment()).thenReturn(appointment);
        found(second, lead);

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).isEqualTo(GuestConfirmationSmsText.render(
                SMS.getWalkInConfirmationSalon(), SMS.getWalkInConfirmation(), lead, LINK));
        assertThat(text).doesNotContain("Мій ");
        verify(bookingRepository, times(1)).findWithVisitForGuestDetails(BOOKING_ID);
        verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
        verify(bookingRepository, never()).findAllByIdsWithGraph(any());
    }

    @Test
    @DisplayName("render the Kyiv wall clock in summer too (UTC+3, DST on) — not a fixed winter offset")
    void should_renderKyivSummerTime_when_bookingInDstPeriod() {
        found(booking(BOOKING_ID, "Манікюр", OffsetDateTime.parse("2027-06-15T07:00:00Z"), BookingStatus.CONFIRMED,
                "350.00", null));

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).as("07:00Z is 10:00 Kyiv in June, actual=%s", text).contains("\n15.06.2027 о 10:00\n");
    }

    @Test
    @DisplayName("still confirm the rest of the visit when the linked line itself was cancelled")
    void should_renderRemainingVisitItems_when_leadItemNoLongerConfirmed() {
        Booking lead = booking(BOOKING_ID, "Стрижка", STARTS_AT, BookingStatus.CANCELLED, "300.00", null);
        Appointment appointment = mock(Appointment.class);
        when(appointment.getId()).thenReturn(APPOINTMENT_ID);
        when(lead.getAppointment()).thenReturn(appointment);
        Booking second = booking(UUID.randomUUID(), "Укладка", STARTS_AT.plusHours(1), BookingStatus.CONFIRMED,
                "200.00", null);
        found(lead, second);

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).contains("\n03.11.2026 о 11:00\n");
    }

    @Test
    @DisplayName("render nothing when every line of the visit is no longer CONFIRMED")
    void should_renderNothing_when_wholeVisitCancelled() {
        Booking lead = booking(BOOKING_ID, "Стрижка", STARTS_AT, BookingStatus.CANCELLED, "300.00", null);
        Appointment appointment = mock(Appointment.class);
        when(lead.getAppointment()).thenReturn(appointment);
        Booking second = booking(UUID.randomUUID(), "Укладка", STARTS_AT.plusHours(1), BookingStatus.DECLINED,
                "200.00", null);
        found(lead, second);

        assertThat(detailsText.render(BOOKING_ID, PROVEN)).isEmpty();
    }

    private static Booking walkIn(Booking booking, String platformName) {
        ServiceType type = mock(ServiceType.class);
        when(type.getNameUk()).thenReturn(platformName);
        when(booking.getMasterService().getServiceDefinition().getServiceType()).thenReturn(type);
        when(booking.getBookingSource()).thenReturn(BookingSource.STAFF);
        return booking;
    }
}
