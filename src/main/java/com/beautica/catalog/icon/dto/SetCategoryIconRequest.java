package com.beautica.catalog.icon.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * B539 — body of {@code PUT /api/v1/internal/catalog/categories/{name}/icon}: the SVG as text (JSON, not multipart, so
 * the W039 {@code adminFetch} proxy stays JSON-only — track D9).
 *
 * <p>{@code @Size} is the ≤ 24 KiB body cap of the phase (in characters). The SVC-59 16 KiB rule itself is the
 * sanitiser's, so a 16–24 KiB upload answers {@code ICON_INVALID} / {@code TOO_LARGE}; only a body past this cap gets
 * the generic validation 400. Content is validated by {@code CategoryIconSanitizer}, never here.
 */
public record SetCategoryIconRequest(
        @NotNull
        @Size(max = SetCategoryIconRequest.MAX_SVG_CHARS)
        String svg
) {
    public static final int MAX_SVG_CHARS = 24 * 1024;
}
