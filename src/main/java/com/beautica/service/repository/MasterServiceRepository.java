package com.beautica.service.repository;

import com.beautica.service.entity.MasterServiceAssignment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MasterServiceRepository extends JpaRepository<MasterServiceAssignment, UUID> {

    /**
     * @deprecated No JOIN FETCH — accessing serviceDefinition will cause a lazy load or
     *             LazyInitializationException outside a transaction.
     *             Use {@link #findByMasterIdAndIdWithGraph} instead.
     */
    @Deprecated(since = "phase-3", forRemoval = false)
    Optional<MasterServiceAssignment> findByMasterIdAndId(UUID masterId, UUID id);

    @Query("""
            SELECT ms FROM MasterServiceAssignment ms
            LEFT JOIN FETCH ms.serviceDefinition
            WHERE ms.master.id = :masterId AND ms.id = :id
            """)
    Optional<MasterServiceAssignment> findByMasterIdAndIdWithGraph(
            @Param("masterId") UUID masterId,
            @Param("id") UUID id);

    boolean existsByMasterIdAndServiceDefinitionId(UUID masterId, UUID serviceDefinitionId);

    /**
     * Returns true if any active master service assignment uses the given service definition
     * and belongs to a master in one of the provided salons.
     *
     * <p>Used by {@code DashboardService} to validate that a {@code serviceDefId} filter
     * belongs to the SALON_OWNER's scope before binding it to the revenue query (FIX 3).
     */
    @Query("""
            SELECT COUNT(msa) > 0
            FROM MasterServiceAssignment msa
            WHERE msa.serviceDefinition.id = :serviceDefId
              AND msa.master.salon.id IN :salonIds
              AND msa.isActive = true
            """)
    boolean existsByServiceDefIdAndSalonIdIn(
            @Param("serviceDefId") UUID serviceDefId,
            @Param("salonIds") List<UUID> salonIds);

    /**
     * Returns true if any active master service assignment uses the given service definition
     * and belongs to the given master.
     *
     * <p>Used by {@code DashboardService} to validate that a {@code serviceDefId} filter
     * belongs to the INDEPENDENT_MASTER's scope (FIX 3).
     */
    @Query("""
            SELECT COUNT(msa) > 0
            FROM MasterServiceAssignment msa
            WHERE msa.serviceDefinition.id = :serviceDefId
              AND msa.master.id = :masterId
              AND msa.isActive = true
            """)
    boolean existsByServiceDefIdAndMasterId(
            @Param("serviceDefId") UUID serviceDefId,
            @Param("masterId") UUID masterId);

    @Query("""
            SELECT msa FROM MasterServiceAssignment msa
            JOIN FETCH msa.serviceDefinition sd
            LEFT JOIN FETCH sd.serviceType
            JOIN FETCH msa.master
            WHERE msa.master.id = :masterId AND msa.isActive = true
            ORDER BY msa.id
            """)
    List<MasterServiceAssignment> findByMasterIdAndIsActiveTrueWithGraph(
            @Param("masterId") UUID masterId,
            Pageable pageable);
}
