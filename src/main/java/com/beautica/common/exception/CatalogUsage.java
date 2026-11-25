package com.beautica.common.exception;

import org.springframework.lang.Nullable;

/**
 * B505 (SVC-57) — what still references a catalog row a delete was refused for ({@code CATALOG_IN_USE}). A count is
 * {@code null} when it does not apply to the target: a service type carries only {@code definitions}; a category
 * carries all three. Flattened next to {@code code} in {@link CatalogRequestConflictResponse}.
 *
 * @param serviceTypes       service types under the category, hidden ones included
 * @param definitions        service definitions naming the row, inactive ones included (SVC-29: never purged)
 * @param pendingSuggestions PENDING service-type suggestions naming the category
 */
public record CatalogUsage(@Nullable Long serviceTypes, @Nullable Long definitions,
                           @Nullable Long pendingSuggestions) {

    public static CatalogUsage ofServiceType(long definitions) {
        return new CatalogUsage(null, definitions, null);
    }

    public static CatalogUsage ofCategory(long serviceTypes, long definitions, long pendingSuggestions) {
        return new CatalogUsage(serviceTypes, definitions, pendingSuggestions);
    }

    /** True when any applicable count is above zero. */
    public boolean inUse() {
        return positive(serviceTypes) || positive(definitions) || positive(pendingSuggestions);
    }

    private static boolean positive(@Nullable Long count) {
        return count != null && count > 0;
    }
}
