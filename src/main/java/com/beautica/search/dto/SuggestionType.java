package com.beautica.search.dto;

/**
 * Discriminates a {@link SearchSuggestionResponse} row (Phase 331).
 *
 * <p>{@link #CATEGORY} carries no {@code serviceTypeSlug} — mobile resolves it by sending the
 * label back as free-text {@code q} (the existing {@code QueryCategoryMatcher} path).
 * {@link #SERVICE} always carries a {@code serviceTypeSlug} + {@code categoryKey}, which mobile
 * sends as {@code category=<categoryKey>&serviceTypeSlugs=<slug>} with no {@code q} — see the
 * phase doc's D2 for why free text cannot resolve a service-type name reliably.
 */
public enum SuggestionType {
    CATEGORY,
    SERVICE
}
