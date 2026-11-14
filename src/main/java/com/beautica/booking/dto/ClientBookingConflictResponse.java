package com.beautica.booking.dto;

import com.beautica.common.TimeZones;
import com.beautica.common.exception.ClientBookingConflictException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Response body for the {@code 409 CLIENT_BOOKING_CONFLICT} returned when an authenticated
 * client tries to create, or reschedule into, a window that overlaps a booking they already
 * hold with any master/salon. {@code startsAt}/{@code endsAt} are zoned to Kyiv time, matching
 * {@link BookingResponse} and {@link BookingDetailResponse} — the two other DTOs that surface
 * booking start/end instants to the mobile client.
 *
 * <p>Provider-initiated requests get a redacted body ({@link ClientBookingConflictException#isRedacted()}):
 * {@code conflictingBookingId}/{@code serviceName}/{@code masterName} are null and OMITTED from
 * the JSON ({@code NON_NULL}), leaving only {@code code} + the window. The client body is
 * unchanged — none of its fields is ever null ({@code masterName} falls back to {@code ""}).
 *
 * @param code                  stable machine-readable error code, always
 *                              {@link ClientBookingConflictException#ERROR_CODE}
 * @param conflictingBookingId  id of the client's existing booking that overlaps the request
 * @param serviceName           service name on the conflicting booking
 * @param masterName            display name ("first last") of the master on the conflicting booking
 * @param startsAt              start of the conflicting booking, Kyiv-zoned
 * @param endsAt                end of the conflicting booking, Kyiv-zoned
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClientBookingConflictResponse(
        String code,
        UUID conflictingBookingId,
        String serviceName,
        String masterName,
        ZonedDateTime startsAt,
        ZonedDateTime endsAt
) {

    public static ClientBookingConflictResponse from(ClientBookingConflictException ex) {
        return new ClientBookingConflictResponse(
                ClientBookingConflictException.ERROR_CODE,
                ex.getConflictingBookingId(),
                ex.getServiceName(),
                ex.getMasterName(),
                ex.getStartsAt().atZoneSameInstant(TimeZones.KYIV),
                ex.getEndsAt().atZoneSameInstant(TimeZones.KYIV));
    }
}
