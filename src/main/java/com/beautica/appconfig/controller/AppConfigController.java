package com.beautica.appconfig.controller;

import com.beautica.appconfig.dto.AppConfigResponse;
import com.beautica.common.ApiResponse;
import com.beautica.config.ClientVersionProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 437 — public app compatibility config. {@code permitAll} in {@code SecurityConfig}; anonymous callers
 * are throttled per IP on {@code catalogueBrowseBuckets} ({@code AuthRateLimitFilter}). The body is built from
 * in-memory properties, identical for every caller, so it is cacheable by clients and the edge for 5 minutes.
 */
@RestController
@RequestMapping("/api/v1/app")
@RequiredArgsConstructor
@Tag(name = "App config", description = "Client version compatibility (public)")
public class AppConfigController {

    /** Literal contract value (phase 437); Spring Security's no-cache writer skips a response that already has one. */
    static final String CACHE_CONTROL = "public, max-age=300";

    private final ClientVersionProperties clientVersionProperties;

    @GetMapping("/config")
    @Operation(summary = "Minimum and latest supported app builds",
            description = "Public. Compare against the Android versionCode (pubspec +N), never the semver string.")
    public ResponseEntity<ApiResponse<AppConfigResponse>> getConfig() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(ApiResponse.ok(AppConfigResponse.from(clientVersionProperties)));
    }
}
