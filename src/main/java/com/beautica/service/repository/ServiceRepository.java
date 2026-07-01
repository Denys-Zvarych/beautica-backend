package com.beautica.service.repository;

import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceDefinition;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRepository extends JpaRepository<ServiceDefinition, UUID> {

    /**
     * @deprecated Unbounded result — add a Pageable overload before using.
     */
    @Deprecated
    List<ServiceDefinition> findByOwnerTypeAndOwnerIdAndIsActiveTrue(OwnerType ownerType, UUID ownerId);

    /**
     * Resolves the owner's user UUID in a single query, avoiding the two-query
     * chain (load ServiceDefinition + load Salon or Master) previously used in
     * AuthorizationService.canManageServiceDefinition.
     *
     * Returns empty when no ServiceDefinition with the given id exists.
     */
    @Query("""
            SELECT CASE sd.ownerType
                WHEN 'SALON' THEN s.owner.id
                ELSE m.user.id
            END
            FROM ServiceDefinition sd
            LEFT JOIN Salon s ON s.id = sd.ownerId AND sd.ownerType = com.beautica.service.entity.OwnerType.SALON
            LEFT JOIN Master m ON m.id = sd.ownerId AND sd.ownerType = com.beautica.service.entity.OwnerType.INDEPENDENT_MASTER
            WHERE sd.id = :serviceDefId
            """)
    Optional<UUID> findOwnerUserId(@Param("serviceDefId") UUID serviceDefId);

    /**
     * Loads a ServiceDefinition together with its serviceType in a single JOIN FETCH
     * query so that callers that need both (e.g. PATCH endpoints that return
     * ServiceDefinitionResponse) do not trigger a second SELECT for the lazy association.
     *
     * Prefer this over {@code findById} in any write method whose response DTO accesses
     * serviceType fields.
     */
    @Query("""
            SELECT sd FROM ServiceDefinition sd
            LEFT JOIN FETCH sd.serviceType
            WHERE sd.id = :id
            """)
    Optional<ServiceDefinition> findByIdWithServiceType(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE ServiceDefinition sd SET sd.isActive = false WHERE sd.id = :id")
    int deactivateById(@Param("id") UUID id);

    /**
     * A salon's bookable catalog for its public profile (Phase 13.6): active
     * {@link ServiceDefinition}s owned by the salon that have at least one active
     * {@link com.beautica.service.entity.MasterServiceAssignment} on an active master.
     *
     * <p>Deliberately NOT a union/dedup across per-master price overrides — this returns
     * one row per distinct {@link ServiceDefinition}, which is what the salon-wide catalog
     * groups by category. {@code EXISTS} against {@code MasterServiceAssignment} is the
     * "bookable" gate: a salon-owned service with zero active master assignments (or whose
     * only assigned masters are deactivated) must not appear on the public profile, since a
     * client could never actually book it.
     *
     * <p>{@code LEFT JOIN FETCH sd.serviceType} avoids the N+1 that
     * {@link ServiceDefinitionResponse#from} would otherwise trigger (it reads
     * {@code sd.getServiceType()}). Bounded by {@code pageable} (soft cap — see the caller)
     * rather than an unbounded {@code List}, mirroring {@code MasterServiceRepository
     * #findByMasterIdAndIsActiveTrueWithGraph}'s same pattern.
     */
    @Query("""
            SELECT DISTINCT sd FROM ServiceDefinition sd
            LEFT JOIN FETCH sd.serviceType
            WHERE sd.ownerType = com.beautica.service.entity.OwnerType.SALON
              AND sd.ownerId = :salonId
              AND sd.isActive = true
              AND EXISTS (
                  SELECT 1 FROM MasterServiceAssignment msa
                  WHERE msa.serviceDefinition = sd
                    AND msa.isActive = true
                    AND msa.master.isActive = true
              )
            ORDER BY sd.category ASC, sd.name ASC
            """)
    List<ServiceDefinition> findBookableServicesBySalon(
            @Param("salonId") UUID salonId,
            Pageable pageable);
}
