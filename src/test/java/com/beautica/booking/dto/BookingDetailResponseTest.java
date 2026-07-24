package com.beautica.booking.dto;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BookingDetailResponse.from — unit")
class BookingDetailResponseTest {

    private static final OffsetDateTime STARTS_AT =
            OffsetDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime ENDS_AT =
            OffsetDateTime.of(2025, 6, 15, 11, 0, 0, 0, ZoneOffset.UTC);
    private static final Instant CREATED_AT = Instant.parse("2025-06-01T08:00:00Z");

    private UUID bookingId;
    private UUID clientId;
    private UUID masterId;
    private UUID masterServiceId;

    // Separate User mocks so the test can verify traversal for client vs. master
    private User clientUser;
    private User masterUser;
    private Booking booking;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        clientId = UUID.randomUUID();
        masterId = UUID.randomUUID();
        masterServiceId = UUID.randomUUID();

        clientUser = mock(User.class);
        when(clientUser.getId()).thenReturn(clientId);
        when(clientUser.getFirstName()).thenReturn("Олена");
        when(clientUser.getLastName()).thenReturn("Коваль");

        masterUser = mock(User.class);
        when(masterUser.getFirstName()).thenReturn("Наталія");
        when(masterUser.getLastName()).thenReturn("Бойко");
        when(masterUser.getProfessionalTitle()).thenReturn("Перукар-стиліст");
        when(masterUser.getLocationNote()).thenReturn("3-й поверх, код 1234");

