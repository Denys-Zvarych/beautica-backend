package com.beautica.service.controller;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.WebMvcTestSupport;
import com.beautica.service.service.ServiceCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression slice for the field-keyed, human-readable Bean Validation message contract on
 * {@link com.beautica.service.controller.ServiceController}'s create endpoint.
 *
 * <p><b>Originating bug.</b> Creating a service with {@code baseDurationMinutes=1123} returned a
 * 400 whose field message was the unreadable Bean-Validation default
 * ("must be less than or equal to 480"). After the fix the constraints carry explicit
 * {@code message=} strings, and {@code GlobalExceptionHandler.handleValidation} surfaces them in
 * the top-level {@code errors} map (field → message) that the mobile
 * {@code ErrorMapperInterceptor._extractFieldErrors} consumes to render inline field errors.
 *
 * <p>The sibling {@link ServiceControllerTest} already asserts the 400 <i>status</i> for these
 * inputs. This class is deliberately separate: it locks the exact <b>per-field message strings</b>
 * — the actual wire contract the mobile client now depends on. {@code CreateServiceDefinitionRequest}
 * is used as the representative DTO covering every constraint class touched across the ~22 DTOs:
 * {@code @NotBlank}, {@code @Size(max)}, {@code @Min}/{@code @Max}, {@code @Positive},
 * {@code @DecimalMax}, and the class-level {@code @ServicePriceValid}.
 *
 * <p>JSON bodies are raw strings (not {@code ObjectMapper.writeValueAsString}) on purpose:
 * {@code baseDurationMinutes} is a primitive {@code int} on the record, so an out-of-range or
 * negative value cannot be represented through the typed DTO constructor and must be sent as wire
 * JSON to reproduce the original bug faithfully.
 */
@WebMvcTest(ServiceController.class)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
@Import(WebMvcTestSupport.class)
@DisplayName("ServiceController — validation message contract (regression)")
class ServiceValidationMessageContractTest {

