package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.master.entity.Master;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService — unit")
class NotificationServiceTest {

    private static final String FRONTEND_BASE_URL = "https://app.beautica.ua";

    @Mock
    private EmailNotificationService emailService;
    @Mock
    private PushNotificationService pushService;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(emailService, pushService, FRONTEND_BASE_URL);
    }

    // -------------------------------------------------------------------------
    // notifyNewBooking
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should send email and push to master when notifyNewBooking is called")
    void should_sendEmailAndPushToMaster_when_notifyNewBookingCalled() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);
        String bookingId = booking.getId().toString();

        service.notifyNewBooking(booking);

        verify(emailService).sendNewBookingEmail(anyString(), eq(booking));
        verify(pushService).sendToUser(
                eq(masterUserId),
                eq("Нове бронювання"),
                anyString(),
                eq(Map.of("type", "NEW_BOOKING", "bookingId", bookingId))
        );
    }

    @Test
    @DisplayName("should include client name and service name in push body when notifyNewBooking is called")
    void should_includClientAndServiceInPushBody_when_notifyNewBookingCalled() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyNewBooking(booking);

        verify(pushService).sendToUser(any(UUID.class), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue()).contains("Тест Клієнт").contains("Тест послуга");
    }

    @Test
    @DisplayName("notifyNewBooking falls back to the guest identity (no NPE) for a null-client guest booking")
    void should_useGuestIdentity_when_notifyNewBookingForGuestBooking() {
        // Guest (LINK) booking: null client (V89 chk_bookings_guest_fields). GuestBookingService
        // enqueues NEW_BOOKING for EVERY guest booking, so an unguarded booking.getClient()
        // dereference here NPEs the drain worker on 100% of guest bookings — the master is never
        // notified at all and the outbox row goes DEAD. Falls back to guestName/guestSurname,
        // mirroring BookingDetailResponse.from.
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildGuestBookingMock(masterUserId, "Олена", "Коваль");
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyNewBooking(booking);

        verify(emailService).sendNewBookingEmail(anyString(), eq(booking));
        verify(pushService).sendToUser(eq(masterUserId), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue())
                .as("push body must carry the guest's name, not throw or read a null client")
                .contains("Олена Коваль");
    }

    // -------------------------------------------------------------------------
    // notifyBookingStatusChanged — CONFIRMED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should send confirmed email and push to client when status is CONFIRMED")
    void should_sendConfirmedEmailAndPushToClient_when_statusConfirmed() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.CONFIRMED);
        String bookingId = booking.getId().toString();

        service.notifyBookingStatusChanged(booking);

        verify(emailService).sendBookingConfirmedEmail(anyString(), eq(booking));
        verify(pushService).sendToUser(
                eq(clientUserId),
                eq("Бронювання підтверджено"),
                anyString(),
                eq(Map.of("type", "BOOKING_CONFIRMED", "bookingId", bookingId))
        );
    }

    // -------------------------------------------------------------------------
    // notifyBookingStatusChanged — DECLINED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should send declined email and push to client when status is DECLINED")
    void should_sendDeclinedEmailAndPushToClient_when_statusDeclined() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.DECLINED);
        String bookingId = booking.getId().toString();

        service.notifyBookingStatusChanged(booking);

        verify(emailService).sendBookingDeclinedEmail(anyString(), eq(booking));
        verify(pushService).sendToUser(
                eq(clientUserId),
                eq("Бронювання скасовано"),
                anyString(),
                eq(Map.of("type", "BOOKING_DECLINED", "bookingId", bookingId))
        );
    }

    // -------------------------------------------------------------------------
    // notifyBookingStatusChanged — COMPLETED / NOT_COMPLETED / other
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should not send email or push when status is COMPLETED")
    void should_notSendEmailOrPush_when_statusCompleted() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.COMPLETED);

        service.notifyBookingStatusChanged(booking);

        verify(emailService, never()).sendBookingConfirmedEmail(anyString(), any());
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("should not send email or push when status is NOT_COMPLETED")
    void should_notSendEmailOrPush_when_statusNotCompleted() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.NOT_COMPLETED);

        service.notifyBookingStatusChanged(booking);

        verify(emailService, never()).sendBookingConfirmedEmail(anyString(), any());
        verify(emailService, never()).sendBookingDeclinedEmail(anyString(), any());
        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), any());
    }

    // -------------------------------------------------------------------------
    // notifyClientCancelled
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should send cancelled email and push to master when notifyClientCancelled is called")
    void should_sendCancelledEmailAndPushToMaster_when_notifyClientCancelledCalled() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CANCELLED);
        String bookingId = booking.getId().toString();

        service.notifyClientCancelled(booking);

        verify(emailService).sendClientCancelledEmail(anyString(), eq(booking));
        verify(pushService).sendToUser(
                eq(masterUserId),
                eq("Клієнт скасував бронювання"),
                anyString(),
                eq(Map.of("type", "CLIENT_CANCELLED", "bookingId", bookingId))
        );
    }

    @Test
    @DisplayName("notifyClientCancelled falls back to the guest identity (no NPE) for a null-client guest booking")
    void should_useGuestIdentity_when_notifyClientCancelledForGuestBooking() {
        // CLIENT_CANCELLED is enqueued ONLY by BookingCancellationService.cancel — the public
        // guest cancel-link flow — so booking.getClient() is guaranteed null on every real call
        // of this method. An unguarded dereference here NPEs 100% of the time.
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildGuestBookingMock(masterUserId, "Іван", "Петренко");
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyClientCancelled(booking);

        verify(emailService).sendClientCancelledEmail(anyString(), eq(booking));
        verify(pushService).sendToUser(eq(masterUserId), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue())
                .as("push body must carry the guest's name, not throw or read a null client")
                .contains("Іван Петренко");
    }

    // -------------------------------------------------------------------------
    // notifyReviewRequested (Phase 18.5)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("notifyReviewRequested targets the CLIENT (email + push) with a scheme-valid review URL")
    void should_targetClientWithReviewUrl_when_notifyReviewRequestedCalled() {
        UUID clientUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(UUID.randomUUID(), clientUserId, BookingStatus.COMPLETED);
        String bookingId = booking.getId().toString();
        String expectedUrl = FRONTEND_BASE_URL + "/bookings/" + bookingId + "/review";
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyReviewRequested(booking);

        // Email is addressed to the client, carries the booking, and the scheme-valid review URL.
        verify(emailService).sendReviewRequestEmail(eq("client@example.com"), eq(booking), urlCaptor.capture());
        assertThat(urlCaptor.getValue()).isEqualTo(expectedUrl);
        assertThat(urlCaptor.getValue()).startsWith("https://");

        // Push is delivered to the CLIENT user id (not the master) with the REVIEW_REQUESTED payload.
        verify(pushService).sendToUser(
                eq(clientUserId),
                eq("Оцініть візит"),
                anyString(),
                eq(Map.of("type", "REVIEW_REQUESTED", "bookingId", bookingId))
        );
    }

    @Test
    @DisplayName("notifyReviewRequested is a safe no-op (no email/push, no throw) for a null-client guest booking")
    void should_noOp_when_notifyReviewRequestedForGuestBooking() {
        // Guest (LINK) booking: null client (V89 chk_bookings_guest_fields). If a REVIEW_REQUESTED
        // row ever reaches the drain route for such a booking, dispatch must be a clean no-op —
        // no NPE on booking.getClient(), no email/push — so the outbox entry settles SENT, not DEAD.
        Booking booking = mock(Booking.class);
        lenient().when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getClient()).thenReturn(null);

        service.notifyReviewRequested(booking);

        verify(emailService, never()).sendReviewRequestEmail(anyString(), any(), anyString());
        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("notifyReviewRequested truncates the push body when the service name is very long")
    void should_truncatePushBody_when_notifyReviewRequestedServiceNameExceeds256Chars() {
        Booking booking = buildBookingMock(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.COMPLETED);
        when(booking.getMasterService().getServiceDefinition().getName()).thenReturn("А".repeat(500));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyReviewRequested(booking);

        verify(pushService).sendToUser(any(UUID.class), anyString(), bodyCaptor.capture(), any(Map.class));
        String body = bodyCaptor.getValue();
        assertThat(body.length()).isLessThanOrEqualTo(256);
        assertThat(body).endsWith("…");
    }

    // -------------------------------------------------------------------------
    // sendInviteEmail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should forward URL verbatim to EmailService when sendInviteEmail called")
    void should_delegateToEmailService_when_sendInviteEmailCalled() {
        // Arrange
        String email = "user@example.com";
        String inviteUrl = "https://app.beautica.ua/invite/accept?token=ABC123";
        String salonName = "Test Salon";

        // Act
        service.sendInviteEmail(email, inviteUrl, salonName);

        // Assert — exact-arg forwarding, no transformation
        verify(emailService).sendInviteEmail(email, inviteUrl, salonName);
        verifyNoMoreInteractions(emailService);
    }

    // -------------------------------------------------------------------------
    // Push body safety — null & length
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should truncate push body when service name exceeds 256 chars")
    void should_truncatePushBody_when_serviceNameExceeds256Chars() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);
        String longServiceName = "А".repeat(500);
        when(booking.getMasterService().getServiceDefinition().getName()).thenReturn(longServiceName);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyNewBooking(booking);

        verify(pushService).sendToUser(any(UUID.class), anyString(), bodyCaptor.capture(), any(Map.class));
        String body = bodyCaptor.getValue();
        assertThat(body.length()).isLessThanOrEqualTo(256);
        assertThat(body).endsWith("…");
    }

    @Test
    @DisplayName("should handle null client name gracefully when firstName is null")
    void should_handleNullClientNameGracefully_when_firstNameIsNull() {
        UUID masterUserId = UUID.randomUUID();
        Booking booking = buildBookingMock(masterUserId, UUID.randomUUID(), BookingStatus.CONFIRMED);
        when(booking.getClient().getFirstName()).thenReturn(null);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.notifyNewBooking(booking);

        verify(pushService).sendToUser(any(UUID.class), anyString(), bodyCaptor.capture(), any(Map.class));
        assertThat(bodyCaptor.getValue()).doesNotContain("null");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal Booking mock with master, client, and masterService chains.
     * Master-chain stubs use lenient() where methods are not accessed by CONFIRMED/DECLINED paths.
     */
    private Booking buildBookingMock(UUID masterUserId, UUID clientUserId, BookingStatus status) {
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(UUID.randomUUID());
        lenient().when(booking.getStatus()).thenReturn(status);

        User clientUser = mock(User.class);
        lenient().when(clientUser.getId()).thenReturn(clientUserId);
        lenient().when(clientUser.getEmail()).thenReturn("client@example.com");
        lenient().when(clientUser.getFirstName()).thenReturn("Тест");
        lenient().when(clientUser.getLastName()).thenReturn("Клієнт");
        when(booking.getClient()).thenReturn(clientUser);

        User masterUser = mock(User.class);
        lenient().when(masterUser.getId()).thenReturn(masterUserId);
        lenient().when(masterUser.getEmail()).thenReturn("master@example.com");
        Master master = mock(Master.class);
        lenient().when(master.getUser()).thenReturn(masterUser);
        lenient().when(booking.getMaster()).thenReturn(master);

        ServiceDefinition sd = mock(ServiceDefinition.class);
        lenient().when(sd.getName()).thenReturn("Тест послуга");
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        lenient().when(msa.getServiceDefinition()).thenReturn(sd);
        lenient().when(booking.getMasterService()).thenReturn(msa);

        return booking;
    }

    /**
     * Builds a guest (LINK) booking mock: {@code getClient()} returns null (V89
     * chk_bookings_guest_fields), and {@code getGuestName}/{@code getGuestSurname} carry the
     * OTP-verified guest identity instead.
     */
    private Booking buildGuestBookingMock(UUID masterUserId, String guestName, String guestSurname) {
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getClient()).thenReturn(null);
        lenient().when(booking.getGuestName()).thenReturn(guestName);
        lenient().when(booking.getGuestSurname()).thenReturn(guestSurname);

        User masterUser = mock(User.class);
        lenient().when(masterUser.getId()).thenReturn(masterUserId);
        lenient().when(masterUser.getEmail()).thenReturn("master@example.com");
        Master master = mock(Master.class);
        lenient().when(master.getUser()).thenReturn(masterUser);
        lenient().when(booking.getMaster()).thenReturn(master);

        ServiceDefinition sd = mock(ServiceDefinition.class);
        lenient().when(sd.getName()).thenReturn("Тест послуга");
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        lenient().when(msa.getServiceDefinition()).thenReturn(sd);
        lenient().when(booking.getMasterService()).thenReturn(msa);

        return booking;
    }
}
