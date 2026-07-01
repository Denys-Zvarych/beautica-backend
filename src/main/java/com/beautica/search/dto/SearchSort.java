package com.beautica.search.dto;

/**
 * Allow-listed sort options for the public discovery endpoints
 * ({@code GET /api/v1/search/masters}, {@code GET /api/v1/search/salons}).
 *
 * <p>Binding the sort parameter to an enum (rather than a free {@code String}
 * the service interpolates into native SQL) is the security control here: the
 * set of orderings a caller can request is fixed at compile time, so there is
 * no path from caller text to an {@code ORDER BY} fragment (SQL-injection safe).
 * Each constant maps to a hard-coded, parameter-free {@code ORDER BY} in the
 * service / a {@code Sort} built from a whitelisted column name — never the
 * caller's raw string.</p>
 *
 * <p>{@link #RATING_DESC} is the documented default (the historical search
 * ordering) and is applied whenever the caller omits {@code sort} or supplies a
 * value that does not bind to one of these constants.</p>
 *
 * <p><b>Enum-binding 400 surface:</b> an unknown {@code ?sort=} value fails to
 * bind and surfaces as a generic 400 via the global handler. The enum constant
 * names are short, self-explanatory, and intentionally NOT sensitive — unlike a
 * domain enum, leaking this list to a client is harmless (it is the documented
 * public contract).</p>
 */
public enum SearchSort {

    /** Highest average rating first (default). Stable tiebreaker on id. */
    RATING_DESC,

    /** Cheapest effective price first. NULL prices sort last. Stable tiebreaker on id. */
    PRICE_ASC,

    /** Most expensive effective price first. NULL prices sort last. Stable tiebreaker on id. */
    PRICE_DESC,

    /** Most-reviewed first. Stable tiebreaker on id. */
    REVIEWS_DESC;

    /**
     * Resolves a possibly-null sort selection to a concrete ordering, applying
     * the {@link #RATING_DESC} default when none was supplied. Keeps the
     * null-handling in one place so callers never branch on null themselves.
     */
    public static SearchSort orDefault(SearchSort sort) {
        return sort == null ? RATING_DESC : sort;
    }
}
