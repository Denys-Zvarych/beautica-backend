package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.util.SchemeGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class NotificationService {

    private static final int PUSH_BODY_MAX_LENGTH = 256;

    private final EmailNotificationService emailService;
    private final PushNotificationService pushService;
    private final String frontendBaseUrl;

    // Explicit constructor — @RequiredArgsConstructor cannot bind the @Value frontend base URL.
    public NotificationService(
            EmailNotificationService emailService,
            PushNotificationService pushService,
            @Value("${app.frontend.base-url}") String frontendBaseUrl
    ) {
        this.emailService = emailService;
        this.pushService = pushService;
        this.frontendBaseUrl = frontendBaseUrl;
    }

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
            default -> log.debug("No notification action for booking status [{}], bookingId={}", status, bookingId);
        }
    }

    /**
     * Notifies the provider (master / salon-admin) that the client rescheduled the booking
     * to a new time and it is awaiting re-approval (Phase 19.2). Targets the master's user —
     * the same recipient as {@link #notifyNewBooking(Booking)} — with «Запит на перенесення» copy.
     */
    public void notifyBookingRescheduled(Booking booking) {
        String masterEmail = booking.getMaster().getUser().getEmail();
        UUID masterUserId = booking.getMaster().getUser().getId();
        String serviceName = safe(booking.getMasterService().getServiceDefinition().getName());
        String bookingId = booking.getId().toString();

        emailService.sendBookingRescheduledEmail(masterEmail, booking);
        pushService.sendToUser(
                masterUserId,
                "Запит на перенесення",
                truncate("Клієнт попросив перенести бронювання на " + serviceName),
                Map.of("type", "BOOKING_RESCHEDULED", "bookingId", bookingId)
        );
    }

    /**
     * Notifies the CLIENT (not the provider) that their completed visit can now be reviewed
     * (Phase 18.5). Builds a {@code /bookings/{id}/review} deep link from the configured frontend
     * base URL — scheme-guarded consistently with {@code InviteService.buildInviteLink} so an
     * unsafe {@code app.frontend.base-url} is rejected before the email is composed.
     */
    public void notifyReviewRequested(Booking booking) {
        // Guest (LINK) bookings have a null client (V89 chk_bookings_guest_fields) and no account
        // to leave a review with. A REVIEW_REQUESTED row should never be enqueued for one
        // (BookingService.completeBooking guards this), but the drain route must stay null-safe:
        // a clean no-op dispatch marks the entry SENT rather than NPEing it 3× into DEAD.
        if (booking.getClient() == null) {
            log.debug("Skipping REVIEW_REQUESTED for account-less guest booking {}", booking.getId());
            return;
        }
        String clientEmail = booking.getClient().getEmail();
        UUID clientUserId = booking.getClient().getId();
        String serviceName = safe(booking.getMasterService().getServiceDefinition().getName());
        String bookingId = booking.getId().toString();

        String reviewUrl = buildReviewUrl(bookingId);

        emailService.sendReviewRequestEmail(clientEmail, booking, reviewUrl);
        pushService.sendToUser(
                clientUserId,
                "Оцініть візит",
                truncate("Як пройшов ваш візит на " + serviceName + "? Залиште відгук"),
                Map.of("type", "REVIEW_REQUESTED", "bookingId", bookingId)
        );
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

    /**
     * Builds the booking-scoped review deep link and guards the frontend base URL scheme
     * (mirrors {@code InviteService.buildInviteLink}). The booking id is a UUID string, so no
     * URL-encoding is required for the path segment.
     */
    private String buildReviewUrl(String bookingId) {
        if (!SchemeGuard.isAllowedScheme(frontendBaseUrl)) {
            throw new IllegalStateException(
                    "app.frontend.base-url must use HTTPS scheme for non-localhost origins, got: " + frontendBaseUrl);
        }
        return frontendBaseUrl + "/bookings/" + bookingId + "/review";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        if (value.length() <= PUSH_BODY_MAX_LENGTH) return value;
        return value.substring(0, PUSH_BODY_MAX_LENGTH - 1) + "…";
    }
}
