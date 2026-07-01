package com.beautica.service.service;

import java.util.UUID;

/**
 * Resolved platform service-type, exposing only the two fields the per-service
 * search filter needs: the {@code service_types.id} FK target and the canonical
 * Ukrainian display name.
 *
 * <p>Produced by {@link ServiceTypeSlugResolver} from the cached active-type
 * list. {@code nameUk} backs the hybrid {@code name ILIKE} fallback that
 * recovers legacy / single-create {@code service_definitions} rows whose
 * {@code service_type_id} FK is still {@code NULL} (Phase 20.x; backfill is the
 * deferred Phase 20.4).</p>
 */
public record ServiceTypeMatch(UUID serviceTypeId, String nameUk) {}
