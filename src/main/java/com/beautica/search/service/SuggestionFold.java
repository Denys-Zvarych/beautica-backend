package com.beautica.search.service;

import java.util.Locale;

/**
 * The single fold definition for {@code GET /api/v1/search/suggestions} (Phase 331, D4):
 * {@link NormalizedSearchQuery#foldApostrophes} then {@code toLowerCase(Locale.ROOT)}. Extracted
 * (audit-fix cycle 1, finding 3) so {@link CatalogueCategoryEntry}/{@link CatalogueServiceEntry}
 * can pre-fold a label ONCE, at snapshot-build time, instead of {@link SearchSuggestionService}
 * recomputing it for every catalogue entry on every request.
 */
final class SuggestionFold {

    private SuggestionFold() {
    }

    /** {@code NormalizedSearchQuery}'s apostrophe fold, then {@code Locale.ROOT} lower-casing. */
    static String fold(String value) {
        return NormalizedSearchQuery.foldApostrophes(value).toLowerCase(Locale.ROOT);
    }
}
