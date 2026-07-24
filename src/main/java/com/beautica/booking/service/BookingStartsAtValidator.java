package com.beautica.booking.service;

import com.beautica.common.BookingWindow;
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
 * at least {@link BookingWindow#MIN_MINUTES_AHEAD} minutes from now and no more than
 * {@link BookingWindow#MAX_DAYS_AHEAD} days ahead. Extracted (DRY) so the two paths cannot drift:
 * before this existed, the guest path relied only on the DTO's {@code @Future} and
 * could persist a CONFIRMED booking for {@code now + 1 min} or {@code now + 5 y}.
 *
 * <p>The lower bound is expressed as {@link BookingWindow#bookableCutoff(Clock)} — the very same
 * instant the slot list ({@code TimeSlotCalculator}) and the day-availability projection
 * ({@code SlotCalculationService#getBookableWorkingDays}) use to decide what to OFFER. Anything the
 * API offers is therefore accepted here, and vice versa.
 *
 * <p>Comparison is done on {@link java.time.Instant}, so the supplied {@link Clock}'s
 * zone is irrelevant — a Kyiv-zoned clock and a UTC clock yield identical results.
 */
final class BookingStartsAtValidator {

    private BookingStartsAtValidator() {
    }

    static void validate(OffsetDateTime startsAt, Clock clock) {
        if (startsAt.toInstant().isBefore(BookingWindow.bookableCutoff(clock))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Booking must start at least " + BookingWindow.MIN_MINUTES_AHEAD
                            + " minutes from now");
        }
        Duration gap = Duration.between(clock.instant(), startsAt.toInstant());
        if (gap.toDays() > BookingWindow.MAX_DAYS_AHEAD) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Booking cannot be more than " + BookingWindow.MAX_DAYS_AHEAD
                            + " days in the future");
        }
    }
}
