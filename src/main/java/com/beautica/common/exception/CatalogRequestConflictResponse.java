package com.beautica.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.lang.Nullable;

/**
 * B502 — {@code data} of the 409 for {@link CatalogRequestConflictException}:
 * {@code {"code":"REQUEST_NOT_PENDING"}}, {@code {"code":"DUPLICATE_PENDING"}} or {@code {"code":"TYPE_EXISTS"}}; B504
 * adds {@code CATEGORY_NAME_EXISTS} and {@code CATEGORY_NOT_APPROVED}.
 *
 * <p>B505 adds {@code CATALOG_IN_USE} with the usage counts flattened next to the code, e.g.
 * {@code {"code":"CATALOG_IN_USE","definitions":3}} for a type or
 * {@code {"code":"CATALOG_IN_USE","serviceTypes":2,"definitions":0,"pendingSuggestions":1}} for a category. Null counts
 * are omitted ({@link JsonInclude.Include#NON_NULL}), so every earlier code still serialises to exactly
 * {@code {"code":"…"}} — no existing body changes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogRequestConflictResponse(String code, @Nullable Long serviceTypes, @Nullable Long definitions,
                                             @Nullable Long pendingSuggestions) {

    public CatalogRequestConflictResponse(String code) {
        this(code, null, null, null);
    }

    /** {@code usage == null} (every code but {@code CATALOG_IN_USE}, or its FK backstop) = the code alone. */
    public static CatalogRequestConflictResponse of(String code, @Nullable CatalogUsage usage) {
        return usage == null
                ? new CatalogRequestConflictResponse(code)
                : new CatalogRequestConflictResponse(code, usage.serviceTypes(), usage.definitions(),
                        usage.pendingSuggestions());
    }
}
