package com.beautica.booking.service;

import com.beautica.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Shared lead-time / max-window guard for booking {@code startsAt} values.
 *
 * <p>Both the authenticated {@link BookingService} and the public
 * {@link GuestBookingService} must enforce the same window — a booking has to start
 * at least {@link #MIN_MINUTES_AHEAD} minutes from now and no more than
 * {@link #MAX_DAYS_AHEAD} days ahead. Extracted (DRY) so the two paths cannot drift:
 * before this existed, the guest path relied only on the DTO's {@code @Future} and
 * could persist a CONFIRMED booking for {@code now + 1 min} or {@code now + 5 y}.
 *
 * <p>Comparison is done on {@link java.time.Instant}, so the supplied {@link Clock}'s
 * zone is irrelevant — a Kyiv-zoned clock and a UTC clock yield identical results.
 */
final class BookingStartsAtValidator {

    static final int MIN_MINUTES_AHEAD = 15;
    static final int MAX_DAYS_AHEAD = 180;

    private BookingStartsAtValidator() {
    }

    static void validate(OffsetDateTime startsAt, Clock clock) {
        Duration gap = Duration.between(clock.instant(), startsAt.toInstant());
        if (gap.toMinutes() < MIN_MINUTES_AHEAD) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Booking must start at least 15 minutes from now");
        }
        if (gap.toDays() > MAX_DAYS_AHEAD) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Booking cannot be more than 180 days in the future");
        }
    }
}
