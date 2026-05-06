package com.beautica.service.repository;

import com.beautica.service.entity.MasterServiceAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MasterServiceRepository extends JpaRepository<MasterServiceAssignment, UUID> {

    List<MasterServiceAssignment> findByMasterIdAndIsActiveTrue(UUID masterId);

    Optional<MasterServiceAssignment> findByMasterIdAndId(UUID masterId, UUID id);

    boolean existsByMasterIdAndServiceDefinitionId(UUID masterId, UUID serviceDefinitionId);

    @Query("""
            SELECT msa FROM MasterServiceAssignment msa
            JOIN FETCH msa.serviceDefinition
            JOIN FETCH msa.master
            WHERE msa.master.id = :masterId AND msa.isActive = true
            """)
    List<MasterServiceAssignment> findByMasterIdAndIsActiveTrueWithGraph(@Param("masterId") UUID masterId);
}
