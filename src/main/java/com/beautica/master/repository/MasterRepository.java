package com.beautica.master.repository;

import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MasterRepository extends JpaRepository<Master, UUID> {

    Optional<Master> findByUserId(UUID userId);

    /**
     * Same as {@link #findByUserId} but also JOIN FETCH-es the {@code salon} association,
     * eliminating the extra {@code SELECT * FROM salons WHERE id = ?} fired when callers
     * dereference {@code master.getSalon().getId()} (MEDIUM F2+F3).
     *
     * <p>Do NOT use for {@link com.beautica.master.service.MasterService#getMasterByUserId}
     * — that cached method never dereferences {@code salon} and must keep its existing query
     * to avoid unnecessary join overhead.
     */
    @Query("""
            SELECT m FROM Master m
            LEFT JOIN FETCH m.salon
            WHERE m.user.id = :userId
            """)
    Optional<Master> findByUserIdWithSalon(@Param("userId") UUID userId);

    /** @deprecated No JOIN FETCH on user — triggers N+1. Use {@link #findBySalonIdAndIsActiveTrueWithUser} instead. */
    @Deprecated
    Page<Master> findBySalonIdAndIsActiveTrue(UUID salonId, Pageable pageable);

    @Query(
        value = "SELECT m FROM Master m JOIN FETCH m.user WHERE m.salon.id = :salonId AND m.isActive = true",
        countQuery = "SELECT COUNT(m) FROM Master m WHERE m.salon.id = :salonId AND m.isActive = true"
    )
    Page<Master> findBySalonIdAndIsActiveTrueWithUser(@Param("salonId") UUID salonId, Pageable pageable);

    boolean existsBySalonIdAndUserIdAndIsActiveTrue(UUID salonId, UUID userId);

    /**
     * Returns {@code true} iff the given user has an active master row of the given type in the
     * given salon. Used by the owner-as-master authorization fast-path (Phase 12.3) and by
     * {@code MasterService.createMasterForOwner} to perform the idempotent active-row check.
     */
    boolean existsByUserIdAndSalonIdAndMasterTypeAndIsActiveTrue(
            UUID userId, UUID salonId, MasterType masterType);

    boolean existsByIdAndSalonId(UUID id, UUID salonId);

    /**
     * Returns {@code true} if a master with the given {@code id} belongs to any of the
     * provided {@code salonIds}. Collapses the N-query ownership loop into a single
     * {@code WHERE id = ? AND salon_id IN (...)} existence check.
     */
    boolean existsByIdAndSalonIdIn(UUID id, Collection<UUID> salonIds);

    /**
     * Eagerly fetches the master together with its user, salon, and salon owner.
     * Used in authorization checks that run outside an active JPA session.
     */
    @Query("""
            SELECT m FROM Master m
            LEFT JOIN FETCH m.user
            LEFT JOIN FETCH m.salon s
            LEFT JOIN FETCH s.owner
            WHERE m.id = :masterId
            """)
    Optional<Master> findByIdWithSalonAndOwner(@Param("masterId") UUID masterId);

    /**
     * Re-computes and persists {@code masters.min_effective_price} for a single
     * master as a single UPDATE — eliminates the load/mutate/save round-trip.
     *
     * <p>The subquery mirrors {@code MIN(COALESCE(ms.price_override, sd.base_price))}
     * across active {@code master_services} rows with an active
     * {@code service_definitions} row. The result is {@code null} when the master
     * has no active services (semantically: no bookable price to display).
     *
     * <p>Must be called inside an existing {@code @Transactional} context — callers
     * in {@link com.beautica.service.service.ServiceCatalogService} satisfy this.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Master m
            SET m.minEffectivePrice = (
                SELECT MIN(COALESCE(ms.priceOverride, sd.basePrice))
                FROM MasterServiceAssignment ms
                JOIN ServiceDefinition sd ON ms.serviceDefinition.id = sd.id
                WHERE ms.master.id = :masterId
                  AND ms.isActive = true
                  AND sd.isActive = true
            )
            WHERE m.id = :masterId
            """)
    void refreshMinEffectivePrice(@Param("masterId") UUID masterId);

    /**
     * Bulk variant of {@link #refreshMinEffectivePrice} — collapses N individual UPDATE
     * round-trips into a single statement (Fix MEDIUM-6 PERF).
     *
     * <p>Replaces the {@code affectedMasterIds.forEach(masterRepository::refreshMinEffectivePrice)}
     * loop in {@code ServiceCatalogService.deactivateServiceDefinition}.
     *
     * <p>Must be called inside an existing {@code @Transactional} context.
     * {@code clearAutomatically = true} ensures the first-level cache is invalidated
     * after the bulk UPDATE so subsequent reads see the refreshed price.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Master m
            SET m.minEffectivePrice = (
                SELECT MIN(COALESCE(ms.priceOverride, sd.basePrice))
                FROM MasterServiceAssignment ms
                JOIN ServiceDefinition sd ON ms.serviceDefinition.id = sd.id
                WHERE ms.master.id = m.id
                  AND ms.isActive = true
                  AND sd.isActive = true
            )
            WHERE m.id IN :masterIds
            """)
    void refreshMinEffectivePriceForAll(@Param("masterIds") List<UUID> masterIds);
}
