package com.beautica.booking.service;

import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.dto.GuestBookingRequest;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.config.BookingSmsProperties;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.sms.SmsService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GuestBookingService — guest booking creation & auto-confirm")
class GuestBookingServiceTest {

    private static final String SLUG = "marija-l-cd34";
    private static final String GUEST_PHONE = "+380501234567";
    private static final String FRONTEND = "https://app.beautica.test";

    @Mock private GuestTokenProvider guestTokenProvider;
    @Mock private com.beautica.master.repository.MasterRepository masterRepository;
    @Mock private com.beautica.service.repository.MasterServiceRepository masterServiceRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private com.beautica.booking.repository.AppointmentRepository appointmentRepository;
    @Mock private SlotCalculationService slotCalculationService;
    @Mock private NotificationOutboxService outboxService;
    @Mock private SmsService smsService;
    @Mock private com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;

    private GuestBookingService service;

    private final UUID masterId = UUID.randomUUID();
    private final UUID serviceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GuestBookingService(
                guestTokenProvider, masterRepository, masterServiceRepository, bookingRepository,
                appointmentRepository, slotCalculationService, outboxService, smsService,
                new BookingSmsProperties(), salonCatalogCacheEvictor,
                new VisitPlanner(masterServiceRepository), FRONTEND,
                java.time.Clock.fixed(OffsetDateTime.parse("2026-06-01T10:00:00Z").toInstant(), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should persist a CONFIRMED LINK booking, send Ukrainian SMS with cancelUrl, and notify master")
    void should_createConfirmedGuestBooking_when_slotIsFree() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-06-10T12:00:00+03:00");
        stubHappyPath(startsAt);
        ArgumentCaptor<Booking> savedCaptor = ArgumentCaptor.forClass(Booking.class);
        when(bookingRepository.saveAndFlush(savedCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        GuestBookingResponse response = service.createGuestBooking(
                "Bearer guest.jwt.token", SLUG,
                new GuestBookingRequest(serviceId, startsAt, "Олена", "Коваль"));

        Booking saved = savedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(saved.getBookingSource()).isEqualTo(BookingSource.LINK);
        assertThat(saved.getGuestName()).isEqualTo("Олена");
        assertThat(saved.getGuestPhone()).isEqualTo(GUEST_PHONE);
        assertThat(saved.getCancelToken()).isNotNull();
        assertThat(response.cancelUrl()).startsWith(FRONTEND + "/book/cancel/");

        verify(outboxService).enqueueNewBooking(any());
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq(GUEST_PHONE), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .contains("Запис підтверджено")
                .contains(response.cancelUrl());
        // Advisory-lock DoS fix regression: the fused acquireAdvisoryLockWithTimeout() query
        // (perf fix — sets the transaction-scoped lock_timeout AND takes the master lock in one
        // round trip) must be the query actually used to acquire the master lock (see
        // BookingRepository.acquireAdvisoryLockWithTimeout / GuestBookingService.persistBooking
        // Javadoc). Its presence here in place of the old two-step
        // setBookingLockTimeout()+acquireAdvisoryLock() pair IS the regression guard that the
        // timeout ceiling is still applied before the lock wait.
        verify(bookingRepository).acquireAdvisoryLockWithTimeout(masterId);
    }

    @Test
    @DisplayName("should throw 409 when the requested slot is already taken")
    void should_throw409_when_slotTaken() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-06-10T12:00:00+03:00");
        when(guestTokenProvider.validate(anyString())).thenReturn(GUEST_PHONE);
        when(masterRepository.findByBookingSlugWithUser(SLUG)).thenReturn(Optional.of(master()));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, serviceId))
                .thenReturn(Optional.of(masterService()));
        when(bookingRepository.acquireAdvisoryLockWithTimeout(masterId)).thenReturn(1);
        when(bookingRepository.existsOverlap(eq(masterId), any(), any())).thenReturn(true);
        // On schedule on purpose: the 409 this test asserts must come from the OVERLAP check, not
        // from the create-path schedule-fit gate (which would return the same status for the wrong
        // reason and defang the assertion).
        stubSlotAvailable(startsAt);

        assertThatThrownBy(() -> service.createGuestBooking(
                "Bearer t", SLUG, new GuestBookingRequest(serviceId, startsAt, "Олена", "Коваль")))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.CONFLICT);

