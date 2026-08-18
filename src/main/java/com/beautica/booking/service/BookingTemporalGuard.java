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
 * existed, {@code completeBooking} trusted the {@code CONFIRMED} status guard alone: a provider
 * could mark COMPLETED a booking that had not even begun yet.
 *
 * <p>Decline and not-complete are deliberately NOT guarded here — a provider may resolve either
 * transition on a CONFIRMED booking at any time, elapsed or not (product decision reversing the
 * earlier future-only decline guard and the earlier elapsed-only not-complete guard).
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
     * Provider-initiated reschedule requires the CURRENT booking to not have started yet — a
     * provider cannot move a booking that is already underway. Kept as its own named method for a
     * reschedule-specific error string and call-site clarity in
     * {@code BookingService.rescheduleBooking}.
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
