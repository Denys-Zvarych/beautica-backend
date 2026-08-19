package com.beautica.booking.service;

import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.BookingSmsProperties;
import com.beautica.master.entity.Master;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.sms.SmsService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GuestVisitCancellationService} — guest cancel of a whole multi-service LINK
 * visit by the single header token.
 *
 * <p>This service sits behind {@code POST /api/v1/book/cancel/{token}}, a {@code permitAll} endpoint:
 * the bearer of the token IS the authorization, so the three guards below are the entire access-control
 * surface and each one is pinned by its own test — a silent regression here is exploitable by anyone
 * who ever saw a cancel link.
 *
 * <p>The clock is fixed at {@code 2026-06-01T10:00:00Z} with the default 2-hour cancel window, so a
 * visit starting at {@code 15:00Z} (18:00 Kyiv on 01.06.2026) is comfortably cancellable and one
 * starting at {@code 12:00Z} sits exactly on the closed edge.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuestVisitCancellationService — guest cancel of a whole visit")
class GuestVisitCancellationServiceTest {

    private static final UUID TOKEN = UUID.randomUUID();
    private static final UUID APPOINTMENT_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final String GUEST_PHONE = "+380501234567";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-01T10:00:00Z");
    /** Comfortably outside the 2h window: 15:00Z → 18:00 Kyiv on 01.06.2026. */
    private static final OffsetDateTime CANCELLABLE_START = NOW.plusHours(5);

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private NotificationOutboxService outboxService;
    @Mock private SmsService smsService;
    @Mock private SlotCalculationService slotCalculationService;
    @Mock private SalonCatalogCacheEvictor salonCatalogCacheEvictor;

    private GuestVisitCancellationService service;

