package com.beautica.common;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable calendar-date range value object used across the schedule feature.
 *
 * <p>Semantics are <strong>inclusive on both ends</strong> ({@code [from, to]}) because these are
 * civil calendar dates, not instants — a master who is available "from the 1st to the 3rd" works on
 * the 3rd. A {@code null} {@code to} means an <strong>open-ended</strong> range extending to +&infin;
 * (used for {@code validTo == null} schedule validity windows, per Phase 15.1).
 *
 * <p>{@code from} must never be {@code null}; an open <em>start</em> is not a concept the schedule
 * domain has.
 */
public record DateRange(LocalDate from, LocalDate to) {

    public DateRange {
        Objects.requireNonNull(from, "from must not be null");
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("DateRange 'to' must not be before 'from'");
        }
    }

    /**
     * Inclusive containment: {@code from <= date <= to}. A {@code null} {@code to} (open-ended) means
     * any date on or after {@code from} is contained.
     */
    public boolean contains(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        if (date.isBefore(from)) {
            return false;
        }
        return to == null || !date.isAfter(to);
    }

    /**
     * Inclusive overlap test used by the 15.4 overlap-detection. Two ranges overlap when each one's
     * start is not after the other's end. A {@code null} {@code to} is treated as +&infin;, so an
     * open-ended range overlaps anything whose end is on or after its start.
     */
    public boolean overlaps(DateRange other) {
        Objects.requireNonNull(other, "other must not be null");
        boolean thisStartsAfterOtherEnds = other.to != null && this.from.isAfter(other.to);
        boolean otherStartsAfterThisEnds = this.to != null && other.from.isAfter(this.to);
        return !thisStartsAfterOtherEnds && !otherStartsAfterThisEnds;
    }
}
