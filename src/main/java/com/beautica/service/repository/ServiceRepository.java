package com.beautica.service.repository;

import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRepository extends JpaRepository<ServiceDefinition, UUID> {

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
}
