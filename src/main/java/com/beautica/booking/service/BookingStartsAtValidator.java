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
        assertWithinHorizon(startsAt, clock);
    }

    /**
     * The STAFF variant (Phase 22.2) — <b>minimum lead 0</b>: {@code startsAt >= now} is accepted,
     * anything strictly before now is rejected, and the {@link BookingWindow#MAX_DAYS_AHEAD} ceiling
     * is unchanged.
     *
     * <p><b>Why staff differ (locked product decision, confirmed 2026-07-08).</b> A staff booking
     * records a walk-in or phone-in appointment the salon has ALREADY arranged, frequently one that
     * starts immediately. The shared 15-minute floor exists to stop a self-service client booking a
     * slot the master cannot physically prepare for — it makes no sense for a client already
     * standing at the counter. Retroactive (past) staff bookings stay forbidden: this is a booking
     * path, not a back-fill path, and the closure rule ({@code CONFIRMED} + elapsed {@code endsAt} =
     * awaiting closure) is derived at read time, so a past-dated create would mint a row that is
     * born needing closure.
     *
     * <p><b>Deliberately a separate method, not a relaxed default.</b> The client/APP and guest/LINK
     * paths call {@link #validate} and keep the ≥15-minute guard untouched; there is no shared
     * mutable floor either path could accidentally inherit.
     *
     * <p>Its counterpart in the slot layer is {@code SlotCalculationService#getStaffAvailableSlots}
     * — see {@link BookingWindow#minLead()} for why both floors must move together.
     */
    static void validateStaff(OffsetDateTime startsAt, Clock clock) {
        if (startsAt.toInstant().isBefore(clock.instant())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Booking cannot start in the past");
        }
        assertWithinHorizon(startsAt, clock);
    }

    /**
     * The {@link BookingWindow#MAX_DAYS_AHEAD} ceiling, identical for every create path — extracted
     * so the client and staff floors are the only thing that differs between them.
     */
    private static void assertWithinHorizon(OffsetDateTime startsAt, Clock clock) {
        Duration gap = Duration.between(clock.instant(), startsAt.toInstant());
        if (gap.toDays() > BookingWindow.MAX_DAYS_AHEAD) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Booking cannot be more than " + BookingWindow.MAX_DAYS_AHEAD
                            + " days in the future");
        }
    }
}
