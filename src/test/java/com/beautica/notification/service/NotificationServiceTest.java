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
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService — unit")
class NotificationServiceTest {

    @Mock
    private EmailNotificationService emailService;
    @Mock
    private PushNotificationService pushService;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(emailService, pushService);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://localhost:3000");
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
                eq("Бронювання відхилено"),
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

    // -------------------------------------------------------------------------
    // sendInviteEmail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("should build invite URL and delegate to emailService when sendInviteEmail is called")
    void should_buildInviteUrlAndDelegate_when_sendInviteEmailCalled() {
        String inviteTokenId = UUID.randomUUID().toString();
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);

        service.sendInviteEmail("master@example.com", inviteTokenId, "Salon UA");

        verify(emailService).sendInviteEmail(eq("master@example.com"), urlCaptor.capture(), eq("Salon UA"));
        assertThat(urlCaptor.getValue())
                .startsWith("http://localhost:3000/invite/accept?token=")
                .contains(inviteTokenId);
    }

    @Test
    @DisplayName("should throw IllegalStateException when frontendBaseUrl uses plain http with non-localhost origin")
    void should_throwIllegalState_when_frontendBaseUrlIsPlainHttp() {
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://example.com");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.sendInviteEmail("x@y.com", UUID.randomUUID().toString(), "Salon")
        ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use HTTPS scheme");
    }

    @Test
    @DisplayName("should accept https URL when frontendBaseUrl is valid https")
    void should_acceptHttpsUrl_when_frontendBaseUrlIsValidHttps() {
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://app.beautica.example");
        String inviteTokenId = UUID.randomUUID().toString();
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);

        service.sendInviteEmail("master@example.com", inviteTokenId, "Salon UA");

        verify(emailService).sendInviteEmail(eq("master@example.com"), urlCaptor.capture(), eq("Salon UA"));
        assertThat(urlCaptor.getValue())
                .startsWith("https://app.beautica.example/invite/accept?token=")
                .contains(inviteTokenId);
    }

    @Test
    @DisplayName("should throw IllegalStateException when frontendBaseUrl spoofs localhost subdomain")
    void should_throwIllegalState_when_frontendBaseUrlSpoofsLocalhostSubdomain() {
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://localhost.attacker.com");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.sendInviteEmail("x@y.com", UUID.randomUUID().toString(), "Salon")
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should throw IllegalStateException when frontendBaseUrl is malformed")
    void should_throwIllegalState_when_frontendBaseUrlIsMalformed() {
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "not-a-url");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.sendInviteEmail("x@y.com", UUID.randomUUID().toString(), "Salon")
        ).isInstanceOf(IllegalStateException.class);
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
}
