package com.beautica.service.repository;

import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceDefinition;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     * Owner-access projection for a {@link ServiceDefinition} — Phase 306 D3.
     *
     * <p>{@code ownerUserId} is the definition owner's user UUID (SALON → the salon's
     * {@code owner.id}; INDEPENDENT_MASTER → the master's {@code user.id}) — unchanged from the
     * original single-column projection and still the sole answer for the INDEPENDENT_MASTER
     * identity arm.
     *
     * <p>{@code salonId} is non-null iff the definition is SALON-owned, null for an
     * INDEPENDENT_MASTER-owned definition. It lets {@code AuthorizationService} resolve a
     * {@code SALON_ADMIN} through {@code hasManagementAccess(salonId, actorId, actorRole)} —
     * the existing salon-management rule {@code canManageSalon} already delegates to — without a
     * second query. An admin's actor id never equals {@code ownerUserId} (that is always the
     * owner's id), which is exactly the bug this projection extension fixes.
     *
     * <p>{@code salonOwnerId} (Phase 306 audit fix #1, backend-perf MEDIUM) is the salon's
     * {@code owner.id}, named distinctly from {@code ownerUserId} so the SALON_OWNER identity
     * check does not have to reason about {@code ownerUserId}'s polymorphic meaning (salon owner
     * OR independent master, depending on {@code ownerType}). It is non-null exactly when
     * {@code salonId} is non-null, and always equal to {@code ownerUserId} on that branch — it
     * rides the SAME {@code LEFT JOIN Salon s} used to resolve {@code salonId}, so adding it costs
     * no extra join and no extra query.
     */
    interface ServiceOwnerAccess {
        UUID getOwnerUserId();

        UUID getSalonId();

        UUID getSalonOwnerId();
    }

    /**
     * Resolves the {@link ServiceOwnerAccess} projection in a single query, avoiding the
     * two-query chain (load ServiceDefinition + load Salon or Master) previously used in
     * AuthorizationService.canManageServiceDefinition.
     *
     * <p>Phase 306 D3 — extended beyond the bare owner user UUID to also project the definition's
     * salon id (see {@link ServiceOwnerAccess}), so {@code canManageServiceDefinition} and
     * {@code enforceCanManageServiceDefinition} can resolve a SALON_ADMIN via salon-management
     * access in the SAME query, not a second round-trip.
     *
     * <p>{@code salonId} and {@code salonOwnerId} ride for free on the existing {@code Salon s}
     * LEFT JOIN: {@code s} is only non-null when {@code sd.ownerType = SALON} (the join's own
     * {@code ON} condition), so both are already null on the INDEPENDENT_MASTER branch with no
     * extra CASE needed.
     *
     * Returns empty when no ServiceDefinition with the given id exists.
     */
    @Query("""
            SELECT CASE sd.ownerType
                WHEN 'SALON' THEN s.owner.id
                ELSE m.user.id
            END AS ownerUserId,
            s.id AS salonId,
            s.owner.id AS salonOwnerId
            FROM ServiceDefinition sd
            LEFT JOIN Salon s ON s.id = sd.ownerId AND sd.ownerType = com.beautica.service.entity.OwnerType.SALON
            LEFT JOIN Master m ON m.id = sd.ownerId AND sd.ownerType = com.beautica.service.entity.OwnerType.INDEPENDENT_MASTER
            WHERE sd.id = :serviceDefId
            """)
    Optional<ServiceOwnerAccess> findOwnerUserId(@Param("serviceDefId") UUID serviceDefId);

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
     * Bulk-deactivates every ACTIVE service definition owned by {@code (ownerType, ownerId)} —
     * used by {@code SalonService.deactivateSalon} (Phase 268 D1) to close the direct
     * salon-owned-catalogue read path when a salon is deleted, on top of the
     * every-master-deactivated mechanism the staff cascade already provides (the two must agree,
     * see the phase doc D1).
     *
     * <p>Mirrors {@link #deactivateById}'s bulk-JPQL idiom exactly, including its caveat: a bulk
     * {@code UPDATE} bypasses the persistence context and {@code AuditableEntity}'s
     * {@code @LastModifiedDate}, so {@code updated_at} is NOT bumped by this call. Not fixed here —
     * consistent with the existing single-row sibling.
     *
     * <p>Scoped by BOTH {@code ownerType} and {@code ownerId} — {@code ownerId} alone is not
     * unique across owner types (a salon and an independent master can share a UUID only by
     * astronomical coincidence, but the predicate is cheap and removes the theoretical case
     * entirely, matching every other owner-scoped finder in this interface).
     */
    @Modifying
    @Query("""
            UPDATE ServiceDefinition sd SET sd.isActive = false
            WHERE sd.ownerType = :ownerType AND sd.ownerId = :ownerId AND sd.isActive = true
            """)
    int deactivateAllByOwner(@Param("ownerType") OwnerType ownerType, @Param("ownerId") UUID ownerId);

    /**
     * Finds the id of an existing ACTIVE {@link ServiceDefinition} that would collide with a
     * write for {@code (ownerType, ownerId, serviceTypeId)} — the exact key of the partial
     * unique index {@code ux_service_def_owner_service_type_active} (V121), which enforces
     * "one active service per owner per service type, regardless of price or duration".
     *
     * <p>Returns the id only (not the entity): the caller needs it solely to populate the
     * {@code DUPLICATE_SERVICE} 409 payload, so hydrating the full row would be waste.
     *
     * <p><b>Not a security boundary.</b> Like every finder here it is unscoped (anti-bug §E-4)
     * — the caller must already have established that the actor may write to this owner.
     *
     * <p><b>Not a substitute for the index.</b> This is a read-then-write check and therefore
     * TOCTOU-prone under concurrency; it exists to produce a friendly, branchable error on the
     * common path. The DB index is the actual guarantee, and the create paths translate its
     * violation into the same exception.
     *
     * @param excludeId the row being updated, so a no-op PATCH cannot collide with itself;
     *                  pass {@code null} on create paths
     */
    @Query("""
            SELECT sd.id FROM ServiceDefinition sd
            WHERE sd.ownerType = :ownerType
              AND sd.ownerId = :ownerId
              AND sd.serviceType.id = :serviceTypeId
              AND sd.isActive = true
              AND (:excludeId IS NULL OR sd.id <> :excludeId)
            """)
    Optional<UUID> findActiveDuplicateId(
            @Param("ownerType") OwnerType ownerType,
            @Param("ownerId") UUID ownerId,
            @Param("serviceTypeId") UUID serviceTypeId,
            @Param("excludeId") UUID excludeId);

    /**
     * Batched sibling of {@link #findActiveDuplicateId}: resolves, in ONE query, every
     * {@code (serviceTypeId → existing active ServiceDefinition id)} pair that would collide
     * with a multi-item write for {@code (ownerType, ownerId)}.
     *
     * <p><b>Why this exists.</b> The bulk service-create path accepts up to 100 items
     * ({@code @Size(max = 100)} on {@code BulkCreateServicesRequest}); calling
     * {@link #findActiveDuplicateId} per item issued 100 serialized SELECTs, regressing the
     * one-query batching its sibling steps ({@code resolveBulkServiceTypes},
     * {@code validateBulkCategoriesActive}) already establish. Each of those per-item SELECTs
     * additionally forced a Hibernate AUTO flush, defeating JDBC insert batching — so the two
     * only pay off together.
     *
     * <p>Returns the definition id alongside the type id (rather than type ids alone) so the
     * caller can still populate {@code existingServiceDefId} on the {@code DUPLICATE_SERVICE}
     * 409 and let the client deep-link to the offending row, exactly as the single-item path does.
     *
     * <p>No {@code excludeId} parameter: this serves create paths only, where nothing is being
     * updated in place. Use {@link #findActiveDuplicateId} for PATCH.
     *
     * <p>Bounded by construction — {@code typeIds} is the caller's own validated, deduplicated
     * request set, so the result can never exceed it (anti-bug §E-3). Like every finder here it
     * is unscoped (§E-4): the caller must already have established write access to this owner.
     * And like its sibling it is a read-then-write check, TOCTOU-prone by nature — the partial
     * unique index {@code ux_service_def_owner_service_type_active} remains the actual guarantee.
     */
    @Query("""
            SELECT new com.beautica.service.repository.ActiveDuplicateProjection(sd.serviceType.id, sd.id)
            FROM ServiceDefinition sd
            WHERE sd.ownerType = :ownerType
              AND sd.ownerId = :ownerId
              AND sd.isActive = true
              AND sd.serviceType.id IN :typeIds
            """)
    List<ActiveDuplicateProjection> findActiveDuplicateTypeIds(
            @Param("ownerType") OwnerType ownerType,
            @Param("ownerId") UUID ownerId,
            @Param("typeIds") Collection<UUID> typeIds);

    /**
     * Phase 302 — the ONE query the salon-branch bulk-create critical section runs: every ACTIVE
     * {@link ServiceDefinition} the batch's service types could collide with or reuse, each paired
     * with the target master's ACTIVE assignment id (or {@code null} when they do not perform it).
     *
     * <p><b>Three round-trips collapsed into one (perf LOW-3).</b> The salon branch previously ran
     * {@code MasterServiceRepository#findActiveAssignedServiceTypeIds} (the per-master conflict),
     * then {@link #findActiveDuplicateTypeIds} (the salon's reusable definition ids), then
     * {@code findAllById} to re-fetch definitions the second query had already joined. All three
     * answers live in this one row shape. It matters because the advisory lock guarding this
     * section is keyed on the SALON (audit HIGH-2): the serialized window is salon-wide, so every
     * extra round-trip inside it multiplies across every concurrent master setup in the salon.
     *
     * <p>Batched over {@code typeIds}, never per item. The bulk endpoint accepts up to 100 items,
     * and the cost of a per-item {@code exists} is <b>100 serialized round-trips held inside the
     * advisory lock</b> — that, not a flush interaction, is the argument (audit INFO-7 corrected an
     * earlier comment here that blamed a Hibernate AUTO flush defeating JDBC insert batching: this
     * guard runs strictly BEFORE any {@code save()}, so nothing is pending to flush).
     *
     * <p><b>Salon-scoped, and that scoping is the security fix (audit HIGH-1).</b> The deleted
     * per-master finder filtered on {@code master_id} ALONE. {@code MasterService.rotateMasterToSalon}
     * moves {@code masters.salon_id} and never touches {@code master_services}, so a rotated master
     * keeps ACTIVE assignments to the SOURCE salon's SALON-owned definitions — the "rotated-master
     * leak" {@code SalonSearchSql} names and every other read query here compensates for. Unscoped,
     * the destination salon's first bulk-create for such a type answered
     * {@code 409 DUPLICATE_SERVICE} carrying a SOURCE-salon {@code existingServiceDefId}: a wrong
     * answer AND a cross-tenant id disclosed to an actor authorised only for the destination. The
     * owner predicate below is what confines both the conflict and the reuse to definitions this
     * salon can legitimately be in conflict with.
     *
     * <p>The {@code INDEPENDENT_MASTER}/{@code :masterId} arm is deliberate, not incidental: ~60
     * pre-Phase-302 rows are still master-owned (phase 303 backfills them), and a master who
     * already performs a type through one of those must keep getting the clean, item-naming 409
     * rather than a raced V121 violation. Such a row is a CONFLICT, never a reuse candidate — the
     * caller reuses only {@code ownerType = SALON} rows, since an insert here writes
     * {@code (SALON, salonId)}.
     *
     * <p>The join is {@code LEFT} so a definition the salon offers but this master does not perform
     * still comes back — that is precisely the reuse row. It cannot multiply rows:
     * {@code master_services} is {@code UNIQUE (master_id, service_def_id)}, so at most one
     * assignment matches per definition. Bounded by construction (§E-3) — {@code typeIds} is the
     * caller's own validated, deduplicated request set, and at most two owners can answer for a
     * type.
     *
     * <p>Read-then-write like every guard on this path (§E-4 — unscoped by role, the caller must
     * already hold write access to both salon and master): the partial unique index
     * {@code ux_service_def_owner_service_type_active} and {@code master_services}' unique key stay
     * the actual guarantees; the salon-keyed advisory lock is what turns ordinary contention into
     * the clean 409 instead of a constraint violation.
     *
     * <p><b>The join is NOT filtered on {@code msa.isActive} (Phase 307 D6).</b> It used to be —
     * {@code AND msa.isActive = true} in the {@code ON} clause — which made an INACTIVE
     * (previously-unassigned, see {@code ServiceCatalogService#unassignServiceFromMaster}) row
     * invisible to this query entirely: the candidate came back with a {@code null}
     * {@code masterAssignmentId}, {@link #findSalonBulkSetupCandidates}'s caller read that as "no
     * assignment", the reuse branch inserted a SECOND {@code master_services} row for the same
     * {@code (master_id, service_def_id)} pair, and — because that unique key is NOT partial —
     * the insert tripped it at flush and surfaced as an opaque 409. The join now returns the row
     * regardless of its {@code is_active} state, and {@code msa.isActive} is projected alongside
     * the id so {@link SalonBulkSetupCandidate#assignedToMaster()} /
     * {@link SalonBulkSetupCandidate#hasInactiveAssignment()} can tell "already offered" (ACTIVE —
     * still a conflict) apart from "previously unassigned" (INACTIVE — a reactivation candidate,
     * not a conflict and not a fresh insert). Cardinality is unaffected: {@code master_services}'
     * unique key still bounds the join to at most one row per definition regardless of its active
     * state.
     */
    @Query("""
            SELECT new com.beautica.service.repository.SalonBulkSetupCandidate(
                       sd.serviceType.id, sd, msa.id, msa.isActive)
            FROM ServiceDefinition sd
            LEFT JOIN MasterServiceAssignment msa
                   ON msa.serviceDefinition = sd
                  AND msa.master.id = :masterId
            WHERE sd.isActive = true
              AND sd.serviceType.id IN :typeIds
              AND ((sd.ownerType = com.beautica.service.entity.OwnerType.SALON
                        AND sd.ownerId = :salonId)
                OR (sd.ownerType = com.beautica.service.entity.OwnerType.INDEPENDENT_MASTER
                        AND sd.ownerId = :masterId))
            """)
    List<SalonBulkSetupCandidate> findSalonBulkSetupCandidates(
            @Param("salonId") UUID salonId,
            @Param("masterId") UUID masterId,
            @Param("typeIds") Collection<UUID> typeIds);

    /**
     * Resolves the owning salon id for a SALON-owned definition (its {@code ownerId} IS the salon id),
     * or empty for a master-owned (INDEPENDENT_MASTER) definition or an unknown id. Used by
     * {@code ServiceCatalogService.deactivateServiceDefinition} to evict the affected salon's
     * {@code salon-service-catalog} entry (perf/security #2/#6) without hydrating the full entity —
     * a master-owned definition owns no salon catalogue entry, hence the empty result.
     */
    @Query("""
            SELECT sd.ownerId FROM ServiceDefinition sd
            WHERE sd.id = :serviceDefId
              AND sd.ownerType = com.beautica.service.entity.OwnerType.SALON
            """)
    Optional<UUID> findSalonOwnerId(@Param("serviceDefId") UUID serviceDefId);

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
     * client could never actually book it. The {@code msa.master.salon.id = :salonId} predicate
     * closes the rotated-master stale-assignment leak: a master who left this salon but kept an
     * active assignment row no longer counts toward the salon's bookable set.
     *
     * <p><b>Coarse gate only.</b> This is the coarse "actively performed by an in-salon master"
     * gate that salon <em>search</em> mirrors ({@code SearchService#appendSalonBookableGate}). The
     * live catalogue ({@code ServiceCatalogService#getSalonServiceCatalog}) additionally requires
     * ≥1 free future slot per the Phase 23.x fix and therefore drives off the assignment-level
     * {@code MasterServiceRepository#findBookableAssignmentsBySalon} query plus the
     * {@code SlotCalculationService} free-slot gate — this method is retained as the canonical
     * coarse-gate reference the search SQL is kept in step with.
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
                    AND msa.master.salon.id = :salonId
              )
            ORDER BY sd.category ASC, sd.name ASC
            """)
    List<ServiceDefinition> findBookableServicesBySalon(
            @Param("salonId") UUID salonId,
            Pageable pageable);
}
