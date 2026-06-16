package com.beautica.master.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * Phase 15.8: one discrete bookable start time of an {@link WeekdayMode#EXPLICIT_TIMES} weekday within a
 * {@link WeeklySchedule}.
 *
 * <p>{@code dayOfWeek} is ISO (1=Mon..7=Sun), mirroring {@link WorkingInterval}. A row is a POINT (a single
 * wall-clock {@code slotTime}), not a span — so the no-cross-midnight interval contract holds by
 * construction. No per-slot duration is stored (that is a future booking concern). De-duplication of
 * {@code (schedule, dayOfWeek, slotTime)} is enforced at the DB layer ({@code uq_working_interval_times_no_dup}).
 */
@Entity
@Table(
        name = "working_interval_times",
        indexes = {
                // Mirrors idx_working_interval_times_schedule_day (V84).
                @Index(name = "idx_working_interval_times_schedule_day", columnList = "schedule_id, day_of_week")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscreteTime {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private WeeklySchedule schedule;

    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "slot_time", nullable = false)
    private LocalTime slotTime;
}
