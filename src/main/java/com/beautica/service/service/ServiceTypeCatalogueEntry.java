package com.beautica.service.service;

import java.util.UUID;

/**
 * One active platform service type reduced to the four fields the search-suggestions catalogue
 * needs (Phase 331): the FK target, its slug (the exact-match filter
 * {@code /search/masters}/{@code /search/salons} already accept via {@code serviceTypeSlugs}),
 * the Ukrainian display name, and the canonical category key it belongs to
 * ({@code platform_category_name} — the SAME value space as {@code service_definitions.category}
 * and the mobile rail's {@code ServiceCategoryOption.name}).
 *
 * <p>Produced by {@link ServiceTypeSlugResolver#allActiveTypes()} from the same cached
 * {@code service-types} / {@code 'ALL'} list {@link ServiceTypeSlugResolver#resolve} already
 * reads — no new cache, no new query.
 */
public record ServiceTypeCatalogueEntry(UUID id, String slug, String nameUk, String categoryKey) {
}
