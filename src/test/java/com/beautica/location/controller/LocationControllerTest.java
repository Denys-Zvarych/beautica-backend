package com.beautica.location.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.location.dto.CityDistrictResponse;
import com.beautica.location.dto.CityResponse;
import com.beautica.location.dto.OblastResponse;
import com.beautica.location.service.LocationQueryService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link LocationController} — HTTP contract
 * only. Locality data is stubbed via {@code @MockBean LocationQueryService};
 * no database, no cache (those are pinned in
 * {@code LocationQueryService*Test}).
 *
 * <p>The inner {@code @TestConfiguration} reproduces the production
 * {@code SecurityConfig} contract for these paths: the 3 locality GETs are
 * {@code permitAll()} and everything else is {@code authenticated()}. This
 * slice therefore also guards against matcher over-broadening — a
 * representative non-locality request must still be rejected with 401.
 */
@WebMvcTest(LocationController.class)
@Import(WebMvcTestSupport.class)
@DisplayName("LocationController — @WebMvcTest slice")
class LocationControllerTest {

    private static final Logger log = LoggerFactory.getLogger(LocationControllerTest.class);

    // ── Security configuration — mirrors production SecurityConfig for these paths ──

    @TestConfiguration
    static class SecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/locations/oblasts").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/locations/oblasts/{oblastId}/cities").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/locations/cities/{cityId}/districts").permitAll()
                            .anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    // ── Slice infrastructure ──────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LocationQueryService locationQueryService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // ── fixtures ──────────────────────────────────────────────────────────────

    private final UUID kyivOblastId = UUID.randomUUID();
    private final UUID kyivCityId = UUID.randomUUID();
    private final UUID holosiivDistrictId = UUID.randomUUID();

    // ── GET /api/v1/locations/oblasts ─────────────────────────────────────────

    @Test
    @DisplayName("GET /locations/oblasts — 200 ApiResponse<List<OblastResponse>> with field-level shape, no auth")
    void should_return200WithOblastList_when_getOblastsWithoutAuth() throws Exception {
        when(locationQueryService.listOblasts()).thenReturn(List.of(
                new OblastResponse(kyivOblastId, "UA80000000000093317", "Київ", "Kyiv")));
        log.debug("Act: GET /api/v1/locations/oblasts — public reference endpoint, no auth header");

        mockMvc.perform(get("/api/v1/locations/oblasts").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(kyivOblastId.toString()))
                .andExpect(jsonPath("$.data[0].katotthCode").value("UA80000000000093317"))
                .andExpect(jsonPath("$.data[0].nameUk").value("Київ"))
                .andExpect(jsonPath("$.data[0].nameEn").value("Kyiv"));
    }

    // ── GET /api/v1/locations/oblasts/{oblastId}/cities ───────────────────────

    @Test
    @DisplayName("GET /locations/oblasts/{id}/cities — 200 with hasDistricts flag, full field shape")
    void should_return200WithCityList_when_getCitiesByOblast() throws Exception {
        when(locationQueryService.listCitiesByOblast(kyivOblastId)).thenReturn(List.of(
                new CityResponse(kyivCityId, kyivOblastId, "UA80000000000093317", "Київ", "Kyiv", true)));
        log.debug("Act: GET /api/v1/locations/oblasts/{}/cities — assert hasDistricts surfaces", kyivOblastId);

        mockMvc.perform(get("/api/v1/locations/oblasts/{oblastId}/cities", kyivOblastId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(kyivCityId.toString()))
                .andExpect(jsonPath("$.data[0].oblastId").value(kyivOblastId.toString()))
                .andExpect(jsonPath("$.data[0].katotthCode").value("UA80000000000093317"))
                .andExpect(jsonPath("$.data[0].nameUk").value("Київ"))
                .andExpect(jsonPath("$.data[0].nameEn").value("Kyiv"))
                .andExpect(jsonPath("$.data[0].hasDistricts").value(true));
    }

    @Test
    @DisplayName("GET /locations/oblasts/{id}/cities — empty data array when oblast has no cities")
    void should_return200WithEmptyArray_when_oblastHasNoCities() throws Exception {
        when(locationQueryService.listCitiesByOblast(kyivOblastId)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/locations/oblasts/{oblastId}/cities", kyivOblastId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("GET /locations/oblasts/{id}/cities — malformed UUID path var → clean 400, not 500, service untouched")
    void should_return400_when_oblastIdPathVarIsNotAUuid() throws Exception {
        log.debug("Act: GET /api/v1/locations/oblasts/not-a-uuid/cities — must be a clean 400");

        mockMvc.perform(get("/api/v1/locations/oblasts/{oblastId}/cities", "not-a-uuid")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'oblastId'"));

        verifyNoInteractions(locationQueryService);
    }

    // ── GET /api/v1/locations/cities/{cityId}/districts ───────────────────────

    @Test
    @DisplayName("GET /locations/cities/{id}/districts — 200 with full district field shape")
    void should_return200WithDistrictList_when_getDistrictsByCity() throws Exception {
        when(locationQueryService.listDistrictsByCity(kyivCityId)).thenReturn(List.of(
                new CityDistrictResponse(holosiivDistrictId, kyivCityId,
                        "UA80B1", "Голосіївський район", "Holosiivskyi district")));
        log.debug("Act: GET /api/v1/locations/cities/{}/districts", kyivCityId);

        mockMvc.perform(get("/api/v1/locations/cities/{cityId}/districts", kyivCityId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(holosiivDistrictId.toString()))
                .andExpect(jsonPath("$.data[0].cityId").value(kyivCityId.toString()))
                .andExpect(jsonPath("$.data[0].katotthCode").value("UA80B1"))
                .andExpect(jsonPath("$.data[0].nameUk").value("Голосіївський район"))
                .andExpect(jsonPath("$.data[0].nameEn").value("Holosiivskyi district"));
    }

    @Test
    @DisplayName("GET /locations/cities/{id}/districts — malformed UUID path var → clean 400, not 500")
    void should_return400_when_cityIdPathVarIsNotAUuid() throws Exception {
        log.debug("Act: GET /api/v1/locations/cities/garbage/districts — must be a clean 400");

        mockMvc.perform(get("/api/v1/locations/cities/{cityId}/districts", "garbage")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'cityId'"));

        verifyNoInteractions(locationQueryService);
    }

    // ── Security regression — no matcher over-broadening ──────────────────────

    @Test
    @DisplayName("POST /locations/oblasts — 401: permitAll is GET-only, write verb still requires auth (no over-broadening)")
    void should_return401_when_postToOblastsPath() throws Exception {
        log.debug("Act: POST /api/v1/locations/oblasts — permitAll is scoped to GET; POST must be 401");

        mockMvc.perform(post("/api/v1/locations/oblasts").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET a non-locality path — 401: permitAll did not leak past the 3 locality matchers")
    void should_return401_when_requestingNonLocalityProtectedPath() throws Exception {
        log.debug("Act: GET /api/v1/locations/secret — outside the 3 permitAll matchers, must be 401");

        mockMvc.perform(get("/api/v1/locations/secret").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(locationQueryService);
    }
}
