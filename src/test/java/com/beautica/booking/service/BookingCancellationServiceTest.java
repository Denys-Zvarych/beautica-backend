package com.beautica.booking.service;

import com.beautica.booking.dto.CancelTokenInfoResponse;
import com.beautica.booking.entity.Booking;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BookingCancellationService} (Phase 13.4) — guest cancel by link.
 *
 * <p>The clock is fixed at {@code 2026-06-01T10:00:00Z} with a 2-hour cancel window, so
 * {@code startsAt} > {@code 12:00Z} is cancellable and {@code startsAt} ≤ {@code 12:00Z}
 * is inside the closed window.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingCancellationService — guest cancel by link")
class BookingCancellationServiceTest {

    private static final UUID TOKEN = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final String GUEST_PHONE = "+380501234567";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-01T10:00:00Z");

    @Mock private GuestVisitCancellationService guestVisitCancellationService;
    @Mock private BookingRepository bookingRepository;
    @Mock private NotificationOutboxService outboxService;
    @Mock private SmsService smsService;
    @Mock private SlotCalculationService slotCalculationService;
    @Mock private com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;

    private BookingCancellationService service;

    @BeforeEach
    void setUp() {
        service = new BookingCancellationService(
                guestVisitCancellationService, bookingRepository, outboxService, smsDispatcher(),
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

    // ── getInfo ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getInfo should return cancellable=true when the appointment is well outside the window")
    void should_returnCancellableTrue_when_appointmentFarEnough() {
        OffsetDateTime startsAt = NOW.plusHours(5);
        when(bookingRepository.findByCancelTokenWithGraph(TOKEN))
                .thenReturn(Optional.of(booking(startsAt, BookingStatus.CONFIRMED)));

        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        assertThat(info.cancellable()).isTrue();
        assertThat(info.masterName()).isEqualTo("Марія Левченко");
        assertThat(info.serviceName()).isEqualTo("Манікюр");
        assertThat(info.startsAt()).isEqualTo(startsAt);
        assertThat(info.windowClosesAt()).isEqualTo(startsAt.minusHours(2));
    }

    @Test
    @DisplayName("getInfo should return cancellable=false when the appointment is inside the 2h window")
    void should_returnCancellableFalse_when_appointmentInsideWindow() {
        OffsetDateTime startsAt = NOW.plusHours(1);
        when(bookingRepository.findByCancelTokenWithGraph(TOKEN))
                .thenReturn(Optional.of(booking(startsAt, BookingStatus.CONFIRMED)));

        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        assertThat(info.cancellable()).isFalse();
    }

    @Test
    @DisplayName("getInfo should throw 404 when the token is unknown / already consumed")
    void should_throwNotFound_when_tokenAbsent() {
        when(bookingRepository.findByCancelTokenWithGraph(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInfo(TOKEN))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("getInfo should throw 404 when the booking is no longer CONFIRMED")
    void should_throwNotFound_when_statusNotConfirmed() {
        when(bookingRepository.findByCancelTokenWithGraph(TOKEN))
                .thenReturn(Optional.of(booking(NOW.plusHours(5), BookingStatus.COMPLETED)));

        assertThatThrownBy(() -> service.getInfo(TOKEN))
                .isInstanceOf(NotFoundException.class);
    }

    // ── cancel ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancel should atomically consume the token, enqueue the master push, and send the Ukrainian SMS")
    void should_cancelAndNotify_when_outsideWindow() {
        OffsetDateTime startsAt = NOW.plusHours(5);
        when(bookingRepository.findByCancelTokenWithGraph(TOKEN))
                .thenReturn(Optional.of(booking(startsAt, BookingStatus.CONFIRMED)));
        when(bookingRepository.consumeCancelToken(TOKEN)).thenReturn(1);

        service.cancel(TOKEN);

        verify(bookingRepository).consumeCancelToken(TOKEN);
        verify(outboxService).enqueueClientCancelled(BOOKING_ID);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq(GUEST_PHONE), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .contains("скасовано")
                .contains("Манікюр")
                .contains("Марія Левченко");
    }

    @Test
    @DisplayName("cancel should throw 422 with the Ukrainian message and fire NO side-effects when inside the window")
    void should_throw422_when_insideWindow() {
        OffsetDateTime startsAt = NOW.plusHours(1);
        when(bookingRepository.findByCancelTokenWithGraph(TOKEN))
                .thenReturn(Optional.of(booking(startsAt, BookingStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.cancel(TOKEN))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        verify(bookingRepository, never()).consumeCancelToken(TOKEN);
        verifyNoInteractions(outboxService, smsService);
    }

    @Test
    @DisplayName("cancel should throw 404 and fire NO side-effects when the conditional consume updates 0 rows (lost race / replay)")
    void should_throwNotFound_when_consumeUpdatesZeroRows() {
        OffsetDateTime startsAt = NOW.plusHours(5);
        when(bookingRepository.findByCancelTokenWithGraph(TOKEN))
                .thenReturn(Optional.of(booking(startsAt, BookingStatus.CONFIRMED)));
        when(bookingRepository.consumeCancelToken(TOKEN)).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(TOKEN))
                .isInstanceOf(NotFoundException.class);

        verify(outboxService, never()).enqueueClientCancelled(BOOKING_ID);
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("cancel should throw 404 when the token is unknown (no consume attempt)")
    void should_throwNotFound_when_cancelTokenAbsent() {
        when(bookingRepository.findByCancelTokenWithGraph(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(TOKEN))
                .isInstanceOf(NotFoundException.class);

        verify(bookingRepository, never()).consumeCancelToken(TOKEN);
        verifyNoInteractions(outboxService, smsService);
    }

    @Test
    @DisplayName("cancel must not expand a {date}/{time} placeholder that arrived inside the service name")
    void should_notExpandAPlaceholderThatArrivedAsAValue_when_aServiceNameContainsOne() {
        // buildCancellationSms substituted {serviceName} BEFORE {date}/{time} with chained
        // String.replace, so each later replace re-scanned the name it had just written in. The
        // service name is provider-controlled free text, so a provider naming a service
        // "Манікюр {date} о {time}" rendered a SECOND, provider-positioned date/time line inside
        // copy the guest reads as platform text — and SMS is the only channel a guest has.
        OffsetDateTime startsAt = NOW.plusHours(5); // 2026-06-01T15:00Z → 18:00 Kyiv on 01.06.2026
        when(bookingRepository.findByCancelTokenWithGraph(TOKEN))
                .thenReturn(Optional.of(booking(startsAt, BookingStatus.CONFIRMED, "Манікюр {date} о {time}")));
        when(bookingRepository.consumeCancelToken(TOKEN)).thenReturn(1);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        service.cancel(TOKEN);

        verify(smsService).send(eq(GUEST_PHONE), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .as("the literal braces from the DATA must survive as text, never be expanded")
                .contains("Манікюр {date} о {time}")
                .as("the real date appears exactly once, where the TEMPLATE puts it")
                .containsOnlyOnce("01.06.2026")
                .as("the real time appears exactly once — a provider must not be able to fabricate a "
                        + "second appointment time")
                .containsOnlyOnce("18:00");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Booking booking(OffsetDateTime startsAt, BookingStatus status) {
        return booking(startsAt, status, "Манікюр");
    }

    private Booking booking(OffsetDateTime startsAt, BookingStatus status, String serviceName) {
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
                .status(status)
                .startsAt(startsAt)
                .endsAt(startsAt.plusMinutes(60))
                .priceAtBooking(new BigDecimal("350.00"))
                .durationMinutesAtBooking(60)
                .guestPhone(GUEST_PHONE)
                .cancelToken(TOKEN)
                .build();
    }
}
