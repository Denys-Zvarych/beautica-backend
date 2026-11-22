package com.beautica.common.exception;

/**
 * B502 — {@code data} of the 409 for {@link CatalogRequestConflictException}:
 * {@code {"code":"REQUEST_NOT_PENDING"}}, {@code {"code":"DUPLICATE_PENDING"}} or {@code {"code":"TYPE_EXISTS"}}; B504 adds {@code CATEGORY_NAME_EXISTS} and {@code CATEGORY_NOT_APPROVED}.
 */
public record CatalogRequestConflictResponse(String code) {}
