package com.beautica.master.repository;

import com.beautica.master.entity.Master;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MasterRepository extends JpaRepository<Master, UUID> {

    Optional<Master> findByUserId(UUID userId);

    List<Master> findBySalonId(UUID salonId);

    Page<Master> findBySalonIdAndIsActiveTrue(UUID salonId, Pageable pageable);
}
