package com.beautica.service.service;

import java.util.UUID;

/**
 * Resolved platform service-type, exposing only the two fields the per-service
 * search filter needs: the {@code service_types.id} FK target and the canonical
 * Ukrainian display name.
 *
 * <p>Produced by {@link ServiceTypeSlugResolver} from the cached active-type
 * list. The per-service search filter matches strictly on {@code serviceTypeId}
 * (the {@code name ILIKE '%nameUk%'} substring fallback was removed — it produced
 * false positives because a type's {@code name_uk} is a substring of unrelated
 * service names). {@code nameUk} is retained as the canonical Ukrainian display
 * name and as the operand for an exact-name recovery of NULL-FK rows should that
 * be reintroduced; legacy NULL-FK rows are otherwise recovered by the deferred
 * Phase 20.4 FK backfill.</p>
 */
public record ServiceTypeMatch(UUID serviceTypeId, String nameUk) {}
