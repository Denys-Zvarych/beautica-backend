package com.beautica.service.dto;

import java.util.List;

/**
 * One category bucket within {@link SalonServiceCatalogResponse}.
 *
 * @param category the raw {@code ServiceDefinition.category} string (e.g. {@code "MANICURE"})
 * @param count    {@code services.size()} — carried as a separate field so the client does
 *                 not need to compute it
 * @param services the leaf rows, reusing {@link ServiceDefinitionResponse} — no separate
 *                  leaf DTO
 */
public record SalonServiceCategoryGroup(
        String category,
        int count,
        List<ServiceDefinitionResponse> services
) {
}
