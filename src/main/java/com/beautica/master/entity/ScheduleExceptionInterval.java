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
 * Phase 15.1: one working interval for a {@code CUSTOM_HOURS} {@link ScheduleException}.
 *
 * <p>Wall-clock times; single-calendar-day windows only ({@code endTime > startTime}, DB CHECK
 * {@code chk_exc_interval_order}). Only present when the parent exception's
 * {@link ScheduleExceptionKind} is {@link ScheduleExceptionKind#CUSTOM_HOURS}.
 */
@Entity
@Table(
        name = "schedule_exception_intervals",
        indexes = {
                // Mirrors idx_exc_intervals_exception (V71).
                @Index(name = "idx_exc_intervals_exception", columnList = "exception_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleExceptionInterval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exception_id", nullable = false)
    private ScheduleException exception;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
}