        var master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getUser()).thenReturn(masterUser);

        var serviceDef = mock(ServiceDefinition.class);
        when(serviceDef.getName()).thenReturn("Манікюр");

        var masterService = mock(MasterServiceAssignment.class);
        when(masterService.getId()).thenReturn(masterServiceId);
        when(masterService.getServiceDefinition()).thenReturn(serviceDef);

        booking = mock(Booking.class);
        when(booking.getId()).thenReturn(bookingId);
        when(booking.getClient()).thenReturn(clientUser);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getMasterService()).thenReturn(masterService);
        when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
        when(booking.getStartsAt()).thenReturn(STARTS_AT);
        when(booking.getEndsAt()).thenReturn(ENDS_AT);
        when(booking.getPriceAtBooking()).thenReturn(new BigDecimal("350.00"));
        when(booking.getDurationMinutesAtBooking()).thenReturn(60);
        when(booking.getCreatedAt()).thenReturn(CREATED_AT);
        when(booking.getClientComment()).thenReturn("great service");
        when(booking.getProviderComment()).thenReturn("punctual");
    }

    @Test
    @DisplayName("maps every field correctly when booking is fully populated, including PII traversal")
    void should_mapAllFields_when_bookingIsValid() {
        var response = BookingDetailResponse.from(booking, true, "Київ", "Шевченківський");

        // shared fields
        assertThat(response.id()).isEqualTo(bookingId);
        assertThat(response.clientId()).isEqualTo(clientId);
        assertThat(response.masterId()).isEqualTo(masterId);
        assertThat(response.masterServiceId()).isEqualTo(masterServiceId);
        assertThat(response.serviceName()).isEqualTo("Манікюр");
        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.priceAtBooking()).isEqualByComparingTo(new BigDecimal("350.00"));
        assertThat(response.priceMaxAtBooking())
                .as("no frozen ceiling on the row means a single price")
                .isNull();
        assertThat(response.durationMinutesAtBooking()).isEqualTo(60);

        // time zone conversion
        assertThat(response.startsAt().getZone()).isEqualTo(ZoneId.of("Europe/Kyiv"));
        assertThat(response.startsAt().getHour()).isEqualTo(13);
        assertThat(response.endsAt().getZone()).isEqualTo(ZoneId.of("Europe/Kyiv"));
        assertThat(response.endsAt().getHour()).isEqualTo(14);

        // createdAt
        assertThat(response.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(response.createdAt().toInstant()).isEqualTo(CREATED_AT);

        // client PII — sourced from booking.getClient()
        assertThat(response.clientFirstName()).isEqualTo("Олена");
        assertThat(response.clientLastName()).isEqualTo("Коваль");

        // master PII — sourced from booking.getMaster().getUser(), NOT booking.getClient()
        assertThat(response.masterFirstName()).isEqualTo("Наталія");
        assertThat(response.masterLastName()).isEqualTo("Бойко");
        assertThat(response.masterProfessionalTitle()).isEqualTo("Перукар-стиліст");

        // comments
        assertThat(response.clientComment()).isEqualTo("great service");
        assertThat(response.providerComment()).isEqualTo("punctual");

        // Phase 19.3 enrichment — passed-in canReview + resolved labels, category from service def
        assertThat(response.canReview()).isTrue();
        assertThat(response.cityLabel()).isEqualTo("Київ");
        assertThat(response.districtLabel()).isEqualTo("Шевченківський");
        assertThat(response.salonName()).isNull();

        // locationNote — no salon on this booking, so it resolves from the master's own user row
        assertThat(response.locationNote()).isEqualTo("3-й поверх, код 1234");
    }

    @Test
    @DisplayName("clientComment and providerComment are null when absent on the booking")
    void should_returnNullComments_when_commentsAreAbsent() {
        when(booking.getClientComment()).thenReturn(null);
        when(booking.getProviderComment()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, null, null);

        assertThat(response.clientComment()).isNull();
        assertThat(response.providerComment()).isNull();
    }

    @Test
    @DisplayName("clientId is null and clientFirstName/clientLastName fall back to the guest identity when the booking has no registered client (LINK)")
    void should_returnGuestIdentity_when_bookingHasNoRegisteredClient() {
        when(booking.getClient()).thenReturn(null);
        when(booking.getGuestName()).thenReturn("Оксана");
        when(booking.getGuestSurname()).thenReturn("Мельник");

        var response = BookingDetailResponse.from(booking, false, "Київ", "Шевченківський");

        assertThat(response.clientId()).isNull();
        assertThat(response.clientFirstName()).isEqualTo("Оксана");
        assertThat(response.clientLastName()).isEqualTo("Мельник");
        // master PII is unaffected — sourced from booking.getMaster().getUser(), never getClient()
        assertThat(response.masterFirstName()).isEqualTo("Наталія");
        assertThat(response.masterLastName()).isEqualTo("Бойко");
        assertThat(response.masterProfessionalTitle()).isEqualTo("Перукар-стиліст");
    }

    @Test
    @DisplayName("masterProfessionalTitle is null (not NPE) when the master never set one")
    void should_returnNullProfessionalTitle_when_masterHasNoTitle() {
        when(masterUser.getProfessionalTitle()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, "Київ", "Шевченківський");

        assertThat(response.masterProfessionalTitle()).isNull();
        // the rest of the master row is unaffected by a missing title
        assertThat(response.masterFirstName()).isEqualTo("Наталія");
        assertThat(response.masterLastName()).isEqualTo("Бойко");
    }

    @Test
    @DisplayName("masterProfessionalTitle and locationNote are null (not NPE) on a guest (LINK) booking whose master has set neither — both fields hang off the master's user, never the (possibly-null) client")
    void should_returnNullProfessionalTitle_when_guestBookingAndMasterHasNoTitle() {
        when(booking.getClient()).thenReturn(null);
        when(booking.getGuestName()).thenReturn("Оксана");
        when(booking.getGuestSurname()).thenReturn("Мельник");
        when(masterUser.getProfessionalTitle()).thenReturn(null);
        when(masterUser.getLocationNote()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, "Київ", "Шевченківський");

        assertThat(response.clientId()).isNull();
        assertThat(response.masterProfessionalTitle()).isNull();
        assertThat(response.locationNote()).isNull();
    }

    // ── locationNote — salon-vs-independent resolution (mirrors street/buildingNo) ────

    @Test
    @DisplayName("locationNote resolves from the master's OWN user row when the master has no salon (independent master)")
    void should_resolveLocationNoteFromMaster_when_masterIsIndependent() {
        when(masterUser.getLocationNote()).thenReturn("Дзвонити двічі");
        // master.getSalon() is unstubbed on this mock -> null, exercising the independent branch.

        var response = BookingDetailResponse.from(booking, false, "Київ", "Шевченківський");

        assertThat(response.salonName()).isNull();
        assertThat(response.locationNote()).isEqualTo("Дзвонити двічі");
    }

    @Test
    @DisplayName("locationNote resolves from the SALON, never the master's own note, when the master is salon-employed")
    void should_resolveLocationNoteFromSalon_when_masterIsSalonEmployed() {
        // The master's own note is set to a DIFFERENT value to prove the salon wins.
        when(masterUser.getLocationNote()).thenReturn("Master's own note — must NOT surface");
        var salon = mock(Salon.class);
        when(salon.getName()).thenReturn("Glamour Studio");
        when(salon.getLocationNote()).thenReturn("3-й поверх, код 1234");
        var master = booking.getMaster();
        when(master.getSalon()).thenReturn(salon);

        var response = BookingDetailResponse.from(booking, false, "Київ", "Шевченківський");

        assertThat(response.salonName()).isEqualTo("Glamour Studio");
        assertThat(response.locationNote()).isEqualTo("3-й поверх, код 1234");
    }

    // ── priceMaxAtBooking — the frozen snapshot column (V119), NOT a live derivation. The
    //    creation-time rule that produced the stored value is pinned by BookingPriceRangeTest.

    @Test
    @DisplayName("priceMaxAtBooking carries the frozen ceiling stored on the booking row")
    void should_returnFrozenCeiling_when_bookingHasOne() {
        when(booking.getPriceMaxAtBooking()).thenReturn(new BigDecimal("500.00"));

        var response = BookingDetailResponse.from(booking, false, "Київ", "Шевченківський");

        assertThat(response.priceMaxAtBooking()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("priceMaxAtBooking is null when the booking froze no ceiling — a single price")
    void should_returnNullPriceMax_when_bookingFrozeNoCeiling() {
        when(booking.getPriceMaxAtBooking()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, "Київ", "Шевченківський");

        assertThat(response.priceMaxAtBooking()).isNull();
    }

    @Test
    @DisplayName("priceMaxAtBooking ignores the service's CURRENT priceType/priceMax — a provider "
            + "editing their service must not rewrite a band the client already agreed to")
    void should_keepFrozenCeiling_when_providerEditsServiceAfterBooking() {
        when(booking.getPriceMaxAtBooking()).thenReturn(new BigDecimal("500.00"));
        // The provider has since widened the live service far beyond the agreed band.
        var serviceDef = booking.getMasterService().getServiceDefinition();
        lenient().when(serviceDef.getPriceType()).thenReturn(PriceType.RANGE);
        lenient().when(serviceDef.getPriceMax()).thenReturn(new BigDecimal("9999.00"));
        lenient().when(booking.getMasterService().getPriceOverride()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, "Київ", "Шевченківський");

        assertThat(response.priceMaxAtBooking())
                .as("the agreed ceiling, not the edited one")
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("a FIXED-service booking that froze a ceiling still shows it — the snapshot wins "
            + "even when the live service could no longer produce one")
    void should_keepFrozenCeiling_when_providerFlippedServiceToFixedAfterBooking() {
        when(booking.getPriceMaxAtBooking()).thenReturn(new BigDecimal("500.00"));
        var serviceDef = booking.getMasterService().getServiceDefinition();
        lenient().when(serviceDef.getPriceType()).thenReturn(PriceType.FIXED);
        lenient().when(serviceDef.getPriceMax()).thenReturn(null);

        var response = BookingDetailResponse.from(booking, false, "Київ", "Шевченківський");

        assertThat(response.priceMaxAtBooking()).isEqualByComparingTo(new BigDecimal("500.00"));
    }
}
