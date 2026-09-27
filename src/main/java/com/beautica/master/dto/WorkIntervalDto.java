package com.beautica.master.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

/**
 * Phase 15.2: one canonical working interval on the wire.
 *
 * <p>Shared by weekly-template days and per-date custom-hours overrides. The backend models
 * intervals only — never "breaks"; the window-with-breaks UI is a pure client-side affordance
 * (mobile {@code schedule_model.dart} {@code fromIntervals}/{@code toIntervals}).
 *
 * <p><b>Wire format:</b> both times are {@link LocalTime}, serialized ISO-8601 as {@code HH:mm:ss}
 * via the default Jackson {@code JavaTimeModule}. Seconds are tolerated on input and are zeroed by
 * the service layer (Phase 15.4).
 *
 * <p>Single-calendar-day windows only: {@code endTime} must be strictly after {@code startTime}
 * (no midnight crossing). Non-overlap across intervals of the same day is a service-layer invariant
 * (Phase 15.4) — it cannot be expressed declaratively here.
 */
public record WorkIntervalDto(
        @NotNull(message = "Start time is required") LocalTime startTime,
        @NotNull(message = "End time is required") LocalTime endTime
) {

    /**
     * Cross-field ordering constraint. Deliberately a method rather than a class-level constraint so the
     * violation reports against the {@code ordered} path of the offending interval
     * ({@code intervals[0].ordered}), which is what {@code ScheduleDtoValidationTest} pins.
     *
     * <p><b>{@code @JsonIgnore}: this is a validator, never a wire field.</b> It follows the JavaBean
     * {@code isXxx()} getter convention, so Jackson would otherwise serialize it as an extra
     * {@code "ordered": true} on EVERY interval of every response carrying a {@link WorkIntervalDto} —
     * and, because this record is a REQUEST shape too, publish {@code ordered} as a documented input
     * property on every embedding request schema. The cost compounds exactly where it is least
     * affordable: {@code GET /salons/{salonId}/masters/effective-schedule} (Phase 321) returns
     * {@code roster × days} day objects, each with its own interval list, so a constant per-interval
     * field is multiplied by the whole board.
     *
     * <p>Identical treatment, and the same reasoning, as the sibling
     * {@link EffectiveDayResponse#isWorkingDay()} — that one carried {@code @JsonIgnore} from the
     * start and this one was missed. Bean Validation reads the method reflectively and is entirely
     * unaffected by the Jackson annotation.
     */
    @JsonIgnore
    @AssertTrue(message = "endTime must be after startTime")
    public boolean isOrdered() {
        return startTime == null || endTime == null || endTime.isAfter(startTime);
    }
}
