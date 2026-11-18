package com.beautica.service.repository;

import java.util.UUID;

/**
 * One strict-verdict candidate of {@link MasterServiceRepository#findBookableAssignmentDurationsByMasterIds}:
 * the assignment id, its master, and exactly the three values the free-slot gate sizes a slot from
 * ({@code SlotCalculationService#effectiveDuration}: override beats base, plus the buffer).
 *
 * <p>A narrow projection, not a managed {@code MasterServiceAssignment} graph (B523 perf fix): the
 * cold search resolve loads every candidate of up to {@code StrictBookableResolver.CANDIDATE_CAP}
 * masters, and hydrating assignment + definition + master + salon entities for that set cost
 * seconds. The active / ownership rule lives in the query's WHERE, so no flag is returned.
 */
public interface BookableAssignmentDurationRow {

    UUID getAssignmentId();

    UUID getMasterId();

    /** Per-master override; {@code null} means the definition's base duration applies. */
    Integer getDurationOverrideMinutes();

    int getBaseDurationMinutes();

    int getBufferMinutesAfter();
}
