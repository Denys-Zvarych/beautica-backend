package com.beautica.master.repository;

import com.beautica.master.entity.ScheduleException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleExceptionRepository extends JpaRepository<ScheduleException, UUID> {

    Optional<ScheduleException> findByMasterIdAndDate(UUID masterId, LocalDate date);

    List<ScheduleException> findByMasterIdAndDateBetween(UUID masterId, LocalDate from, LocalDate to);
}
