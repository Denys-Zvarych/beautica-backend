package com.beautica.master.repository;

import com.beautica.master.entity.WorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkingHoursRepository extends JpaRepository<WorkingHours, UUID> {

    /**
     * Fetches ALL rows for a master, including inactive ones. This is the correct finder for the
     * upsert merge map: the DB unique key is {@code (master_id, day_of_week)} regardless of
     * {@code is_active} (see V4__Patch_salons_add_masters.sql), so the merge must match an existing
     * inactive row by weekday and UPDATE it in place rather than INSERT a duplicate (23505).
     * Read/display paths must instead use {@link #findByMasterIdAndIsActiveTrue} so inactive days
     * are not surfaced.
     */
    List<WorkingHours> findByMasterId(UUID masterId);

    List<WorkingHours> findByMasterIdAndIsActiveTrue(UUID masterId);

    Optional<WorkingHours> findByMasterIdAndDayOfWeek(UUID masterId, int dayOfWeek);

    void deleteAllByMasterId(UUID masterId);
}
