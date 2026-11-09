package com.beautica.service.repository;

import java.util.UUID;

/**
 * One collision found by {@link ServiceRepository#findActiveDuplicateCopies}: the requested
 * copy's {@code serviceTypeId} whose (type, duration, name) is already taken, paired with the id of the ACTIVE
 * {@code ServiceDefinition} holding it.
 *
 * <p>Both halves are load-bearing. {@code serviceTypeId} is the join key back onto the caller's
 * already-resolved {@code ServiceType} map, so the {@code DUPLICATE_SERVICE} 409 can name the
 * service without a second query. {@code serviceDefinitionId} populates
 * {@code existingServiceDefId}, letting the mobile client deep-link straight to the offending
 * row instead of showing a dead-end error.
 *
 * <p>A record projection (native-query aliases map onto its components) rather than {@code Object[]}: the query returns two
 * bare UUIDs of identical type, and positional array access would silently survive swapping them.
 */
public record ActiveDuplicateProjection(UUID serviceTypeId, UUID serviceDefinitionId) {
}
