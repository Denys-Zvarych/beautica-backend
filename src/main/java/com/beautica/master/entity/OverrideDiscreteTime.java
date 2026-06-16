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
 * Phase 15.9: one discrete bookable start time of an {@link WeekdayMode#EXPLICIT_TIMES} per-date
 * {@code CUSTOM_HOURS} override (a {@link ScheduleException}).
 *
 * <p>Mirrors {@link DiscreteTime} (the weekly-template variant) but is date-scoped via its parent
 * {@link ScheduleException}, so it carries no {@code dayOfWeek}. A row is a POINT (a single wall-clock
 * {@code slotTime}), not a span — so the no-cross-midnight interval contract holds by construction. No
 * per-slot duration is stored (that is a future booking concern). De-duplication of
 * {@code (exception, slotTime)} is enforced at the DB layer ({@code uq_schedule_exception_times_no_dup}).
 */
@Entity
@Table(
        name = "schedule_exception_times",
        indexes = {
                // Mirrors idx_schedule_exception_times_exception (V85).
                @Index(name = "idx_schedule_exception_times_exception", columnList = "exception_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OverrideDiscreteTime {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exception_id", nullable = false)
    private ScheduleException exception;

    @Column(name = "slot_time", nullable = false)
    private LocalTime slotTime;
}
