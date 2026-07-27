package com.beautica.master.entity;

import com.beautica.common.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "schedule_exceptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleException extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_id", nullable = false)
    private Master master;

    @Column(nullable = false)
    private LocalDate date;

    /**
     * Phase 15.1: DAY_OFF (closure, no reason, no intervals) vs CUSTOM_HOURS (override, has intervals).
     * Existing rows default to DAY_OFF (DB DEFAULT, V71). The former {@code reason} and {@code note}
     * fields were removed in V83 — a day-off carries neither.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ScheduleExceptionKind kind = ScheduleExceptionKind.DAY_OFF;

    /**
     * Phase 15.1: custom working intervals for a {@code CUSTOM_HOURS} exception; empty for {@code DAY_OFF}.
     */
    @OneToMany(
            mappedBy = "exception",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @Builder.Default
    private List<ScheduleExceptionInterval> intervals = new ArrayList<>();

    /**
     * Phase 15.9: discrete bookable start times for an {@link WeekdayMode#EXPLICIT_TIMES} per-date
     * {@code CUSTOM_HOURS} override; empty for {@code DAY_OFF} and for {@code INTERVAL} custom-hours.
     *
     * <p>Modelled as a {@link Set} (not a {@code List}) deliberately, exactly as
     * {@link WeeklySchedule#getDiscreteTimes()}: {@code intervals} is already a bag ({@code List}), and
     * Hibernate forbids fetch-joining two bags in one query ({@code MultipleBagFetchException}). The
     * override finders {@code LEFT JOIN FETCH} only {@code intervals}; this {@code Set} is hydrated by
     * {@code hibernate.default_batch_fetch_size} (no second fetch-join — avoids the cartesian product).
     * Mode exclusivity (an override is EITHER in {@code intervals} OR here) is a service-layer invariant.
     */
    @OneToMany(
            mappedBy = "exception",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @Builder.Default
    private Set<OverrideDiscreteTime> discreteTimes = new LinkedHashSet<>();

    /**
     * Phase 15.12: optional, <b>display-only</b> working-window start of a {@code CUSTOM_HOURS} override —
     * the counterpart of {@link WeeklyScheduleDayWindow} on the per-date surface. Because
     * {@code schedule_exceptions} already IS the "one day of a schedule" row, the bounds live as two columns
     * here rather than in a child table.
     *
     * <p><b>Not a source of availability.</b> The window carries no scheduling meaning: the interval list
     * ({@link #getIntervals()}) remains the single canonical source of bookable time.
     * {@code MasterScheduleService#resolveEffectiveDay} — and therefore
     * {@code com.beautica.booking.service.SlotCalculationService} — never reads these columns, so a stored
     * window can neither widen nor narrow a bookable slot.
     *
     * <p><b>Invariants</b> (DB CHECKs, V129): both bounds null or both set ({@code chk_exc_window_pair});
     * {@code windowEnd > windowStart} strictly, no midnight crossing ({@code chk_exc_window_order}); set only
     * on a {@code CUSTOM_HOURS} row ({@code chk_exc_window_kind}) — a {@code DAY_OFF} has no working window.
     * <b>Containment</b> (the window contains every interval of the date) is a service-layer invariant.
     */
    @Column(name = "window_start")
    private LocalTime windowStart;

    /** Phase 15.12: display-only working-window end — see {@link #getWindowStart()} for the full contract. */
    @Column(name = "window_end")
    private LocalTime windowEnd;
}
