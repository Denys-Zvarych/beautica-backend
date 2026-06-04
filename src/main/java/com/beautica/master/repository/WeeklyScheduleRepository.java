package com.beautica.master.repository;

import com.beautica.common.DateRange;
import com.beautica.master.entity.WeeklySchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 15.1: read access to a master's weekly templates.
 *
 * <p>NOTE: finders are unscoped — they trust the caller-supplied {@code masterId}. The service layer
 * must enforce ownership before calling (a master may only read/write their own schedules).
 */
public interface WeeklyScheduleRepository extends JpaRepository<WeeklySchedule, UUID> {

    /**
     * Schedules whose active window covers {@code date}. {@code validTo IS NULL} = open-ended,
     * so it always matches on the upper bound. May return more than one row if the service layer's
     * non-overlap invariant has not yet been enforced (Phase 15.4); callers decide precedence.
     */
    @Query("""
            SELECT ws FROM WeeklySchedule ws
            WHERE ws.master.id = :masterId
              AND ws.validFrom <= :date
              AND (ws.validTo IS NULL OR ws.validTo >= :date)
            """)
    List<WeeklySchedule> findCoveringDate(@Param("masterId") UUID masterId, @Param("date") LocalDate date);

    /**
     * Non-graph variant: used only by the window-overlap invariant check, which reads
     * {@code validFrom}/{@code validTo} alone and never traverses {@code intervals} — so no
     * N+1 hazard (Anti-Bug §E exemption documented here). The list-read endpoint uses the
     * graph variant {@link #findByMasterIdOrderByValidFromAscWithIntervals} instead.
     */
    List<WeeklySchedule> findByMasterIdOrderByValidFromAsc(UUID masterId);

    /**
     * Phase 15.5: a master's weekly templates ordered by {@code validFrom}, with
     * {@code working_intervals} graph-fetched, for the {@code GET /weekly-schedules} list endpoint
     * (the {@link com.beautica.master.service.ScheduleMapper} traverses {@code getIntervals()}).
     * {@code DISTINCT} de-duplicates the fetch-join row multiplication.
     */
    @Query("""
            SELECT DISTINCT ws FROM WeeklySchedule ws
            LEFT JOIN FETCH ws.intervals
            WHERE ws.master.id = :masterId
            ORDER BY ws.validFrom ASC
            """)
    List<WeeklySchedule> findByMasterIdOrderByValidFromAscWithIntervals(@Param("masterId") UUID masterId);

    /**
     * Phase 15.4: every schedule for a master whose validity window <em>overlaps</em> {@code [from, to]},
     * with {@code working_intervals} eagerly graph-fetched. Used by the effective-availability range
     * resolver to load the covering template(s) for an entire window in a single query (no per-date
     * round-trip / N+1 — Anti-Bug §E). Overlap test mirrors {@link DateRange#overlaps}: a window matches
     * when {@code validFrom <= to} and ({@code validTo IS NULL} OR {@code validTo >= from}).
     *
     * <p>{@code LEFT JOIN FETCH} the intervals so the resolver's {@code schedule.getIntervals()} traversal
     * does not lazy-load. {@code DISTINCT} de-duplicates the row-multiplication the fetch join causes.
     */
    @Query("""
            SELECT DISTINCT ws FROM WeeklySchedule ws
            LEFT JOIN FETCH ws.intervals
            WHERE ws.master.id = :masterId
              AND ws.validFrom <= :to
              AND (ws.validTo IS NULL OR ws.validTo >= :from)
            """)
    List<WeeklySchedule> findOverlappingRangeWithIntervals(
            @Param("masterId") UUID masterId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Phase 15.4: a single schedule by id, with its {@code working_intervals} graph-fetched, used by the
     * edit path of {@code upsertWeeklySchedule} so interval replacement happens on an initialized
     * collection without a lazy round-trip. The owning {@code master} is also fetched so the service can
     * authorize ownership in-memory.
     */
    @Query("""
            SELECT ws FROM WeeklySchedule ws
            LEFT JOIN FETCH ws.intervals
            JOIN FETCH ws.master
            WHERE ws.id = :scheduleId
            """)
    Optional<WeeklySchedule> findByIdWithIntervals(@Param("scheduleId") UUID scheduleId);
}
