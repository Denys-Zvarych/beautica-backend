package com.beautica.search.service;

/**
 * One selectable platform category in the {@link SearchSuggestionCatalogue} snapshot (Phase 331).
 *
 * @param key         the canonical {@code platform_categories.name} — the same value space as
 *                    {@code service_definitions.category} and the mobile rail's
 *                    {@code ServiceCategoryOption.name}
 * @param label       the Ukrainian {@code display_name} matched against the caller's term
 * @param foldedLabel {@code label} pre-folded ({@link SuggestionFold#fold}) ONCE, when the
 *                    snapshot is built — audit-fix cycle 1, finding 3 (LOW perf). The catalogue
 *                    snapshot is cached for 10 minutes, but {@code label} never changes for the
 *                    life of an entry, so folding it again on every {@code suggest()} call (once
 *                    per entry, on every keystroke, behind an unauthenticated endpoint) was pure
 *                    waste. {@link SearchSuggestionService} matches against this field, never
 *                    against {@code label} directly.
 */
record CatalogueCategoryEntry(String key, String label, String foldedLabel) {

    /**
     * Convenience constructor used by {@link SearchSuggestionCatalogue#snapshot()} and by tests —
     * computes {@code foldedLabel} from {@code label} exactly once, here, so every caller gets the
     * pre-fold for free regardless of which constructor it uses.
     */
    CatalogueCategoryEntry(String key, String label) {
        this(key, label, SuggestionFold.fold(label));
    }
}
