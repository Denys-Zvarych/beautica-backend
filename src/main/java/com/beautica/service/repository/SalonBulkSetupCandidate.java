package com.beautica.service.repository;

import com.beautica.service.entity.ServiceDefinition;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * One row of {@link ServiceRepository#findSalonBulkSetupCandidates}: an ACTIVE
 * {@link ServiceDefinition} that a salon-branch bulk-create batch could collide with or reuse,
 * plus the target master's own {@code master_services} row for it, if any.
 *
 * <p><b>Why one projection and not three queries (Phase 302 perf LOW-3).</b> The salon branch used
 * to ask three separate questions inside the advisory lock — "which of these types does this master
 * already perform" ({@code MasterServiceRepository#findActiveAssignedServiceTypeIds}, deleted),
 * "which does the salon already offer" ({@link ServiceRepository#findActiveDuplicateTypeIds}) and
 * then a bare {@code findAllById} to re-fetch definitions the second query had already joined. Once
 * the advisory lock is keyed on the SALON (audit HIGH-2) the serialized window is salon-wide, so
 * three round-trips inside it multiply across every concurrent master setup in the salon. All three
 * answers come from one row shape, so they come from one query.
 *
 * <p>{@code definition} is the managed entity, not an id: the assignment insert needs it, D3's
 * override comparison reads its {@code basePrice}/{@code baseDurationMinutes}, and the response DTO
 * renders it — exactly the reasons the deleted {@code findAllById} re-fetch existed.
 *
 * <p>{@code serviceTypeId} is projected explicitly rather than read back off
 * {@code definition.getServiceType()} so the caller never depends on that association being
 * initialised.
 *
 * <p>{@code masterAssignmentId} is the LEFT-JOIN half: non-null iff the target master holds ANY
 * {@code master_services} row for this definition, active or not (Phase 307 D6 — the join no
 * longer filters on {@code is_active}; see {@link ServiceRepository#findSalonBulkSetupCandidates}'s
 * javadoc for the bug that filtering caused). {@code masterAssignmentActive} disambiguates which:
 * {@code true} = the master already performs it (a conflict), {@code false} = the master
 * previously unassigned it (a reactivation candidate, Phase 307 D6), {@code null} = no row at all
 * (a fresh insert). Both are nullable ids/booleans rather than a single {@code CASE} so the query
 * stays a plain outer join with nothing to mis-read.
 */
public record SalonBulkSetupCandidate(
        UUID serviceTypeId,
        ServiceDefinition definition,
        @Nullable UUID masterAssignmentId,
        @Nullable Boolean masterAssignmentActive
) {

    /**
     * True iff the target master already holds an ACTIVE {@code master_services} row for this
     * definition — the only state that is a genuine conflict. Narrowed by Phase 307 D6 from "any
     * row exists": an INACTIVE row is a previously-unassigned service, not an active offering, and
     * must be reactivated rather than rejected or re-inserted.
     */
    public boolean assignedToMaster() {
        return masterAssignmentId != null && Boolean.TRUE.equals(masterAssignmentActive);
    }

    /**
     * True iff the target master holds an INACTIVE {@code master_services} row for this
     * definition — Phase 307 D6's reactivation candidate. {@code master_services}' {@code UNIQUE
     * (master_id, service_def_id)} is NOT partial, so the caller must reactivate this exact row
     * instead of inserting a second one for the same pair.
     */
    public boolean hasInactiveAssignment() {
        return masterAssignmentId != null && Boolean.FALSE.equals(masterAssignmentActive);
    }
}
