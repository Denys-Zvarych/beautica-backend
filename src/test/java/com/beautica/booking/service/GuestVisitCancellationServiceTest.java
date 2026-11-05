package com.beautica.booking.service;

import com.beautica.booking.domain.GuestCancelToken;
import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.booking.dto.CancelTokenInfoResponse;
import com.beautica.booking.dto.CancelTokenItemResponse;
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
import com.beautica.notification.inapp.entity.InAppNotificationType;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
@DisplayName("GuestVisitCancellationService — guest cancel of a visit (whole or partial)")
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
            itemsUpdateReturns(Set.of(BOOKING_ID), 1);
            when(appointmentRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(1);

            CancelTokenInfoResponse after = service.cancel(TOKEN).orElseThrow();

            assertThat(after.status()).isEqualTo(BookingStatus.CANCELLED);
            verify(outboxService).enqueueClientCancelled(BOOKING_ID, List.of(BOOKING_ID));
        }
    }

    @Nested
    @DisplayName("cancel — header lock + atomic token consume")
    class TokenConsume {

        @Test
        @DisplayName("throws 404 and fires NO side-effect when the header left CONFIRMED before the lock (lost race)")
        void should_throw404_when_headerLockFindsNoConfirmedRow() {
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
            when(appointmentRepository.lockHeaderIfConfirmedWithToken(APPOINTMENT_ID, TOKEN_HASH)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancel(TOKEN))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Cancel token not found");

            assertNothingWasCancelled();
        }

        @Test
        @DisplayName("throws 404 with ONE hash lookup when the token was consumed between the first read and the lock")
        void should_throw404_when_tokenGoneAfterLock() {
            // The locking statement itself re-checks the token hash (perf L2): no second findByCancelTokenHash.
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
            when(appointmentRepository.lockHeaderIfConfirmedWithToken(APPOINTMENT_ID, TOKEN_HASH))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancel(TOKEN)).isInstanceOf(NotFoundException.class);

            verify(appointmentRepository, org.mockito.Mockito.times(1)).findByCancelTokenHash(TOKEN_HASH);
            assertNothingWasCancelled();
        }

        @Test
        @DisplayName("throws 404 and notifies nobody when the header consume updates 0 rows")
        void should_throw404_when_consumeCancelTokenHashReturnsZero() {
            liveVisitStartingAt(CANCELLABLE_START);
            itemsUpdateReturns(Set.of(BOOKING_ID), 1);
            when(appointmentRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(0);

            assertThatThrownBy(() -> service.cancel(TOKEN))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Cancel token not found");

            verifyNoInteractions(outboxService, inAppNotificationService, slotCalculationService,
                    salonCatalogCacheEvictor);
        }

        @Test
        @DisplayName("consumes the token exactly once on the happy path, cancels the items, notifies once and evicts")
        void should_consumeTokenOnce_when_visitIsCancellable() {
            liveVisitStartingAt(CANCELLABLE_START);
            itemsUpdateReturns(Set.of(BOOKING_ID), 1);
            when(appointmentRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(1);

            service.cancel(TOKEN);

            verify(appointmentRepository).consumeCancelTokenHash(TOKEN_HASH);
            verify(outboxService).enqueueClientCancelled(BOOKING_ID, List.of(BOOKING_ID));
            verify(inAppNotificationService).notifyVisitEvent(
                    eq(InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT), eq(APPOINTMENT_ID), any(Booking.class),
                    isNull());
            verify(slotCalculationService).evictMasterAvailabilityCaches(any(UUID.class));
        }

        /** Phase 412 audit-fix — a walk-in's cancel page names the platform service type (phase 435: no SMS). */
        @Test
        @DisplayName("names the PLATFORM service type on the cancel page, never the custom name, for a walk-in visit")
        void should_usePlatformServiceName_when_walkInVisitCancelled() {
            String hostile = "Акція! пишіть на example.test";
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
            when(appointmentRepository.lockHeaderIfConfirmedWithToken(APPOINTMENT_ID, TOKEN_HASH)).thenReturn(Optional.of(APPOINTMENT_ID));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID))
                    .thenReturn(List.of(walkInItem(CANCELLABLE_START, hostile)));
            itemsUpdateReturns(Set.of(BOOKING_ID), 1);
            when(appointmentRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(1);

            String pageServiceName = service.getInfo(TOKEN).orElseThrow().serviceName();
            CancelTokenInfoResponse after = service.cancel(TOKEN).orElseThrow();

            assertThat(pageServiceName).isEqualTo(PLATFORM_SERVICE_NAME);
            assertThat(after.items()).extracting(CancelTokenItemResponse::serviceName)
                    .containsExactly(PLATFORM_SERVICE_NAME);
        }
    }

    @Nested
    @DisplayName("phase 436 — partial cancel by bookingIds")
    class PartialCancel {

        private final UUID first = UUID.randomUUID();
        private final UUID middle = UUID.randomUUID();
        private final UUID last = UUID.randomUUID();

        @Test
        @DisplayName("cancels only the middle line, keeps the header and token live, and returns the after-view")
        void should_cancelOnlyNamedLine_when_middleServiceChosen() {
            liveVisit(line(first, CANCELLABLE_START, BookingStatus.CONFIRMED),
                    line(middle, CANCELLABLE_START.plusHours(1), BookingStatus.CONFIRMED),
                    line(last, CANCELLABLE_START.plusHours(2), BookingStatus.CONFIRMED));
            itemsUpdateReturns(Set.of(middle), 1);

            CancelTokenInfoResponse after = service.cancel(TOKEN, List.of(middle)).orElseThrow();

            assertThat(after.status()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(after.cancellable()).isTrue();
            assertThat(after.items()).extracting(CancelTokenItemResponse::bookingId)
                    .containsExactly(first, middle, last);
            assertThat(after.items()).extracting(CancelTokenItemResponse::status)
                    .containsExactly(BookingStatus.CONFIRMED, BookingStatus.CANCELLED, BookingStatus.CONFIRMED);
            assertThat(after.items()).extracting(CancelTokenItemResponse::cancellable)
                    .containsExactly(true, false, true);
            verify(appointmentRepository, never()).consumeCancelTokenHash(any());
            verify(outboxService).enqueueClientCancelled(middle, List.of(middle));
            verify(inAppNotificationService).notifyVisitEvent(
                    eq(InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT), eq(APPOINTMENT_ID),
                    bookingWithId(middle), isNull(), eq(middle));
        }

        @Test
        @DisplayName("sends ONE e-mail row naming every line cancelled in the request, in visit order")
        void should_enqueueOneAggregatedRow_when_severalLinesChosen() {
            liveVisit(line(first, CANCELLABLE_START, BookingStatus.CONFIRMED),
                    line(middle, CANCELLABLE_START.plusHours(1), BookingStatus.CONFIRMED),
                    line(last, CANCELLABLE_START.plusHours(2), BookingStatus.CONFIRMED));
            itemsUpdateReturns(Set.of(last, first), 2);

            service.cancel(TOKEN, List.of(last, first));

            verify(outboxService).enqueueClientCancelled(first, List.of(first, last));
            verify(outboxService, never()).enqueueClientCancelled(any(UUID.class));
        }

        @Test
        @DisplayName("cancelling the last CONFIRMED line flips the header and consumes the token")
        void should_consumeToken_when_lastConfirmedLineCancelled() {
            liveVisit(line(first, CANCELLABLE_START, BookingStatus.CANCELLED),
                    line(middle, CANCELLABLE_START.plusHours(1), BookingStatus.CONFIRMED));
            itemsUpdateReturns(Set.of(middle), 1);
            when(appointmentRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(1);

            CancelTokenInfoResponse after = service.cancel(TOKEN, null).orElseThrow();

            assertThat(after.status()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(after.cancellable()).isFalse();
            verify(inAppNotificationService).notifyVisitEvent(
                    eq(InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT), eq(APPOINTMENT_ID), bookingWithId(middle),
                    isNull());
        }

        @Test
        @DisplayName("404s the whole request, cancelling nothing, when an id is not a line of this visit")
        void should_throw404_when_idNotInVisit() {
            liveVisit(line(first, CANCELLABLE_START, BookingStatus.CONFIRMED));

            assertThatThrownBy(() -> service.cancel(TOKEN, List.of(first, UUID.randomUUID())))
                    .isInstanceOf(NotFoundException.class);

            assertNothingWasCancelled();
        }

        @Test
        @DisplayName("422s the whole request when a chosen line is inside the cancel window")
        void should_throw422_when_chosenLineInsideWindow() {
            liveVisit(line(first, NOW.plusHours(1), BookingStatus.CONFIRMED),
                    line(middle, CANCELLABLE_START, BookingStatus.CONFIRMED));

            assertThatThrownBy(() -> service.cancel(TOKEN, List.of(first, middle)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            assertNothingWasCancelled();
        }

        @Test
        @DisplayName("422s when a chosen line is already CANCELLED")
        void should_throw422_when_chosenLineNotConfirmed() {
            liveVisit(line(first, CANCELLABLE_START, BookingStatus.CANCELLED),
                    line(middle, CANCELLABLE_START.plusHours(1), BookingStatus.CONFIRMED));

            assertThatThrownBy(() -> service.cancel(TOKEN, List.of(first)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("вже скасовано")
                    .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            assertNothingWasCancelled();
        }

        @Test
        @DisplayName("422s and notifies nobody when the conditional UPDATE matches fewer rows than requested (race)")
        void should_throw422_when_updateRowCountShort() {
            liveVisit(line(first, CANCELLABLE_START, BookingStatus.CONFIRMED),
                    line(middle, CANCELLABLE_START.plusHours(1), BookingStatus.CONFIRMED));
            itemsUpdateReturns(Set.of(first, middle), 1);

            assertThatThrownBy(() -> service.cancel(TOKEN, List.of(first, middle)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            verifyNoInteractions(outboxService, inAppNotificationService, slotCalculationService);
        }

        @Test
        @DisplayName("400s on duplicate ids before touching the database")
        void should_throw400_when_duplicateIds() {
            assertThatThrownBy(() -> service.cancel(TOKEN, List.of(first, first)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

            verifyNoInteractions(appointmentRepository, bookingRepository);
        }

        @Test
        @DisplayName("400s on an EMPTY selection — never cancel-all — before touching the database")
        void should_throw400_when_emptySelection() {
            assertThatThrownBy(() -> service.cancel(TOKEN, List.of()))
                    .isInstanceOf(BusinessException.class)
                    .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

            verifyNoInteractions(appointmentRepository, bookingRepository);
        }

        @Test
        @DisplayName("400s on more than 10 ids before touching the database")
        void should_throw400_when_moreThanTenIds() {
            List<UUID> eleven = java.util.stream.Stream.generate(UUID::randomUUID).limit(11).toList();

            assertThatThrownBy(() -> service.cancel(TOKEN, eleven))
                    .isInstanceOf(BusinessException.class)
                    .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

            verifyNoInteractions(appointmentRepository, bookingRepository);
        }

        @Test
        @DisplayName("getInfo lists every line in visit order with its own cancellable flag")
        void should_listLinesWithFlags_when_getInfo() {
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID)).thenReturn(List.of(
                    line(first, NOW.plusHours(1), BookingStatus.CONFIRMED),
                    line(middle, CANCELLABLE_START, BookingStatus.DECLINED),
                    line(last, CANCELLABLE_START.plusHours(1), BookingStatus.CONFIRMED)));

            CancelTokenInfoResponse info = service.getInfo(TOKEN).orElseThrow();

            assertThat(info.items()).extracting(CancelTokenItemResponse::cancellable)
                    .containsExactly(false, false, true);
            assertThat(info.items()).extracting(CancelTokenItemResponse::durationMinutes)
                    .containsOnly(60);
            assertThat(info.cancellable()).isTrue();
        }

        private void liveVisit(Booking... lines) {
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
            when(appointmentRepository.lockHeaderIfConfirmedWithToken(APPOINTMENT_ID, TOKEN_HASH)).thenReturn(Optional.of(APPOINTMENT_ID));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID)).thenReturn(List.of(lines));
        }

        private Booking line(UUID id, OffsetDateTime startsAt, BookingStatus status) {
            Booking base = item(startsAt, "Послуга " + id);
            return Booking.builder()
                    .id(id).master(base.getMaster()).masterService(base.getMasterService())
                    .appointment(appointment(BookingStatus.CONFIRMED))
                    .status(status).startsAt(startsAt).endsAt(startsAt.plusMinutes(60))
                    .priceAtBooking(new BigDecimal("350.00")).durationMinutesAtBooking(60)
                    .guestPhone(GUEST_PHONE)
                    .build();
        }

        private Booking bookingWithId(UUID id) {
            return argThat(b -> b != null && id.equals(b.getId()));
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
            visitInfoStartingAt(CANCELLABLE_START);

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
        @DisplayName("returns empty without touching anything when the token matches no appointment header")
        void should_returnEmpty_when_tokenIsNotAVisitHeader() {
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH)).thenReturn(Optional.empty());

            assertThat(service.cancel(TOKEN))
                    .as("an unknown token is not this service's business — the caller falls through "
                            + "to the legacy single-booking path")
                    .isEmpty();

            verify(appointmentRepository, never()).consumeCancelTokenHash(any());
            verifyNoInteractions(
                    bookingRepository, outboxService, slotCalculationService, salonCatalogCacheEvictor);
        }

        @Test
        @DisplayName("returns empty — indistinguishably from an unknown token — when the header is no longer CONFIRMED")
        void should_returnEmpty_when_visitHeaderIsNoLongerConfirmed() {
            // No state oracle: an already-cancelled visit must produce the SAME observable outcome as a
            // token that never existed. If this branch ever threw 422/409 instead, the public endpoint
            // would confirm to an unauthenticated caller that a given token WAS a real appointment.
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.CANCELLED)));

            assertThat(service.cancel(TOKEN)).isEmpty();

            verify(appointmentRepository, never()).lockHeaderIfConfirmedWithToken(any(), any());
            verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
            verify(appointmentRepository, never()).consumeCancelTokenHash(any());
            verifyNoInteractions(outboxService, slotCalculationService, salonCatalogCacheEvictor);
        }

        @Test
        @DisplayName("returns empty rather than blowing up when a CONFIRMED header has no items (defensive)")
        void should_returnEmpty_when_confirmedHeaderHasNoItems() {
            // items.get(0) on an empty list would surface as a 500 on a permitAll endpoint.
            when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                    .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
            when(appointmentRepository.lockHeaderIfConfirmedWithToken(APPOINTMENT_ID, TOKEN_HASH)).thenReturn(Optional.of(APPOINTMENT_ID));
            when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID)).thenReturn(List.of());

            assertThat(service.cancel(TOKEN)).isEmpty();

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

    /** Stubs a CONFIRMED, lockable header whose single item starts at {@code startsAt}. */
    private void liveVisitStartingAt(OffsetDateTime startsAt) {
        when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
        when(appointmentRepository.lockHeaderIfConfirmedWithToken(APPOINTMENT_ID, TOKEN_HASH)).thenReturn(Optional.of(APPOINTMENT_ID));
        when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID))
                .thenReturn(List.of(item(startsAt, "Манікюр")));
    }

    /** Stubs a CONFIRMED header + one item for a read-only {@code getInfo} (no lock). */
    private void visitInfoStartingAt(OffsetDateTime startsAt) {
        when(appointmentRepository.findByCancelTokenHash(TOKEN_HASH))
                .thenReturn(Optional.of(appointment(BookingStatus.CONFIRMED)));
        when(bookingRepository.findByAppointmentIdWithGraph(APPOINTMENT_ID))
                .thenReturn(List.of(item(startsAt, "Манікюр")));
    }

    /** Stubs the per-line conditional UPDATE for exactly {@code ids}, cutoff = now + 2h. */
    private void itemsUpdateReturns(Set<UUID> ids, int rows) {
        when(bookingRepository.cancelGuestVisitItemsByIds(
                eq(APPOINTMENT_ID),
                argThat(c -> c != null && c.size() == ids.size() && ids.containsAll(c)),
                argThat(cutoff -> cutoff != null && cutoff.isEqual(NOW.plusHours(2)))))
                .thenReturn(rows);
    }

    /** Q6 — a rejected cancel must leave the visit and every downstream side-effect untouched. */
    private void assertNothingWasCancelled() {
        verify(appointmentRepository, never()).consumeCancelTokenHash(any());
        verify(bookingRepository, never()).cancelGuestVisitItemsByIds(any(), any(), any());
        verify(bookingRepository, never()).cancelItemsByAppointmentId(any());
        verifyNoInteractions(outboxService, inAppNotificationService, slotCalculationService,
                salonCatalogCacheEvictor);
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
