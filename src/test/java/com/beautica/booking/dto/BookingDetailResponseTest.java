package com.beautica.booking.dto;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BookingDetailResponse.from — unit")
class BookingDetailResponseTest {

    private static final UUID BOOKING_ID        = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID CLIENT_ID         = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID MASTER_ID         = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final UUID MASTER_SERVICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000014");

    private static final OffsetDateTime STARTS_AT =
            OffsetDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime ENDS_AT =
            OffsetDateTime.of(2025, 6, 15, 11, 0, 0, 0, ZoneOffset.UTC);
    private static final Instant CREATED_AT = Instant.parse("2025-06-01T08:00:00Z");

    // Separate User mocks so the test can verify traversal for client vs. master
    private User clientUser;
    private User masterUser;
    private Booking booking;

    @BeforeEach
    void setUp() {
        clientUser = mock(User.class);
        when(clientUser.getId()).thenReturn(CLIENT_ID);
        when(clientUser.getFirstName()).thenReturn("Олена");
        when(clientUser.getLastName()).thenReturn("Коваль");

        masterUser = mock(User.class);
        when(masterUser.getFirstName()).thenReturn("Наталія");
        when(masterUser.getLastName()).thenReturn("Бойко");

        var master = mock(Master.class);
        when(master.getId()).thenReturn(MASTER_ID);
        when(master.getUser()).thenReturn(masterUser);

        var serviceDef = mock(ServiceDefinition.class);
        when(serviceDef.getName()).thenReturn("Манікюр");

        var masterService = mock(MasterServiceAssignment.class);
        when(masterService.getId()).thenReturn(MASTER_SERVICE_ID);
        when(masterService.getServiceDefinition()).thenReturn(serviceDef);

        booking = mock(Booking.class);
        when(booking.getId()).thenReturn(BOOKING_ID);
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
        var response = BookingDetailResponse.from(booking);

        // shared fields
        assertThat(response.id()).isEqualTo(BOOKING_ID);
        assertThat(response.clientId()).isEqualTo(CLIENT_ID);
        assertThat(response.masterId()).isEqualTo(MASTER_ID);
        assertThat(response.masterServiceId()).isEqualTo(MASTER_SERVICE_ID);
        assertThat(response.serviceName()).isEqualTo("Манікюр");
        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.priceAtBooking()).isEqualByComparingTo(new BigDecimal("350.00"));
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

        // comments
        assertThat(response.clientComment()).isEqualTo("great service");
        assertThat(response.providerComment()).isEqualTo("punctual");
    }

    @Test
    @DisplayName("clientComment and providerComment are null when absent on the booking")
    void should_returnNullComments_when_commentsAreAbsent() {
        when(booking.getClientComment()).thenReturn(null);
        when(booking.getProviderComment()).thenReturn(null);

        var response = BookingDetailResponse.from(booking);

        assertThat(response.clientComment()).isNull();
        assertThat(response.providerComment()).isNull();
    }
}
