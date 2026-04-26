package com.beautica.master.repository;

import com.beautica.master.entity.WorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkingHoursRepository extends JpaRepository<WorkingHours, UUID> {

    List<WorkingHours> findByMasterId(UUID masterId);

    List<WorkingHours> findByMasterIdAndIsActiveTrue(UUID masterId);

    Optional<WorkingHours> findByMasterIdAndDayOfWeek(UUID masterId, int dayOfWeek);

    void deleteAllByMasterId(UUID masterId);
}
