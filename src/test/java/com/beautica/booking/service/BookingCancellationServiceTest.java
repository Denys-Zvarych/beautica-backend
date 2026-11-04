package com.beautica.booking.service;

import com.beautica.booking.domain.GuestCancelToken;
import com.beautica.notification.channel.GuestMessageRouter;
import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.booking.dto.CancelTokenInfoResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.config.BookingLinksProperties;
import com.beautica.config.BookingSmsProperties;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.sms.SmsService;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 *
 * <p>Phase 435: the guest's own cancellation sends the guest NO SMS / Telegram copy. The service holds
 * no guest-messaging collaborator at all, which {@link #should_holdNoGuestMessagingCollaborator_when_constructed}
 * pins structurally — a mock {@code SmsService} that is never wired in could not prove that.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingCancellationService — guest cancel by link")
class BookingCancellationServiceTest {

    private static final GuestCancelToken TOKEN = GuestCancelToken.of(UUID.randomUUID());
    /** V206: repositories are keyed by the token's SHA-256 hash, never the raw token. */
    private static final String TOKEN_HASH = CancelTokenHasher.hash(TOKEN);
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final String GUEST_PHONE = "+380501234567";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-01T10:00:00Z");

    @Mock private GuestVisitCancellationService guestVisitCancellationService;
    @Mock private BookingRepository bookingRepository;
    @Mock private NotificationOutboxService outboxService;
    @Mock private SlotCalculationService slotCalculationService;
    @Mock private com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    @Mock private InAppNotificationService inAppNotificationService;

    private BookingCancellationService service;

    @BeforeEach
    void setUp() {
        service = new BookingCancellationService(
                guestVisitCancellationService, bookingRepository, outboxService,
                slotCalculationService, new BookingSmsProperties(), salonCatalogCacheEvictor,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), inAppNotificationService,
                new GuestRebookPaths(shareEnabledLinks()));
    }

    private static BookingLinksProperties shareEnabledLinks() {
        BookingLinksProperties properties = new BookingLinksProperties();
        properties.setShareEnabled(true);
        return properties;
    }

    // ── getInfo ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getInfo should return cancellable=true when the appointment is well outside the window")
    void should_returnCancellableTrue_when_appointmentFarEnough() {
        OffsetDateTime startsAt = NOW.plusHours(5);
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
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
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
                .thenReturn(Optional.of(booking(startsAt, BookingStatus.CONFIRMED)));

        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        assertThat(info.cancellable()).isFalse();
    }

    @Test
    @DisplayName("getInfo should throw 404 when the token is unknown / already consumed")
    void should_throwNotFound_when_tokenAbsent() {
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInfo(TOKEN))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("phase 435: getInfo returns a read-only view (status, cancellable=false) for a provider-declined booking")
    void should_returnReadOnlyView_when_bookingDeclined() {
        // Arrange
        OffsetDateTime startsAt = NOW.plusHours(5);
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
                .thenReturn(Optional.of(booking(startsAt, BookingStatus.DECLINED)));

        // Act
        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        // Assert
        assertThat(info.status()).isEqualTo(BookingStatus.DECLINED);
        assertThat(info.cancellable()).isFalse();
        assertThat(info.startsAt()).isEqualTo(startsAt);
        assertThat(info.masterName()).isEqualTo("Марія Левченко");
    }

    @Test
    @DisplayName("phase 435: getInfo returns a read-only CANCELLED view, never cancellable")
    void should_returnReadOnlyView_when_bookingCancelledByProvider() {
        // Arrange
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
                .thenReturn(Optional.of(booking(NOW.plusHours(5), BookingStatus.CANCELLED)));

        // Act
        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        // Assert
        assertThat(info.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(info.cancellable()).isFalse();
    }

    @Test
    @DisplayName("phase 435 audit-fix: a DECLINED booking that started 30 days minus 1 minute ago is still readable")
    void should_returnReadOnlyView_when_terminalBookingJustInsideViewWindow() {
        // Arrange
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
                .thenReturn(Optional.of(booking(NOW.minusDays(30).plusMinutes(1), BookingStatus.DECLINED)));

        // Act
        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        // Assert
        assertThat(info.status()).isEqualTo(BookingStatus.DECLINED);
    }

    @Test
    @DisplayName("phase 435 audit-fix: a DECLINED booking that started 30 days plus 1 minute ago 404s like an unknown token")
    void should_throw404_when_terminalBookingJustOutsideViewWindow() {
        // Arrange
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
                .thenReturn(Optional.of(booking(NOW.minusDays(30).minusMinutes(1), BookingStatus.DECLINED)));

        // Act + Assert
        assertThatThrownBy(() -> service.getInfo(TOKEN))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Cancel token not found");
    }

    @Test
    @DisplayName("phase 435 audit-fix: a CONFIRMED booking is never cut off by the terminal view window")
    void should_returnView_when_confirmedBookingStartedLongAgo() {
        // Arrange
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
                .thenReturn(Optional.of(booking(NOW.minusDays(90), BookingStatus.CONFIRMED)));

        // Act
        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        // Assert
        assertThat(info.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(info.cancellable()).isFalse();
    }

    @Test
    @DisplayName("phase 435: getInfo of a live booking carries status CONFIRMED")
    void should_returnStatusConfirmed_when_bookingLive() {
        // Arrange
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
                .thenReturn(Optional.of(booking(NOW.plusHours(5), BookingStatus.CONFIRMED)));

        // Act
        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        // Assert
        assertThat(info.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(info.rebookPath()).isNull(); // a salon master with no salon: no rebook page
    }

    @Test
    @DisplayName("phase 435: getInfo offers an active independent master's own page as rebookPath")
    void should_returnMasterRebookPath_when_independentMasterHasSlug() {
        // Arrange
        Master independent = Master.builder().id(UUID.randomUUID()).user(masterUser())
                .masterType(MasterType.INDEPENDENT_MASTER).isActive(true).bookingSlug("mariia-levchenko").build();
        Booking booking = booking(NOW.plusHours(5), BookingStatus.DECLINED, "Манікюр", TOKEN, independent);
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH)).thenReturn(Optional.of(booking));

        // Act
        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        // Assert
        assertThat(info.rebookPath()).isEqualTo("/book/mariia-levchenko");
    }

    @Test
    @DisplayName("phase 435: getInfo carries the salon name and the salon's street + building as the address")
    void should_returnSalonNameAndStreetAddress_when_salonBooking() {
        // Arrange
        Salon salon = Salon.builder().id(UUID.randomUUID()).name("Студія Лілія").street("Хрещатик")
                .buildingNo("10").address("старий текст").build();
        Booking booking = booking(NOW.plusHours(5), BookingStatus.CONFIRMED, salon);
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH)).thenReturn(Optional.of(booking));

        // Act
        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        // Assert
        assertThat(info.salonName()).isEqualTo("Студія Лілія");
        assertThat(info.address()).isEqualTo("Хрещатик 10");
    }

    @Test
    @DisplayName("phase 435: a salon with no street falls back to its legacy address — also on a read-only view")
    void should_returnLegacySalonAddress_when_salonHasNoStreetAndBookingDeclined() {
        // Arrange
        Salon salon = Salon.builder().id(UUID.randomUUID()).name("Студія Лілія")
                .address("вул. Шевченка,\n5").build();
        Booking booking = booking(NOW.plusHours(5), BookingStatus.DECLINED, salon);
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH)).thenReturn(Optional.of(booking));

        // Act
        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        // Assert
        assertThat(info.status()).isEqualTo(BookingStatus.DECLINED);
        assertThat(info.salonName()).isEqualTo("Студія Лілія");
        assertThat(info.address()).isEqualTo("вул. Шевченка, 5");
    }

    @Test
    @DisplayName("phase 435: an independent master's booking has no salonName and the master's own street address")
    void should_returnMasterOwnAddressAndNoSalonName_when_independentMaster() {
        // Arrange
        User user = masterUser();
        user.setStreet("Сагайдачного");
        user.setBuildingNo("3А");
        Master independent = Master.builder().id(UUID.randomUUID()).user(user)
                .masterType(MasterType.INDEPENDENT_MASTER).isActive(true).build();
        Booking booking = booking(NOW.plusHours(5), BookingStatus.CANCELLED, "Манікюр", TOKEN, independent);
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH)).thenReturn(Optional.of(booking));

        // Act
        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        // Assert
        assertThat(info.salonName()).isNull();
        assertThat(info.address()).isEqualTo("Сагайдачного 3А");
    }

    @Test
    @DisplayName("phase 435: address is null when neither salon nor master has one")
    void should_returnNullAddress_when_noAddressKnown() {
        // Arrange
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
                .thenReturn(Optional.of(booking(NOW.plusHours(5), BookingStatus.CONFIRMED)));

        // Act
        CancelTokenInfoResponse info = service.getInfo(TOKEN);

        // Assert
        assertThat(info.salonName()).isNull();
        assertThat(info.address()).isNull();
    }

    @Test
    @DisplayName("phase 435: cancel still 404s (no consume) for a booking that is no longer CONFIRMED")
    void should_throwNotFoundWithoutConsume_when_cancellingDeclinedBooking() {
        // Arrange
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
                .thenReturn(Optional.of(booking(NOW.plusHours(5), BookingStatus.DECLINED)));

        // Act + Assert
        assertThatThrownBy(() -> service.cancel(TOKEN)).isInstanceOf(NotFoundException.class);
        verify(bookingRepository, never()).consumeCancelTokenHash(TOKEN_HASH);
        verifyNoInteractions(outboxService, inAppNotificationService);
    }

    // ── cancel ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancel should atomically consume the token, notify the provider and evict — the guest gets no SMS")
    void should_cancelAndNotifyProviderOnly_when_outsideWindow() {
        OffsetDateTime startsAt = NOW.plusHours(5);
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
                .thenReturn(Optional.of(booking(startsAt, BookingStatus.CONFIRMED)));
        when(bookingRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(1);

        service.cancel(TOKEN);

        verify(bookingRepository).consumeCancelTokenHash(TOKEN_HASH);
        verify(outboxService).enqueueClientCancelled(BOOKING_ID);
        verify(inAppNotificationService).notifyBookingEvent(
                org.mockito.ArgumentMatchers.eq(InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT),
                org.mockito.ArgumentMatchers.any(Booking.class), org.mockito.ArgumentMatchers.isNull());
        verify(slotCalculationService).evictMasterAvailabilityCaches(org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    @DisplayName("phase 435: the service holds no SMS / Telegram collaborator, so a guest self-cancel cannot message the guest")
    void should_holdNoGuestMessagingCollaborator_when_constructed() {
        // Act
        var collaboratorTypes = Arrays.stream(BookingCancellationService.class.getDeclaredFields())
                .map(Field::getType)
                .toList();

        // Assert
        assertThat(collaboratorTypes)
                .doesNotContain(BookingSmsDispatcher.class, SmsService.class, GuestMessageRouter.class);
    }

    @Test
    @DisplayName("cancel should throw 422 with the Ukrainian message and fire NO side-effects when inside the window")
    void should_throw422_when_insideWindow() {
        OffsetDateTime startsAt = NOW.plusHours(1);
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
                .thenReturn(Optional.of(booking(startsAt, BookingStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.cancel(TOKEN))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        verify(bookingRepository, never()).consumeCancelTokenHash(TOKEN_HASH);
        verifyNoInteractions(outboxService, inAppNotificationService);
    }

    @Test
    @DisplayName("cancel should throw 404 and fire NO side-effects when the conditional consume updates 0 rows (lost race / replay)")
    void should_throwNotFound_when_consumeUpdatesZeroRows() {
        OffsetDateTime startsAt = NOW.plusHours(5);
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH))
                .thenReturn(Optional.of(booking(startsAt, BookingStatus.CONFIRMED)));
        when(bookingRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(TOKEN))
                .isInstanceOf(NotFoundException.class);

        verify(outboxService, never()).enqueueClientCancelled(BOOKING_ID);
        verifyNoInteractions(inAppNotificationService, slotCalculationService);
    }

    @Test
    @DisplayName("cancel should throw 404 when the token is unknown (no consume attempt)")
    void should_throwNotFound_when_cancelTokenAbsent() {
        when(bookingRepository.findByCancelTokenHashWithGraph(TOKEN_HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(TOKEN))
                .isInstanceOf(NotFoundException.class);

        verify(bookingRepository, never()).consumeCancelTokenHash(TOKEN_HASH);
        verifyNoInteractions(outboxService, inAppNotificationService);
    }

    // ── cancelGuestBooking seam (phase 373 D12) ──────────────────────────────

    @Test
    @DisplayName("cancelGuestBooking should consume, notify the provider and evict")
    void should_notifyProviderAndEvict_when_seamCalled() {
        Booking booking = booking(NOW.plusMinutes(30), BookingStatus.CONFIRMED);
        when(bookingRepository.consumeCancelTokenHash(TOKEN_HASH)).thenReturn(1);

        boolean cancelled = service.cancelGuestBooking(booking);

        assertThat(cancelled).isTrue();
        verify(outboxService).enqueueClientCancelled(BOOKING_ID);
        verify(inAppNotificationService).notifyBookingEvent(
                InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, booking, null);
        verify(slotCalculationService).evictMasterAvailabilityCaches(booking.getMaster().getId());
    }

    @Test
    @DisplayName("cancelGuestBooking should cancel a tokenless STAFF walk-in by id, never via the token consume")
    void should_cancelById_when_bookingHasNoCancelToken() {
        Booking booking = booking(NOW.plusHours(5), BookingStatus.CONFIRMED, "Манікюр", null);
        when(bookingRepository.cancelGuestRowById(BOOKING_ID)).thenReturn(1);

        boolean cancelled = service.cancelGuestBooking(booking);

        assertThat(cancelled).isTrue();
        verify(bookingRepository, never()).consumeCancelTokenHash(org.mockito.ArgumentMatchers.any());
        verify(outboxService).enqueueClientCancelled(BOOKING_ID);
    }

    @Test
    @DisplayName("cancelGuestBooking should return false and fire NO side-effects when a concurrent cancel already won")
    void should_returnFalseWithoutSideEffects_when_cancelByIdMatchesNoRow() {
        Booking booking = booking(NOW.plusHours(5), BookingStatus.CONFIRMED, "Манікюр", null);
        when(bookingRepository.cancelGuestRowById(BOOKING_ID)).thenReturn(0);

        boolean cancelled = service.cancelGuestBooking(booking);

        assertThat(cancelled).isFalse();
        verifyNoInteractions(outboxService, inAppNotificationService, slotCalculationService);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Booking booking(OffsetDateTime startsAt, BookingStatus status) {
        return booking(startsAt, status, "Манікюр");
    }

    private Booking booking(OffsetDateTime startsAt, BookingStatus status, String serviceName) {
        return booking(startsAt, status, serviceName, TOKEN);
    }

    private Booking booking(OffsetDateTime startsAt, BookingStatus status, String serviceName, GuestCancelToken cancelToken) {
        Master master = Master.builder().id(UUID.randomUUID()).user(masterUser()).isActive(true).build();
        return booking(startsAt, status, serviceName, cancelToken, master);
    }

    private Booking booking(OffsetDateTime startsAt, BookingStatus status, Salon salon) {
        Master master = Master.builder().id(UUID.randomUUID()).user(masterUser()).isActive(true).build();
        return booking(startsAt, status, "Манікюр", TOKEN, master, salon);
    }

    private static User masterUser() {
        return new User("m@beautica.test", "x", com.beautica.auth.Role.SALON_MASTER, "Марія", "Левченко", null);
    }

    private Booking booking(OffsetDateTime startsAt, BookingStatus status, String serviceName, GuestCancelToken cancelToken,
                            Master master) {
        return booking(startsAt, status, serviceName, cancelToken, master, null);
    }

    private Booking booking(OffsetDateTime startsAt, BookingStatus status, String serviceName, GuestCancelToken cancelToken,
                            Master master, Salon salon) {
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
                .status(status)
                .startsAt(startsAt)
                .endsAt(startsAt.plusMinutes(60))
                .priceAtBooking(new BigDecimal("350.00"))
                .durationMinutesAtBooking(60)
                .guestPhone(GUEST_PHONE)
                .cancelToken(cancelToken)
                .build();
    }
}
