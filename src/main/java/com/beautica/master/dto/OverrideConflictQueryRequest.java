package com.beautica.master.dto;

import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/masters/{masterId}/overrides/conflicts} (2026-07-26 design —
 * "schedule override over existing bookings"): a read-only preview of which {@code CONFIRMED}
 * bookings an intended override would orphan, evaluated over a whole inclusive date range in ONE
 * call — mirroring how the mobile client saves a multi-day span (a single override shape applied to
 * every date in {@code [from, to]}), never one preview call per expanded date.
 *
 * <p>Carries the identical {@code kind}/{@code mode}/{@code intervals}/{@code times} shape as
 * {@link ScheduleOverrideRequest} — same discriminator, same mode exclusivity rule
 * ({@link ScheduleOverrideRequest#isKindConsistent(ScheduleExceptionKind, WeekdayMode, List, List)},
 * reused here rather than re-implemented) — plus the {@code from}/{@code to} range that request
 * lacks (a write targets exactly one date; a preview spans many). The range itself is bounded by
 * {@code ScheduleDateMath#assertExpandable} in the service layer (≤366 days), not here.
 */
public record OverrideConflictQueryRequest(
        @NotNull(message = "'from' is required")
        LocalDate from,

        @NotNull(message = "'to' is required")
        LocalDate to,

        @NotNull(message = "Kind is required")
        ScheduleExceptionKind kind,

        // null = INTERVAL (default), mirroring ScheduleOverrideRequest#mode.
        WeekdayMode mode,

        @Valid
        @Size(max = 6, message = "An override may have at most 6 intervals")
        List<WorkIntervalDto> intervals,

        @Size(max = 24, message = "An override may have at most 24 discrete times")
        List<@NotNull(message = "A discrete time must not be null") LocalTime> times
) {

    /** The effective mode: an absent {@code mode} defaults to {@link WeekdayMode#INTERVAL}. */
    public WeekdayMode effectiveMode() {
        return mode != null ? mode : WeekdayMode.INTERVAL;
    }

    @AssertTrue(message =
            "DAY_OFF carries no intervals or times; CUSTOM_HOURS carries either intervals (INTERVAL) "
                    + "or a non-empty times list (EXPLICIT_TIMES), never both")
    public boolean isKindConsistent() {
        return ScheduleOverrideRequest.isKindConsistent(kind, mode, intervals, times);
    }
}
