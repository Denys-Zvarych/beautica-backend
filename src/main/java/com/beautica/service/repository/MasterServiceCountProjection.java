package com.beautica.service.repository;

import java.util.UUID;

/**
 * One row of {@link MasterServiceRepository#countActiveByMasterIdIn} — a master id paired with
 * the count of that master's currently ACTIVE {@code master_services} rows.
 *
 * <p>Backs {@code SalonService#getSalonStaff} (Phase 21.5 staff roster): the roster renders one
 * {@code serviceCount} per master, and this batch {@code GROUP BY} query resolves the whole
 * salon's masters in one round trip — mirroring the batch style of
 * {@link MasterServiceRepository#findDistinctOfferedCategoriesByMasterIds} — instead of one
 * {@code COUNT} query per master, which would be an N+1 (Anti-Bug §E-3).
 */
public interface MasterServiceCountProjection {

    UUID getMasterId();

    long getServiceCount();
}
