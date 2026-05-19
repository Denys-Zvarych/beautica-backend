package com.beautica.config;

import com.beautica.auth.JwtAuthenticationFilter;
import com.beautica.auth.filter.AuthRateLimitFilter;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final Environment environment;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          AuthRateLimitFilter authRateLimitFilter,
                          Environment environment) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authRateLimitFilter = authRateLimitFilter;
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
                        .authenticationEntryPoint(unauthorizedEntryPoint()))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/v1/auth/register", "/api/v1/auth/register/independent-master", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/invite/accept", "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification").permitAll();
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
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/service-categories").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/service-types").permitAll();
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
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/salons/{salonId}/portfolio").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/masters/{masterId}/portfolio").permitAll();
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(authRateLimitFilter, JwtAuthenticationFilter.class);

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
