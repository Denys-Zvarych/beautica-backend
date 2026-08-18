package com.beautica.common.util;

import com.beautica.common.BookingWindow;
import com.beautica.common.TimeZones;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class TimeSlotCalculator {

    private final Clock clock;

    public TimeSlotCalculator(Clock clock) {
        this.clock = clock;
    }

    public record TimeRange(Instant start, Instant end) {}

    /**
     * The earliest instant a generated slot may start ({@code now + BookingWindow.MIN_MINUTES_AHEAD}) —
     * the SAME floor {@code BookingStartsAtValidator} enforces on booking create and
     * {@code SlotCalculationService} applies to its day-availability projection. Exposed so callers that
     * post-filter generated slots key off one cutoff rather than re-deriving it.
     */
    public Instant bookableCutoff() {
        return BookingWindow.bookableCutoff(clock);
    }

    /**
     * Materialises EVERY bookable slot in the window, using the calculator's own
     * {@link #bookableCutoff()}. Kept for callers that genuinely need the whole list (the slot list
     * behind {@code GET /masters/{id}/slots}).
     *
     * <p>Prefer {@link #calculateAvailableSlots(LocalDate, LocalTime, LocalTime, Duration, Duration,
     * List, Instant)} whenever the caller already holds the request's cutoff — see
     * {@link com.beautica.common.BookingWindow#bookableCutoff(Instant)} for why ONE cutoff must be
     * threaded through a request rather than re-derived per interval.
     *
     * @param occupied pre-filtered to the target {@code date}; ranges outside the date window
     *                 are silently ignored but waste comparison cycles — callers must narrow the
     *                 query window to [dayStart, dayEnd) before invoking this method.
     */
    public List<TimeRange> calculateAvailableSlots(
            LocalDate date,
            LocalTime workStart,
            LocalTime workEnd,
            Duration serviceDuration,
            Duration step,
            List<TimeRange> occupied
    ) {
        // The lead-time floor, NOT a bare "now". A candidate that merely starts after now (e.g. 5 minutes
        // out) is rejected by BookingStartsAtValidator on create (MIN_MINUTES_AHEAD = 15), so listing it
        // as available offered a slot that could only 400. Both paths now derive their floor from
        // BookingWindow.bookableCutoff — see BookingWindow for the three call sites that must agree.
        return calculateAvailableSlots(date, workStart, workEnd, serviceDuration, step, occupied,
                bookableCutoff());
    }

    /**
     * {@link #calculateAvailableSlots(LocalDate, LocalTime, LocalTime, Duration, Duration, List)} with an
     * explicit, caller-supplied {@code cutoff} (Perf LOW-2) — the whole request then shares ONE
     * {@link Instant} instead of each interval re-reading the clock and deriving a slightly different
     * floor.
     */
    public List<TimeRange> calculateAvailableSlots(
            LocalDate date,
            LocalTime workStart,
            LocalTime workEnd,
            Duration serviceDuration,
            Duration step,
            List<TimeRange> occupied,
            Instant cutoff
    ) {
        return walk(date, workStart, workEnd, serviceDuration, step, occupied, cutoff, false);
    }

    /**
     * <b>Existence-only</b> counterpart of {@link #calculateAvailableSlots} (Perf MEDIUM-4): {@code true}
     * as soon as ONE candidate slot in the window is bookable (starts at/after {@code cutoff} and
     * overlaps no occupied range) — the walk returns at the first hit instead of materialising every
     * remaining candidate.
     *
     * <p>The day-availability callers ({@code SlotCalculationService#isDayBookable}, which backs both the
     * calendar day projection and the catalogue/bookable-masters gate) only ever ask "is there one?", yet
     * previously built the full per-day slot list (up to 6 intervals × ~48 candidates) and threw all but
     * the first away. Same predicate, same cutoff, same overlap test as the list variant — only the
     * termination differs, so the two can never disagree.
     */
    public boolean hasAvailableSlot(
            LocalDate date,
            LocalTime workStart,
            LocalTime workEnd,
            Duration serviceDuration,
            Duration step,
            List<TimeRange> occupied,
            Instant cutoff
    ) {
        return !walk(date, workStart, workEnd, serviceDuration, step, occupied, cutoff, true).isEmpty();
    }

    /**
     * The single slot-generation walk shared by {@link #calculateAvailableSlots} and
     * {@link #hasAvailableSlot}. {@code stopAtFirst} makes it short-circuit; everything else — argument
     * validation, the DST-correct window resolution, the cutoff floor and the overlap test — is identical
     * by construction, so the list and the existence answer can never drift apart.
     */
    private List<TimeRange> walk(
            LocalDate date,
            LocalTime workStart,
            LocalTime workEnd,
            Duration serviceDuration,
            Duration step,
            List<TimeRange> occupied,
            Instant cutoff,
            boolean stopAtFirst
    ) {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(workStart, "workStart must not be null");
        Objects.requireNonNull(workEnd, "workEnd must not be null");
        Objects.requireNonNull(cutoff, "cutoff must not be null");

        if (serviceDuration.isNegative() || serviceDuration.isZero())
            throw new IllegalArgumentException("serviceDuration must be positive");
        if (step.isNegative() || step.isZero())
            throw new IllegalArgumentException("step must be positive");
        if (serviceDuration.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("serviceDuration must not exceed 24 hours");
        if (step.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("step must not exceed 24 hours");

        List<TimeRange> safeOccupied = occupied != null ? occupied : List.of();

        Instant workStartInst = date.atTime(workStart).atZone(TimeZones.KYIV).toInstant();
        Instant workEndInst   = date.atTime(workEnd).atZone(TimeZones.KYIV).toInstant();

        if (workEndInst.equals(workStartInst)) {
            return List.of();
        }
        if (workEndInst.isBefore(workStartInst)) {
            // DEFENSIVE, NOT REACHABLE THROUGH THE SCHEDULE API. The persisted model enforces
            // end_time > start_time on every working interval at four layers (WorkIntervalDto.isOrdered,
            // MasterScheduleService validation, chk_interval_order, chk_exc_interval_order), so a
            // cross-midnight window (workEnd <= workStart) never arrives from a resolved schedule — a
            // night shift is two single-calendar-day rows on two adjacent ISO weekdays, not one wrapping
            // row. This branch exists only so the utility stays correct for any DIRECT caller that hands
            // it a cross-midnight window: the end is the SAME wall-clock time on the next calendar day,
            // resolved as a civil instant on date+1 so DST is applied correctly — a flat +24h
            // (Duration.ofDays(1)) would be one real hour short on a 25h fall-back day and one hour long
            // on a 23h spring-forward day, dropping or inventing a bookable slot (Anti-Bug §G).
            workEndInst = date.plusDays(1).atTime(workEnd).atZone(TimeZones.KYIV).toInstant();
        }

        List<TimeRange> result = new ArrayList<>();
        Instant t = workStartInst;

        while (!t.plus(serviceDuration).isAfter(workEndInst)) {
            Instant candidateEnd = t.plus(serviceDuration);
            TimeRange candidate = new TimeRange(t, candidateEnd);

            if (!candidate.start().isBefore(cutoff) && !overlapsAny(candidate, safeOccupied)) {
                result.add(candidate);
                if (stopAtFirst) {
                    return result;
                }
            }

            t = t.plus(step);
        }

        return result;
    }

    /**
     * <b>Declared-times slot list</b> — the EXPLICIT_TIMES counterpart of
     * {@link #calculateAvailableSlots}. For a day whose effective schedule is a set of DISCRETE start
     * times (a weekly-template weekday or a per-date override with {@code EXPLICIT_TIMES} rows), the
     * bookable slots are EXACTLY those declared times: there is no window to stride and no grid to
     * synthesize.
     *
     * <p><b>Why this exists (bug fix, 2026-08-11).</b> The resolver projects an EXPLICIT_TIMES day as a
     * DERIVED window {@code [min(times) .. max(times)]} so that window-only consumers keep working
     * ({@code ScheduleMapper#toDerivedWindow}). Feeding that synthetic window to
     * {@link #calculateAvailableSlots} fabricated a 30-minute grid across it: a master declaring exactly
     * {13:00, 15:00} was offered 13:00/13:30/14:00 for a 60-min service — two times they never declared —
     * while 15:00, which they DID declare, was unreachable (it is the window END, so nothing can start
     * there and still fit). The derived window is a display artifact and must never act as a fit
     * constraint.
     *
     * <p><b>End bound.</b> An explicit-times day has no real interval end, so the only non-artifact
     * bound is the calendar day itself: a candidate is dropped if it would run past {@code date}'s
     * midnight in {@link TimeZones#KYIV}. The schedule model forbids cross-midnight ranges (a night
     * shift is two single-day rows on adjacent ISO weekdays), so "ends within the same Kyiv civil day"
     * is the strictest bound the domain actually asserts — and, unlike the derived window end, it never
     * swallows a declared time.
     *
     * <p>Every other per-candidate filter is IDENTICAL to the interval walk, by construction: the
     * lead-time {@code cutoff} floor and the same strict-overlap test ({@link #overlapsAny}; end-to-start
     * touching stays allowed, and each occupied range's end already folds in the booking's buffer).
     *
     * @param declaredTimes the day's declared start times, sorted ascending and de-duplicated by the
     *                      resolver; iterated in order, so the result is ascending too
     * @param occupied      pre-filtered to {@code date}'s window, exactly as {@link #calculateAvailableSlots}
     *                      requires
     */
    public List<TimeRange> calculateDeclaredSlots(
            LocalDate date,
            List<LocalTime> declaredTimes,
            Duration serviceDuration,
            List<TimeRange> occupied,
            Instant cutoff
    ) {
        return walkDeclared(date, declaredTimes, serviceDuration, occupied, cutoff, false);
    }

    /**
     * <b>Existence-only</b> counterpart of {@link #calculateDeclaredSlots}, mirroring
     * {@link #hasAvailableSlot}'s relationship to {@link #calculateAvailableSlots}: {@code true} as soon
     * as ONE declared time is bookable. Same predicate, same cutoff, same overlap test, same day-end
     * bound — only the termination differs, so the day-gate ({@code SlotCalculationService#isDayBookable},
     * behind {@code GET /masters/{id}/working-days} and the catalogue gate) and the materialised slot
     * list can never disagree about an EXPLICIT_TIMES day.
     */
    public boolean hasDeclaredSlot(
            LocalDate date,
            List<LocalTime> declaredTimes,
            Duration serviceDuration,
            List<TimeRange> occupied,
            Instant cutoff
    ) {
        return !walkDeclared(date, declaredTimes, serviceDuration, occupied, cutoff, true).isEmpty();
    }

    /**
     * The single declared-times walk shared by {@link #calculateDeclaredSlots} and
     * {@link #hasDeclaredSlot} — the exact shape {@link #walk} has for interval days, so the list and the
     * existence answer are the same code path with a different termination.
     */
    private List<TimeRange> walkDeclared(
            LocalDate date,
            List<LocalTime> declaredTimes,
            Duration serviceDuration,
            List<TimeRange> occupied,
            Instant cutoff,
            boolean stopAtFirst
    ) {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(declaredTimes, "declaredTimes must not be null");
        Objects.requireNonNull(cutoff, "cutoff must not be null");

        if (serviceDuration.isNegative() || serviceDuration.isZero())
            throw new IllegalArgumentException("serviceDuration must be positive");
        if (serviceDuration.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("serviceDuration must not exceed 24 hours");

        List<TimeRange> safeOccupied = occupied != null ? occupied : List.of();
        // Exclusive end-of-day bound, resolved as a civil instant so a DST transition shortens/lengthens
        // the day correctly (never a flat +24h — see the cross-midnight branch in #walk).
        Instant dayEndInst = date.plusDays(1).atStartOfDay(TimeZones.KYIV).toInstant();

        List<TimeRange> result = new ArrayList<>();
        for (LocalTime declared : declaredTimes) {
            if (declared == null) {
                continue;
            }
            Instant start = date.atTime(declared).atZone(TimeZones.KYIV).toInstant();
            TimeRange candidate = new TimeRange(start, start.plus(serviceDuration));

            if (candidate.start().isBefore(cutoff)
                    || candidate.end().isAfter(dayEndInst)
                    || overlapsAny(candidate, safeOccupied)) {
                continue;
            }
            result.add(candidate);
            if (stopAtFirst) {
                return result;
            }
        }
        return result;
    }

    private boolean overlapsAny(TimeRange candidate, List<TimeRange> occupied) {
        for (TimeRange o : occupied) {
            if (candidate.start().isBefore(o.end()) && candidate.end().isAfter(o.start())) {
                return true;
            }
        }
        return false;
    }
}
