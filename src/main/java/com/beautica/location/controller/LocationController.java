package com.beautica.location.controller;

import com.beautica.common.ApiResponse;
import com.beautica.location.dto.CityDistrictResponse;
import com.beautica.location.dto.CityResponse;
import com.beautica.location.dto.OblastResponse;
import com.beautica.location.service.LocationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Public, read-only KATOTTH locality reference endpoints feeding the (future)
 * mobile cascading picker: oblast → city → urban district.
 *
 * <p>Non-sensitive reference data — these GETs are {@code permitAll()} in
 * {@code SecurityConfig}, consistent with the existing public reference
 * endpoints ({@code /service-categories}, {@code /service-types}). No
 * {@code @PreAuthorize} (§D: no authz placement needed for public reference
 * reads). No write endpoints — locality mutations are Flyway-only.
 *
 * <p>HTTP concerns only: parse path, delegate, wrap in {@link ApiResponse}.
 * Caching and the no-N+1 {@code hasDistricts} computation live in
 * {@link LocationQueryService}. Endpoints are auto-discovered by SpringDoc
 * (the mobile Dio client is generated from the resulting spec later).
 *
 * <p><b>Phase 10.7 — rate-limit posture (decision, not a TODO):</b> these
 * GETs are intentionally NOT added to the Bucket4j per-IP throttle
 * ({@code AuthRateLimitFilter}, scoped to {@code /auth/*} and a few mutating
 * paths). The KATOTTH taxonomy is a tiny, fully static dataset (V53 seed)
 * served by {@link LocationQueryService} behind a long-lived
 * {@code @Cacheable} with no write/eviction path: after one cold miss per
 * JVM the uncached-miss surface is bounded by deploy frequency, not request
 * volume, so a dedicated bucket would be wasted state. This is consistent
 * with the sibling un-throttled public reference reads
 * ({@code /service-categories}, {@code /service-types}). The full rationale
 * and the explicit security-config allow-list live in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LocationController {

    private final LocationQueryService locationQueryService;

    @GetMapping("/locations/oblasts")
    public ApiResponse<List<OblastResponse>> getOblasts() {
        return ApiResponse.ok(locationQueryService.listOblasts());
    }

    @GetMapping("/locations/oblasts/{oblastId}/cities")
    public ApiResponse<List<CityResponse>> getCitiesByOblast(@PathVariable UUID oblastId) {
        return ApiResponse.ok(locationQueryService.listCitiesByOblast(oblastId));
    }

    @GetMapping("/locations/cities/{cityId}/districts")
    public ApiResponse<List<CityDistrictResponse>> getDistrictsByCity(@PathVariable UUID cityId) {
        return ApiResponse.ok(locationQueryService.listDistrictsByCity(cityId));
    }
}
