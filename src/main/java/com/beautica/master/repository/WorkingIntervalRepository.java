package com.beautica.master.repository;

import com.beautica.master.entity.WorkingInterval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Phase 15.1: read access to a weekly schedule's working intervals.
 *
 * <p>Scoped by {@code scheduleId}, which is itself owned by a master — the service layer resolves
 * and authorizes the owning {@link com.beautica.master.entity.WeeklySchedule} before calling.
 */
public interface WorkingIntervalRepository extends JpaRepository<WorkingInterval, UUID> {

    List<WorkingInterval> findByScheduleIdAndDayOfWeek(UUID scheduleId, int dayOfWeek);

    List<WorkingInterval> findByScheduleId(UUID scheduleId);
}
