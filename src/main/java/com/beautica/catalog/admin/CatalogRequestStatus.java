package com.beautica.catalog.admin;

/**
 * B502 — the list filter ({@code ?status=}). Both request tables store exactly these three values
 * ({@code PlatformCategoryStatus}, {@code ServiceTypeSuggestionStatus}).
 */
public enum CatalogRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}
