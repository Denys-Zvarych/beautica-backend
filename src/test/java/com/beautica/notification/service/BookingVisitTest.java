package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@DisplayName("BookingVisit — unit")
class BookingVisitTest {

    private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-06-15T10:00:00Z");

    @Test
    @DisplayName("should_collapseToTheBookingItself_when_single")
    void should_collapseToTheBookingItself_when_single() {
        Booking booking = booking("Стрижка", BASE, 60);

        BookingVisit visit = BookingVisit.single(booking);

        assertThat(visit.items()).containsExactly(booking);
        assertThat(visit.lead()).isSameAs(booking);
        assertThat(visit.size()).isEqualTo(1);
        assertThat(visit.isMultiService()).isFalse();
        assertThat(visit.serviceNames()).containsExactly("Стрижка");
        assertThat(visit.leadServiceName()).isEqualTo("Стрижка");
    }

    @Test
    @DisplayName("should_orderItemsByStartsAt_when_repositoryOrderIsShuffled")
    void should_orderItemsByStartsAt_when_repositoryOrderIsShuffled() {
        Booking first = booking("Стрижка", BASE, 60);
        Booking second = booking("Фарбування", BASE.plusHours(1), 90);
        Booking third = booking("Укладка", BASE.plusHours(3), 30);

        BookingVisit visit = BookingVisit.of(first, List.of(third, first, second));

        assertThat(visit.serviceNames())
                .as("render order must come from startsAt, never from repository iteration order")
                .containsExactly("Стрижка", "Фарбування", "Укладка");
        assertThat(visit.startsAt()).isEqualTo(BASE);
    }

    @Test
    @DisplayName("should_returnTheLatestEnd_when_aLaterItemEndsBeforeAnEarlierOne")
    void should_returnTheLatestEnd_when_aLaterItemEndsBeforeAnEarlierOne() {
        // A per-item reschedule can leave the LAST-STARTING item ending before an earlier one, so
        // endsAt() must be a max over the items, not "the last element's end".
        Booking longFirst = booking("Фарбування", BASE, 240);           // ends 14:00
        Booking shortSecond = booking("Брови", BASE.plusHours(1), 15);  // starts 11:00, ends 11:15

        BookingVisit visit = BookingVisit.of(longFirst, List.of(longFirst, shortSecond));

        assertThat(visit.endsAt()).isEqualTo(BASE.plusMinutes(240));
    }

    @Test
    @DisplayName("should_sumPerItemDurations_when_totalDurationRequested")
    void should_sumPerItemDurations_when_totalDurationRequested() {
        BookingVisit visit = BookingVisit.of(
                booking("Стрижка", BASE, 60),
                List.of(booking("Стрижка", BASE, 60), booking("Укладка", BASE.plusHours(1), 45)));

        assertThat(visit.totalDurationMinutes()).isEqualTo(105);
    }

    @Test
    @DisplayName("should_retainDuplicates_when_theSameServiceIsBookedTwiceInOneVisit")
    void should_retainDuplicates_when_theSameServiceIsBookedTwiceInOneVisit() {
        // VisitPlanner allows the same service verbatim more than once in a chain.
        BookingVisit visit = BookingVisit.of(
                booking("Манікюр", BASE, 60),
                List.of(booking("Манікюр", BASE, 60), booking("Манікюр", BASE.plusHours(1), 60)));

        assertThat(visit.serviceNames()).containsExactly("Манікюр", "Манікюр");
    }

    @Test
    @DisplayName("should_defaultToNoAppointmentStatus_when_theHeaderIsNotSupplied")
    void should_defaultToNoAppointmentStatus_when_theHeaderIsNotSupplied() {
        // A null header is the CONSERVATIVE default: consumers that branch on it (the decline copy)
        // fall back to the per-item wording, so a caller that cannot resolve the header can never
        // accidentally announce a whole-visit cancellation.
        Booking booking = booking("Стрижка", BASE, 60);

        assertThat(BookingVisit.single(booking).appointmentStatus()).isNull();
        assertThat(BookingVisit.of(booking, List.of(booking)).appointmentStatus()).isNull();
    }

    @Test
    @DisplayName("should_carryTheAppointmentStatus_when_theHeaderIsSupplied")
    void should_carryTheAppointmentStatus_when_theHeaderIsSupplied() {
        Booking booking = booking("Стрижка", BASE, 60);

        BookingVisit visit = BookingVisit.of(booking, List.of(booking), BookingStatus.DECLINED);

        assertThat(visit.appointmentStatus()).isEqualTo(BookingStatus.DECLINED);
    }

    @Test
    @DisplayName("should_readTheLeadNameFromTheOrderedItems_when_theListArrivesShuffled")
    void should_readTheLeadNameFromTheOrderedItems_when_theListArrivesShuffled() {
        // leadServiceName() is an index read into the precomputed, ORDERED name list — it must be
        // the earliest-starting service, not whichever row the repository happened to return first.
        Booking first = booking("Стрижка", BASE, 60);
        Booking second = booking("Фарбування", BASE.plusHours(1), 90);

        BookingVisit visit = BookingVisit.of(second, List.of(second, first));

        assertThat(visit.leadServiceName()).isEqualTo("Стрижка");
    }

    @Test
    @DisplayName("should_rejectAnEmptyItemList_when_constructed")
    void should_rejectAnEmptyItemList_when_constructed() {
        Booking booking = booking("Стрижка", BASE, 60);

        assertThatThrownBy(() -> BookingVisit.of(booking, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    private static Booking booking(String serviceName, OffsetDateTime startsAt, int durationMinutes) {
        Booking booking = mock(Booking.class);
        lenient().when(booking.getId()).thenReturn(UUID.randomUUID());
        lenient().when(booking.getStartsAt()).thenReturn(startsAt);
        lenient().when(booking.getEndsAt()).thenReturn(startsAt.plusMinutes(durationMinutes));
        lenient().when(booking.getDurationMinutesAtBooking()).thenReturn(durationMinutes);

        ServiceDefinition sd = mock(ServiceDefinition.class);
        lenient().when(sd.getName()).thenReturn(serviceName);
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        lenient().when(msa.getServiceDefinition()).thenReturn(sd);
        lenient().when(booking.getMasterService()).thenReturn(msa);
        return booking;
    }
}
