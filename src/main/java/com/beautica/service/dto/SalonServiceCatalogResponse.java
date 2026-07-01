package com.beautica.service.dto;

import java.util.List;

/**
 * A salon's public, bookable service catalog grouped by category (Phase 13.6, {@code GET
 * /api/v1/salons/{salonId}/services}).
 *
 * <p>Group order: known/approved+active platform categories sort first, in the same order
 * used by the category picker; a category string with no match (legacy/inactive) sorts
 * alphabetically after every known category. See {@code ServiceCatalogService
 * #getSalonServiceCatalog} for the exact ordering source.
 */
public record SalonServiceCatalogResponse(List<SalonServiceCategoryGroup> categories) {
}
