package com.beautica.search.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole search-suggestions catalogue (Phase 331) — every selectable category and every active
 * service type, with NO availability applied. {@link SearchSuggestionService} intersects this
 * against a place's {@link PlaceAvailability} before matching/ranking.
 *
 * @param categoriesByKey {@link #categories()} indexed by {@link CatalogueCategoryEntry#key()},
 *                        built ONCE here — audit-fix cycle 2, finding 1 (LOW perf). This snapshot
 *                        is cached for 10 minutes and never mutates, but
 *                        {@link SearchSuggestionService#suggest} rebuilt this exact map from
 *                        {@code categories()} on every call (once per keystroke, behind an
 *                        unauthenticated endpoint) purely to resolve a SERVICE row's category for
 *                        the D4 dedup check. Mirrors {@link CatalogueCategoryEntry#foldedLabel()}
 *                        and {@link CatalogueServiceEntry#foldedNameUk()} — pre-fold once at
 *                        snapshot-build time, never per request.
 */
record SuggestionCatalogueSnapshot(
        List<CatalogueCategoryEntry> categories,
        List<CatalogueServiceEntry> services,
        Map<String, CatalogueCategoryEntry> categoriesByKey) {

    /**
     * Convenience constructor used by {@link SearchSuggestionCatalogue#snapshot()} and by tests —
     * computes {@code categoriesByKey} from {@code categories} exactly once, here, so every caller
     * gets the pre-built index for free regardless of which constructor it uses.
     */
    SuggestionCatalogueSnapshot(List<CatalogueCategoryEntry> categories, List<CatalogueServiceEntry> services) {
        this(categories, services, buildCategoriesByKey(categories));
    }

    private static Map<String, CatalogueCategoryEntry> buildCategoriesByKey(List<CatalogueCategoryEntry> categories) {
        Map<String, CatalogueCategoryEntry> byKey = new HashMap<>();
        for (CatalogueCategoryEntry category : categories) {
            byKey.put(category.key(), category);
        }
        return Map.copyOf(byKey);
    }
}
