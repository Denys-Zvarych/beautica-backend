package com.beautica.booking.service;

import com.beautica.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Anti-tamper temporal guards for the CURRENT booking's relationship to "now" (Phase 27.1).
 *
 * <p>Sibling of {@link BookingStartsAtValidator}, not an extension of it: that class validates a
 * REQUEST-supplied new {@code startsAt} against the lead-time/max-window rule (400 on violation).
 * This class instead guards the PERSISTED booking's existing {@code startsAt} against the action
 * being attempted right now (409 on violation) — a different question entirely. Before this
 * existed, {@code declineBooking}/{@code completeBooking} trusted the {@code CONFIRMED} status
 * guard alone: a provider could decline a booking whose appointment window had already started
 * (or completed), or mark COMPLETED a booking that had not even begun yet.
 *
 * <p>Every guard throws a 409 {@link BusinessException} — the same status the existing
 * {@code assertNotElapsedForClient}/{@code BookingElapsedException} client-side guard uses, since
 * this is the identical class of problem (server-authoritative time vs. a stale/tampered client
 * assumption), just on the provider side. Comparisons are on the absolute
 * {@link java.time.Instant} via the injected {@link Clock}, exactly like
 * {@link BookingStartsAtValidator} — never {@code OffsetDateTime.now()} directly (Anti-Bug §G).
 */
final class BookingTemporalGuard {

    private BookingTemporalGuard() {
    }

    /**
     * Decline is future-only: a provider may back out of a booking only before it has begun.
     * Once {@code now >= startsAt} the appointment is underway or past, and the provider's only
     * remaining resolution is {@code /complete} (or the untouched {@code /not-complete}).
     */
    static void assertFutureForProviderCancel(OffsetDateTime startsAt, Clock clock) {
        if (!isStrictlyFuture(startsAt, clock)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Cannot decline a booking that has already started");
        }
    }

    /**
     * Complete unlocks once the appointment has begun/elapsed — {@code now >= startsAt}. There is
     * deliberately no requirement that it has ENDED (a provider may mark a visit complete the
     * moment it starts, e.g. when running early).
     */
    static void assertElapsedForComplete(OffsetDateTime startsAt, Clock clock) {
        if (isStrictlyFuture(startsAt, clock)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Cannot complete a booking that has not started yet");
        }
    }

    /**
     * No-show (not-complete) shares the exact predicate {@link #assertElapsedForComplete} uses —
     * {@code now >= startsAt} — since recording a client no-show is only meaningful once the
     * appointment slot has begun/elapsed: a provider cannot claim the client failed to show up for
     * a slot that has not started yet (there is nothing to have missed). Kept as its own named
     * method — not a silent alias of {@link #assertElapsedForComplete} — for a no-show-specific
     * error string and call-site clarity in {@code BookingService#notCompleteBooking} /
     * {@code AppointmentTransitionService#notCompleteAppointment}, mirroring how
     * {@link #assertCurrentNotElapsedForReschedule} shares {@link #isStrictlyFuture} with
     * {@link #assertFutureForProviderCancel} below without becoming an alias of it.
     */
    static void assertElapsedForNotComplete(OffsetDateTime startsAt, Clock clock) {
        if (isStrictlyFuture(startsAt, clock)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Cannot mark a booking not-completed before it has started");
        }
    }

    /**
     * Provider-initiated reschedule requires the CURRENT booking to not have started yet — same
     * boolean predicate as {@link #assertFutureForProviderCancel} (a provider cannot move a
     * booking that is already underway any more than they can decline one), but kept as its own
     * named method — not a silent alias — for a reschedule-specific error string and call-site
     * clarity in {@code BookingService.rescheduleBooking}. See
     * {@link #assertFutureForProviderCancel} for the shared boolean logic
     * ({@link #isStrictlyFuture}).
     */
    static void assertCurrentNotElapsedForReschedule(OffsetDateTime startsAt, Clock clock) {
        if (!isStrictlyFuture(startsAt, clock)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Cannot reschedule a booking that has already started");
        }
    }

    /** {@code true} iff {@code startsAt} is strictly after the clock's current instant. */
    private static boolean isStrictlyFuture(OffsetDateTime startsAt, Clock clock) {
        return startsAt.toInstant().isAfter(clock.instant());
    }
}
