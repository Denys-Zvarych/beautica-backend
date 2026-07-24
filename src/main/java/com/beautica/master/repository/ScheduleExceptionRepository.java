package com.beautica.master.repository;

import com.beautica.master.entity.ScheduleException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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
}
