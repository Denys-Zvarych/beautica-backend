package com.beautica.common.exception;

/**
 * B502 — {@code data} of the 409 for {@link CatalogRequestConflictException}:
 * {@code {"code":"REQUEST_NOT_PENDING"}}, {@code {"code":"DUPLICATE_PENDING"}} or {@code {"code":"TYPE_EXISTS"}}.
 */
public record CatalogRequestConflictResponse(String code) {}
