package com.beautica.location.dto;

import com.beautica.location.entity.Oblast;

import java.util.UUID;

/**
 * Public read-only projection of an {@link Oblast} for the cascading
 * locality picker (top tier).
 *
 * <p>Carries only the four non-sensitive reference fields — surrogate id
 * (needed as the {@code oblastId} path variable for the cities call),
 * stable KATOTTH code, and the localized names. No internal flags.
 */
public record OblastResponse(
        UUID id,
        String katotthCode,
        String nameUk,
        String nameEn
) {

    public static OblastResponse from(Oblast oblast) {
        return new OblastResponse(
                oblast.getId(),
                oblast.getKatotthCode(),
                oblast.getNameUk(),
                oblast.getNameEn());
    }
}
