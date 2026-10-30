package com.beautica.booking.service;

import com.beautica.TestConstants;
import com.beautica.booking.dto.CreateAppointmentRequest;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.salon.entity.Salon;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the master-resolution gate at the head of
 * {@code AppointmentService#doCreateAppointment} — {@code POST /api/v1/appointments}.
 *
 * <p><b>Why this class exists (2026-08 security re-audit HIGH).</b> The multi-service visit path
 * takes a client-supplied {@code request.masterId()} exactly like the single-service
 * {@code BookingService#doCreateBooking}, but it carried only {@code Master::isActive} while the
 * single-service path had been hardened with the salon-active term. An attacker could replay the
 * scenario blocked by {@code BookingServiceTest#should_return404_when_bookingMasterOfDeactivatedSalon}
 * one endpoint over and still mint {@code CONFIRMED} bookings against a closed salon. Both create
 * paths now share {@link com.beautica.booking.domain.MasterBookability}.
 *
 * <p>The guard is the very first statement of the create path, so only {@code masterRepository}
 * needs stubbing; the assertions below prove nothing downstream is even reached.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentService — salon-active guard on the visit-create path")
class AppointmentServiceTest {

    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.randomUUID();
    private static final UUID MASTER_SERVICE_ID = UUID.randomUUID();
    private static final String IDEMPOTENCY_KEY = "visit-key-1";

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private MasterRepository masterRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationOutboxService outboxService;
    @Mock
    private SlotCalculationService slotCalculationService;
    @Mock
    private SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    @Mock
    private AuthorizationService authz;
    @Mock
    private DiscoveryLocationResolver discoveryLocationResolver;
    @Mock
    private VisitPlanner visitPlanner;
    // Never read: the salon-active guard short-circuits before any temporal validation. Declared
    // only so @InjectMocks can satisfy the constructor.
    @Mock
    private Clock clock;

    @Mock
    private com.beautica.moderation.service.BookingBlockGuard bookingBlockGuard;

    @InjectMocks
    private AppointmentService appointmentService;

    @Test
    @DisplayName("404 is thrown when the master's salon was deactivated, even though the master row "
            + "itself is still active")
    void should_return404_when_creatingVisitForMasterOfDeactivatedSalon() {
        // Arrange — the ONLY false flag is the salon's. masters.is_active stays true, exactly the
        // state SalonService.deactivateSalon leaves behind (it does not cascade to masters).
        Master salonMaster = salonMaster(false);
        assertThat(salonMaster.isActive())
                .as("precondition: the master row is NOT deactivated — otherwise this test would "
                        + "merely be re-testing the pre-existing Master::isActive filter")
                .isTrue();
        when(masterRepository.findByIdWithUserAndSalon(MASTER_ID)).thenReturn(Optional.of(salonMaster));

        // Act + Assert
        assertThatThrownBy(() -> appointmentService.createAppointment(CLIENT_ID, null, request()))
                .isInstanceOf(NotFoundException.class);

        // No visit may be planned or written, and the client must not even be loaded — the guard is
        // part of the same filter chain as the existence check, so it short-circuits everything.
        verifyNoInteractions(visitPlanner);
        verify(appointmentRepository, never()).save(any());
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("404 is thrown when the master row itself is inactive (pre-existing filter still holds)")
    void should_return404_when_masterIsInactive() {
        Master inactiveMaster = Master.builder()
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(false)
                .build();
        ReflectionTestUtils.setField(inactiveMaster, "id", MASTER_ID);
        when(masterRepository.findByIdWithUserAndSalon(MASTER_ID)).thenReturn(Optional.of(inactiveMaster));

        assertThatThrownBy(() -> appointmentService.createAppointment(CLIENT_ID, null, request()))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(visitPlanner);
    }

    @Test
    @DisplayName("404 is thrown when the master does not exist at all")
    void should_return404_when_masterNotFound() {
        when(masterRepository.findByIdWithUserAndSalon(MASTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.createAppointment(CLIENT_ID, null, request()))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(visitPlanner);
    }

    // ── Phase 398 QA LOW — the block gate runs AFTER the under-lock replay re-check ─────────────

    @Test
    @DisplayName("an idempotent replay found under the client lock returns before the block gate runs")
    void should_replayWithoutBlockCheck_when_visitFoundOnUnderLockRecheck() {
        UUID existingId = UUID.randomUUID();
        arrangeCreateUpToReplay();
        // Fast path (no lock) misses, the authoritative re-check under the client lock hits.
        com.beautica.booking.entity.Appointment existing = appointment(existingId);
        when(appointmentRepository.findActiveByClientIdAndIdempotencyKey(CLIENT_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(appointmentRepository.findById(existingId)).thenReturn(Optional.of(existing));
        when(bookingRepository.findByAppointmentIdWithGraph(existingId)).thenReturn(List.of());

        // enrichCreated(existingId) reached with the replayed id; the empty item list is this fixture's stop.
        assertThatThrownBy(() -> appointmentService.createAppointment(CLIENT_ID, IDEMPOTENCY_KEY, request()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Appointment has no booking items");

        verifyNoInteractions(bookingBlockGuard);
        verify(bookingRepository, never()).acquireAdvisoryLockWithTimeout(any());
    }

    @Test
    @DisplayName("a non-replay create runs the block gate after the client lock + replay re-check, before the "
            + "master lock")
    void should_runBlockCheckAfterReplayRecheck_when_noExistingVisit() {
        Master master = arrangeCreateUpToReplay();
        when(appointmentRepository.findActiveByClientIdAndIdempotencyKey(CLIENT_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(com.beautica.common.exception.BookingUnavailableException.forSalon())
                .when(bookingBlockGuard).requireClientMayBook(master, CLIENT_ID);

        assertThatThrownBy(() -> appointmentService.createAppointment(CLIENT_ID, IDEMPOTENCY_KEY, request()))
                .isInstanceOf(com.beautica.common.exception.BookingUnavailableException.class);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bookingRepository, appointmentRepository,
                bookingBlockGuard);
        order.verify(bookingRepository).acquireClientAdvisoryLockWithTimeout(CLIENT_ID);
        order.verify(appointmentRepository).findActiveByClientIdAndIdempotencyKey(CLIENT_ID, IDEMPOTENCY_KEY);
        order.verify(bookingBlockGuard).requireClientMayBook(master, CLIENT_ID);
        verify(bookingRepository, never()).acquireAdvisoryLockWithTimeout(any());
        verify(appointmentRepository, never()).save(any());
    }

    /** Bookable master, a fixed clock the request's start is valid against, one planned item, a CLIENT, a lock. */
    private Master arrangeCreateUpToReplay() {
        ReflectionTestUtils.setField(appointmentService, "clock",
                Clock.fixed(java.time.Instant.parse("2026-08-09T08:00:00Z"), java.time.ZoneOffset.UTC));
        Master master = salonMaster(true);
        when(masterRepository.findByIdWithUserAndSalon(MASTER_ID)).thenReturn(Optional.of(master));
        java.time.OffsetDateTime start = request().startsAt().toOffsetDateTime();
        when(visitPlanner.planChainedItems(any(), any(), any())).thenReturn(List.of(new VisitPlanner.PlannedItem(
                null, start, start.plusHours(1), 60, 0, BigDecimal.TEN, null)));
        com.beautica.user.User client = org.mockito.Mockito.mock(com.beautica.user.User.class);
        when(client.getRole()).thenReturn(com.beautica.auth.Role.CLIENT);
        when(userRepository.findById(CLIENT_ID)).thenReturn(Optional.of(client));
        when(bookingRepository.acquireClientAdvisoryLockWithTimeout(CLIENT_ID)).thenReturn(1);
        return master;
    }

    private static com.beautica.booking.entity.Appointment appointment(UUID id) {
        com.beautica.booking.entity.Appointment appointment =
                org.mockito.Mockito.mock(com.beautica.booking.entity.Appointment.class);
        org.mockito.Mockito.lenient().when(appointment.getId()).thenReturn(id);
        return appointment;
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────

    /** An ACTIVE salon-employed master whose salon carries the given active flag. */
    private static Master salonMaster(boolean salonActive) {
        Salon salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
                .id(UUID.randomUUID())
                .isActive(salonActive)
                .build();
        Master master = Master.builder()
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "id", MASTER_ID);
        master.setSalon(salon);
        return master;
    }

    private static CreateAppointmentRequest request() {
        return new CreateAppointmentRequest(
                MASTER_ID,
                List.of(MASTER_SERVICE_ID),
                ZonedDateTime.parse("2026-08-10T10:00:00Z"),
                null,
                null,
                false);
    }
}
