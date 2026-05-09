package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int PUSH_BODY_MAX_LENGTH = 256;

    private final EmailNotificationService emailService;
    private final PushNotificationService pushService;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    // NOT @Async — called synchronously by NotificationOutboxDrainWorker

    public void notifyNewBooking(Booking booking) {
        String masterEmail = booking.getMaster().getUser().getEmail();
        UUID masterUserId = booking.getMaster().getUser().getId();
        String clientName = (safe(booking.getClient().getFirstName()) + " " + safe(booking.getClient().getLastName())).trim();
        String serviceName = safe(booking.getMasterService().getServiceDefinition().getName());
        String bookingId = booking.getId().toString();

        emailService.sendNewBookingEmail(masterEmail, booking);
        pushService.sendToUser(
                masterUserId,
                "Нове бронювання",
                truncate("Клієнт " + clientName + " забронював " + serviceName),
                Map.of("type", "NEW_BOOKING", "bookingId", bookingId)
        );
    }

    public void notifyBookingStatusChanged(Booking booking) {
        BookingStatus status = booking.getStatus();
        String clientEmail = booking.getClient().getEmail();
        UUID clientUserId = booking.getClient().getId();
        String serviceName = safe(booking.getMasterService().getServiceDefinition().getName());
        String bookingId = booking.getId().toString();

        switch (status) {
            case CONFIRMED -> {
                emailService.sendBookingConfirmedEmail(clientEmail, booking);
                pushService.sendToUser(
                        clientUserId,
                        "Бронювання підтверджено",
                        truncate("Ваше бронювання на " + serviceName + " підтверджено"),
                        Map.of("type", "BOOKING_CONFIRMED", "bookingId", bookingId)
                );
            }
            case DECLINED -> {
                emailService.sendBookingDeclinedEmail(clientEmail, booking);
                pushService.sendToUser(
                        clientUserId,
                        "Бронювання відхилено",
                        truncate("Ваше бронювання на " + serviceName + " відхилено"),
                        Map.of("type", "BOOKING_DECLINED", "bookingId", bookingId)
                );
            }
            default -> log.info("No notification action for booking status [{}], bookingId={}", status, bookingId);
        }
    }

    public void notifyClientCancelled(Booking booking) {
        String masterEmail = booking.getMaster().getUser().getEmail();
        UUID masterUserId = booking.getMaster().getUser().getId();
        String clientName = (safe(booking.getClient().getFirstName()) + " " + safe(booking.getClient().getLastName())).trim();
        String serviceName = safe(booking.getMasterService().getServiceDefinition().getName());
        String bookingId = booking.getId().toString();

        emailService.sendClientCancelledEmail(masterEmail, booking);
        pushService.sendToUser(
                masterUserId,
                "Клієнт скасував бронювання",
                truncate(clientName + " скасував бронювання на " + serviceName),
                Map.of("type", "CLIENT_CANCELLED", "bookingId", bookingId)
        );
    }

    public void sendInviteEmail(String email, String inviteTokenId, String salonName) {
        String inviteUrl = buildInviteUrl(inviteTokenId);
        emailService.sendInviteEmail(email, inviteUrl, salonName);
    }

    private String buildInviteUrl(String inviteTokenId) {
        validateFrontendBaseUrl();
        return frontendBaseUrl + "/invite/accept?token=" + URLEncoder.encode(inviteTokenId, StandardCharsets.UTF_8);
    }

    private void validateFrontendBaseUrl() {
        URI uri;
        try {
            uri = URI.create(frontendBaseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.frontend.base-url is not a valid URI: " + frontendBaseUrl, e);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IllegalStateException("app.frontend.base-url must include scheme and host: " + frontendBaseUrl);
        }
        boolean isHttps = "https".equalsIgnoreCase(scheme);
        boolean isLocalHttp = "http".equalsIgnoreCase(scheme) && "localhost".equalsIgnoreCase(host);
        if (!isHttps && !isLocalHttp) {
            throw new IllegalStateException("app.frontend.base-url must use HTTPS scheme for non-localhost origins");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        if (value.length() <= PUSH_BODY_MAX_LENGTH) return value;
        return value.substring(0, PUSH_BODY_MAX_LENGTH - 1) + "…";
    }
}
