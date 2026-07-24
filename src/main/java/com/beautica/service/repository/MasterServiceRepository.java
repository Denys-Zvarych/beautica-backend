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
     * Returns the distinct master IDs that have at least one assignment referencing
     * the given service definition. Used by {@link com.beautica.service.service.ServiceCatalogService}
     * to perform targeted per-key cache eviction when a service definition is deactivated,
     * avoiding the blanket {@code allEntries=true} thundering-herd amplifier.
     */
    @Query("SELECT DISTINCT a.master.id FROM MasterServiceAssignment a WHERE a.serviceDefinition.id = :serviceDefId")
    List<UUID> findMasterIdsByServiceDefinitionId(@Param("serviceDefId") UUID serviceDefId);

    /**
     * Returns true if the given master has at least one assignment whose linked service
     * definition is <em>also</em> active — i.e. at least one service visible in the
     * master's menu and the public browse.
     *
     * <p>Used by the first-time bulk-setup precondition: the bulk endpoint is valid only
     * when a master currently has zero active services. The {@code sd.isActive = true}
     * predicate mirrors {@link #findByMasterIdAndIsActiveTrueWithGraph} so a soft-deleted
     * definition does not count as an active service and does not block the first-time flow.
     */
    @Query("""
            SELECT COUNT(msa) > 0
            FROM MasterServiceAssignment msa
            WHERE msa.master.id = :masterId
              AND msa.isActive = true
              AND msa.serviceDefinition.isActive = true
            """)
    boolean existsActiveServiceForMaster(@Param("masterId") UUID masterId);

    /**
     * Acquires a transaction-scoped Postgres advisory lock keyed by the master id so that
     * concurrent first-time bulk-setup calls for the same master serialize.
     *
     * <p>Phase 16.x (TOCTOU): {@link #existsActiveServiceForMaster} is a read-then-write
     * check with no DB-level unique/idempotency backstop, so two concurrent bulk POSTs from
     * the same principal could both pass the "no active services" precondition and both
     * commit, doubling the master's menu. Taking this lock before the precondition (re-)check
     * — inside the same {@code @Transactional} — forces the second caller to wait until the
     * first commits, after which its re-check sees the now-existing services and rejects (409).
     *
     * <p>The lock is held until the surrounding transaction commits or rolls back
     * ({@code pg_advisory_xact_lock} — no manual unlock needed). Mirrors the booking
     * overlap-guard lock in {@code BookingRepository#acquireAdvisoryLock}.
     *
     * <p>Hash collision risk: {@code hashtextextended} produces a 64-bit hash of the UUID
     * text. Birthday-paradox probability is negligible for current master counts; a collision
     * would only cause two unrelated masters' bulk setups to serialize, never a correctness
     * bug.
     */
    @Query(value = """
            SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(CAST(:masterId AS text), 0))) sub
            """, nativeQuery = true)
    Integer acquireBulkSetupLock(@Param("masterId") UUID masterId);

    /**
     * Booking-selection candidates (Phase 23.x): the active {@link MasterServiceAssignment}s for
     * {@code serviceDefId} belonging to active masters of {@code salonId}. Returns the assignment
     * (not the bare master) so both the master's display fields and the assignment id
     * ({@code msa.id} — the {@code masterServiceId} the downstream slot/booking calls require)
     * are available in one round-trip, letting the mobile client drop its per-master
     * {@code getMasterServices} fan-out.
     *
     * <p>{@code JOIN FETCH msa.master m} and {@code JOIN FETCH m.user} initialise everything
     * {@code BookableMasterResponse#from} reads (Anti-Bug §E — no N+1 / lazy load outside the
     * transaction). The DB {@code UNIQUE (master_id, service_def_id)} constraint (V7) guarantees
     * at most one row per (master, service), so no {@code DISTINCT} is needed.
     *
     * <p><strong>Bookability is intentionally NOT filtered here.</strong> Whether a candidate has a
     * free future slot is resolved in-memory by
     * {@code com.beautica.booking.service.BookingMasterService}, which delegates to
     * {@code SlotCalculationService.hasBookableFutureSlot} — the shared free-slot verdict over the
     * same effective-day resolver and slot subtraction the salon catalogue and slot picker use — so
     * the master list, catalogue, and slot picker can never disagree (see that class's Javadoc).
     */
    @Query("""
            SELECT msa FROM MasterServiceAssignment msa
            JOIN FETCH msa.master m
            JOIN FETCH m.user
            WHERE m.salon.id = :salonId
              AND m.isActive = true
              AND msa.serviceDefinition.id = :serviceDefId
              AND msa.isActive = true
            """)
    List<MasterServiceAssignment> findBookableAssignmentsBySalonAndServiceDef(
            @Param("salonId") UUID salonId,
            @Param("serviceDefId") UUID serviceDefId);

    /**
     * Catalogue candidates (Phase 23.x — CRITICAL free-slot fix): every active
     * {@link MasterServiceAssignment} of an active master belonging to {@code salonId}, whose linked
     * service definition is an active SALON-owned service of the same salon. Returns the assignment
     * (not the bare definition) so the caller has, per row, both the {@code master} (to group by and
     * resolve a schedule/booking window once per master) and the effective
     * duration/{@code serviceDefinition} needed by the {@code SlotCalculationService} free-slot gate.
     *
     * <p>{@code JOIN FETCH msa.serviceDefinition sd} + {@code LEFT JOIN FETCH sd.serviceType} + the
     * {@code sd.serviceType} hop initialise everything {@code ServiceDefinitionResponse#from} reads,
     * and {@code JOIN FETCH msa.master m} initialises the master used to group the batched free-slot
     * check (Anti-Bug §E — no N+1 / lazy load outside the transaction). One row per (master, def)
     * assignment: the caller de-duplicates to distinct bookable definitions after the free-slot gate.
     *
     * <p>The {@code m.salon.id = :salonId} + {@code sd.ownerId = :salonId} predicates jointly close the
     * rotated-master and cross-salon leaks (a master who left the salon, or an assignment pointing at a
     * definition owned by a different salon, never contributes). Bounded by the salon's own
     * roster × menu (not caller-supplied), mirroring {@link #findBookableAssignmentsBySalonAndServiceDef}.
     */
    @Query("""
            SELECT msa FROM MasterServiceAssignment msa
            JOIN FETCH msa.serviceDefinition sd
            LEFT JOIN FETCH sd.serviceType
            JOIN FETCH msa.master m
            WHERE m.salon.id = :salonId
              AND m.isActive = true
              AND msa.isActive = true
              AND sd.ownerType = com.beautica.service.entity.OwnerType.SALON
              AND sd.ownerId = :salonId
              AND sd.isActive = true
            """)
    List<MasterServiceAssignment> findBookableAssignmentsBySalon(@Param("salonId") UUID salonId);
}
