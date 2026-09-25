package com.beautica.search.service;

import java.util.UUID;

/**
 * One active service type in the {@link SearchSuggestionCatalogue} snapshot (Phase 331).
 *
 * @param id            {@code service_types.id} — bound as {@code serviceTypeIds} by
 *                      {@link SearchSuggestionAvailability}
 * @param slug          the exact-match value mobile sends as {@code serviceTypeSlugs} on the
 *                      results endpoints
 * @param nameUk        the label matched against the caller's term and shown in the response
 * @param categoryKey   the category this type belongs to ({@code platform_category_name}) — a
 *                      SERVICE whose {@code nameUk} folds equal to ITS category's label is dropped
 *                      (D4 dedup)
 * @param foldedNameUk  {@code nameUk} pre-folded ({@link SuggestionFold#fold}) ONCE, when the
 *                      snapshot is built — audit-fix cycle 1, finding 3 (LOW perf), mirroring
 *                      {@link CatalogueCategoryEntry#foldedLabel()}. Used for both matching and the
 *                      D4 duplicate-label check, so neither recomputes the fold per request.
 */
record CatalogueServiceEntry(UUID id, String slug, String nameUk, String categoryKey, String foldedNameUk) {

    /**
     * Convenience constructor used by {@link SearchSuggestionCatalogue#snapshot()} and by tests —
     * computes {@code foldedNameUk} from {@code nameUk} exactly once, here.
     */
    CatalogueServiceEntry(UUID id, String slug, String nameUk, String categoryKey) {
        this(id, slug, nameUk, categoryKey, SuggestionFold.fold(nameUk));
    }
}
