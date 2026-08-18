package com.beautica.service.service;

/**
 * A selectable platform category reduced to the two fields free-text search needs:
 * the canonical {@code platform_categories.name} (the value persisted in
 * {@code service_definitions.category}) and the Ukrainian {@code display_name} the
 * user actually sees and types.
 *
 * <p>Produced by {@link PlatformCategoryLabelResolver} from the cached
 * approved+active category list. The search side matches the user's tokens against
 * {@link #displayName()} and then filters {@code service_definitions.category} by
 * {@link #name()} — never the other way round: {@code name} is an opaque
 * SCREAMING_SNAKE wire value ({@code LASH_EXTENSIONS}) that no Ukrainian-speaking
 * client would ever type.
 *
 * <p>Deliberately NOT the entity and NOT {@code PlatformCategoryResponse}: the
 * entity is a JPA aggregate that must not cross a feature boundary, and the
 * response DTO is a controller-facing shape carrying admin-workflow fields
 * (status, request metadata) that have no business in a query planner input.
 */
public record PlatformCategoryLabel(String name, String displayName) {}
