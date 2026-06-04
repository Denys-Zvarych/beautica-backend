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
 * Phase 15.1: one canonical working interval within a {@link WeeklySchedule}.
 *
 * <p>{@code dayOfWeek} is ISO (1=Mon..7=Sun). Wall-clock times; single-calendar-day windows only
 * ({@code endTime > startTime}, no midnight crossing — DB CHECK {@code chk_interval_order}).
 * Non-overlap on the same {@code (schedule, dayOfWeek)} is enforced in the service layer (Phase 15.4).
 */
@Entity
@Table(
        name = "working_intervals",
        indexes = {
                // Mirrors idx_working_intervals_schedule_day (V70).
                @Index(name = "idx_working_intervals_schedule_day", columnList = "schedule_id, day_of_week")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkingInterval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private WeeklySchedule schedule;

    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
}
