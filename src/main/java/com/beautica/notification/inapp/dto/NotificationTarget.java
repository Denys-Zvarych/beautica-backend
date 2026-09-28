package com.beautica.notification.inapp.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Where a {@link NotificationResponse} navigates. {@code bookingId}/{@code appointmentId} are
 * populated only when {@code kind} is {@link TargetKind#BOOKING} or
 * {@link TargetKind#BOOKING_REVIEW}; {@code salonId} is populated whenever the underlying feed
 * row carries a salon id, regardless of {@code kind}, so salon staff can still resolve the salon
 * context even when the booking itself is no longer visible.
 *
 * @param bookingId     the booking to open, or the visit's EARLIEST booking (by start time, then
 *                       id) for a multi-service visit event — null unless {@code kind} is
 *                       {@code BOOKING}/{@code BOOKING_REVIEW}
 * @param appointmentId the visit this event belongs to, or null for a single-service event
 * @param salonId       the salon context, or null for an independent-master event
 */
public record NotificationTarget(
        TargetKind kind,
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true)
        UUID bookingId,
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true)
        UUID appointmentId,
        @Schema(types = {"string", "null"}, format = "uuid", nullable = true)
        UUID salonId
) {

    public static NotificationTarget none(UUID salonId) {
        return new NotificationTarget(TargetKind.NONE, null, null, salonId);
    }
}
