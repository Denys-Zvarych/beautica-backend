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

@DisplayName("BookingResponse.from — unit")
class BookingResponseTest {

    private static final OffsetDateTime STARTS_AT =
            OffsetDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime ENDS_AT =
            OffsetDateTime.of(2025, 6, 15, 11, 0, 0, 0, ZoneOffset.UTC);
    private static final Instant CREATED_AT = Instant.parse("2025-06-01T08:00:00Z");

    private UUID bookingId;
    private UUID clientId;
    private UUID masterId;
    private UUID masterServiceId;

    private Booking booking;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        clientId = UUID.randomUUID();
        masterId = UUID.randomUUID();
        masterServiceId = UUID.randomUUID();

        var client = mock(User.class);
        when(client.getId()).thenReturn(clientId);

        var master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);

        var serviceDef = mock(ServiceDefinition.class);
        when(serviceDef.getName()).thenReturn("Манікюр");

        var masterService = mock(MasterServiceAssignment.class);
        when(masterService.getId()).thenReturn(masterServiceId);
        when(masterService.getServiceDefinition()).thenReturn(serviceDef);

        booking = mock(Booking.class);
        when(booking.getId()).thenReturn(bookingId);
        when(booking.getClient()).thenReturn(client);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getMasterService()).thenReturn(masterService);
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getStartsAt()).thenReturn(STARTS_AT);
        when(booking.getEndsAt()).thenReturn(ENDS_AT);
        when(booking.getPriceAtBooking()).thenReturn(new BigDecimal("350.00"));
        when(booking.getDurationMinutesAtBooking()).thenReturn(60);
        when(booking.getCreatedAt()).thenReturn(CREATED_AT);
    }

    @Test
    @DisplayName("maps every field correctly when booking is fully populated")
    void should_mapAllFields_when_bookingIsValid() {
        var response = BookingResponse.from(booking);

        assertThat(response.id()).isEqualTo(bookingId);
        assertThat(response.clientId()).isEqualTo(clientId);
        assertThat(response.masterId()).isEqualTo(masterId);
        assertThat(response.masterServiceId()).isEqualTo(masterServiceId);
        assertThat(response.serviceName()).isEqualTo("Манікюр");
        assertThat(response.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(response.priceAtBooking()).isEqualByComparingTo(new BigDecimal("350.00"));
        assertThat(response.durationMinutesAtBooking()).isEqualTo(60);

        // startsAt: 10:00 UTC → 13:00 Kyiv (UTC+3 in summer)
        assertThat(response.startsAt().getZone()).isEqualTo(ZoneId.of("Europe/Kyiv"));
        assertThat(response.startsAt().getHour()).isEqualTo(13);

        // endsAt: 11:00 UTC → 14:00 Kyiv
        assertThat(response.endsAt().getZone()).isEqualTo(ZoneId.of("Europe/Kyiv"));
        assertThat(response.endsAt().getHour()).isEqualTo(14);

        // createdAt: Instant → OffsetDateTime at UTC
        assertThat(response.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(response.createdAt().toInstant()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("startsAt is converted to Europe/Kyiv zone (UTC+3 in summer → hour = 13)")
    void should_returnKyivZone_when_mappingStartsAt() {
        var response = BookingResponse.from(booking);

        assertThat(response.startsAt().getZone()).isEqualTo(ZoneId.of("Europe/Kyiv"));
        assertThat(response.startsAt().getHour()).isEqualTo(13);
    }

    @Test
    @DisplayName("createdAt carries UTC offset when mapped from Instant")
    void should_returnUtcOffset_when_mappingCreatedAt() {
        var response = BookingResponse.from(booking);

        assertThat(response.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    }
}
