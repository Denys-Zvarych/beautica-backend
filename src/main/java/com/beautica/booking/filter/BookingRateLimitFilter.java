package com.beautica.booking.filter;

import com.beautica.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Per-authenticated-user rate limit on the two booking write endpoints that take the
 * per-client advisory lock ({@code BookingRepository.acquireClientAdvisoryLockWithTimeout}):
 * {@code POST /api/v1/bookings} and {@code PATCH /api/v1/bookings/{bookingId}/reschedule}.
 *
 * <p><b>Why user-keyed, unlike every bucket in {@link com.beautica.auth.filter.AuthRateLimitFilter}:</b>
 * that filter is IP-keyed because it runs BEFORE {@code JwtAuthenticationFilter} on mostly
 * {@code permitAll} surfaces, so no principal is available yet. Here the threat model is the
 * opposite: the per-client advisory lock is salted by the CALLER'S OWN authenticated user id,
 * so a single CLIENT account needs no IP diversity at all to serialize every one of its own
 * requests on the identical lock. Without a per-user throttle, N concurrent requests from one
 * free account (N greater than Hikari's {@code maximum-pool-size: 10}) park connections on the
 * lock wait until the pool is exhausted and the whole app 503s for every other tenant —
 * the advisory-lock DoS this filter closes (paired with the bounded {@code lock_timeout} fused
 * into {@code BookingRepository.acquireClientAdvisoryLockWithTimeout(UUID)}, which is the
 * second, independent half of the same fix).
 *
 * <p><b>Must run AFTER {@code JwtAuthenticationFilter}</b> — see
 * {@code SecurityConfig#securityFilterChain}'s {@code addFilterAfter} — so
 * {@link Authentication#getDetails()} (the JWT subject / user id, set by
 * {@code JwtAuthenticationFilter}) is already populated by the time this filter runs. An
 * unauthenticated request (no principal, or a non-UUID details value — see Anti-Bug §B, never
 * a raw cast) is passed through untouched: this filter has no user id to key a bucket on, and
 * the downstream {@code anyRequest().authenticated()} rule / controller
 * {@code @PreAuthorize("hasRole('CLIENT')")} rejects it with 401/403 regardless.
 */
@Component
public class BookingRateLimitFilter extends OncePerRequestFilter {

    private static final String BOOKINGS_PATH = "/api/v1/bookings";
    private static final String BOOKINGS_PATH_PREFIX = "/api/v1/bookings/";
    private static final String RESCHEDULE_SUFFIX = "/reschedule";
    private static final int RETRY_AFTER_SECONDS = 10;

    private final LoadingCache<String, Bucket> bookingWriteBuckets;
    private final ObjectMapper objectMapper;

    public BookingRateLimitFilter(
            @Qualifier("bookingWriteBuckets") LoadingCache<String, Bucket> bookingWriteBuckets,
            ObjectMapper objectMapper) {
        this.bookingWriteBuckets = bookingWriteBuckets;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isThrottledBookingWrite(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getDetails() instanceof UUID userId)) {
            filterChain.doFilter(request, response);
            return;
        }

        Bucket bucket = bookingWriteBuckets.get(userId.toString());
        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            writeTooManyRequests(response);
        }
    }

    private boolean isThrottledBookingWrite(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        boolean isCreate = HttpMethod.POST.matches(method) && BOOKINGS_PATH.equals(path);
        boolean isReschedule = HttpMethod.PATCH.matches(method)
                && path.startsWith(BOOKINGS_PATH_PREFIX)
                && path.endsWith(RESCHEDULE_SUFFIX);
        return isCreate || isReschedule;
    }

    /**
     * Writes the project's standard {@link ApiResponse} error envelope
     * ({@code {"success":false,"data":null,"message":"..."}}) rather than the ad-hoc
     * {@code {"error":"..."}} body some legacy buckets in {@code AuthRateLimitFilter} use, so
     * the mobile client's shared error-mapping path handles this 429 identically to every
     * other endpoint's error responses.
     */
    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(
                ApiResponse.error("Too many requests — please slow down"));
        response.setStatus(429);
        response.setContentType("application/json");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
