package com.beautica.master.repository;

import com.beautica.master.entity.ScheduleException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleExceptionRepository extends JpaRepository<ScheduleException, UUID> {

    Optional<ScheduleException> findByMasterIdAndDate(UUID masterId, LocalDate date);

    List<ScheduleException> findByMasterIdAndDateBetween(UUID masterId, LocalDate from, LocalDate to);

    /**
     * Phase 15.4: the single override for {@code (master, date)} with its {@code CUSTOM_HOURS}
     * intervals graph-fetched, used by the override upsert (replace-intervals path) and the
     * single-date resolver so {@code exception.getIntervals()} does not lazy-load.
     */
    @Query("""
            SELECT se FROM ScheduleException se
            LEFT JOIN FETCH se.intervals
            WHERE se.master.id = :masterId
              AND se.date = :date
            """)
    Optional<ScheduleException> findByMasterIdAndDateWithIntervals(
            @Param("masterId") UUID masterId, @Param("date") LocalDate date);

    /**
     * Phase 15.4: every override for a master within {@code [from, to]} inclusive, with intervals
     * graph-fetched, used by the effective-availability range resolver to load all overrides for a
     * window in a single query (no per-date round-trip / N+1 — Anti-Bug §E). {@code DISTINCT}
     * de-duplicates the fetch-join row multiplication.
     */
    @Query("""
            SELECT DISTINCT se FROM ScheduleException se
            LEFT JOIN FETCH se.intervals
            WHERE se.master.id = :masterId
              AND se.date BETWEEN :from AND :to
            """)
    List<ScheduleException> findByMasterIdAndDateBetweenWithIntervals(
            @Param("masterId") UUID masterId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Phase 315 (D1/D2/D4) — the multi-master sibling of
     * {@link #findByMasterIdAndDateBetweenWithIntervals}, used ONLY by {@code MasterScheduleService}'s
     * batched loader (the shared private loading step behind both {@code foldRange} — the
     * single-master case — and {@code resolveEffectiveRangeBatch}), so a salon-catalogue read resolves
     * every master's overrides in ONE statement instead of one per master.
     *
     * <p>{@code ORDER BY se.master.id, se.date ASC} (D4): the fold's consumer is
     * {@code Collectors.toMap(ScheduleException::getDate, e -> e, (a, b) -> a)}, which keeps the FIRST
     * override on a duplicate {@code (master, date)} key. A DB unique constraint should make that
     * duplicate impossible in practice, but the ordering is asserted explicitly rather than left to
     * incidental row order, for the same reason the sibling {@code WeeklySchedule} query is — batching
     * interleaves rows across masters, and an unordered query gives no guarantee about which duplicate
     * physically arrives first. The single-master finder above is intentionally left untouched.
     */
    @Query("""
            SELECT DISTINCT se FROM ScheduleException se
            LEFT JOIN FETCH se.intervals
            WHERE se.master.id IN :masterIds
              AND se.date BETWEEN :from AND :to
            ORDER BY se.master.id, se.date ASC
            """)
    List<ScheduleException> findByMasterIdsAndDateBetweenWithIntervals(
            @Param("masterIds") Collection<UUID> masterIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
