package com.beautica.booking.service;

import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.security.PhoneHasher;
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

/** Plain mocks (no strict-stubs extension): each booking fixture stubs every getter the renderer may read. */
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

    private BookingRepository bookingRepository;
    private GuestBookingDetailsText detailsText;
    private Master master;
    private Salon salon;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        detailsText = new GuestBookingDetailsText(bookingRepository, PHONE_HASHER, Clock.fixed(NOW, ZoneOffset.UTC));
        master = mock(Master.class);
        when(master.displayFirstName()).thenReturn(" Марія ");
        when(master.displayLastName()).thenReturn("Левченко");
        salon = mock(Salon.class);
        when(salon.getName()).thenReturn("Beauty Room");
        when(salon.getStreet()).thenReturn("вул. Хрещатик");
        when(salon.getBuildingNo()).thenReturn("1");
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
        return booking;
    }

    private void found(Booking... rows) {
        when(bookingRepository.findWithVisitForGuestDetails(BOOKING_ID)).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("render heading, service, Kyiv date/time, master, salon, address and price for one booking")
    void should_renderAllDetails_when_singleSalonBooking() {
        found(booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.00", null));

        Optional<String> text = detailsText.render(BOOKING_ID, PROVEN);

        assertThat(text).contains("""
                Запис підтверджено ✅
                Послуга: Манікюр
                Дата: 03.11.2026 о 10:00
                Майстер: Марія Левченко
                Салон: Beauty Room
                Адреса: вул. Хрещатик, 1
                Ціна: 350 ₴""");
    }

    @Test
    @DisplayName("list every CONFIRMED visit service in time order and sum the price band")
    void should_listConfirmedServicesAndSumBand_when_visit() {
        Booking lead = booking(BOOKING_ID, "Стрижка", STARTS_AT, BookingStatus.CONFIRMED, "300.00", "500.00");
        Appointment appointment = mock(Appointment.class);
        when(appointment.getId()).thenReturn(APPOINTMENT_ID);
        when(lead.getAppointment()).thenReturn(appointment);
        Booking second = booking(UUID.randomUUID(), "Укладка", STARTS_AT.plusHours(1), BookingStatus.CONFIRMED,
                "200.00", null);
        Booking declined = booking(UUID.randomUUID(), "Фарбування", STARTS_AT.plusHours(2), BookingStatus.DECLINED,
                "900.00", null);
        found(second, declined, lead);

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).contains("\nПослуги: Стрижка, Укладка\n").doesNotContain("Фарбування")
                .endsWith("\nЦіна: від 500 до 700 ₴");
    }

    @Test
    @DisplayName("use the independent master's own address and no salon line when there is no salon")
    void should_useMasterAddressWithoutSalonLine_when_independentMaster() {
        Booking lead = booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.50", null);
        when(lead.getSalon()).thenReturn(null);
        User masterUser = mock(User.class);
        when(masterUser.getStreet()).thenReturn("вул. Січових Стрільців");
        when(masterUser.getBuildingNo()).thenReturn(null);
        when(master.getUser()).thenReturn(masterUser);
        found(lead);

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).doesNotContain("Салон:").contains("\nАдреса: вул. Січових Стрільців\n")
                .endsWith("\nЦіна: 350.50 ₴");
    }

    @Test
    @DisplayName("omit the address line when the salon has none")
    void should_omitAddressLine_when_salonHasNoAddress() {
        when(salon.getStreet()).thenReturn(" ");
        when(salon.getBuildingNo()).thenReturn(null);
        found(booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.00", null));

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).doesNotContain("Адреса:");
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
    @DisplayName("name a walk-in visit's services by platform service type, loading the whole visit in one query")
    void should_usePlatformNamesInOneQuery_when_walkInVisit() {
        Booking lead = walkIn(booking(BOOKING_ID, "Мій манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.00", null),
                "Манікюр");
        Booking second = walkIn(booking(UUID.randomUUID(), "Мій педикюр", STARTS_AT.plusHours(1),
                BookingStatus.CONFIRMED, "400.00", null), "Педикюр");
        Appointment appointment = mock(Appointment.class);
        when(appointment.getId()).thenReturn(APPOINTMENT_ID);
        when(lead.getAppointment()).thenReturn(appointment);
        when(second.getAppointment()).thenReturn(appointment);
        found(second, lead);

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).contains("\nПослуги: Манікюр, Педикюр\n").doesNotContain("Мій ")
                .endsWith("\nЦіна: 750 ₴");
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

        assertThat(text).as("07:00Z is 10:00 Kyiv in June, actual=%s", text).contains("\nДата: 15.06.2027 о 10:00\n");
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

        assertThat(text).contains("\nПослуга: Укладка\n").doesNotContain("Стрижка")
                .contains("\nДата: 03.11.2026 о 11:00\n").endsWith("\nЦіна: 200 ₴");
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

    @Test
    @DisplayName("show a single price, not «від X до X», when the frozen ceiling equals the floor")
    void should_renderFixedPrice_when_ceilingEqualsFloor() {
        found(booking(BOOKING_ID, "Манікюр", STARTS_AT, BookingStatus.CONFIRMED, "350.00", "350.00"));

        String text = detailsText.render(BOOKING_ID, PROVEN).orElseThrow();

        assertThat(text).endsWith("\nЦіна: 350 ₴").doesNotContain("від");
    }

    private static Booking walkIn(Booking booking, String platformName) {
        ServiceType type = mock(ServiceType.class);
        when(type.getNameUk()).thenReturn(platformName);
        when(booking.getMasterService().getServiceDefinition().getServiceType()).thenReturn(type);
        when(booking.getBookingSource()).thenReturn(BookingSource.STAFF);
        return booking;
    }
}
