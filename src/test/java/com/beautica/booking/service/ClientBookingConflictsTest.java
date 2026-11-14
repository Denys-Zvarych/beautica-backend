package com.beautica.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.BookingTimeRange;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ClientBookingConflictException;
import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Unit coverage for {@link ClientBookingConflicts#build} — the single builder of
 * {@link ClientBookingConflictException} shared by {@code BookingService},
 * {@code AppointmentService} and {@code AppointmentTransitionService} (BOOK-74/75).
 *
 * <p>The provider branch is the security contract: the conflicting row may be the client's booking
 * at a COMPETING salon, so the builder must read only the 2-column window projection and must never
 * hydrate the full graph (a hydrated entity is one refactor away from leaking into the body or a log).
 * Names are random per test so no fixture value can coincidentally satisfy an assertion.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClientBookingConflicts.build — provider window-only vs client full body (BOOK-74/75)")
class ClientBookingConflictsTest {

    @Mock
    private BookingRepository bookingRepository;

    private UUID conflictingBookingId;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;

    @BeforeEach
    void setUp() {
        conflictingBookingId = UUID.randomUUID();
        startsAt = OffsetDateTime.of(2026, 7, 15, 14, 0, 0, 0, ZoneOffset.ofHours(3));
        endsAt = startsAt.plusMinutes(90);
    }

    @Nested
    @DisplayName("provider-initiated (initiatedByProvider = true)")
    class ProviderInitiated {

        @Test
        @DisplayName("returns a redacted exception carrying ONLY the window — id, service and master are null")
        void should_returnWindowOnlyException_when_providerInitiated() {
            when(bookingRepository.findTimeRangeById(conflictingBookingId))
                    .thenReturn(Optional.of(new BookingTimeRange(startsAt, endsAt)));

            ClientBookingConflictException ex =
                    ClientBookingConflicts.build(bookingRepository, conflictingBookingId, true);

            assertThat(ex.isRedacted()).as("provider-initiated conflict must be redacted").isTrue();
            assertThat(ex.getConflictingBookingId()).as("provider must not learn the booking id").isNull();
            assertThat(ex.getServiceName()).as("provider must not learn the service").isNull();
            assertThat(ex.getMasterName()).as("provider must not learn the master").isNull();
            assertThat(ex.getStartsAt()).isEqualTo(startsAt);
            assertThat(ex.getEndsAt()).isEqualTo(endsAt);
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("reads the conflicting row via the window projection only — the full graph is never loaded")
        void should_neverLoadFullGraph_when_providerInitiated() {
            when(bookingRepository.findTimeRangeById(conflictingBookingId))
                    .thenReturn(Optional.of(new BookingTimeRange(startsAt, endsAt)));

            ClientBookingConflicts.build(bookingRepository, conflictingBookingId, true);

            verify(bookingRepository).findTimeRangeById(conflictingBookingId);
            verify(bookingRepository, never()).findByIdWithFullGraph(any());
            verify(bookingRepository, never()).findById(any());
        }

        @Test
        @DisplayName("throws 500 BusinessException when the window row is missing — id never echoed in the message")
        void should_throwInternalError_when_providerInitiatedAndRowMissing() {
            when(bookingRepository.findTimeRangeById(conflictingBookingId)).thenReturn(Optional.empty());

            Throwable thrown = catchThrowable(
                    () -> ClientBookingConflicts.build(bookingRepository, conflictingBookingId, true));

            assertThat(thrown).isExactlyInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(thrown.getMessage()).isNotBlank().doesNotContain(conflictingBookingId.toString());
            verify(bookingRepository, never()).findByIdWithFullGraph(any());
        }
    }

    @Nested
    @DisplayName("client-initiated (initiatedByProvider = false)")
    class ClientInitiated {

        @Test
        @DisplayName("returns the FULL exception — id, service name, master name and window of the client's own booking")
        void should_returnFullException_when_clientInitiated() {
            String serviceName = "svc-" + UUID.randomUUID();
            String firstName = "first-" + UUID.randomUUID();
            String lastName = "last-" + UUID.randomUUID();
            Booking conflicting = conflictingBooking(serviceName, firstName, lastName);
            when(bookingRepository.findByIdWithFullGraph(conflictingBookingId)).thenReturn(Optional.of(conflicting));

            ClientBookingConflictException ex =
                    ClientBookingConflicts.build(bookingRepository, conflictingBookingId, false);

            assertThat(ex.isRedacted()).as("client-initiated conflict must keep full detail").isFalse();
            assertThat(ex.getConflictingBookingId()).isEqualTo(conflictingBookingId);
            assertThat(ex.getServiceName()).isEqualTo(serviceName);
            assertThat(ex.getMasterName()).isEqualTo(firstName + " " + lastName);
            assertThat(ex.getStartsAt()).isEqualTo(startsAt);
            assertThat(ex.getEndsAt()).isEqualTo(endsAt);
        }

        @Test
        @DisplayName("loads the full graph and never takes the provider window-projection path")
        void should_loadFullGraphOnly_when_clientInitiated() {
            Booking conflicting = conflictingBooking("svc-" + UUID.randomUUID(), null, null);
            when(bookingRepository.findByIdWithFullGraph(conflictingBookingId)).thenReturn(Optional.of(conflicting));

            ClientBookingConflicts.build(bookingRepository, conflictingBookingId, false);

            verify(bookingRepository).findByIdWithFullGraph(conflictingBookingId);
            verify(bookingRepository, never()).findTimeRangeById(any());
        }

        @Test
        @DisplayName("throws the same 500 BusinessException as the provider branch when the row is missing")
        void should_throwSameInternalError_when_clientInitiatedAndRowMissing() {
            when(bookingRepository.findByIdWithFullGraph(conflictingBookingId)).thenReturn(Optional.empty());
            when(bookingRepository.findTimeRangeById(conflictingBookingId)).thenReturn(Optional.empty());

            Throwable clientThrown = catchThrowable(
                    () -> ClientBookingConflicts.build(bookingRepository, conflictingBookingId, false));
            Throwable providerThrown = catchThrowable(
                    () -> ClientBookingConflicts.build(bookingRepository, conflictingBookingId, true));

            assertThat(clientThrown).isExactlyInstanceOf(BusinessException.class);
            assertThat(((BusinessException) clientThrown).getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(clientThrown.getMessage())
                    .as("both branches share one not-loaded message")
                    .isEqualTo(providerThrown.getMessage());
        }
    }

    private Booking conflictingBooking(String serviceName, String masterFirstName, String masterLastName) {
        ServiceDefinition definition = mock(ServiceDefinition.class);
        when(definition.getName()).thenReturn(serviceName);
        MasterServiceAssignment assignment = mock(MasterServiceAssignment.class);
        when(assignment.getServiceDefinition()).thenReturn(definition);
        Master master = mock(Master.class);
        when(master.displayFirstName()).thenReturn(masterFirstName);
        when(master.displayLastName()).thenReturn(masterLastName);
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(conflictingBookingId);
        when(booking.getMasterService()).thenReturn(assignment);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getStartsAt()).thenReturn(startsAt);
        when(booking.getEndsAt()).thenReturn(endsAt);
        return booking;
    }
}