    @BeforeEach
    void setUp() {
        service = new GuestVisitCancellationService(
                appointmentRepository, bookingRepository, outboxService, smsDispatcher(),
                slotCalculationService, new BookingSmsProperties(), salonCatalogCacheEvictor,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    /**
     * The REAL {@link BookingSmsDispatcher} over the mocked {@link SmsService} seam, driven by a
     * {@link SyncTaskExecutor} — the same shape {@code StaffBookingServiceTest} uses, and the same
     * shape the {@code test} profile wires in production code ({@code AsyncConfig#syncSmsSendExecutor}).
     *
     * <p>A mocked dispatcher would have been less work and strictly worse: every {@code
     * verify(smsService)} row below would then assert only that a hand-off was requested, and a
     * dispatcher that silently stopped sending would keep them all green. Running the real one inline
     * keeps those rows meaning "the message was sent", exactly as before the hand-off was introduced,
     * while still proving the service holds no {@code SmsService} of its own.
     */
    private BookingSmsDispatcher smsDispatcher() {
        return new BookingSmsDispatcher(smsService, new SyncTaskExecutor());
    }

    @Nested
    @DisplayName("cancel — 2h cancellation window")
    class CancelWindow {

        @Test
        @DisplayName("throws 422 with the Ukrainian message and fires NO side-effect when the visit is inside the window")
        void should_throw422_when_insideCancelWindow() {
            liveVisitStartingAt(NOW.plusHours(1));

            assertThatThrownBy(() -> service.cancel(TOKEN))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Скасування недоступне")
                    .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            assertNothingWasCancelled();
        }

        @Test
        @DisplayName("throws 422 on the exact window edge — the guard is strictly-after, so startsAt == now+2h is already closed")
        void should_throw422_when_exactlyOnTheCancelWindowEdge() {
            // Boundary that separates 422 from success. Without it, relaxing isAfter() to a
            // not-isBefore() form (or widening the window by an hour) stays green.
            liveVisitStartingAt(NOW.plusHours(2));

            assertThatThrownBy(() -> service.cancel(TOKEN))
                    .isInstanceOf(BusinessException.class)
                    .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            assertNothingWasCancelled();
        }

        @Test
        @DisplayName("cancels one minute past the edge — the window is closed, not merely narrower than the test assumes")
        void should_cancel_when_oneMinutePastTheCancelWindowEdge() {
            // The other half of the boundary: proves the 422 tests above fail for the WINDOW and not
            // because the fixture is uncancellable for some unrelated reason.
            liveVisitStartingAt(NOW.plusHours(2).plusMinutes(1));
            when(appointmentRepository.consumeCancelToken(TOKEN)).thenReturn(1);

            assertThat(service.cancel(TOKEN)).isTrue();

            verify(bookingRepository).cancelItemsByAppointmentId(APPOINTMENT_ID);
            verify(outboxService).enqueueClientCancelled(BOOKING_ID);
        }
    }

    @Nested
    @DisplayName("cancel — atomic one-time token consume")
    class TokenConsume {

        @Test
        @DisplayName("throws 404 and fires NO side-effect when the conditional consume updates 0 rows (replay / lost race)")
        void should_throw404_when_consumeCancelTokenReturnsZero() {
            // consumeCancelToken is the single-use gate: its WHERE guards on the token still being
            // present AND the header still CONFIRMED. A replayed link, or the loser of a concurrent
            // double-click, updates 0 rows and must abort BEFORE the items are cancelled — otherwise a
            // second caller re-cancels an already-cancelled visit and re-sends the guest an SMS.
            liveVisitStartingAt(CANCELLABLE_START);
            when(appointmentRepository.consumeCancelToken(TOKEN)).thenReturn(0);

            assertThatThrownBy(() -> service.cancel(TOKEN))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Cancel token not found");

            verify(bookingRepository, never()).cancelItemsByAppointmentId(any());
            verifyNoInteractions(outboxService, smsService, slotCalculationService, salonCatalogCacheEvictor);
        }

        @Test
        @DisplayName("consumes the token exactly once on the happy path, then cancels the items and notifies")
        void should_consumeTokenOnce_when_visitIsCancellable() {
            liveVisitStartingAt(CANCELLABLE_START);
            when(appointmentRepository.consumeCancelToken(TOKEN)).thenReturn(1);

            assertThat(service.cancel(TOKEN)).isTrue();

            verify(appointmentRepository).consumeCancelToken(TOKEN);
            verify(bookingRepository).cancelItemsByAppointmentId(APPOINTMENT_ID);
            verify(outboxService).enqueueClientCancelled(BOOKING_ID);
            verify(smsService).send(eq(GUEST_PHONE), any());
        }
    }

    @Nested
    @DisplayName("cancel — fall-through when the token is not a live visit header (no state oracle)")
    class NotAVisitToken {

        @Test
        @DisplayName("returns false without touching anything when the token matches no appointment header")
        void should_returnFalse_when_tokenIsNotAVisitHeader() {
            when(appointmentRepository.findByCancelToken(TOKEN)).thenReturn(Optional.empty());

            assertThat(service.cancel(TOKEN))
                    .as("an unknown token is not this service's business — the caller falls through "
                            + "to the legacy single-booking path")
                    .isFalse();

            verify(appointmentRepository, never()).consumeCancelToken(any());
            verifyNoInteractions(
                    bookingRepository, outboxService, smsService, slotCalculationService, salonCatalogCacheEvictor);
        }

        @Test
        @DisplayName("returns false — indistinguishably from an unknown token — when the header is no longer CONFIRMED")
        void should_returnFalse_when_visitHeaderIsNoLongerConfirmed() {
            // No state oracle: an already-cancelled visit must produce the SAME observable outcome as a
            // token that never existed. If this branch ever threw 422/409 instead, the public endpoint
            // would confirm to an unauthenticated caller that a given token WAS a real appointment.
            when(appointmentRepository.findByCancelToken(TOKEN))
                    .thenReturn(Optional.of(appointment(BookingStatus.CANCELLED)));

            assertThat(service.cancel(TOKEN)).isFalse();

            verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
            verify(appointmentRepository, never()).consumeCancelToken(any());
            verifyNoInteractions(outboxService, smsService, slotCalculationService, salonCatalogCacheEvictor);
        }

        @Test
        @DisplayName("returns false rather than blowing up when a CONFIRMED header has no items (defensive)")
        void should_returnFalse_when_confirmedHeaderHasNoItems() {
            // items.get(0) on an empty list would surface as a 500 on a permitAll endpoint.
            when(appointmentRepository.findByCancelToken(TOKEN))
                    .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID)).thenReturn(List.of());

            assertThat(service.cancel(TOKEN)).isFalse();

            verify(appointmentRepository, never()).consumeCancelToken(any());
            verifyNoInteractions(outboxService, smsService, slotCalculationService, salonCatalogCacheEvictor);
        }
    }

