package com.beautica.master.dto;

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

    @AssertTrue(message = "endTime must be after startTime")
    public boolean isOrdered() {
        return startTime == null || endTime == null || endTime.isAfter(startTime);
    }
}
