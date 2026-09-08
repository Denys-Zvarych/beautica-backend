package com.beautica.service.repository;

import com.beautica.service.entity.ServiceDefinition;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * One row of {@link ServiceRepository#findSalonBulkSetupCandidates}: an ACTIVE
 * {@link ServiceDefinition} that a salon-branch bulk-create batch could collide with or reuse,
 * plus whether the target master already performs it.
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
 * <p>{@code masterAssignmentId} is the LEFT-JOIN half: non-null iff the target master holds an
 * ACTIVE {@code master_services} row for this definition. It is a nullable id rather than a boolean
 * {@code CASE} so the query stays a plain outer join with nothing to mis-read.
 */
public record SalonBulkSetupCandidate(
        UUID serviceTypeId,
        ServiceDefinition definition,
        @Nullable UUID masterAssignmentId
) {

    /** True iff the target master already performs this definition (an active assignment exists). */
    public boolean assignedToMaster() {
        return masterAssignmentId != null;
    }
}
