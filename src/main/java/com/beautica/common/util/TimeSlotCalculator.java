package com.beautica.common.util;

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

    public List<TimeRange> calculateAvailableSlots(
            LocalDate date,
            LocalTime workStart,
            LocalTime workEnd,
            Duration serviceDuration,
            Duration step,
            List<TimeRange> occupied
    ) {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(workStart, "workStart must not be null");
        Objects.requireNonNull(workEnd, "workEnd must not be null");

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
            workEndInst = workEndInst.plus(Duration.ofDays(1));
        }

        Instant nowInst = clock.instant();

        List<TimeRange> result = new ArrayList<>();
        Instant t = workStartInst;

        while (!t.plus(serviceDuration).isAfter(workEndInst)) {
            Instant candidateEnd = t.plus(serviceDuration);
            TimeRange candidate = new TimeRange(t, candidateEnd);

            if (candidate.start().isAfter(nowInst) && !overlapsAny(candidate, safeOccupied)) {
                result.add(candidate);
            }

            t = t.plus(step);
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
