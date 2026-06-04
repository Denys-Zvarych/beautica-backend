package com.beautica.master.repository;

import com.beautica.master.entity.WeeklySchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
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

    List<WeeklySchedule> findByMasterIdOrderByValidFromAsc(UUID masterId);
}
