package com.beautica.notification.email;

import com.beautica.booking.entity.Booking;
import com.beautica.notification.ServiceLineExpectedCopy;
import com.beautica.notification.service.BookingVisit;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ServiceLines — unit")
class ServiceLinesTest {

    // 2025-11-21 13:00Z = 14:00 Kyiv (UTC+2, winter)
    private static final OffsetDateTime WINTER_START = OffsetDateTime.parse("2025-11-21T12:00:00Z");
    // 2025-07-04 11:00Z = 14:00 Kyiv (UTC+3, summer / DST)
    private static final OffsetDateTime SUMMER_START = OffsetDateTime.parse("2025-07-04T11:00:00Z");

    @Test
    @DisplayName("lines follow visit order, not the order the items were passed in")
    void should_keepVisitOrder_when_itemsPassedUnsorted() {
        Booking first = booking("Манікюр", WINTER_START, 60, "500", null);
        Booking second = booking("Педикюр", WINTER_START.plusMinutes(60), 90, "500", null);

        List<ServiceLine> lines = ServiceLines.of(BookingVisit.of(first, List.of(second, first)));

        assertThat(lines).extracting(ServiceLine::text).containsExactly(
                ServiceLineExpectedCopy.text("Манікюр"),
                ServiceLineExpectedCopy.text("15:00", "Педикюр", "1 год 30 хв", ServiceLineExpectedCopy.PRICE));
    }

    @Test
    @DisplayName("price comes from the booking snapshot, range ceiling included")
    void should_useFrozenPriceRange_when_ceilingSnapshotted() {
        Booking booking = booking("Манікюр", WINTER_START, 60, "500", "700");

        ServiceLine line = ServiceLines.of(booking);

        assertThat(line.priceLabel()).isEqualTo(ServiceLineExpectedCopy.RANGE_PRICE);
        assertThat(line.text()).isEqualTo(ServiceLineExpectedCopy.text(
                ServiceLineExpectedCopy.TIME, "Манікюр", ServiceLineExpectedCopy.DURATION,
                ServiceLineExpectedCopy.RANGE_PRICE));
    }

    @Test
    @DisplayName("rangeText is «{start}–{end} » + the same body the line uses")
    void should_composeRangeFromLineBody_when_rangeTextBuilt() {
        Booking booking = booking("Манікюр", WINTER_START, 60, "500", null);

        String range = ServiceLines.rangeText(booking);

        assertThat(range).isEqualTo(ServiceLineExpectedCopy.timeRange("14:00", "15:00") + " "
                + ServiceLines.of(booking).body());
        assertThat(range).isEqualTo(ServiceLineExpectedCopy.rangeText(
                "14:00", "15:00", "Манікюр", ServiceLineExpectedCopy.DURATION, ServiceLineExpectedCopy.PRICE));
        assertThat(ServiceLines.of(booking).body()).isEqualTo(
                ServiceLineExpectedCopy.text("Манікюр").substring(ServiceLineExpectedCopy.TIME.length() + 1));
    }

    @Test
    @DisplayName("visit header is Kyiv time, winter offset")
    void should_renderKyivHeader_when_winterVisit() {
        Booking first = booking("Манікюр", WINTER_START, 60, "500", null);
        Booking second = booking("Педикюр", WINTER_START.plusMinutes(60), 90, "500", null);

        String header = ServiceLines.visitHeader(BookingVisit.of(first, List.of(first, second)));

        assertThat(header).isEqualTo(ServiceLineExpectedCopy.VISIT_HEADER);
    }

    @Test
    @DisplayName("visit header is Kyiv time, summer (DST) offset")
    void should_renderKyivHeader_when_summerVisit() {
        Booking first = booking("Манікюр", SUMMER_START, 60, "500", null);
        Booking second = booking("Педикюр", SUMMER_START.plusMinutes(60), 90, "500", null);

        String header = ServiceLines.visitHeader(BookingVisit.of(first, List.of(first, second)));

        assertThat(header).isEqualTo(ServiceLineExpectedCopy.visitHeader("пт, 4 липня", "14:00", "16:30"));
    }

    @Test
    @DisplayName("a single-service visit has no header")
    void should_returnNullHeader_when_singleService() {
        Booking only = booking("Манікюр", WINTER_START, 60, "500", null);

        assertThat(ServiceLines.visitHeader(BookingVisit.single(only))).isNull();
    }

    private static Booking booking(String name, OffsetDateTime start, int minutes, String price, String priceMax) {
        ServiceDefinition definition = mock(ServiceDefinition.class);
        when(definition.getName()).thenReturn(name);
        MasterServiceAssignment assignment = mock(MasterServiceAssignment.class);
        when(assignment.getServiceDefinition()).thenReturn(definition);
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getMasterService()).thenReturn(assignment);
        when(booking.getStartsAt()).thenReturn(start);
        when(booking.getEndsAt()).thenReturn(start.plusMinutes(minutes));
        when(booking.getDurationMinutesAtBooking()).thenReturn(minutes);
        when(booking.getPriceAtBooking()).thenReturn(new BigDecimal(price));
        when(booking.getPriceMaxAtBooking()).thenReturn(priceMax == null ? null : new BigDecimal(priceMax));
        return booking;
    }
}
