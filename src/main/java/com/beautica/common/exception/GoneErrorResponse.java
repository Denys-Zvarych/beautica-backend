package com.beautica.common.exception;

/**
 * {@code data} of a {@code 410 Gone} envelope (phase 379): only the stable {@link GoneException#getCode()},
 * never the requested slug.
 */
public record GoneErrorResponse(String code) {
}
