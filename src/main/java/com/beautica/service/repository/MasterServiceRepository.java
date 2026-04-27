package com.beautica.service.repository;

import com.beautica.service.entity.MasterServiceAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MasterServiceRepository extends JpaRepository<MasterServiceAssignment, UUID> {

    List<MasterServiceAssignment> findByMasterIdAndIsActiveTrue(UUID masterId);

    Optional<MasterServiceAssignment> findByMasterIdAndId(UUID masterId, UUID id);

    boolean existsByMasterIdAndServiceDefinitionId(UUID masterId, UUID serviceDefinitionId);
}
