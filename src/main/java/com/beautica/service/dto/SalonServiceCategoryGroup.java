package com.beautica.service.dto;

import java.util.List;

/**
 * One category bucket within {@link SalonServiceCatalogResponse}.
 *
 * @param category    the raw {@code ServiceDefinition.category} wire slug (e.g.
 *                    {@code "HARDWARE_COSMETOLOGY"}) — kept for clients that key off the
 *                    canonical code
 * @param displayName human-readable label for {@code category} (e.g. {@code "Апаратна
 *                    косметологія"}), resolved from the approved+active {@code
 *                    PlatformCategory} rows. Falls back to {@code category} itself when the
 *                    category has no matching platform-category entry (inactive/legacy/unknown
 *                    — never null/blank).
 * @param count       {@code services.size()} — carried as a separate field so the client does
 *                    not need to compute it
 * @param services    the leaf rows, reusing {@link ServiceDefinitionResponse} — no separate
 *                    leaf DTO
 */
public record SalonServiceCategoryGroup(
        String category,
        String displayName,
        int count,
        List<ServiceDefinitionResponse> services
) {
}
