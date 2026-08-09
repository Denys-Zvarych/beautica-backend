package com.beautica.notification.service;

import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingVisitResolver — unit")
class BookingVisitResolverTest {

    private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-06-15T10:00:00Z");
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.randomUUID();

    @Mock
    private BookingRepository bookingRepository;

    private BookingVisitResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new BookingVisitResolver(bookingRepository);
    }

    // ── hydrate: one query for the whole batch ───────────────────────────────

    @Test
    @DisplayName("should_issueNoQuery_when_noLeadBelongsToAVisit")
    void should_issueNoQuery_when_noLeadBelongsToAVisit() {
        Booking booking = booking("Стрижка", BASE, BookingStatus.CONFIRMED);
        when(booking.getAppointment()).thenReturn(null);

        Map<UUID, List<Booking>> hydrated = resolver.hydrate(List.of(booking));

        assertThat(hydrated).isEmpty();
        verify(bookingRepository, never()).findByAppointmentIdsWithGraph(any());
    }

    @Test
    @DisplayName("should_issueExactlyOneQuery_when_aBatchCarriesSeveralEntriesOfSeveralVisits")
    void should_issueExactlyOneQuery_when_aBatchCarriesSeveralEntriesOfSeveralVisits() {
        // The production shape this exists for: ONE created visit enqueues TWO outbox rows
        // (NEW_BOOKING + STATUS_CHANGED) against the SAME lead booking, so a batch of four entries
        // spanning two visits must still cost one statement — not one per entry, and not one per
        // visit either.
        Appointment first = appointment(BookingStatus.CONFIRMED);
        Appointment second = appointment(BookingStatus.CONFIRMED);
        Booking leadA = booking("Стрижка", BASE, BookingStatus.CONFIRMED, first);
        Booking leadB = booking("Манікюр", BASE, BookingStatus.CONFIRMED, second);
        when(bookingRepository.findByAppointmentIdsWithGraph(anyList()))
                .thenReturn(List.of(leadA, leadB));

        Map<UUID, List<Booking>> hydrated = resolver.hydrate(List.of(leadA, leadA, leadB, leadB));

        assertThat(hydrated).containsOnlyKeys(first.getId(), second.getId());
        verify(bookingRepository, org.mockito.Mockito.times(1)).findByAppointmentIdsWithGraph(anyList());
    }

    @Test
    @DisplayName("should_deduplicateAppointmentIds_when_twoEntriesShareOneVisit")
    void should_deduplicateAppointmentIds_when_twoEntriesShareOneVisit() {
        Appointment appointment = appointment(BookingStatus.CONFIRMED);
        Booking lead = booking("Стрижка", BASE, BookingStatus.CONFIRMED, appointment);
        when(bookingRepository.findByAppointmentIdsWithGraph(List.of(appointment.getId())))
                .thenReturn(List.of(lead));

        resolver.hydrate(List.of(lead, lead));

        // A duplicated id in the IN-list would make Postgres scan the same rows twice for nothing.
        verify(bookingRepository).findByAppointmentIdsWithGraph(List.of(appointment.getId()));
    }

    @Test
    @DisplayName("should_readOnlyTheAppointmentIdentifier_when_hydratingFromTheCallersProxy")
    void should_readOnlyTheAppointmentIdentifier_when_hydratingFromTheCallersProxy() {
        // The caller's Appointment is an uninitialised LAZY proxy on a DETACHED booking during the
        // drain worker's NOT_SUPPORTED phase: getId() is served off the proxy, any other property
        // would throw LazyInitializationException in production. Pin that only getId() is touched.
        Appointment proxy = mock(Appointment.class);
        UUID appointmentId = UUID.randomUUID();
        when(proxy.getId()).thenReturn(appointmentId);
        Booking lead = booking("Стрижка", BASE, BookingStatus.CONFIRMED);
        when(lead.getAppointment()).thenReturn(proxy);
        when(bookingRepository.findByAppointmentIdsWithGraph(List.of(appointmentId)))
                .thenReturn(List.of());

        resolver.hydrate(List.of(lead));

        verify(proxy).getId();
        verifyNoMoreInteractions(proxy);
    }

    // ── resolve: pure, no query ──────────────────────────────────────────────

    @Test
    @DisplayName("should_collapseToTheLeadBooking_when_theBookingHasNoAppointment")
    void should_collapseToTheLeadBooking_when_theBookingHasNoAppointment() {
        Booking booking = booking("Стрижка", BASE, BookingStatus.CONFIRMED);
        when(booking.getAppointment()).thenReturn(null);

        BookingVisit visit = resolver.resolve(booking, Map.of());

        assertThat(visit.items()).containsExactly(booking);
        assertThat(visit.isMultiService()).isFalse();
        assertThat(visit.appointmentStatus()).isNull();
        verifyNoMoreInteractions(bookingRepository);
    }

    @Test
    @DisplayName("should_nameEverySibling_when_theBookingBelongsToAVisit")
    void should_nameEverySibling_when_theBookingBelongsToAVisit() {
        Appointment appointment = appointment(BookingStatus.CONFIRMED);
        Booking lead = booking("Стрижка", BASE, BookingStatus.CONFIRMED, appointment);
        Booking sibling = booking("Фарбування", BASE.plusHours(1), BookingStatus.CONFIRMED, appointment);

        BookingVisit visit = resolver.resolve(
                lead, Map.of(appointment.getId(), List.of(lead, sibling)));

        assertThat(visit.serviceNames()).containsExactly("Стрижка", "Фарбування");
        assertThat(visit.lead()).isSameAs(lead);
        assertThat(visit.appointmentStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verifyNoMoreInteractions(bookingRepository);
    }

    @Test
    @DisplayName("should_fallBackToTheLeadBooking_when_theAppointmentResolvesToNoItems")
    void should_fallBackToTheLeadBooking_when_theAppointmentResolvesToNoItems() {
        Appointment appointment = appointment(BookingStatus.CONFIRMED);
        Booking lead = booking("Стрижка", BASE, BookingStatus.CONFIRMED, appointment);

        BookingVisit visit = resolver.resolve(lead, Map.of());

        assertThat(visit.items())
                .as("a notification must still go out rather than dead-lettering on an impossible state")
                .containsExactly(lead);
    }

    // ── S1: only items in the lead's status belong to the event ──────────────

    @Test
    @DisplayName("should_dropSiblingsInADifferentStatus_when_oneItemWasAlreadyCancelled")
    void should_dropSiblingsInADifferentStatus_when_oneItemWasAlreadyCancelled() {
        // A STATUS_CHANGED row that retries after an SMTP failure, or drains after a same-window
        // per-item decline, must not mail the client a CONFIRMED list still naming the cancelled
        // service.
        Appointment appointment = appointment(BookingStatus.CONFIRMED);
        Booking lead = booking("Стрижка", BASE, BookingStatus.CONFIRMED, appointment);
        Booking cancelled = booking("Фарбування", BASE.plusHours(1), BookingStatus.DECLINED, appointment);

        BookingVisit visit = resolver.resolve(
                lead, Map.of(appointment.getId(), List.of(lead, cancelled)));

        assertThat(visit.serviceNames()).containsExactly("Стрижка");
        assertThat(visit.isMultiService()).isFalse();
    }

    @Test
    @DisplayName("should_carryTheDeclinedHeaderStatus_when_theWholeVisitWasDeclined")
    void should_carryTheDeclinedHeaderStatus_when_theWholeVisitWasDeclined() {
        // The signal NotificationService#isWholeVisitDecline branches on — without it a whole-visit
        // decline names only the lead service and the client turns up for the rest.
        Appointment appointment = appointment(BookingStatus.DECLINED);
        Booking lead = booking("Стрижка", BASE, BookingStatus.DECLINED, appointment);
        Booking sibling = booking("Фарбування", BASE.plusHours(1), BookingStatus.DECLINED, appointment);

        BookingVisit visit = resolver.resolve(
                lead, Map.of(appointment.getId(), List.of(lead, sibling)));

        assertThat(visit.appointmentStatus()).isEqualTo(BookingStatus.DECLINED);
        assertThat(visit.serviceNames()).containsExactly("Стрижка", "Фарбування");
    }

    // ── S4: defensive tenancy filter ─────────────────────────────────────────

    @Test
    @DisplayName("should_dropAForeignMastersItem_when_anAppointmentChainsRowsOfTwoMasters")
    void should_dropAForeignMastersItem_when_anAppointmentChainsRowsOfTwoMasters() {
        // Nothing at DB level requires rows sharing an appointment_id to share a master_id. No code
        // path writes such a row today; this resolver is what would turn one into another master's
        // service name rendered inside a client's mail body.
        Appointment appointment = appointment(BookingStatus.CONFIRMED);
        Booking lead = booking("Стрижка", BASE, BookingStatus.CONFIRMED, appointment);
        Booking foreign = booking("Секретна послуга", BASE.plusHours(1), BookingStatus.CONFIRMED,
                appointment, UUID.randomUUID());

        BookingVisit visit = resolver.resolve(
                lead, Map.of(appointment.getId(), List.of(lead, foreign)));

        assertThat(visit.serviceNames())
                .as("a foreign master's service name must never reach this client's e-mail")
                .containsExactly("Стрижка");
    }

    @Test
    @DisplayName("should_dropAForeignClientsItem_when_anAppointmentChainsRowsOfTwoClients")
    void should_dropAForeignClientsItem_when_anAppointmentChainsRowsOfTwoClients() {
        // The CLIENT is the direction the mail travels: sendBookingConfirmedEmail and
        // sendVisitDeclinedEmail are both addressed to lead.getClient().getEmail(). A row chained
        // onto the same appointment under the SAME master but a DIFFERENT client would therefore
        // render that other client's service names into THIS client's inbox. Nothing at DB level
        // forbids the row (V125 adds only the FK + index).
        Appointment appointment = appointment(BookingStatus.CONFIRMED);
        Booking lead = booking("Стрижка", BASE, BookingStatus.CONFIRMED, appointment);
        Booking foreign = booking("Секретна послуга", BASE.plusHours(1), BookingStatus.CONFIRMED,
                appointment, MASTER_ID, UUID.randomUUID());

        BookingVisit visit = resolver.resolve(
                lead, Map.of(appointment.getId(), List.of(lead, foreign)));

        assertThat(visit.serviceNames())
                .as("another client's service name must never reach this client's e-mail")
                .containsExactly("Стрижка");
    }

    @Test
    @DisplayName("should_keepEverySibling_when_aGuestVisitChainsRowsWithNoClientAccount")
    void should_keepEverySibling_when_aGuestVisitChainsRowsWithNoClientAccount() {
        // A guest (LINK) visit has client_id NULL on EVERY row (V89). The client leg of the tenancy
        // filter must treat null == null as the same party, or guest visits silently collapse to a
        // one-service e-mail — the very defect this whole change fixes.
        Appointment appointment = appointment(BookingStatus.CONFIRMED);
        Booking lead = booking("Стрижка", BASE, BookingStatus.CONFIRMED, appointment, MASTER_ID, null);
        Booking sibling = booking("Фарбування", BASE.plusHours(1), BookingStatus.CONFIRMED,
                appointment, MASTER_ID, null);

        BookingVisit visit = resolver.resolve(
                lead, Map.of(appointment.getId(), List.of(lead, sibling)));

        assertThat(visit.serviceNames()).containsExactly("Стрижка", "Фарбування");
    }

    @Test
    @DisplayName("should_fallBackToTheLeadBooking_when_everyItemIsFilteredOut")
    void should_fallBackToTheLeadBooking_when_everyItemIsFilteredOut() {
        Appointment appointment = appointment(BookingStatus.CONFIRMED);
        Booking lead = booking("Стрижка", BASE, BookingStatus.CONFIRMED, appointment);
        Booking other = booking("Фарбування", BASE.plusHours(1), BookingStatus.DECLINED, appointment);

        BookingVisit visit = resolver.resolve(lead, Map.of(appointment.getId(), List.of(other)));

        assertThat(visit.items()).containsExactly(lead);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static Appointment appointment(BookingStatus status) {
        Appointment appointment = mock(Appointment.class);
        lenient().when(appointment.getId()).thenReturn(UUID.randomUUID());
        lenient().when(appointment.getStatus()).thenReturn(status);
        return appointment;
    }

    private static Booking booking(String serviceName, OffsetDateTime startsAt, BookingStatus status) {
        return booking(serviceName, startsAt, status, null, MASTER_ID);
    }

    private static Booking booking(String serviceName, OffsetDateTime startsAt, BookingStatus status,
                                   Appointment appointment) {
        return booking(serviceName, startsAt, status, appointment, MASTER_ID);
    }

    private static Booking booking(String serviceName, OffsetDateTime startsAt, BookingStatus status,
                                   Appointment appointment, UUID masterId) {
        return booking(serviceName, startsAt, status, appointment, masterId, CLIENT_ID);
    }

    /** {@code clientId == null} models a guest (LINK) visit — {@code bookings.client_id} is nullable. */
    private static Booking booking(String serviceName, OffsetDateTime startsAt, BookingStatus status,
                                   Appointment appointment, UUID masterId, UUID clientId) {
        Booking booking = mock(Booking.class);
        lenient().when(booking.getId()).thenReturn(UUID.randomUUID());
        lenient().when(booking.getStartsAt()).thenReturn(startsAt);
        lenient().when(booking.getEndsAt()).thenReturn(startsAt.plusMinutes(60));
        lenient().when(booking.getStatus()).thenReturn(status);
        lenient().when(booking.getAppointment()).thenReturn(appointment);

        if (clientId != null) {
            User client = mock(User.class);
            lenient().when(client.getId()).thenReturn(clientId);
            lenient().when(booking.getClient()).thenReturn(client);
        }

        Master master = mock(Master.class);
        lenient().when(master.getId()).thenReturn(masterId);
        lenient().when(booking.getMaster()).thenReturn(master);

        ServiceDefinition sd = mock(ServiceDefinition.class);
        lenient().when(sd.getName()).thenReturn(serviceName);
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        lenient().when(msa.getServiceDefinition()).thenReturn(sd);
        lenient().when(booking.getMasterService()).thenReturn(msa);
        return booking;
    }
}
