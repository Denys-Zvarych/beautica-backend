package com.beautica.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Phase 437 — app version compatibility values served by {@code GET /api/v1/app/config}.
 *
 * <p>Binding prefix {@code app.client-version} (defaults in {@code application.yml}, which never block anyone):
 * <ul>
 *   <li>{@code android.min-version-code} (env {@code APP_ANDROID_MIN_VERSION_CODE}, default {@code 0}) — builds
 *       below this Android {@code versionCode} (pubspec {@code +N}) are blocked by the app (mobile phase 438).</li>
 *   <li>{@code android.latest-version-code} (env {@code APP_ANDROID_LATEST_VERSION_CODE}, default {@code 0}) —
 *       the newest build on Play; older builds get a soft update prompt.</li>
 *   <li>{@code android.store-url} (env {@code APP_ANDROID_STORE_URL}) — the Play listing.</li>
 * </ul>
 *
 * <p>Validated at boot: both codes in {@code [0, }{@value Android#MAX_VERSION_CODE}{@code ]} (the Play
 * {@code versionCode} ceiling), {@code latest >= min} (always — also when latest is the {@code 0} default, so a lone
 * typo'd {@code APP_ANDROID_MIN_VERSION_CODE} can never force-block every installed build), store URL on
 * {@code https://play.google.com/}. A bad value fails boot and is never served; the effective values are logged once
 * at startup by {@link ClientVersionStartupLogger}. The values are in-memory, so the
 * endpoint needs no server cache. An {@code ios} block may be added later (additive).
 */
@Validated
@ConfigurationProperties(prefix = "app.client-version")
public record ClientVersionProperties(@Valid @NotNull Android android) {

    /** Android build-number window and store link. */
    public record Android(
            @Min(0) @Max(MAX_VERSION_CODE) int minVersionCode,
            @Min(0) @Max(MAX_VERSION_CODE) int latestVersionCode,
            @NotBlank @Pattern(regexp = STORE_URL_REGEX) String storeUrl
    ) {

        /** Google Play's maximum {@code versionCode}; anything above is a typo, never a real build. */
        public static final int MAX_VERSION_CODE = 2_100_000_000;

        /** Absolute Play URL only; no whitespace, so a CR/LF can never reach the client. */
        static final String STORE_URL_REGEX = "^https://play\\.google\\.com/\\S*$";

        @AssertTrue(message = "APP_ANDROID_LATEST_VERSION_CODE (app.client-version.android.latest-version-code) must be"
                + " >= APP_ANDROID_MIN_VERSION_CODE (app.client-version.android.min-version-code); a min above latest"
                + " would force-block every installed build")
        public boolean isLatestAtLeastMin() {
            return latestVersionCode >= minVersionCode;
        }
    }
}
