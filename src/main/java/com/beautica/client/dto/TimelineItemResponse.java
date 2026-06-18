package com.beautica.client.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One past procedure on the signed-in client's BEAUTY TIMELINE (Phase 19.5).
 *
 * <p>Chronological (most-recent-first) rail of COMPLETED bookings. The backend returns
 * no per-booking photo: the mobile client maps {@code categoryKey} to its own static
 * service-category icon (decision 7 client-side icon map), and shows {@code categoryName}
 * as the display label.
 *
 * @param bookingId    the source booking id
 * @param categoryKey  stable machine key for the client-side icon map (uppercase slug of
 *                     the service category; {@code "UNKNOWN"} when the service has none)
 * @param categoryName human-readable category label
 * @param date         booking day derived from {@code startsAt} in {@code Europe/Kyiv}
 * @param masterId     the master who performed the service
 * @param serviceName  the booked service definition name
 */
public record TimelineItemResponse(
        UUID bookingId,
        String categoryKey,
        String categoryName,
        LocalDate date,
        UUID masterId,
        String serviceName
) {
}