    @Nested
    @DisplayName("cancel — SMS rendering")
    class SmsRendering {

        @Test
        @DisplayName("must not expand a {date}/{time} placeholder that arrived inside the service name")
        void should_notExpandAPlaceholderThatArrivedAsAValue_when_aServiceNameContainsOne() {
            // buildCancellationSms substituted {serviceName} BEFORE {date}/{time} with chained
            // String.replace, so each later replace re-scanned the name it had just written in. The
            // service name is provider-controlled free text, so a provider naming a service
            // "Манікюр {date} о {time}" rendered a SECOND, provider-positioned date/time line inside
            // copy the guest reads as platform text — and SMS is the only channel a guest has.
            when(appointmentRepository.findByCancelToken(TOKEN))
                    .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID))
                    .thenReturn(List.of(item(CANCELLABLE_START, "Манікюр {date} о {time}")));
            when(appointmentRepository.consumeCancelToken(TOKEN)).thenReturn(1);
            ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

            service.cancel(TOKEN);

            verify(smsService).send(eq(GUEST_PHONE), textCaptor.capture());
            assertThat(textCaptor.getValue())
                    .as("the literal braces from the DATA must survive as text, never be expanded")
                    .contains("Манікюр {date} о {time}")
                    .as("the real date appears exactly once, where the TEMPLATE puts it")
                    .containsOnlyOnce("01.06.2026")
                    .as("the real time appears exactly once — a provider must not be able to "
                            + "fabricate a second appointment time")
                    .containsOnlyOnce("18:00");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Stubs a CONFIRMED header whose single item starts at {@code startsAt}. */
    private void liveVisitStartingAt(OffsetDateTime startsAt) {
        when(appointmentRepository.findByCancelToken(TOKEN))
                .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
        when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID))
                .thenReturn(List.of(item(startsAt, "Манікюр")));
    }

    /** Q6 — a rejected cancel must leave the visit and every downstream side-effect untouched. */
    private void assertNothingWasCancelled() {
        verify(appointmentRepository, never()).consumeCancelToken(any());
        verify(bookingRepository, never()).cancelItemsByAppointmentId(any());
        verifyNoInteractions(outboxService, smsService, slotCalculationService, salonCatalogCacheEvictor);
    }

    private Appointment appointment(BookingStatus status) {
        return Appointment.builder()
                .id(APPOINTMENT_ID)
                .status(status)
                .cancelToken(TOKEN)
                .guestPhone(GUEST_PHONE)
                .build();
    }

    private Booking item(OffsetDateTime startsAt, String serviceName) {
        User user = new User("m@beautica.test", "x", com.beautica.auth.Role.SALON_MASTER,
                "Марія", "Левченко", null);
        Master master = Master.builder().id(UUID.randomUUID()).user(user).isActive(true).build();
        ServiceDefinition def = ServiceDefinition.builder()
                .name(serviceName)
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .basePrice(new BigDecimal("350.00"))
                .build();
        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .master(master)
                .serviceDefinition(def)
                .isActive(true)
                .build();
        return Booking.builder()
                .id(BOOKING_ID)
                .master(master)
                .masterService(msa)
                .appointment(appointment(BookingStatus.CONFIRMED))
                .status(BookingStatus.CONFIRMED)
                .startsAt(startsAt)
                .endsAt(startsAt.plusMinutes(60))
                .priceAtBooking(new BigDecimal("350.00"))
                .durationMinutesAtBooking(60)
                .guestPhone(GUEST_PHONE)
                .build();
    }
}
