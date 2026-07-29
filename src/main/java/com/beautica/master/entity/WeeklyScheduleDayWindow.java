package com.beautica.master.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;
import java.util.UUID;

/**
 * Phase 15.12: the optional, <b>display-only</b> working-window bounds of one {@link WeekdayMode#INTERVAL}
 * weekday within a {@link WeeklySchedule}. ISO {@code dayOfWeek} (1=Mon..7=Sun), mirroring
 * {@link WorkingInterval} and {@link DiscreteTime}.
 *
 * <p><b>Why this table exists.</b> The mobile editor models a day as ONE window (від–до) with breaks carved
 * out; only the resulting {@link WorkingInterval}s are stored, and the editor rebuilds breaks from the GAPS
 * BETWEEN consecutive intervals. A break flush against an edge of the window leaves no gap — window
 * 09:00–18:00 with a 09:00–10:00 break stores as the single interval {@code [10:00–18:00]} and reads back as
 * "window 10:00–18:00, no breaks". Recording the window lets the client derive breaks as
 * {@code window MINUS intervals}, which recovers edge-flush breaks with no second source of truth.
 *
 * <p><b>Not a source of availability.</b> These bounds carry no scheduling meaning whatsoever. The interval
 * list remains the single canonical source of bookable time: {@code MasterScheduleService#resolveEffectiveDay}
 * — and therefore {@code com.beautica.booking.service.SlotCalculationService} — reads
 * {@link WeeklySchedule#getIntervals()}/{@link WeeklySchedule#getDiscreteTimes()} only and never this
 * collection. A stored window can neither widen nor narrow a bookable slot (pinned by
 * {@code SlotCalculationScheduleIT#should_produceIdenticalSlots_when_workingWindowIsStored}).
 *
 * <p><b>Invariants.</b> {@code windowEnd > windowStart} strictly — no zero-length, no midnight crossing
 * (DB CHECK {@code chk_day_window_order}, matching the Phase 15.x locked no-cross-midnight contract). At most
 * one row per {@code (schedule, dayOfWeek)} (DB {@code uq_day_window_per_day}). <b>Containment</b> — the
 * window must contain every working interval of the same day — is a service-layer invariant
 * ({@code MasterScheduleService}); Postgres CHECK cannot reference the sibling {@code working_intervals}
 * table. A day with no intervals (day off) or an {@link WeekdayMode#EXPLICIT_TIMES} day never gets a row.
 */
// No `indexes = {...}` here, unlike the sibling WorkingInterval / DiscreteTime entities: V129 declares no
// standalone index on this table. The UNIQUE constraint uq_day_window_per_day is already backed by a btree
// on exactly (schedule_id, day_of_week), which serves every access shape this table has; a mirrored
// @Index would describe an index that does not exist. (`ddl-auto: validate` does not inspect indexes, so
// this is documentation parity, not a validation requirement.)
@Entity
@Table(name = "weekly_schedule_day_windows")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeeklyScheduleDayWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private WeeklySchedule schedule;

    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "window_start", nullable = false)
    private LocalTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalTime windowEnd;
}
