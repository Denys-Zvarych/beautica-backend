package com.beautica.appconfig.dto;

import com.beautica.config.ClientVersionProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase 437 — Android block of {@code GET /api/v1/app/config}. Stable contract (mobile 438): fields are never
 * renamed or removed and their types never change; new fields may be added.
 *
 * @param minVersionCode    builds with a lower Android {@code versionCode} (pubspec {@code +N}) must update
 * @param latestVersionCode the newest {@code versionCode} on Play; lower builds may be offered an update
 * @param storeUrl          absolute {@code https://play.google.com/...} listing URL
 */
@Schema(description = "Android version window")
public record AndroidAppConfig(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0",
                description = "Minimum supported Android versionCode (pubspec +N build number)", example = "0")
        int minVersionCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0",
                description = "Latest Android versionCode on Google Play", example = "0")
        int latestVersionCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Google Play listing URL",
                example = "https://play.google.com/store/apps/details?id=com.beautica.app")
        String storeUrl
) {

    public static AndroidAppConfig from(ClientVersionProperties.Android android) {
        return new AndroidAppConfig(android.minVersionCode(), android.latestVersionCode(), android.storeUrl());
    }
}
