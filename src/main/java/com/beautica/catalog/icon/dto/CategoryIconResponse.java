package com.beautica.catalog.icon.dto;

import org.springframework.lang.Nullable;

/**
 * B539 — {@code data} of {@code PUT /api/v1/internal/catalog/categories/{name}/icon}.
 *
 * @param name    the category wire name
 * @param iconUrl the public URL of the stored canonical SVG (unchanged on a same-icon no-op); {@code null} only when R2
 *                is disabled, which the PUT already refuses with 503
 */
public record CategoryIconResponse(String name, @Nullable String iconUrl) {}
