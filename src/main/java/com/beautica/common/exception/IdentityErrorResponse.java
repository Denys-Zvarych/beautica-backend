package com.beautica.common.exception;

/** B514 — {@code data} of an {@link IdentityException} error envelope: the stable code the app branches on. */
public record IdentityErrorResponse(String code) {
}