        verify(bookingRepository, never()).saveAndFlush(any());
        verifyNoInteractions(smsService);
        // Lock timeout must still be set even when the slot turns out to be taken — it is
        // fused into the same statement as the lock acquisition attempt, which is the first
        // lock-related statement in the transaction.
        verify(bookingRepository).acquireAdvisoryLockWithTimeout(masterId);
    }

    @Test
    @DisplayName("should throw 404 when the service does not belong to the resolved master")
    void should_throw404_when_serviceNotOwnedByMaster() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-06-10T12:00:00+03:00");
        when(guestTokenProvider.validate(anyString())).thenReturn(GUEST_PHONE);
        when(masterRepository.findByBookingSlugWithUser(SLUG)).thenReturn(Optional.of(master()));
        // empty = the WHERE ms.master.id = :masterId AND ms.id = :id matched nothing
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, serviceId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createGuestBooking(
                "Bearer t", SLUG, new GuestBookingRequest(serviceId, startsAt, "Олена", "Коваль")))
                .isInstanceOf(NotFoundException.class);

        // Neither the fused (with-timeout) nor the plain master-lock query ran — persistBooking
        // was never reached, so no lock-acquisition attempt (and therefore no timeout) happened.
        verify(bookingRepository, never()).acquireAdvisoryLockWithTimeout(any());
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
    }

    // ── REGRESSION PINS (Phase 13.3): startsAt lead-time + max-window parity ──
    // The authenticated BookingService.validateStartsAt enforces MIN_MINUTES_AHEAD (15 min) and
    // MAX_DAYS_AHEAD (180 d). The guest path once relied ONLY on the DTO's @Future, so a guest could
    // book 1 minute from now or 5 years out; these two tests were written RED against that gap.
    //
    // The fix landed in 3afa360 (2026-07-01) — GuestBookingService#createGuestBooking now calls
    // BookingStartsAtValidator.validate(req.startsAt(), kyivClock), the very same guard the
    // authenticated path uses — so both have been GREEN since. They are ordinary regression pins now:
    // deleting that validate() call turns them red again, which is exactly their job. (The stale
    // "EXPECTED-RED until the fix lands" markers they carried until 2026-08-11 claimed the opposite and
    // invited a reader to dismiss a real failure as expected.)
    //
    // NOT made redundant by the create-path schedule-fit gate added the same day: that gate answers
    // "does the master work then", returns 409, and is only reached for a start these 400s let through.

    @Test
    @DisplayName("guest create — 400 when startsAt is below the 15-min lead time (parity with the "
            + "authenticated path's BookingStartsAtValidator)")
    void should_reject_when_guestStartsAtBelowLeadTime() {
        // clock is fixed at 2026-06-01T10:00:00Z; +5 min is in the future (passes @Future)
        // but below the 15-min lead time the authenticated path enforces.
        OffsetDateTime tooSoon = OffsetDateTime.parse("2026-06-01T10:05:00Z");
        // lenient: the fix may validate startsAt before OR after the master/service lookup;
        // either ordering is acceptable, so these stubs must not be required.
        lenient().when(guestTokenProvider.validate(anyString())).thenReturn(GUEST_PHONE);
        lenient().when(masterRepository.findByBookingSlugWithUser(SLUG)).thenReturn(Optional.of(master()));
        lenient().when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, serviceId))
                .thenReturn(Optional.of(masterService()));

        assertThatThrownBy(() -> service.createGuestBooking(
                "Bearer t", SLUG, new GuestBookingRequest(serviceId, tooSoon, "Олена", "Коваль")))
                .as("guest startsAt below the 15-min lead time must be a 400 (parity with "
                        + "BookingService.validateStartsAt)")
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);

        verify(bookingRepository, never()).saveAndFlush(any());
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("guest create — 400 when startsAt is beyond the 180-day availability window (parity "
            + "with the authenticated path's BookingStartsAtValidator)")
    void should_reject_when_guestStartsAtBeyondAvailabilityWindow() {
        // clock is fixed at 2026-06-01; +400 days is well beyond both the authenticated
        // 180-day cap and the 60-day availabilityMaxDays the public endpoint serves.
        OffsetDateTime tooFar = OffsetDateTime.parse("2026-06-01T10:00:00Z").plusDays(400);
        // lenient: the fix may validate startsAt before OR after the master/service lookup.
        lenient().when(guestTokenProvider.validate(anyString())).thenReturn(GUEST_PHONE);
        lenient().when(masterRepository.findByBookingSlugWithUser(SLUG)).thenReturn(Optional.of(master()));
        lenient().when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, serviceId))
                .thenReturn(Optional.of(masterService()));

        assertThatThrownBy(() -> service.createGuestBooking(
                "Bearer t", SLUG, new GuestBookingRequest(serviceId, tooFar, "Олена", "Коваль")))
                .as("guest startsAt beyond the availability window must be a 400 (parity with "
                        + "BookingService.validateStartsAt / availabilityMaxDays)")
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);

        verify(bookingRepository, never()).saveAndFlush(any());
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("should propagate JwtException (→401) when the guest token is expired/invalid")
    void should_throwJwt_when_tokenExpired() {
        when(guestTokenProvider.validate(anyString()))
                .thenThrow(new io.jsonwebtoken.ExpiredJwtException(null, null, "expired"));

        assertThatThrownBy(() -> service.createGuestBooking(
                "Bearer expired", SLUG,
                new GuestBookingRequest(serviceId, OffsetDateTime.parse("2026-06-10T12:00:00+03:00"), "A", "B")))
                .isInstanceOf(JwtException.class);

        verifyNoInteractions(masterRepository, masterServiceRepository, bookingRepository);
    }

    @Test
    @DisplayName("should reject a missing Authorization header as a JwtException (→401)")
    void should_throwJwt_when_noAuthHeader() {
        assertThatThrownBy(() -> service.createGuestBooking(
                null, SLUG,
                new GuestBookingRequest(serviceId, OffsetDateTime.parse("2026-06-10T12:00:00+03:00"), "A", "B")))
                .isInstanceOf(JwtException.class);

        verifyNoInteractions(guestTokenProvider, masterRepository, bookingRepository);
    }

    // ── multi-service visit (BE-7) — ONE SMS naming the whole visit ───────────

    @Test
    @DisplayName("should name the visit as «<first> та ще N послуги» in the ONE guest confirmation SMS "
            + "when a multi-service visit is booked")
    void should_nameTheWholeVisitInOneSms_when_guestBooksMultipleServices() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-06-10T12:00:00+03:00");
        UUID secondServiceId = UUID.randomUUID();
        UUID thirdServiceId = UUID.randomUUID();
        when(guestTokenProvider.validate(anyString())).thenReturn(GUEST_PHONE);
        when(masterRepository.findByBookingSlugWithUser(SLUG)).thenReturn(Optional.of(master()));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, serviceId))
                .thenReturn(Optional.of(masterService()));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, secondServiceId))
                .thenReturn(Optional.of(masterService(secondServiceId, "Педикюр")));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, thirdServiceId))
                .thenReturn(Optional.of(masterService(thirdServiceId, "Брови")));
        when(bookingRepository.acquireAdvisoryLockWithTimeout(masterId)).thenReturn(1);
        when(bookingRepository.existsOverlap(eq(masterId), any(), any())).thenReturn(false);
        when(bookingRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        stubVisitSlotAvailable(startsAt, List.of(serviceId, secondServiceId, thirdServiceId));

        GuestBookingResponse response = service.createGuestBooking(
                "Bearer guest.jwt.token", SLUG,
                new GuestBookingRequest(null, List.of(serviceId, secondServiceId, thirdServiceId),
                        startsAt, "Олена", "Коваль"));

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq(GUEST_PHONE), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .as("the guest must learn the visit covers more than the lead service")
                .isEqualTo("Beautica: Запис підтверджено!\n"
                        + "Марія Левченко, Манікюр та ще 2 послуги\n"
                        + "10.06.2026 о 12:00\n\n"
                        + "Скасувати: " + response.cancelUrl());
        // Exactly ONE SMS and ONE outbox row for the whole visit — cardinality is unchanged.
        verify(smsService).send(anyString(), anyString());
        verify(outboxService).enqueueNewBooking(any());
    }

    @Test
    @DisplayName("should send the bare service name — no «та ще» suffix — when a one-item "
            + "masterServiceIds visit is booked")
    void should_sendBareServiceName_when_guestVisitCarriesASingleService() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-06-10T12:00:00+03:00");
        when(guestTokenProvider.validate(anyString())).thenReturn(GUEST_PHONE);
        when(masterRepository.findByBookingSlugWithUser(SLUG)).thenReturn(Optional.of(master()));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, serviceId))
                .thenReturn(Optional.of(masterService()));
        when(bookingRepository.acquireAdvisoryLockWithTimeout(masterId)).thenReturn(1);
        when(bookingRepository.existsOverlap(eq(masterId), any(), any())).thenReturn(false);
        when(bookingRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        stubVisitSlotAvailable(startsAt, List.of(serviceId));

        GuestBookingResponse response = service.createGuestBooking(
                "Bearer guest.jwt.token", SLUG,
                new GuestBookingRequest(null, List.of(serviceId), startsAt, "Олена", "Коваль"));

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq(GUEST_PHONE), textCaptor.capture());
        // CHAR-EXACT on purpose: this is the dominant production shape, and the whole point of the
        // visit refactor is that it did not touch it. A `contains` assertion would tolerate the
        // «та ще 0 послуг» / stray-separator class of regression the new branch can introduce.
        assertThat(textCaptor.getValue())
                .as("a one-item visit must render the pre-visit SMS byte for byte")
                .isEqualTo("Beautica: Запис підтверджено!\n"
                        + "Марія Левченко, Манікюр\n"
                        + "10.06.2026 о 12:00\n\n"
                        + "Скасувати: " + response.cancelUrl());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubHappyPath(OffsetDateTime startsAt) {
        when(guestTokenProvider.validate(anyString())).thenReturn(GUEST_PHONE);
        when(masterRepository.findByBookingSlugWithUser(SLUG)).thenReturn(Optional.of(master()));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, serviceId))
                .thenReturn(Optional.of(masterService()));
        when(bookingRepository.acquireAdvisoryLockWithTimeout(masterId)).thenReturn(1);
        when(bookingRepository.existsOverlap(eq(masterId), any(), any())).thenReturn(false);
        stubSlotAvailable(startsAt);
    }

    /**
     * Stubs the create-path schedule-fit oracle (2026-08-11) for the LEGACY single-service guest path,
     * so {@code startsAt} resolves to a slot the master actually works.
     *
     * <p>Required by every guest test that expects the booking to be PERSISTED: the guest/LINK create
     * path now asserts schedule fit exactly as the authenticated path does, and an unstubbed mock
     * answers with an empty slot list — i.e. "the master does not work then" — which is a 409.
     */
    private void stubSlotAvailable(OffsetDateTime startsAt) {
        when(slotCalculationService.getAvailableSlots(eq(masterId), any(java.time.LocalDate.class), eq(serviceId),
                nullable(MasterServiceAssignment.class)))
                .thenReturn(List.of(new com.beautica.booking.dto.AvailableSlotResponse(
                        startsAt.toZonedDateTime(), startsAt.plusMinutes(60).toZonedDateTime())));
    }

    /** {@link #stubSlotAvailable} for the multi-service VISIT path — the BE-2 ordered-list overload. */
    private void stubVisitSlotAvailable(OffsetDateTime startsAt, List<UUID> serviceIds) {
        when(slotCalculationService.getAvailableSlots(eq(masterId), any(java.time.LocalDate.class), eq(serviceIds),
                anyList()))
                .thenReturn(List.of(new com.beautica.booking.dto.AvailableSlotResponse(
                        startsAt.toZonedDateTime(), startsAt.plusMinutes(60).toZonedDateTime())));
    }

    private Master master() {
        User user = new User("m@beautica.test", "x", com.beautica.auth.Role.SALON_MASTER,
                "Марія", "Левченко", null);
        return Master.builder().id(masterId).user(user).isActive(true).build();
    }

    @Test
    @DisplayName("a provider-chosen service name containing a template placeholder is emitted "
            + "LITERALLY — it must not steer the guest SMS or duplicate the cancel link")
    void should_notExpandAPlaceholderInsideAServiceName_when_theConfirmationSmsIsBuilt() {
        // The service name is provider-controlled free text. Chained String.replace substituted
        // {serviceName} BEFORE {date}/{time}/{cancelUrl}, so each later replace re-scanned the name
        // it had just written in — a service called "Манікюр {cancelUrl}" got the guest's one-time
        // cancellation link expanded a second time, in a position the provider chose, inside a
        // message the guest reads as platform copy.
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-06-10T12:00:00+03:00");
        when(guestTokenProvider.validate(anyString())).thenReturn(GUEST_PHONE);
        when(masterRepository.findByBookingSlugWithUser(SLUG)).thenReturn(Optional.of(master()));
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, serviceId))
                .thenReturn(Optional.of(masterService(serviceId, "Манікюр {cancelUrl} {date}")));
        when(bookingRepository.acquireAdvisoryLockWithTimeout(masterId)).thenReturn(1);
        when(bookingRepository.existsOverlap(eq(masterId), any(), any())).thenReturn(false);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSlotAvailable(startsAt);

        GuestBookingResponse response = service.createGuestBooking(
                "Bearer guest.jwt.token", SLUG,
                new GuestBookingRequest(serviceId, startsAt, "Олена", "Коваль"));

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq(GUEST_PHONE), textCaptor.capture());
        String sms = textCaptor.getValue();
        assertThat(sms)
                .as("the braces that arrived as DATA must survive as text")
                .contains("Манікюр {cancelUrl} {date}");
        assertThat(countOccurrences(sms, response.cancelUrl()))
                .as("the cancel link appears exactly once, where the TEMPLATE puts it")
                .isEqualTo(1);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private MasterServiceAssignment masterService() {
        return masterService(serviceId, "Манікюр");
    }

    private MasterServiceAssignment masterService(UUID id, String name) {
        ServiceDefinition def = ServiceDefinition.builder()
                .name(name)
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .basePrice(new BigDecimal("350.00"))
                .build();
        return MasterServiceAssignment.builder()
                .id(id)
                .master(master())
                .serviceDefinition(def)
                .isActive(true)
                .build();
    }
}
