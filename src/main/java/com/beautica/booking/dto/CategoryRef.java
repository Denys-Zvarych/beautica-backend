package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** A service category of the public master booking page (phase 380 D2): id + Ukrainian name. */
public record CategoryRef(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String name
) {
}
