package com.beautica.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The single source of truth for the bookable time window.
 *
 * <p>A booking may only start at least {@link #MIN_MINUTES_AHEAD} minutes from now and no more than
 * {@link #MAX_DAYS_AHEAD} days ahead. Three call sites must agree on that floor or the API contradicts
 * itself:
 *
 * <ol>
 *   <li>the <b>slot list</b> ({@code TimeSlotCalculator#calculateAvailableSlots}, behind
 *       {@code GET /masters/{id}/slots} and {@code GET /book/{slug}/availability}) — which slots it
 *       <em>offers</em>;</li>
 *   <li>the <b>day projection</b> ({@code SlotCalculationService#getBookableWorkingDays}, behind
 *       {@code GET /masters/{id}/working-days?serviceId=…}) — which days it marks selectable;</li>
 *   <li>the <b>booking create</b> guard ({@code BookingStartsAtValidator}) — which starts it
 *       <em>accepts</em>.</li>
 * </ol>
 *
 * <p>Before this class existed the slot list kept any candidate merely {@code start > now}, while create
 * required {@code start >= now + 15 min} — so a slot 5 minutes out was listed as bookable and then
 * rejected with a 400. Everything now derives its floor from {@link #bookableCutoff(Clock)}.
 *
 * <p>Comparison is on {@link Instant}, so the supplied {@link Clock}'s zone is irrelevant — a Kyiv-zoned
 * clock and a UTC clock yield identical cutoffs.
 */
public final class BookingWindow {

    /** Minimum lead time: a booking cannot start sooner than this many minutes from now. */
    public static final int MIN_MINUTES_AHEAD = 15;

    /** Booking horizon: a booking cannot start more than this many days from now. */
    public static final int MAX_DAYS_AHEAD = 180;

    /**
     * {@link #MIN_MINUTES_AHEAD} as a {@link Duration}, hoisted to a constant (Perf LOW-3).
     * {@link #bookableCutoff(Instant)} is called once per (day × work interval) inside the slot walk —
     * up to ~2 200 times on a whole-horizon range — so allocating a fresh {@code Duration} per call was
     * pure garbage. {@link Duration} is immutable, so a shared instance is safe.
     */
    private static final Duration MIN_LEAD = Duration.ofMinutes(MIN_MINUTES_AHEAD);

    private BookingWindow() {
    }

    /**
     * The earliest instant at which a slot may start and still be genuinely bookable
     * ({@code now + MIN_MINUTES_AHEAD}). A candidate slot is bookable iff
     * {@code !start.isBefore(bookableCutoff(clock))}.
     */
    public static Instant bookableCutoff(Clock clock) {
        return bookableCutoff(clock.instant());
    }

    /**
     * {@link #MIN_MINUTES_AHEAD} as a shared, immutable {@link Duration} — the client/APP + guest/LINK
     * lead-time floor, expressed so a caller that must vary it can name the default instead of
     * re-deriving it.
     *
     * <p>Exactly ONE caller varies it: the STAFF create path (Phase 22.2). A walk-in is a client
     * standing at the counter, so its locked product rule is "now is allowed, the past is not"
     * (minimum lead 0). That floor has to be applied to the SLOT LIST as well as to
     * {@code BookingStartsAtValidator}, because the create path proves schedule-fit by requiring
     * {@code startsAt} to MATCH a generated slot — with the 15-minute floor baked into the generator
     * a walk-in "now" could never match anything, and the phase's own acceptance criterion ("a
     * {@code startsAt} equal to now, within a working slot, is accepted") would be unimplementable.
     * {@code SlotCalculationService#getStaffAvailableSlots} therefore passes {@link Duration#ZERO}
     * where every other caller passes this.
     *
     * <p>This does NOT weaken the three call sites in the list above: they all still derive their
     * floor from here, and the staff variant is a separate, uncached entry point.
     */
    public static Duration minLead() {
        return MIN_LEAD;
    }

    /**
     * {@link #bookableCutoff(Clock)} for a caller that has ALREADY read the clock (Perf LOW-2). A single
     * request must derive exactly ONE {@code now}: reading the clock again per day / per work interval
     * both wasted calls and — worse — produced two slightly different cutoffs for the same logical
     * request (the day-projection's cutoff vs the one {@code TimeSlotCalculator} re-derived internally),
     * so a slot could sit on the wrong side of the floor depending on which cutoff saw it. Callers read
     * the clock once and thread the resulting {@link Instant} through the whole computation.
     */
    public static Instant bookableCutoff(Instant now) {
        return now.plus(MIN_LEAD);
    }
}
