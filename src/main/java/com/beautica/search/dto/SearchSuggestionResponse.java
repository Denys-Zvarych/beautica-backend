package com.beautica.search.dto;

import org.springframework.lang.Nullable;

/**
 * One ranked autocomplete suggestion for {@code GET /api/v1/search/suggestions} (Phase 331).
 *
 * <p>Carries catalogue data only — labels, slugs, keys. No master/salon ids, no counts, no user
 * data (§I). Availability is visible only as the presence of a label, the same fact an empty or
 * non-empty {@code /search/masters} page already reveals.
 *
 * @param type            {@link SuggestionType#CATEGORY} or {@link SuggestionType#SERVICE}
 * @param label           the category display name, or the {@code ServiceType.nameUk}
 * @param categoryKey     the rail key ({@code platform_categories.name}); for a CATEGORY row this
 *                        is its own key, mirroring the {@code category} filter
 *                        {@code /search/masters}/{@code /search/salons} already accept
 * @param serviceTypeSlug {@code SERVICE} only — {@code null} for CATEGORY
 */
public record SearchSuggestionResponse(
        SuggestionType type,
        String label,
        String categoryKey,
        @Nullable String serviceTypeSlug
) {
}
