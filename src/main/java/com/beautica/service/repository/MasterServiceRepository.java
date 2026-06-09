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
            JOIN FETCH ms.master
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

    /**
     * Returns a master's active service assignments whose linked service definition is
     * <em>also</em> active.
     *
     * <p>The {@code sd.isActive = true} predicate is essential: soft-deleting a service
     * (DELETE → {@code deactivateServiceDefinition}) sets the {@link ServiceDefinition}'s
     * {@code isActive=false} but leaves the {@link MasterServiceAssignment} row active.
     * Without this predicate, a deactivated definition would keep appearing in the
     * master's list and in the public client browse. Filtering on both flags makes a
     * soft-deleted definition disappear from every consumer of this query while
     * preserving booking history (the assignment row itself is untouched).
     */
    @Query("""
            SELECT msa FROM MasterServiceAssignment msa
            JOIN FETCH msa.serviceDefinition sd
            LEFT JOIN FETCH sd.serviceType
            JOIN FETCH msa.master
            WHERE msa.master.id = :masterId
              AND msa.isActive = true
              AND sd.isActive = true
            ORDER BY msa.createdAt ASC, msa.id ASC
            """)
    List<MasterServiceAssignment> findByMasterIdAndIsActiveTrueWithGraph(
            @Param("masterId") UUID masterId,
            Pageable pageable);

    /**
     * Owner-scoped variant of {@link #findByMasterIdAndIsActiveTrueWithGraph} (Phase 16.9
     * Part 2): returns the master's own services <em>including drafts</em>.
     *
     * <p>Unlike the public query, the {@code msa.isActive = true} assignment filter is
     * dropped and the definition predicate is widened to {@code sd.isActive = true OR
     * sd.isDraft = true}. This is required because Phase 16.9 auto-creates a draft
     * {@link ServiceDefinition} ({@code is_draft = true}, {@code is_active = false}) together
     * with an <em>inactive</em> {@link MasterServiceAssignment}; the public query would hide
     * both. Soft-deleted non-draft rows ({@code is_active = false AND is_draft = false}) stay
     * excluded, matching the public list's soft-delete semantics.
     *
     * <p><strong>Owner-scoping:</strong> {@code masterId} must be the authenticated caller's
     * own master id (derived from the principal), never a client-supplied path/query param —
     * the service layer enforces this before calling. A master must not read another master's
     * drafts.
     *
     * <p>Graph fetches {@code serviceDefinition}, its {@code serviceType}, and {@code master}
     * so {@link com.beautica.service.dto.MasterServiceResponse#from} can traverse both hops
     * without an N+1 or {@code LazyInitializationException}.
     */
    @Query("""
            SELECT msa FROM MasterServiceAssignment msa
            JOIN FETCH msa.serviceDefinition sd
            LEFT JOIN FETCH sd.serviceType
            JOIN FETCH msa.master
            WHERE msa.master.id = :masterId
              AND (sd.isActive = true OR sd.isDraft = true)
            ORDER BY msa.createdAt ASC, msa.id ASC
            """)
    List<MasterServiceAssignment> findOwnedIncludingDraftsWithGraph(
            @Param("masterId") UUID masterId,
            Pageable pageable);

    /**
     * Returns the distinct master IDs that have at least one assignment referencing
     * the given service definition. Used by {@link com.beautica.service.service.ServiceCatalogService}
     * to perform targeted per-key cache eviction when a service definition is deactivated,
     * avoiding the blanket {@code allEntries=true} thundering-herd amplifier.
     */
    @Query("SELECT DISTINCT a.master.id FROM MasterServiceAssignment a WHERE a.serviceDefinition.id = :serviceDefId")
    List<UUID> findMasterIdsByServiceDefinitionId(@Param("serviceDefId") UUID serviceDefId);

    /**
     * Secondary idempotency guard for {@code ServiceTypePromotionService} (Phase 16.9.1):
     * true when the given master already owns a draft assignment whose linked service
     * definition carries the same {@code name} + {@code category} and {@code isDraft = true}.
     *
     * <p>The primary guard is the cleared single-use token (a replayed approve never
     * re-enters the promote branch). This existence check is defense-in-depth so a future
     * abnormal double-invocation of {@code promote} does not persist a duplicate draft.
     */
    @Query("""
            SELECT COUNT(a) > 0
            FROM MasterServiceAssignment a
            JOIN a.serviceDefinition sd
            WHERE a.master.id = :masterId
              AND sd.name = :name
              AND sd.category = :category
              AND sd.isDraft = true
            """)
    boolean existsDraftAssignment(
            @Param("masterId") UUID masterId,
            @Param("name") String name,
            @Param("category") String category);
}
