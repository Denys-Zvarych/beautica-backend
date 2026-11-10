package com.beautica.service.service;

import com.beautica.service.entity.OwnerType;
import com.beautica.service.repository.ActiveDuplicateProjection;
import com.beautica.service.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Cold-path lookup of the COMMITTED copy that won a V233 race
 * ({@code ux_service_def_owner_type_duration_name_active}), so the flush-time
 * {@code DUPLICATE_SERVICE} 409 can carry {@code serviceName} + {@code existingServiceDefId}
 * like every pre-check 409 does.
 *
 * <p><b>Why a separate bean with {@code REQUIRES_NEW}.</b> The unique violation aborts the
 * caller's Postgres transaction, so no further statement can run on its connection; a new,
 * read-only transaction on a second pooled connection can. It must be a separate bean — a
 * {@code REQUIRES_NEW} method invoked on {@code this} bypasses the transactional proxy. The
 * violation is raised only after the winning transaction committed (the loser's INSERT blocks on
 * the index until then), so the new transaction sees the winner's row. Runs only on the rare
 * failing path; the success path never pays for it.
 *
 * <p><b>Internal — no authorization of its own.</b> It reads any owner's active copies by the
 * {@code ownerType}/{@code ownerId} it is handed. Callers MUST already have verified the caller's
 * write access to that owner (today only {@link ServiceCatalogService}, after its owner gate);
 * hence package-private, so no other feature package can reach it.
 *
 * <p><b>Bounded.</b> {@value #LOOKUP_TIMEOUT_SECONDS}s transaction timeout (applied by Spring as
 * the JPA query timeout) so a slow lookup cannot hold the race loser's request; any failure,
 * including a transaction that cannot begin, degrades to the pre-lookup 409 in the caller.
 * Pool-acquisition wait is governed by Hikari's pool-wide {@code connectionTimeout}, not by this.
 */
@Component
@RequiredArgsConstructor
class DuplicateServiceCopyLookup {

    static final int LOOKUP_TIMEOUT_SECONDS = 2;

    private final ServiceRepository serviceRepository;

    /**
     * {@link ServiceRepository#findActiveDuplicateCopies} in its own read-only transaction — the
     * same zipped (type, duration, {@code lower(btrim(name))}) match the bulk pre-check uses.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, timeout = LOOKUP_TIMEOUT_SECONDS)
    public List<ActiveDuplicateProjection> findCommittedCopies(OwnerType ownerType, UUID ownerId,
            UUID[] typeIds, Integer[] durations, String[] names) {
        return serviceRepository.findActiveDuplicateCopies(ownerType.name(), ownerId, typeIds, durations, names);
    }
}
