package com.beautica.master.dto;

import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Phase 15.2 / 15.9: a per-date override of the weekly template — one row per calendar date.
 *
 * <p>The {@code kind} discriminator selects the consistent field set:
 * <ul>
 *   <li>{@link ScheduleExceptionKind#DAY_OFF} — closure; neither {@code intervals} nor {@code times}.
 *       A day-off carries no reason and no note (both removed in V83).</li>
 *   <li>{@link ScheduleExceptionKind#CUSTOM_HOURS} — different hours; one of two modes
 *       (Phase 15.9), exactly mirroring {@link WeeklyScheduleDayRequest}:
 *       <ul>
 *         <li>{@link WeekdayMode#INTERVAL} (default) — continuous window(s) carried in {@code intervals}.</li>
 *         <li>{@link WeekdayMode#EXPLICIT_TIMES} — a set of discrete bookable start times carried in
 *             {@code times}; these ARE the slot set, the displayed window is derived {@code [min..max]}.</li>
 *       </ul></li>
 * </ul>
 *
 * <p><b>Mode exclusivity</b> is enforced by {@link #isKindConsistent()}: a {@code CUSTOM_HOURS} override
 * carries EITHER intervals OR times, never both; a {@code DAY_OFF} carries neither. {@code mode == null}
 * defaults to {@link WeekdayMode#INTERVAL} for backward compatibility with pre-15.9 clients (the
 * {@code mode}/{@code times} fields are additive/nullable).
 *
 * <p><b>Wire format:</b> {@code date} is a {@link LocalDate}, serialized ISO-8601 as {@code yyyy-MM-dd};
 * each {@code times} entry is a {@link LocalTime}, serialized ISO-8601 as {@code HH:mm:ss}. Multi-day spans
 * are expanded client-side into N single-date rows on save.
 *
 * <p><b>Deferred to Phase 15.4 (service layer):</b> the authoritative "no past edits" rule against the
 * injected Kyiv {@code Clock} ({@link FutureOrPresent} is request-time convenience only and uses the
 * server default zone); non-overlap of {@code intervals} within the date.
 *
 * <p><b>2026-07-26 design — booking-conflict cancellation.</b> {@code cancelOverlapping} reverses
 * the former OQ-1 ("always allowed, never blocked by nor mutating existing bookings") rule for
 * {@code MasterScheduleService#upsertOverride}: when the intended override would leave one or more
 * {@code CONFIRMED} bookings without availability, {@code ScheduleOverrideConflictService} rejects
 * the write with a 409 unless {@code cancelOverlapping} is {@code true}, in which case every
 * conflicting booking is declined (reason {@code PROVIDER_UNAVAILABLE}). Defaults to {@code false}
 * ("no cancellation") so every pre-existing caller — including the convenience constructors below —
 * keeps its old, unconditional behaviour verbatim when no bookings are affected.
 *
 * <p><b>D2 REVISED 2026-07-26 (product decision reversal) — no master note on this path.</b> A
 * master taking a day-off/pause gives no reason: there is deliberately no {@code providerComment}
 * field here at all (the original "one optional master note" design was superseded the same day it
 * shipped). Every booking declined via this path carries {@code cancellationReason =
 * PROVIDER_UNAVAILABLE} and a {@code null} {@code providerComment} — see
 * {@code ScheduleOverrideConflictService#declineConflicts}. This is unrelated to, and does not
 * change, {@code providerComment} on the single-booking {@code /decline}/{@code /not-complete}
 * paths, which remain optional free text for every role (CLAUDE.md).
 *
 * <p><b>D6 (new) — no notification for this path.</b> A booking declined via a schedule-override
 * conflict enqueues nothing in the notification outbox; the client discovers the cancellation from
 * the booking's status alone. Notifying the client for this flow is deferred to a later phase — see
 * {@code AppointmentTransitionService#declineAppointmentItems} and
 * {@code BookingService#declineBookingForBatch}.
 */
public record ScheduleOverrideRequest(
        @NotNull(message = "Date is required")
        @FutureOrPresent(message = "Date must be today or in the future")
        LocalDate date,

        @NotNull(message = "Kind is required")
        ScheduleExceptionKind kind,

        // null = INTERVAL (default) for backward compatibility with pre-15.9 clients.
        WeekdayMode mode,

        // Required iff CUSTOM_HOURS + INTERVAL mode.
        @Valid
        @Size(max = 6, message = "An override may have at most 6 intervals")
        List<WorkIntervalDto> intervals,

        // Required iff CUSTOM_HOURS + EXPLICIT_TIMES mode (Phase 15.9).
        @Size(max = 24, message = "An override may have at most 24 discrete times")
        List<@NotNull(message = "A discrete time must not be null") LocalTime> times,

        // Defaults to false (see the convenience constructors) — "cancel whatever overlaps",
        // never a client-supplied id list (the server always re-computes conflicts server-side).
        // No accompanying note field — see the class javadoc's D2-REVISED section: a master takes
        // a day-off/pause without justification.
        boolean cancelOverlapping
) {

    /**
     * Pre-15.9 convenience constructor: an INTERVAL override (or DAY_OFF) with only intervals. Keeps the
     * backward-compatible call shape for callers (and the wire contract) that predate {@code mode}/{@code times}.
     * No cancellation is requested ({@code cancelOverlapping = false}).
     */
    public ScheduleOverrideRequest(LocalDate date, ScheduleExceptionKind kind, List<WorkIntervalDto> intervals) {
        this(date, kind, WeekdayMode.INTERVAL, intervals, null, false);
    }

    /**
     * Pre-2026-07-26 convenience constructor: the full {@code mode}/{@code intervals}/{@code times}
     * shape that predates the booking-conflict field. No cancellation is requested.
     */
    public ScheduleOverrideRequest(
            LocalDate date, ScheduleExceptionKind kind, WeekdayMode mode,
            List<WorkIntervalDto> intervals, List<LocalTime> times) {
        this(date, kind, mode, intervals, times, false);
    }

    /** The effective mode: an absent {@code mode} defaults to {@link WeekdayMode#INTERVAL}. */
    public WeekdayMode effectiveMode() {
        return mode != null ? mode : WeekdayMode.INTERVAL;
    }

    @AssertTrue(message =
            "DAY_OFF carries no intervals or times; CUSTOM_HOURS carries either intervals (INTERVAL) "
                    + "or a non-empty times list (EXPLICIT_TIMES), never both")
    public boolean isKindConsistent() {
        return isKindConsistent(kind, mode, intervals, times);
    }

    /**
     * Shared kind/mode consistency rule, extracted so {@link OverrideConflictQueryRequest} (the
     * 2026-07-26 conflict-preview request, which carries the identical {@code kind}/{@code mode}/
     * {@code intervals}/{@code times} shape) enforces the exact same invariant without copy-pasting
     * the {@code switch}. {@code kind == null} returns {@code true} — {@code @NotNull} on the
     * caller's own {@code kind} field reports that error instead.
     */
    public static boolean isKindConsistent(
            ScheduleExceptionKind kind, WeekdayMode mode, List<WorkIntervalDto> intervals, List<LocalTime> times) {
        if (kind == null) {
            return true;
        }
        boolean hasIntervals = intervals != null && !intervals.isEmpty();
        boolean hasTimes = times != null && !times.isEmpty();
        WeekdayMode effectiveMode = mode != null ? mode : WeekdayMode.INTERVAL;
        return switch (kind) {
            case DAY_OFF -> !hasIntervals && !hasTimes;
            case CUSTOM_HOURS -> switch (effectiveMode) {
                case INTERVAL -> hasIntervals && !hasTimes;
                case EXPLICIT_TIMES -> hasTimes && !hasIntervals;
            };
        };
    }
}
