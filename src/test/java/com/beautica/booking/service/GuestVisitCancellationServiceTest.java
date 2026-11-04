package com.beautica.booking.service;

import com.beautica.booking.domain.GuestCancelToken;
import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.BookingLinksProperties;
import com.beautica.config.BookingSmsProperties;
import com.beautica.master.entity.Master;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.channel.GuestMessageRouter;
import com.beautica.notification.sms.SmsService;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
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
 *
 * <p>Phase 435: the guest's own visit cancellation sends the guest NO SMS / Telegram copy — the service
 * holds no guest-messaging collaborator, pinned structurally by
 * {@link GuestMessaging#should_holdNoGuestMessagingCollaborator_when_constructed}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuestVisitCancellationService — guest cancel of a whole visit")
class GuestVisitCancellationServiceTest {

    private static final GuestCancelToken TOKEN = GuestCancelToken.of(UUID.randomUUID());
    /** V206: repositories are keyed by the token's SHA-256 hash, never the raw token. */
    private static final String TOKEN_HASH = CancelTokenHasher.hash(TOKEN);
    private static final UUID APPOINTMENT_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final String GUEST_PHONE = "+380501234567";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-01T10:00:00Z");
    /** Comfortably outside the 2h window: 15:00Z → 18:00 Kyiv on 01.06.2026. */
    private static final OffsetDateTime CANCELLABLE_START = NOW.plusHours(5);

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private NotificationOutboxService outboxService;
    @Mock private SlotCalculationService slotCalculationService;
    @Mock private SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    @Mock private InAppNotificationService inAppNotificationService;

    private GuestVisitCancellationService service;

    @BeforeEach
    void setUp() {
        service = new GuestVisitCancellationService(
                appointmentRepository, bookingRepository, outboxService,
                slotCalculationService, new BookingSmsProperties(), salonCatalogCacheEvictor,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), inAppNotificationService,
                new GuestRebookPaths(new BookingLinksProperties()));
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
            when(appointmentRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(1);

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
        void should_throw404_when_consumeCancelTokenHashReturnsZero() {
            // consumeCancelTokenHash is the single-use gate: its WHERE guards on the token still being
            // present AND the header still CONFIRMED. A replayed link, or the loser of a concurrent
            // double-click, updates 0 rows and must abort BEFORE the items are cancelled — otherwise a
            // second caller re-cancels an already-cancelled visit and re-notifies the provider.
            liveVisitStartingAt(CANCELLABLE_START);
            when(appointmentRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(0);

            assertThatThrownBy(() -> service.cancel(TOKEN))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Cancel token not found");

            verify(bookingRepository, never()).cancelItemsByAppointmentId(any());
            verifyNoInteractions(outboxService, slotCalculationService, salonCatalogCacheEvictor);
        }

        @Test
        @DisplayName("consumes the token exactly once on the happy path, then cancels the items and notifies")
        void should_consumeTokenOnce_when_visitIsCancellable() {
            liveVisitStartingAt(CANCELLABLE_START);
            when(appointmentRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(1);

            assertThat(service.cancel(TOKEN)).isTrue();

            verify(appointmentRepository).consumeCancelTokenHash(TOKEN_HASH);
            verify(bookingRepository).cancelItemsByAppointmentId(APPOINTMENT_ID);
            verify(outboxService).enqueueClientCancelled(BOOKING_ID);
            verify(slotCalculationService).evictMasterAvailabilityCaches(any(UUID.class));
        }

        /** Phase 412 audit-fix — a walk-in's cancel page names the platform service type (phase 435: no SMS). */
        @Test
        @DisplayName("names the PLATFORM service type on the cancel page, never the custom name, for a walk-in visit")
        void should_usePlatformServiceName_when_walkInVisitCancelled() {
            String hostile = "Акція! пишіть на example.test";
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID))
                    .thenReturn(List.of(walkInItem(CANCELLABLE_START, hostile)));
            when(appointmentRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(1);

            String pageServiceName = service.getInfo(TOKEN).orElseThrow().serviceName();

            assertThat(service.cancel(TOKEN)).isTrue();
            assertThat(pageServiceName).isEqualTo(PLATFORM_SERVICE_NAME);
        }
    }

    @Nested
    @DisplayName("phase 435 — getInfo read-only view for a visit that is no longer CONFIRMED")
    class ReadOnlyView {

        @Test
        @DisplayName("returns the header's DECLINED status, never cancellable, for a provider-declined visit")
        void should_returnReadOnlyView_when_visitHeaderDeclined() {
            // Arrange
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.DECLINED)));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID))
                    .thenReturn(List.of(item(CANCELLABLE_START, "Манікюр")));

            // Act
            var info = service.getInfo(TOKEN).orElseThrow();

            // Assert
            assertThat(info.status()).isEqualTo(BookingStatus.DECLINED);
            assertThat(info.cancellable()).isFalse();
            assertThat(info.startsAt()).isEqualTo(CANCELLABLE_START);
        }

        @Test
        @DisplayName("returns status CONFIRMED and cancellable=true for a live visit outside the window")
        void should_returnConfirmedStatus_when_visitLive() {
            // Arrange
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID))
                    .thenReturn(List.of(item(CANCELLABLE_START, "Манікюр")));

            // Act
            var info = service.getInfo(TOKEN).orElseThrow();

            // Assert
            assertThat(info.status()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(info.cancellable()).isTrue();
            assertThat(info.rebookPath()).isNull(); // booking-link sharing off
        }

        @Test
        @DisplayName("audit-fix: a COMPLETED visit that started 30 days minus 1 minute ago is still readable")
        void should_returnReadOnlyView_when_terminalVisitJustInsideViewWindow() {
            // Arrange
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.COMPLETED)));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID))
                    .thenReturn(List.of(item(NOW.minusDays(30).plusMinutes(1), "Манікюр")));

            // Act
            var info = service.getInfo(TOKEN).orElseThrow();

            // Assert
            assertThat(info.status()).isEqualTo(BookingStatus.COMPLETED);
        }

        @Test
        @DisplayName("audit-fix: a COMPLETED visit that started 30 days plus 1 minute ago 404s (no single-path fallback)")
        void should_throw404_when_terminalVisitJustOutsideViewWindow() {
            // Arrange
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.COMPLETED)));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID))
                    .thenReturn(List.of(item(NOW.minusDays(30).minusMinutes(1), "Манікюр")));

            // Act + Assert
            assertThatThrownBy(() -> service.getInfo(TOKEN))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Cancel token not found");
        }
    }

    @Nested
    @DisplayName("phase 435 — getInfo salonName + address")
    class VenueFields {

        @Test
        @DisplayName("carries the salon name and the salon street + building on a read-only visit")
        void should_returnSalonNameAndAddress_when_salonVisitDeclined() {
            // Arrange
            Salon salon = Salon.builder().id(UUID.randomUUID()).name("Студія Лілія")
                    .street("Хрещатик").buildingNo("10").build();
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.DECLINED)));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID))
                    .thenReturn(List.of(item(CANCELLABLE_START, "Манікюр", salon)));

            // Act
            var info = service.getInfo(TOKEN).orElseThrow();

            // Assert
            assertThat(info.status()).isEqualTo(BookingStatus.DECLINED);
            assertThat(info.salonName()).isEqualTo("Студія Лілія");
            assertThat(info.address()).isEqualTo("Хрещатик 10");
        }

        @Test
        @DisplayName("an independent master's visit has no salonName and the master's own street address")
        void should_returnMasterOwnAddress_when_independentMasterVisit() {
            // Arrange
            Booking item = item(CANCELLABLE_START, "Манікюр");
            item.getMaster().getUser().setStreet("Сагайдачного");
            item.getMaster().getUser().setBuildingNo("3А");
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID)).thenReturn(List.of(item));

            // Act
            var info = service.getInfo(TOKEN).orElseThrow();

            // Assert
            assertThat(info.salonName()).isNull();
            assertThat(info.address()).isEqualTo("Сагайдачного 3А");
        }

        @Test
        @DisplayName("address is null when the master has no street")
        void should_returnNullAddress_when_noAddressKnown() {
            // Arrange
            liveVisitStartingAt(CANCELLABLE_START);

            // Act
            var info = service.getInfo(TOKEN).orElseThrow();

            // Assert
            assertThat(info.salonName()).isNull();
            assertThat(info.address()).isNull();
        }
    }

    @Nested
    @DisplayName("cancel — fall-through when the token is not a live visit header (no state oracle)")
    class NotAVisitToken {

        @Test
        @DisplayName("returns false without touching anything when the token matches no appointment header")
        void should_returnFalse_when_tokenIsNotAVisitHeader() {
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH)).thenReturn(Optional.empty());

            assertThat(service.cancel(TOKEN))
                    .as("an unknown token is not this service's business — the caller falls through "
                            + "to the legacy single-booking path")
                    .isFalse();

            verify(appointmentRepository, never()).consumeCancelTokenHash(any());
            verifyNoInteractions(
                    bookingRepository, outboxService, slotCalculationService, salonCatalogCacheEvictor);
        }

        @Test
        @DisplayName("returns false — indistinguishably from an unknown token — when the header is no longer CONFIRMED")
        void should_returnFalse_when_visitHeaderIsNoLongerConfirmed() {
            // No state oracle: an already-cancelled visit must produce the SAME observable outcome as a
            // token that never existed. If this branch ever threw 422/409 instead, the public endpoint
            // would confirm to an unauthenticated caller that a given token WAS a real appointment.
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.CANCELLED)));

            assertThat(service.cancel(TOKEN)).isFalse();

            verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
            verify(appointmentRepository, never()).consumeCancelTokenHash(any());
            verifyNoInteractions(outboxService, slotCalculationService, salonCatalogCacheEvictor);
        }

        @Test
        @DisplayName("returns false rather than blowing up when a CONFIRMED header has no items (defensive)")
        void should_returnFalse_when_confirmedHeaderHasNoItems() {
            // items.get(0) on an empty list would surface as a 500 on a permitAll endpoint.
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID)).thenReturn(List.of());

            assertThat(service.cancel(TOKEN)).isFalse();

            verify(appointmentRepository, never()).consumeCancelTokenHash(any());
            verifyNoInteractions(outboxService, slotCalculationService, salonCatalogCacheEvictor);
        }
    }

    @Nested
    @DisplayName("phase 435 — no guest message on self-cancel")
    class GuestMessaging {

        @Test
        @DisplayName("holds no SMS / Telegram collaborator, so a guest visit self-cancel cannot message the guest")
        void should_holdNoGuestMessagingCollaborator_when_constructed() {
            // Act
            var collaboratorTypes = Arrays.stream(GuestVisitCancellationService.class.getDeclaredFields())
                    .map(Field::getType)
                    .toList();

            // Assert
            assertThat(collaboratorTypes)
                    .doesNotContain(BookingSmsDispatcher.class, SmsService.class, GuestMessageRouter.class);
        }
    }

    @Nested
    @DisplayName("cancelGuestVisit seam (phase 373 D12)")
    class EraseSeam {

        @Test
        @DisplayName("consumes, cancels the items, notifies once and evicts")
        void should_notifyProviderAndEvict_when_seamCalled() {
            Appointment header = appointment(BookingStatus.CONFIRMED);
            List<Booking> items = List.of(item(NOW.plusMinutes(30), "Манікюр"));
            when(appointmentRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(1);

            boolean cancelled = service.cancelGuestVisit(header, items);

            assertThat(cancelled).isTrue();
            verify(bookingRepository).cancelItemsByAppointmentId(APPOINTMENT_ID);
            verify(outboxService).enqueueClientCancelled(BOOKING_ID);
            verify(slotCalculationService).evictMasterAvailabilityCaches(items.get(0).getMaster().getId());
        }

        @Test
        @DisplayName("a tokenless STAFF header is cancelled by id, never via the token consume")
        void should_cancelById_when_headerHasNoCancelToken() {
            Appointment header = Appointment.builder()
                    .id(APPOINTMENT_ID).status(BookingStatus.CONFIRMED).guestPhone(GUEST_PHONE).build();
            when(appointmentRepository.cancelGuestVisitById(APPOINTMENT_ID)).thenReturn(1);

            boolean cancelled = service.cancelGuestVisit(header, List.of(item(CANCELLABLE_START, "Манікюр")));

            assertThat(cancelled).isTrue();
            verify(appointmentRepository, never()).consumeCancelTokenHash(any());
            verify(bookingRepository).cancelItemsByAppointmentId(APPOINTMENT_ID);
        }

        @Test
        @DisplayName("returns false and fires NO side-effects when a concurrent cancel already won")
        void should_returnFalse_when_headerUpdateMatchesNoRow() {
            when(appointmentRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(0);

            boolean cancelled = service.cancelGuestVisit(
                    appointment(BookingStatus.CONFIRMED), List.of(item(CANCELLABLE_START, "Манікюр")));

            assertThat(cancelled).isFalse();
            verify(bookingRepository, never()).cancelItemsByAppointmentId(any());
            verifyNoInteractions(outboxService, slotCalculationService);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Stubs a CONFIRMED header whose single item starts at {@code startsAt}. */
    private void liveVisitStartingAt(OffsetDateTime startsAt) {
        when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
        when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID))
                .thenReturn(List.of(item(startsAt, "Манікюр")));
    }

    /** Q6 — a rejected cancel must leave the visit and every downstream side-effect untouched. */
    private void assertNothingWasCancelled() {
        verify(appointmentRepository, never()).consumeCancelTokenHash(any());
        verify(bookingRepository, never()).cancelItemsByAppointmentId(any());
        verifyNoInteractions(outboxService, slotCalculationService, salonCatalogCacheEvictor);
    }

    private Appointment appointment(BookingStatus status) {
        return Appointment.builder()
                .id(APPOINTMENT_ID)
                .status(status)
                .cancelToken(TOKEN)
                .guestPhone(GUEST_PHONE)
                .build();
    }

    private static final String PLATFORM_SERVICE_NAME = "Манікюр класичний";

    /** A STAFF walk-in item whose service carries a custom name AND a platform service type. */
    private Booking walkInItem(OffsetDateTime startsAt, String customServiceName) {
        Booking base = item(startsAt, customServiceName);
        ServiceDefinition def = ServiceDefinition.builder()
                .name(customServiceName)
                .serviceType(com.beautica.service.entity.ServiceType.builder().nameUk(PLATFORM_SERVICE_NAME).build())
                .baseDurationMinutes(60).bufferMinutesAfter(0).basePrice(new BigDecimal("350.00"))
                .build();
        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(UUID.randomUUID()).master(base.getMaster()).serviceDefinition(def).isActive(true).build();
        return Booking.builder()
                .id(BOOKING_ID).master(base.getMaster()).masterService(msa)
                .appointment(appointment(BookingStatus.CONFIRMED))
                .bookingSource(com.beautica.booking.enums.BookingSource.STAFF)
                .status(BookingStatus.CONFIRMED).startsAt(startsAt).endsAt(startsAt.plusMinutes(60))
                .priceAtBooking(new BigDecimal("350.00")).durationMinutesAtBooking(60)
                .guestPhone(GUEST_PHONE)
                .build();
    }

    private Booking item(OffsetDateTime startsAt, String serviceName) {
        return item(startsAt, serviceName, null);
    }

    private Booking item(OffsetDateTime startsAt, String serviceName, Salon salon) {
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
                .salon(salon)
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