    private static final Logger log = LoggerFactory.getLogger(ServiceValidationMessageContractTest.class);

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http,
                JwtAuthenticationFilter jwtFilter) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((req, res, exc) ->
                                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ServiceCatalogService serviceCatalogService;

    @MockBean(name = "authz")
    private AuthorizationService authorizationService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static RequestPostProcessor owner() {
        var authority = new SimpleGrantedAuthority("ROLE_" + Role.SALON_OWNER.name());
        var token = new UsernamePasswordAuthenticationToken("owner@beautica.test", null, List.of(authority));
        token.setDetails(UUID.randomUUID());
        return authentication(token);
    }

    /** Performs the POST and returns the result actions for {@code errors.*} assertions. */
    private org.springframework.test.web.servlet.ResultActions postService(UUID salonId, String body)
            throws Exception {
        when(authorizationService.canManageSalon(any(), eq(salonId))).thenReturn(true);
        return mockMvc.perform(post("/api/v1/salons/" + salonId + "/services")
                .with(owner())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ── 1. Originating bug — baseDurationMinutes @Max(480) ─────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — errors.baseDurationMinutes carries the readable @Max message when duration=1123 (originating bug)")
    void should_returnReadableMaxMessage_when_durationIs1123() throws Exception {
        var salonId = UUID.randomUUID();
        String body = "{\"name\":\"Manicure\",\"category\":\"MANICURE\","
                + "\"baseDurationMinutes\":1123,\"bufferMinutesAfter\":0,"
                + "\"priceType\":\"FIXED\",\"price\":350.00}";

        log.debug("Act: POST create-service with baseDurationMinutes=1123 — exact @Max message must surface under errors.baseDurationMinutes");
        postService(salonId, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.baseDurationMinutes")
                        .value("Duration must be at most 480 minutes (8 hours)"));

        verify(serviceCatalogService, never()).addServiceToSalon(any(), any());
    }

    @Test
    @DisplayName("POST /salons/{id}/services — errors.baseDurationMinutes carries the readable @Positive message when duration=0")
    void should_returnReadablePositiveMessage_when_durationIsZero() throws Exception {
        var salonId = UUID.randomUUID();
        String body = "{\"name\":\"Manicure\",\"category\":\"MANICURE\","
                + "\"baseDurationMinutes\":0,\"bufferMinutesAfter\":0,"
                + "\"priceType\":\"FIXED\",\"price\":350.00}";

        log.debug("Act: POST create-service with baseDurationMinutes=0 — @Positive readable message must surface");
        postService(salonId, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.baseDurationMinutes")
                        .value("Duration must be a positive number of minutes"));
    }

    @Test
    @DisplayName("POST /salons/{id}/services — errors.baseDurationMinutes carries the readable @Positive message when duration is negative")
    void should_returnReadablePositiveMessage_when_durationIsNegative() throws Exception {
        var salonId = UUID.randomUUID();
        String body = "{\"name\":\"Manicure\",\"category\":\"MANICURE\","
                + "\"baseDurationMinutes\":-5,\"bufferMinutesAfter\":0,"
                + "\"priceType\":\"FIXED\",\"price\":350.00}";

        log.debug("Act: POST create-service with baseDurationMinutes=-5 — @Positive readable message must surface");
        postService(salonId, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.baseDurationMinutes")
                        .value("Duration must be a positive number of minutes"));
    }

    // ── 2. name is no longer @NotBlank ─────────────────────────────────────────
    // @NotBlank was removed from CreateServiceDefinitionRequest.name: the name is
    // optional and the service layer defaults it to the selected service type's
    // Ukrainian display name when blank. A blank/whitespace name therefore passes
    // Bean Validation and is accepted by the controller (service is mocked here).

    @Test
    @DisplayName("POST /salons/{id}/services — a blank name passes validation (no @NotBlank on name)")
    void should_acceptBlankName_when_nameIsOptional() throws Exception {
        var salonId = UUID.randomUUID();
        // serviceTypeId is now @NotNull, so it must be present for the request to reach the
        // controller body — otherwise a missing-type violation would mask the point of this
        // test, which is that a BLANK NAME is not itself a Bean Validation violation.
        String body = "{\"name\":\"   \",\"category\":\"MANICURE\",\"serviceTypeId\":\"" + UUID.randomUUID() + "\","
                + "\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0,"
                + "\"priceType\":\"FIXED\",\"price\":350.00}";

        log.debug("Act: POST create-service with blank name — must NOT be rejected for a missing name");
        postService(salonId, body)
                .andExpect(status().isCreated());

        verify(serviceCatalogService).addServiceToSalon(eq(salonId), any());
    }

    // ── 3. @Size(max) — name overflow ──────────────────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — errors.name carries the readable @Size(max=100) message when name is 101 chars")
    void should_returnReadableSizeMessage_when_nameExceedsMax() throws Exception {
        var salonId = UUID.randomUUID();
        String longName = "a".repeat(101);
        String body = "{\"name\":\"" + longName + "\",\"category\":\"MANICURE\","
                + "\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0,"
                + "\"priceType\":\"FIXED\",\"price\":350.00}";

        log.debug("Act: POST create-service with 101-char name — @Size(max=100) readable message must surface under errors.name");
        postService(salonId, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Name must be at most 100 characters"));
    }

    // ── 4. @Max — bufferMinutesAfter numeric range ─────────────────────────────

    @Test
    @DisplayName("POST /salons/{id}/services — errors.bufferMinutesAfter carries the readable @Max(120) message when buffer=121")
    void should_returnReadableMaxMessage_when_bufferExceedsMax() throws Exception {
        var salonId = UUID.randomUUID();
        String body = "{\"name\":\"Manicure\",\"category\":\"MANICURE\","
                + "\"baseDurationMinutes\":60,\"bufferMinutesAfter\":121,"
                + "\"priceType\":\"FIXED\",\"price\":350.00}";

        log.debug("Act: POST create-service with bufferMinutesAfter=121 — @Max(120) readable message must surface");
        postService(salonId, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bufferMinutesAfter")
                        .value("Buffer must be at most 120 minutes"));
    }

    // ── 5. Class-level @ServicePriceValid — RANGE invariant priceMax <= priceMin ─

    @Test
    @DisplayName("POST /salons/{id}/services — errors.priceMax carries the @ServicePriceValid invariant message when priceMax < priceMin")
    void should_returnReadablePriceMaxMessage_when_rangeMaxLessThanMin() throws Exception {
        var salonId = UUID.randomUUID();
        // RANGE with priceMax (500) <= priceMin (800) — class-level validator targets the priceMax node.
        String body = "{\"name\":\"Range Manicure\",\"category\":\"MANICURE\","
                + "\"baseDurationMinutes\":60,\"bufferMinutesAfter\":0,"
                + "\"priceType\":\"RANGE\",\"priceMin\":800.00,\"priceMax\":500.00}";

        log.debug("Act: POST create-service RANGE with priceMax < priceMin — class-level @ServicePriceValid message must surface under errors.priceMax");
        postService(salonId, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.priceMax")
                        .value("priceMax must be strictly greater than priceMin"));

        verify(serviceCatalogService, never()).addServiceToSalon(any(), any());
    }

    // ── 6. Multi-field — every violation appears as a distinct key ─────────────

    @Test
    @DisplayName("POST /salons/{id}/services — errors map carries a distinct key per simultaneous violation")
    void should_returnAllFieldKeys_when_multipleViolationsAtOnce() throws Exception {
        var salonId = UUID.randomUUID();
        // Two independent field violations in one request — each must surface under its
        // own errors.* key with the readable message. (name is no longer @NotBlank, so a
        // blank name is not a violation; we pick two fields whose blank/out-of-range value
        // deterministically fails exactly one constraint each.)
        //   baseDuration → @Max(480) ("Duration must be at most 480 minutes (8 hours)")
        //   buffer       → @Max(120) ("Buffer must be at most 120 minutes")
        String body = "{\"name\":\"Manicure\",\"category\":\"MANICURE\","
                + "\"baseDurationMinutes\":1123,\"bufferMinutesAfter\":200,"
                + "\"priceType\":\"FIXED\",\"price\":350.00}";

        log.debug("Act: POST create-service with duration+buffer invalid — each must appear as its own errors.* key");
        postService(salonId, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.baseDurationMinutes")
                        .value("Duration must be at most 480 minutes (8 hours)"))
                .andExpect(jsonPath("$.errors.bufferMinutesAfter")
                        .value("Buffer must be at most 120 minutes"))
                // top-level message stays the generic, non-leaking sentinel
                .andExpect(jsonPath("$.message").value("Validation failed — check request parameters"));

        verify(serviceCatalogService, never()).addServiceToSalon(any(), any());
    }
}
