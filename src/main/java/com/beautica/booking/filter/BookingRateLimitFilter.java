package com.beautica.booking.filter;

import com.beautica.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Per-authenticated-user rate limit on the booking write endpoints that either take the
 * per-client advisory lock or dispatch a note into a client-facing notification channel:
 * {@code POST /api/v1/bookings}, {@code POST /api/v1/appointments} (BE-3 multi-service visit
 * create), {@code PATCH /api/v1/bookings/{bookingId}/reschedule},
 * {@code PATCH /api/v1/bookings/{bookingId}/decline}, and
 * {@code PATCH /api/v1/bookings/{bookingId}/not-complete}. These map to TWO independent buckets
 * with different capacities, because they close two different threat models:
 *
 * <ul>
 *   <li><b>create/appointment-create/reschedule → {@code bookingWriteBuckets}:</b> all take the per-client advisory
 *   lock ({@code BookingRepository.acquireClientAdvisoryLockWithTimeout}). The per-client
 *   advisory lock is salted by the CALLER'S OWN authenticated user id, so a single CLIENT account
 *   needs no IP diversity at all to serialize every one of its own requests on the identical
 *   lock. Without a per-user throttle, N concurrent requests from one free account (N greater
 *   than Hikari's {@code maximum-pool-size: 10}) park connections on the lock wait until the pool
 *   is exhausted and the whole app 503s for every other tenant — the advisory-lock DoS this
 *   bucket closes (paired with the bounded {@code lock_timeout} fused into
 *   {@code BookingRepository.acquireClientAdvisoryLockWithTimeout(UUID)}, the second, independent
 *   half of the same fix).</li>
 *   <li><b>decline/not-complete → {@code bookingDeclineBuckets}:</b> {@code decline} substitutes
 *   the calling provider's free-text note into a Beautica-branded SMS sent to a real,
 *   OTP-verified guest phone number ({@code NotificationService.buildGuestDeclineSms}, Phase
 *   25.7); {@code not-complete} writes the same {@code provider_comment} column and is grouped
 *   into the same budget for consistency/defense-in-depth. A single compromised/malicious
 *   provider account could otherwise fire either endpoint against every booking they own
 *   back-to-back, mass-dispatching an attacker-controlled message to many real phone numbers in
 *   one burst (a smishing/SMS-bomb concern, NOT a connection-pool concern) — a separate, smaller
 *   bucket with its own {@code Retry-After} bounds that burst independently of the
 *   create/reschedule budget.</li>
 * </ul>
 *
 * <p><b>Why user-keyed, unlike every bucket in {@link com.beautica.auth.filter.AuthRateLimitFilter}:</b>
 * that filter is IP-keyed because it runs BEFORE {@code JwtAuthenticationFilter} on mostly
 * {@code permitAll} surfaces, so no principal is available yet. Every endpoint this filter covers
 * requires authentication, so keying by the caller's own user id is both available and the
 * correct scope for each threat model above.
 *
 * <p><b>Must run AFTER {@code JwtAuthenticationFilter}</b> — see
 * {@code SecurityConfig#securityFilterChain}'s {@code addFilterAfter} — so
 * {@link Authentication#getDetails()} (the JWT subject / user id, set by
 * {@code JwtAuthenticationFilter}) is already populated by the time this filter runs. An
 * unauthenticated request (no principal, or a non-UUID details value — see Anti-Bug §B, never
 * a raw cast) is passed through untouched: this filter has no user id to key a bucket on, and
 * the downstream {@code anyRequest().authenticated()} rule / controller
 * {@code @PreAuthorize} rejects it with 401/403 regardless.
 *
 * <p><b>Deliberately NOT a {@code @Component}</b> — it is declared as an explicit {@code @Bean} in
 * {@code RateLimitConfig#bookingRateLimitFilter}, right next to the {@link LoadingCache} buckets
 * it consumes. {@code @WebMvcTest} includes {@link jakarta.servlet.Filter} in
 * its component-scan type filter, so a {@code @Component} filter is auto-detected by EVERY narrow
 * slice, while the {@code @Configuration} that supplies its buckets ({@code RateLimitConfig}) is
 * not — which is exactly how this filter broke unrelated slices ({@code InternalApiKeyFilterTest},
 * {@code InternalCategoryControllerTest}) with
 * {@code No qualifying bean of type LoadingCache<String, Bucket>}. Declaring the filter and its
 * buckets in ONE non-scanned {@code @Configuration} makes them all-or-nothing: the full application
 * context loads both (production rate limit fully active, unchanged), and a slice loads neither
 * (context refreshes cleanly). No slice can be broken by this filter merely existing, and no
 * per-test mock or import is required.
 */
public class BookingRateLimitFilter extends OncePerRequestFilter {

    private static final String BOOKINGS_PATH = "/api/v1/bookings";
    private static final String BOOKINGS_PATH_PREFIX = "/api/v1/bookings/";
    /** BE-3: the multi-service single-visit create endpoint, throttled on the create/reschedule budget. */
    private static final String APPOINTMENTS_PATH = "/api/v1/appointments";
    private static final String RESCHEDULE_SUFFIX = "/reschedule";
    private static final String DECLINE_SUFFIX = "/decline";
    private static final String NOT_COMPLETE_SUFFIX = "/not-complete";

    /** {@code Retry-After} for the create/reschedule bucket — matches its 10s refill window. */
    private static final int CREATE_RESCHEDULE_RETRY_AFTER_SECONDS = 10;

    /** {@code Retry-After} for the decline/not-complete bucket — matches its 60s refill window. */
    private static final int DECLINE_RETRY_AFTER_SECONDS = 60;

    private final LoadingCache<String, Bucket> bookingWriteBuckets;
    private final LoadingCache<String, Bucket> bookingDeclineBuckets;
    private final ObjectMapper objectMapper;

    public BookingRateLimitFilter(
            LoadingCache<String, Bucket> bookingWriteBuckets,
            LoadingCache<String, Bucket> bookingDeclineBuckets,
            ObjectMapper objectMapper) {
        this.bookingWriteBuckets = bookingWriteBuckets;
        this.bookingDeclineBuckets = bookingDeclineBuckets;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        BucketRoute route = selectRoute(request);
        if (route == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getDetails() instanceof UUID userId)) {
            filterChain.doFilter(request, response);
            return;
        }

        Bucket bucket = route.buckets().get(userId.toString());
        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            writeTooManyRequests(response, route.retryAfterSeconds());
        }
    }

    /**
     * Maps a request to the bucket cache (and matching {@code Retry-After} value) it must be
     * throttled against, or {@code null} if this filter does not cover the request at all.
     */
    private BucketRoute selectRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // POST /bookings (single-service create) and POST /appointments (BE-3 multi-service visit
        // create) share the bookingWriteBuckets budget: both take the per-client advisory lock, so a
        // visit create is one token on the same threat model as a single-service create.
        if (HttpMethod.POST.matches(method) && (BOOKINGS_PATH.equals(path) || APPOINTMENTS_PATH.equals(path))) {
            return new BucketRoute(bookingWriteBuckets, CREATE_RESCHEDULE_RETRY_AFTER_SECONDS);
        }
        if (HttpMethod.PATCH.matches(method) && path.startsWith(BOOKINGS_PATH_PREFIX)) {
            if (path.endsWith(RESCHEDULE_SUFFIX)) {
                return new BucketRoute(bookingWriteBuckets, CREATE_RESCHEDULE_RETRY_AFTER_SECONDS);
            }
            if (path.endsWith(DECLINE_SUFFIX) || path.endsWith(NOT_COMPLETE_SUFFIX)) {
                return new BucketRoute(bookingDeclineBuckets, DECLINE_RETRY_AFTER_SECONDS);
            }
        }
        return null;
    }

    /** Pairs the bucket cache a request must consume from with its bucket-specific Retry-After. */
    private record BucketRoute(LoadingCache<String, Bucket> buckets, int retryAfterSeconds) {}

    /**
     * Writes the project's standard {@link ApiResponse} error envelope
     * ({@code {"success":false,"data":null,"message":"..."}}) rather than the ad-hoc
     * {@code {"error":"..."}} body some legacy buckets in {@code AuthRateLimitFilter} use, so
     * the mobile client's shared error-mapping path handles this 429 identically to every
     * other endpoint's error responses.
     */
    private void writeTooManyRequests(HttpServletResponse response, int retryAfterSeconds) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(
                ApiResponse.error("Too many requests — please slow down"));
        response.setStatus(429);
        response.setContentType("application/json");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
