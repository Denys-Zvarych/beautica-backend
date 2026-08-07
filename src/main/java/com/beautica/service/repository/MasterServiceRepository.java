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

    /**
     * <p>{@code LEFT JOIN FETCH m.salon} (2026-08 re-audit) initialises the salon that
     * {@link com.beautica.booking.domain.MasterBookability#isBookable} dereferences in
     * {@code SlotCalculationService}, so the salon-active gate costs no lazy load. It MUST stay
     * {@code LEFT}: an {@code INDEPENDENT_MASTER} has no salon and an INNER join would drop every
     * one of them from the slot and booking paths. Both fetches are to-one — they add columns, not
     * rows, so this single-row lookup carries no cartesian risk.
     */
    @Query("""
            SELECT ms FROM MasterServiceAssignment ms
            LEFT JOIN FETCH ms.serviceDefinition
            JOIN FETCH ms.master m
            LEFT JOIN FETCH m.salon
            WHERE m.id = :masterId AND ms.id = :id
            """)
    Optional<MasterServiceAssignment> findByMasterIdAndIdWithGraph(
            @Param("masterId") UUID masterId,
            @Param("id") UUID id);

    /**
     * Loads one assignment by its own id with {@code serviceDefinition} and {@code master}
     * initialised — the master-unscoped counterpart of
     * {@link #findByMasterIdAndIdWithGraph} (Phase 31.3).
     *
     * <p><b>Unscoped by design (§E-4).</b> A {@code SERVICE} favourite is looked up by
     * {@code master_services.id} alone: the favouriting CLIENT is not the owner of the row and
     * has no {@code masterId} to scope by. There is nothing to enforce — the row is public
     * catalogue data already served by {@code GET /masters/&#123;id&#125;/services} — but do not
     * reuse this finder on a provider write path, where ownership scoping IS required.
     *
     * <p>All THREE fetches are load-bearing for {@code FavoriteService.validateServiceTarget},
     * which reads {@code msa.isActive()}, {@code msa.getServiceDefinition().isActive()},
     * {@code msa.getMaster().isActive()} and — since the 2026-08 security re-audit —
     * {@code msa.getMaster().getSalon()}. {@code Master.salon} is {@code FetchType.LAZY}, so
     * without the {@code LEFT JOIN FETCH} the salon-active guard would fire an extra SELECT per
     * validation; with it the whole check stays a single statement.
     *
     * <p>The salon fetch is a {@code LEFT} join specifically: an {@code INDEPENDENT_MASTER} has
     * {@code salon_id = NULL}, and an inner {@code JOIN FETCH} would make this finder return
     * empty for every independent master's service — turning a valid wish-list target into a 404.
     */
    @Query("""
            SELECT msa FROM MasterServiceAssignment msa
            JOIN FETCH msa.serviceDefinition
            JOIN FETCH msa.master m
            LEFT JOIN FETCH m.salon
            WHERE msa.id = :id
            """)
    Optional<MasterServiceAssignment> findByIdWithServiceDefinitionAndMaster(@Param("id") UUID id);

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
     * <p>The {@code sd.isActive = true} predicate mirrors
     * {@link #findByMasterIdAndIsActiveTrueWithGraph} so a soft-deleted definition does not
     * count as an active service.
     *
     * <p><b>No production caller — RETAINED DELIBERATELY. Do not delete as "dead code".</b> This
     * backed the old "bulk setup is first-time only" precondition, removed when bulk create became
     * additive. It is kept because it is the SUBJECT of the regression pins that assert the
     * precondition stays gone:
     * {@code ServiceCatalogServiceBulkCreateTest#should_createBatch_when_salonMasterAlreadyHasActiveServices}
     * and {@code #should_createBatch_when_masterAlreadyHasActiveServices} both assert
     * {@code verify(masterServiceRepository, never()).existsActiveServiceForMaster(any())}. Those
     * verifications name this method, so deleting it does not merely delete a query — it deletes
     * the guard, and the precondition could be reintroduced with nothing failing. The method's own
     * repository tests ({@code MasterServiceRepositoryTest}) keep the JPQL itself honest so the
     * pins verify a method that still works.
     *
     * <p>Remove it only together with a replacement guard that proves the same thing without
     * naming the method (there is currently no such guard), never as an interface tidy-up.
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
     * concurrent bulk service-create calls for the same master serialize.
     *
     * <p>Phase 16.x (TOCTOU): the bulk path's per-item duplicate guard
     * ({@code ServiceCatalogService#assertNoActiveDuplicatesInBatch}) reads the owner's
     * already-taken service types and then inserts, so two concurrent bulk POSTs for the same
     * master could both read "this type is free" and race. Taking this lock at the top of the
     * bulk transaction forces the second caller to wait for the first to commit, after which
     * its guard sees the committed rows and returns the clean, item-naming 409
     * {@code DUPLICATE_SERVICE} rather than tripping the V121 unique index at flush.
     *
     * <p>The lock is held until the surrounding transaction commits or rolls back
     * ({@code pg_advisory_xact_lock} — no manual unlock needed). Mirrors the SHAPE of the booking
     * overlap-guard lock in {@code BookingRepository#acquireAdvisoryLock} but deliberately not its
     * KEY — see the salt-allocation section below.
     *
     * <p><b>{@code lock_timeout} (bulk-additive audit finding 1).</b> Fuses
     * {@code set_config('lock_timeout', '3s', true)} into the SAME statement/round-trip, mirroring
     * {@code BookingRepository#acquireClientAdvisoryLockWithTimeout}'s exact shape. The
     * {@code is_local=true} third argument makes it functionally identical to
     * {@code SET LOCAL lock_timeout = '3s'} — transaction-scoped, reset automatically at
     * commit/rollback, never leaking onto the next borrower of the pooled connection. Postgres
     * does NOT formally guarantee the evaluation order of subexpressions (docs §4.2.14 leaves it
     * undefined), but the executor's {@code ExecProject} evaluates target-list entries in order,
     * so in every current implementation {@code set_config(...)} runs before
     * {@code pg_advisory_xact_lock(...)} on the same row and the 3s ceiling is already in force
     * for THIS acquisition. Were that ever to change, the GUC would still take effect for the rest
     * of the transaction; only this one acquisition would wait unbounded — i.e. it would degrade
     * to the pre-fix behaviour, not to a correctness bug.
     *
     * <p>Why it is required: bulk create became ADDITIVE (the "first-time only" precondition was
     * removed), so every add now contends on this lock instead of short-circuiting on a trivial
     * {@code EXISTS}. Unbounded, a caller firing their full rate-limit burst at one masterId with
     * DISJOINT service-type sets (nothing 409s them early) would run N full batches strictly in
     * series, each parking one of only 10 Hikari connections for the full 20s connection-timeout
     * and starving the pool app-wide. A wait exceeding 3s now aborts with Postgres
     * {@code 55P03 lock_not_available} instead.
     *
     * <p>Ordinary contention is unaffected: the loser waits (well under 3s), acquires, re-runs its
     * duplicate guard against the winner's COMMITTED rows and returns the clean 409
     * {@code DUPLICATE_SERVICE} carrying a populated {@code existingServiceDefId} — the contract
     * {@code BulkServiceSetupIntegrationTest} pins and the mobile screen deep-links on. This is
     * precisely why a bounded wait was chosen over {@code pg_try_advisory_xact_lock}, which would
     * fail the loser instantly and null out that field.
     *
     * <p><b>Advisory-lock salt allocation.</b> The {@code hashtextextended} salt partitions the
     * single, process-wide 64-bit advisory-lock key space by lock PURPOSE. Allocation across the
     * codebase — grep {@code hashtextextended} in {@code src/main} before claiming a new one:
     * <ul>
     *   <li>{@code 0} — booking per-MASTER overlap lock
     *       ({@code BookingRepository#acquireAdvisoryLock},
     *       {@code BookingRepository#acquireAdvisoryLockWithTimeout})</li>
     *   <li>{@code 1} — booking per-CLIENT conflict lock
     *       ({@code BookingRepository#acquireClientAdvisoryLockWithTimeout})</li>
     *   <li>{@code 2} — this bulk service-setup lock</li>
     * </ul>
     *
     * <p>Salt {@code 2} replaced an earlier salt {@code 0} (bulk-additive re-audit). Keyed on the
     * same {@code masterId} with the same salt, this lock was not merely at risk of a
     * birthday-paradox hash collision with the booking master lock — it WAS that lock, exactly, so
     * every bulk add serialized against every booking create / reschedule / guest-booking for that
     * master. That was negligible while bulk setup ran once per master ever; the additive rewrite
     * makes it routine traffic the mobile "add services" screen issues on demand, at which point a
     * SALON_OWNER/SALON_ADMIN could (10×/min, per the endpoint rate limit) hold a master's BOOKING
     * lock for the duration of a 100-item batch and push that master's concurrent bookers into the
     * booking path's own 3s timeout. A dedicated salt removes the cross-feature coupling outright.
     *
     * <p><b>Deadlock freedom (ADVISORY locks).</b> Trivially preserved: a bulk-setup transaction
     * takes the salt-2 lock and NO other advisory lock, and no other code path takes salt
     * {@code 2} at all — so no session can hold a salt-2 lock while waiting on salt 0/1, nor the
     * reverse, and the two-lock cycle precondition never arises among advisory locks. (It also
     * held before this change, because bulk took only salt 0 while booking always acquires
     * client-then-master; the argument no longer depends on booking's internal ordering.)
     *
     * <p><b>Deadlock freedom (ROW locks) — why the advisory argument above is not the whole
     * story.</b> Verified separately, because a cycle needs only ONE advisory edge and one row-lock
     * edge: after taking this lock, the bulk transaction writes {@code service_definitions},
     * {@code master_service_assignments} and {@code masters.min_effective_price} — and no booking
     * path writes {@code masters} at all, so no booking transaction can be holding a conflicting
     * {@code masters} row lock while waiting on anything bulk holds. The one place the two paths
     * meet a shared row is that {@code masters} UPDATE itself: {@code idx_masters_min_effective_price}
     * is non-unique, so the UPDATE takes {@code FOR NO KEY UPDATE}, which is compatible with the
     * {@code FOR KEY SHARE} a booking insert's FK reference to {@code masters} takes — the two do
     * not block each other, so no wait edge forms in either direction.
     *
     * <p>Hash collision risk WITHIN salt {@code 2}: {@code hashtextextended} produces a 64-bit hash
     * of the UUID text. Birthday-paradox probability is negligible for current master counts, and a
     * genuine collision would only cause two unrelated masters' bulk setups to serialize, never a
     * correctness bug.
     */
    @Query(value = """
            SELECT 1 FROM (
                SELECT set_config('lock_timeout', '3s', true),
                       pg_advisory_xact_lock(hashtextextended(CAST(:masterId AS text), 2))
            ) sub
            """, nativeQuery = true)
    Integer acquireBulkSetupLockWithTimeout(@Param("masterId") UUID masterId);

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
     * <p>{@code JOIN FETCH m.salon s} (2026-08 re-audit) replaces the former implicit
     * {@code WHERE m.salon.id = :salonId} path predicate. It is semantically identical — that
     * predicate already required a non-null salon, so this stays INNER, unlike the {@code LEFT}
     * fetch on the master-scoped finders that must keep independent masters — but it additionally
     * initialises {@code salon} for the
     * {@link com.beautica.booking.domain.MasterBookability#isBookable} gate in
     * {@code SlotCalculationService#hasBookableFutureSlot}, which this query's rows reach as the
     * {@code preloaded} argument from {@code BookingMasterService}. Without the fetch that gate
     * would trigger a lazy salon load on the catalogue path.
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
            JOIN FETCH m.salon s
            WHERE s.id = :salonId
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
