package com.beautica.booking.dto;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
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
        when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
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
        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.priceAtBooking()).isEqualByComparingTo(new BigDecimal("350.00"));
        assertThat(response.priceMaxAtBooking())
                .as("no frozen ceiling on the row means a single price")
                .isNull();
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

    @Test
    @DisplayName("startsAt has +02:00 offset when mapping a January (winter) UTC timestamp")
    void should_returnUtcPlusTwoOffset_when_mappingWinterStartsAt() {
        // 2025-01-15T10:00:00Z — Kyiv is on UTC+2 in January (no DST)
        var winterStartsAt = OffsetDateTime.of(2025, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC);

        var client = booking.getClient();
        var master = booking.getMaster();
        var masterService = booking.getMasterService();

        var winterBooking = mock(Booking.class);
        when(winterBooking.getId()).thenReturn(bookingId);
        when(winterBooking.getClient()).thenReturn(client);
        when(winterBooking.getMaster()).thenReturn(master);
        when(winterBooking.getMasterService()).thenReturn(masterService);
        when(winterBooking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
        when(winterBooking.getStartsAt()).thenReturn(winterStartsAt);
        when(winterBooking.getEndsAt()).thenReturn(winterStartsAt.plusHours(1));
        when(winterBooking.getPriceAtBooking()).thenReturn(new BigDecimal("350.00"));
        when(winterBooking.getDurationMinutesAtBooking()).thenReturn(60);
        when(winterBooking.getCreatedAt()).thenReturn(CREATED_AT);

        var response = BookingResponse.from(winterBooking);

        assertThat(response.startsAt().getZone()).isEqualTo(ZoneId.of("Europe/Kyiv"));
        assertThat(response.startsAt().getOffset())
                .as("Kyiv offset in January must be UTC+2 (winter time, no DST)")
                .isEqualTo(ZoneOffset.ofHours(2));
        assertThat(response.startsAt().getHour())
                .as("10:00 UTC in January Kyiv (UTC+2) must map to hour 12")
                .isEqualTo(12);
    }

    // ── priceMaxAtBooking — the frozen snapshot column (V119), NOT a live derivation. The
    //    creation-time rule that produced the stored value is pinned by BookingPriceRangeTest.

    @Test
    @DisplayName("priceMaxAtBooking carries the frozen ceiling stored on the booking row")
    void should_returnFrozenCeiling_when_bookingHasOne() {
        when(booking.getPriceMaxAtBooking()).thenReturn(new BigDecimal("500.00"));

        var response = BookingResponse.from(booking);

        assertThat(response.priceMaxAtBooking()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("priceMaxAtBooking is null when the booking froze no ceiling — a single price")
    void should_returnNullPriceMax_when_bookingFrozeNoCeiling() {
        when(booking.getPriceMaxAtBooking()).thenReturn(null);

        var response = BookingResponse.from(booking);

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

        var response = BookingResponse.from(booking);

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

        var response = BookingResponse.from(booking);

        assertThat(response.priceMaxAtBooking()).isEqualByComparingTo(new BigDecimal("500.00"));
    }
}
