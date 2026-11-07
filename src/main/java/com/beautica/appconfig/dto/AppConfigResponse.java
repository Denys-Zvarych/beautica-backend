package com.beautica.appconfig.dto;

import com.beautica.config.ClientVersionProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase 437 — body of {@code GET /api/v1/app/config}. Additive only: an {@code ios} block may be added later,
 * {@code android} is never renamed or removed.
 */
@Schema(description = "Client compatibility config")
public record AppConfigResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        AndroidAppConfig android
) {

    public static AppConfigResponse from(ClientVersionProperties properties) {
        return new AppConfigResponse(AndroidAppConfig.from(properties.android()));
    }
}
