package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Thin notification-side facade that dispatches email and push notifications.
 *
 * <p>Methods are synchronous — invoked by {@code NotificationOutboxDrainWorker}.
 * URL composition (and the HTTPS scheme guard) for invite links lives in
 * {@code InviteService.buildInviteLink}, not in this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int PUSH_BODY_MAX_LENGTH = 256;

    private final EmailNotificationService emailService;
    private final PushNotificationService pushService;

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

    /**
     * Forwards a pre-built invite acceptance URL to the email transport.
     *
     * <p>The caller is responsible for URL construction and validation
     * (scheme guard, encoding). See {@code InviteService.buildInviteLink}.
     *
     * @param email     recipient address
     * @param inviteUrl the fully-built invite acceptance URL — caller is
     *                  responsible for URL construction and validation
     * @param salonName salon display name shown in the email body
     */
    public void sendInviteEmail(String email, String inviteUrl, String salonName) {
        emailService.sendInviteEmail(email, inviteUrl, salonName);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        if (value.length() <= PUSH_BODY_MAX_LENGTH) return value;
        return value.substring(0, PUSH_BODY_MAX_LENGTH - 1) + "…";
    }
}
