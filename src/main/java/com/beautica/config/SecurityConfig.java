package com.beautica.config;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.filter.AuthRateLimitFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.UUID;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final InternalApiKeyFilter internalApiKeyFilter;
    private final Environment environment;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          AuthRateLimitFilter authRateLimitFilter,
                          InternalApiKeyFilter internalApiKeyFilter,
                          Environment environment) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authRateLimitFilter = authRateLimitFilter;
        this.internalApiKeyFilter = internalApiKeyFilter;
        this.environment = environment;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean isDevProfile = environment.acceptsProfiles(Profiles.of("local | test"));

        var authorizeConfig = http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            "/api/v1/auth/register",
                            "/api/v1/auth/register/independent-master",
                            "/api/v1/auth/login",
                            "/api/v1/auth/refresh",
                            "/api/v1/auth/invite/accept",
                            "/api/v1/auth/verify-email",
                            "/api/v1/auth/resend-verification",
                            // Phase 11.2 / 11.3 — password-reset flow is unauthenticated by design.
                            // The caller holds no JWT (that is why they are resetting the password).
                            "/api/v1/auth/forgot-password",
                            "/api/v1/auth/reset-password"
                    ).permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/auth/invite/validate").permitAll();
                    if (isDevProfile) {
                        auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html").permitAll();
                        auth.requestMatchers("/api-docs/**", "/api-docs").permitAll();
                    }
                    auth.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/salons/{salonId}").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/salons/{salonId}/masters").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/masters/{masterId}").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/masters/{masterId}/services").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/masters/{masterId}/reviews").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/reviews/**").permitAll();
                    // Phase 13.6 (Public Salon Profile). "/reviews/summary" is registered
                    // BEFORE "/reviews" defensively; in practice PathPattern matching for
                    // "/api/v1/salons/{salonId}/reviews" is an exact-segment-count match (no
                    // trailing **), so it does not match the extra "/summary" segment either
                    // way — order does not actually change behaviour here.
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/salons/{salonId}/reviews/summary").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/salons/{salonId}/reviews").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/salons/{salonId}/services").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/service-categories").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/service-types").permitAll();
                    // Self-service category approval pages — token-authenticated
                    // (the unguessable single-use token IS the credential), so the
                    // routes are permitAll at the Spring Security layer. The GET
                    // review page performs NO state change; only the POST
                    // approve/reject mutate. POST /requests (provider role-gated)
                    // and GET /approved (any authenticated user) are NOT listed
                    // here and fall through to anyRequest().authenticated().
                    // Token rides as the trailing path segment (review/{token}) to keep
                    // it out of the email-link query string (proxy/Referer log exposure).
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/service-categories/requests/review/*").permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/service-categories/requests/approve").permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/service-categories/requests/reject").permitAll();
                    // Phase 16.8 service-type suggestion review pages — token-authenticated
                    // (the unguessable single-use token IS the credential), identical to the
                    // category-request review routes above. GET review performs NO state
                    // change; only the POST approve/reject mutate. POST /service-types/suggest
                    // (provider role-gated) is NOT listed and falls through to authenticated().
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/service-types/suggestions/review").permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/service-types/suggestions/approve").permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/service-types/suggestions/reject").permitAll();
                    // Phase 10.4 KATOTTH locality reference reads — DELIBERATE
                    // public allow-list (not incidental). These three GETs
                    // expose only non-sensitive reference data (oblast/city/
                    // district name_uk/name_en + stable KATOTTH codes + a
                    // boolean hasDistricts) — no provider counts, no PII, no
                    // owner UUIDs — and back the unauthenticated mobile
                    // cascading picker, exactly like /service-categories and
                    // /service-types above. GET-only and path-scoped: writes
                    // are Flyway-only (no controller mutation surface).
                    //
                    // Rate-limit posture (Phase 10.7 Step 1 decision): NOT
                    // added to the Bucket4j per-IP throttle (AuthRateLimitFilter,
                    // scoped to /auth/*, /devices/token, /media/*, /slots).
                    // Rationale: the taxonomy is a tiny, fully static dataset
                    // (V53 seed) served by LocationQueryService behind a
                    // long-lived @Cacheable with NO write/eviction path — after
                    // a single cold miss per JVM every response is cache-served
                    // with zero DB cost, so the uncached-miss surface is bounded
                    // by deploy frequency, not request volume. This matches the
                    // existing un-throttled public reference reads
                    // (/service-categories, /service-types); a dedicated bucket
                    // would be wasted state. Revisit only if Part B adds a
                    // dynamic/parameterised locality query.
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/locations/oblasts").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/locations/oblasts/{oblastId}/cities").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/locations/cities/{cityId}/districts").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/search/**").permitAll();
                    // Phase 13.1 — Guest Booking Link. The public booking page
                    // (beautica.app/book/{slug}) is opened by unauthenticated clients
                    // from a shared link, so GET /api/v1/book/** is permitAll. Placed
                    // before anyRequest().authenticated() below. CSRF is already disabled
                    // on /api/** and the response carries no owner identifiers (§I-2).
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/book/**").permitAll();
                    // Phase 13.2 — Phone OTP & Guest Token. Unauthenticated clients on the
                    // public booking page request and verify an SMS code before they hold any
                    // JWT, so POST /api/v1/book/otp/{send,verify} must be permitAll. Scoped
                    // tightly to /otp/** (not all POST /book/**) so future authenticated
                    // booking-write endpoints under /book are not accidentally opened. A
                    // dedicated per-IP Bucket4j throttle guards /send (AuthRateLimitFilter);
                    // the per-phone limit + generic-401 verify guard live in PhoneOtpService.
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/book/otp/**").permitAll();
                    // Phase 13.3 — Guest Booking Creation. An unauthenticated client on the
                    // public booking page holds only a guest JWT (type=GUEST, no Spring
                    // principal), so the create-booking POST must be permitAll at the filter
                    // chain; GuestBookingService validates the guest token itself. Scoped
                    // tightly to POST /api/v1/book/*/booking (single path segment for the slug,
                    // then literal /booking) so no other authenticated POST under /book is opened.
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/book/*/booking").permitAll();
                    // Phase 13.4 — Guest Booking Cancellation by Link. The cancel link in
                    // the confirmation SMS is opened by an unauthenticated guest holding no
                    // JWT; the one-time cancel_token in the path is the credential. GET is
                    // already covered by the /api/v1/book/** permitAll above; the cancel POST
                    // must also be permitAll. Scoped tightly to POST /api/v1/book/cancel/*
                    // (literal "cancel" segment, then exactly one token segment) — a different
                    // path shape from POST /api/v1/book/*/booking (slug, then literal
                    // "booking"), so the two matchers cannot shadow or collide with each other.
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/book/cancel/*").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/salons/{salonId}/portfolio").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/masters/{masterId}/portfolio").permitAll();
                    // Internal management API — authenticated by InternalApiKeyFilter (X-Internal-Key header),
                    // not by JWT. Spring Security must not reject these as unauthenticated; the filter handles
                    // all access control. See InternalApiKeyFilter for the key-comparison logic.
                    auth.requestMatchers("/api/v1/internal/**").permitAll();
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(authRateLimitFilter, JwtAuthenticationFilter.class)
                .addFilterBefore(internalApiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        return authorizeConfig.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(frontendBaseUrl));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) ->
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }

    /**
     * Filter-chain (pre-controller) access-denied handler for already-authenticated
     * principals whose request is rejected by {@code ExceptionTranslationFilter} (e.g. a
     * URL-matcher authorization rule). Method-security ({@code @PreAuthorize}) denials are
     * handled by {@code GlobalExceptionHandler#handleAuthorizationDenied} instead.
     *
     * <p>Logs the same self-diagnosing line — HTTP method + request URI + the principal's
     * authorities + the non-PII subject (the user id in {@link Authentication#getDetails()},
     * set by {@code JwtAuthenticationFilter}) — then delegates to the default 403 response so
     * the wire contract is unchanged. Never logs the email ({@code getName()}), the JWT, or
     * the query string.
     */
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        AccessDeniedHandlerImpl delegate = new AccessDeniedHandlerImpl();
        return (request, response, accessDeniedException) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            Object subject = (auth != null && auth.getDetails() instanceof UUID userId)
                    ? userId
                    : "[unknown]";
            log.warn("Access denied: {} {} for authorities={} (subject={})",
                    request.getMethod(),
                    request.getRequestURI(),
                    auth != null ? auth.getAuthorities() : "[none]",
                    subject);
            delegate.handle(request, response, accessDeniedException);
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
