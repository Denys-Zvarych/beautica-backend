package com.beautica.master.service;

import java.time.Duration;

/**
 * Optional capability of the day list {@link MasterScheduleService#reduceEffectiveRangeBatch} hands its
 * reducer (B523 perf fix): an upper bound on the longest single bookable span any day of the range can
 * offer, read from the loaded schedule rows WITHOUT folding a day.
 *
 * <p>The bound is safe, never tight-at-the-expense-of-correctness: it is at least the longest template or
 * override interval, at least {@code 24:00} minus the earliest declared discrete time, a full day for a
 * custom override whose shape is not read here, plus one hour of DST slack (a 25-hour fall-back day).
 * So a duration longer than the bound fits NO day — the free-slot walk for it would return false — and
 * a caller may skip that walk; a duration within the bound must still be walked.
 */
public interface SpanBoundedDays {

    /** Upper bound on any free span in the range; a duration above it cannot fit any day. */
    Duration longestPossibleSpan();
}
