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
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.UUID;

/**
 * Per-authenticated-user rate limit on the booking write endpoints that either take a real row
 * lock (the per-client advisory lock, or — since track 27.x's multi-service visit family — a
 * {@code SELECT ... FOR UPDATE} on the {@code appointments} header) or dispatch a note into a
 * client-facing notification channel: {@code POST /api/v1/bookings}, {@code POST
 * /api/v1/appointments} (BE-3 multi-service visit create), {@code POST
 * /api/v1/masters/&#123;masterId&#125;/bookings} (Phase 22.4 staff walk-in create), {@code PATCH
 * /api/v1/bookings/{bookingId}/{reschedule,cancel}}, {@code PATCH
 * /api/v1/bookings/{bookingId}/{decline,not-complete}}, the WHOLE {@code PATCH
 * /api/v1/appointments/{appointmentId}/*} mutation family ({@code cancel}, {@code decline},
 * {@code complete}, {@code not-complete}, {@code reschedule}) PLUS its per-item counterparts
 * (track 30.x) at {@code services/{bookingId}/{decline,reschedule,cancel}} — all three per-item
 * verbs are matched by the identical {@code endsWith} suffix checks as their whole-visit siblings
 * and bucketed with them, never on a dedicated per-item budget — and {@code PUT
 * /api/v1/masters/{masterId}/overrides/{date}} (the schedule-override write, which can bulk-decline
 * every conflicting booking on a date). These map to FOUR independent buckets with different
 * capacities, because they close different threat models:
 *
 * <ul>
 *   <li><b>{@code bookingWriteBuckets}:</b> create/appointment-create/reschedule
 *   (both paths), plus cancel (both paths) and appointment-complete. All of these take a real row
 *   lock that can
 *   pin a Hikari connection under contention — either the per-client advisory lock
 *   ({@code BookingRepository.acquireClientAdvisoryLockWithTimeout}, salted by the CALLER'S OWN
 *   authenticated user id, so a single CLIENT account needs no IP diversity at all to serialize
 *   every one of its own requests on the identical lock) or, for every {@code /appointments/**}
 *   route in this bucket, the {@code appointments} header's {@code SELECT ... FOR UPDATE}
 *   ({@code AppointmentRepository.lockHeaderRegardlessOfStatus} /
 *   {@code lockHeaderIfConfirmed}) plus the 5-way {@code JOIN FETCH} item load that follows it.
 *   Without a per-user throttle, N concurrent requests from one account (N greater than Hikari's
 *   {@code maximum-pool-size: 10}) park connections on the lock wait until the pool is exhausted
 *   and the whole app 503s for every other tenant — the lock-contention DoS this bucket closes
 *   (paired with the bounded {@code lock_timeout} fused into every lock query above, the second,
 *   independent half of the same fix). {@code PATCH .../cancel} and {@code PATCH
 *   .../{appointmentId}/complete} are ordinary, infrequent user actions (a client cancelling
 *   their own booking; a provider closing out a visit), so they share the generous
 *   create/reschedule budget rather than the tighter decline budget below — nothing about them is
 *   an abuse signal on its own, only the row lock they take is the concern.
 *   {@code PATCH /bookings/{bookingId}/complete} is deliberately NOT in this bucket (see the
 *   still-unthrottled-route test below): a single-booking complete touches only the
 *   {@code bookings} table, never {@code appointments}, so it carries none of the lock-contention
 *   risk above.</li>
 *   <li><b>{@code bookingDeclineBuckets}:</b> decline/not-complete on both paths (including the
 *   PER-SERVICE {@code /appointments/{id}/services/{bookingId}/decline} variant). {@code decline}
 *   substitutes the calling provider's free-text note into a Beautica-branded SMS sent to a real,
 *   OTP-verified guest phone number ({@code NotificationService.buildGuestDeclineSms}, Phase
 *   25.7); {@code not-complete} writes the same {@code provider_comment} column and is grouped
 *   into the same budget for consistency/defense-in-depth. A single compromised/malicious
 *   provider account could otherwise fire either endpoint against every booking (or every
 *   service line of every visit) they own back-to-back, mass-dispatching an attacker-controlled
 *   message to many real phone numbers in one burst (a smishing/SMS-bomb concern, NOT a
 *   connection-pool concern) — a separate, smaller bucket with its own {@code Retry-After} bounds
 *   that bursts independently of the create/reschedule/cancel budget. {@code complete} carries no
 *   free-text note field at all (see {@code AppointmentTransitionService#completeAppointment}),
 *   so it is never grouped here.</li>
 *   <li><b>{@code scheduleOverrideWriteBuckets}:</b> {@code PUT
 *   /api/v1/masters/{masterId}/overrides/{date}} — its OWN bucket, not shared with either bucket
 *   above (2026-07-26 product decision reversal, D6). This route used to share
 *   {@code bookingDeclineBuckets} (plus an additional proportional per-conflict charge) because an
 *   override write could fan a provider-authored note out to guest phones as SMS, exactly like
 *   {@code decline}. That vector no longer exists: an override-driven decline carries no note and
 *   dispatches no notification at all — see {@code ScheduleOverrideConflictService}'s and
 *   {@code ScheduleOverrideRequest}'s javadoc. What remains is an ordinary destructive-bulk-write
 *   concern (each request can mass-decline up to
 *   {@code ScheduleOverrideConflictService.MAX_CONFLICTS_PER_WRITE} bookings) with its OWN fan-out
 *   pattern — the mobile client expands a multi-day save into one PUT per date, so a legitimate
 *   month-long vacation is realistically ~31 consecutive requests from one actor — which neither
 *   sibling bucket was sized for. See {@code RateLimitConfig#scheduleOverrideWriteCapacity}'s
 *   javadoc for the exact capacity and its justification.</li>
 *   <li><b>{@code staffBookingSmsBuckets}:</b> {@code POST
 *   /api/v1/masters/&#123;masterId&#125;/bookings} — the Phase 22.4 staff walk-in create, moved off
 *   {@code bookingWriteBuckets} in Phase 22.7 (SEC MEDIUM, 2026-08-18). Two threat models overlap on
 *   this one route and the tighter budget covers both: it takes the PER-MASTER advisory lock
 *   ({@code BookingSlotLockGuard.lockMasterAndAssertFree}), keyed on the TARGET master rather than
 *   the caller, so an unthrottled staff token degrades availability for every real CLIENT booking
 *   that master; AND since 22.7 every success dispatches a Beautica-branded SMS, carrying a
 *   provider-authored {@code serviceName}, to a staff-TYPED number that never consented. The second
 *   is a strictly worse smishing surface than {@code bookingDeclineBuckets}' — decline at least
 *   targets an already-OTP-verified guest — so it gets the same 10/60s shape, which being 3x tighter
 *   than the create budget it left also still bounds the lock contention. Per-ACTOR only; the
 *   per-RECIPIENT half lives in {@code StaffBookingService#assertWalkInSmsBudgetForPhone}, because
 *   no per-actor bucket can bound an attacker holding several staff accounts.</li>
 *   <li><b>{@code selfDeleteBuckets}:</b> {@code DELETE /api/v1/users/me} — the CLIENT
 *   self-deletion endpoint (Phase 300, perf finding 2, 2026-09 audit). NOT a booking route,
 *   but throttled through this filter rather than a second, parallel per-user mechanism —
 *   see {@code USERS_ME_PATH}'s own javadoc. Bounds a token-holder retrying the hard-delete
 *   cascade, which opens a real row lock on the caller's OWN {@code users} row for up to
 *   30s per attempt; a legitimate CLIENT self-deletes once, so the budget is small and
 *   hourly rather than shaped like the other, more frequent booking-write buckets above.</li>
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
    /**
     * {@code DELETE /api/v1/users/me} — the CLIENT self-deletion endpoint (Phase 300, perf finding
     * 2, 2026-09 audit — MEDIUM, raised independently by both security and perf). Not a booking
     * route at all, but throttled here anyway rather than through a second, parallel per-user
     * filter: this class is the ONLY per-authenticated-user Bucket4j mechanism in the app (see the
     * class Javadoc's "why user-keyed" note), and {@code JwtAuthenticationFilter} has already run by
     * the time this filter sees the request, so the principal is available exactly as it is for
     * every other route below. A legitimate CLIENT self-deletes once; the tight
     * {@code selfDeleteBuckets} budget exists only to bound a token-holder retrying (or scripting)
     * the irreversible hard-delete cascade, which opens a real {@code PESSIMISTIC_WRITE} row lock on
     * the caller's own {@code users} row for up to the 30s transaction timeout on every attempt.
     */
    private static final String USERS_ME_PATH = "/api/v1/users/me";
    /** BE-3: the multi-service single-visit create endpoint, throttled on the create/reschedule budget. */
    private static final String APPOINTMENTS_PATH = "/api/v1/appointments";
    /** Prefix for the whole {@code /appointments/{id}/*} PATCH mutation family — see class Javadoc. */
    private static final String APPOINTMENTS_PATH_PREFIX = "/api/v1/appointments/";
    /**
     * Prefix shared by the TWO routes this filter covers under {@code /masters/}: the {@code PUT
     * .../overrides/{date}} schedule-override write (security audit finding 5) and the {@code POST
     * .../bookings} staff walk-in create (Phase 22.4). {@code GET .../overrides} (list) and
     * {@code POST .../overrides/conflicts} (read-only preview) never decline anything and are
     * deliberately left unmatched by {@link #selectRoute}'s method/suffix checks below.
     */
    private static final String MASTERS_PATH_PREFIX = "/api/v1/masters/";
    private static final String OVERRIDES_SEGMENT = "/overrides/";
    /**
     * Suffix of {@code POST /api/v1/masters/&#123;masterId&#125;/bookings} — Phase 22.4's staff
     * walk-in create, throttled on the create/reschedule budget. Matched as
     * {@code startsWith(MASTERS_PATH_PREFIX) && endsWith(BOOKINGS_SUFFIX)} rather than by segment
     * count, consistent with every other route in {@link #selectRoute}.
     *
     * <p><b>Any future sub-path under {@code /masters/&#123;masterId&#125;/bookings} needs its own
     * route entry in {@link #selectRoute}</b> — this suffix match ends at {@code "/bookings"}, so a
     * hypothetical {@code POST .../bookings/bulk} (no such route exists today) would be silently
     * unbucketed rather than inheriting this one.
     */
    private static final String BOOKINGS_SUFFIX = "/bookings";
    private static final String RESCHEDULE_SUFFIX = "/reschedule";
    private static final String CANCEL_SUFFIX = "/cancel";
    private static final String COMPLETE_SUFFIX = "/complete";
    private static final String DECLINE_SUFFIX = "/decline";
    private static final String NOT_COMPLETE_SUFFIX = "/not-complete";

    // Resolves the DECODED + NORMALIZED request path for rule matching (see resolveMatchPath).
    // urlDecode + removeSemicolonContent are UrlPathHelper defaults; set explicitly so the
    // security-critical decode step is self-documenting and cannot be silently disabled by a
    // future default change. Stateless after construction and thread-safe for the read-only
    // getPathWithinApplication call, so a single shared static instance is correct.
    // Identical to AuthRateLimitFilter#MATCH_PATH_HELPER — deliberately duplicated rather than
    // hoisted to a shared util so each filter's security-critical matching input is visible at
    // the site that depends on it; the two are named identically so they read as one pattern.
    private static final UrlPathHelper MATCH_PATH_HELPER = createMatchPathHelper();

    private static UrlPathHelper createMatchPathHelper() {
        UrlPathHelper helper = new UrlPathHelper();
        helper.setUrlDecode(true);
        helper.setRemoveSemicolonContent(true);
        return helper;
    }

    /** {@code Retry-After} for the create/reschedule bucket — matches its 10s refill window. */
    private static final int CREATE_RESCHEDULE_RETRY_AFTER_SECONDS = 10;

    /** {@code Retry-After} for the decline/not-complete bucket — matches its 60s refill window. */
    private static final int DECLINE_RETRY_AFTER_SECONDS = 60;

    /**
     * {@code Retry-After} for the schedule-override-write bucket — matches its own 60s refill
     * window (see {@code RateLimitConfig#scheduleOverrideWriteBuckets}).
     */
    private static final int SCHEDULE_OVERRIDE_RETRY_AFTER_SECONDS = 60;

    /** {@code Retry-After} for the staff walk-in SMS-spend bucket — matches its 60s refill window. */
    private static final int STAFF_BOOKING_SMS_RETRY_AFTER_SECONDS = 60;

    /**
     * {@code Retry-After} for the CLIENT self-delete bucket — matches its 60-minute refill window
     * (see {@code RateLimitConfig#selfDeleteCapacity}'s javadoc for the sizing rationale).
     */
    private static final int SELF_DELETE_RETRY_AFTER_SECONDS = 3600;

    private final LoadingCache<String, Bucket> bookingWriteBuckets;
    private final LoadingCache<String, Bucket> bookingDeclineBuckets;
    private final LoadingCache<String, Bucket> scheduleOverrideWriteBuckets;
    private final LoadingCache<String, Bucket> staffBookingSmsBuckets;
    private final LoadingCache<String, Bucket> selfDeleteBuckets;
    private final ObjectMapper objectMapper;

    public BookingRateLimitFilter(
            LoadingCache<String, Bucket> bookingWriteBuckets,
            LoadingCache<String, Bucket> bookingDeclineBuckets,
            LoadingCache<String, Bucket> scheduleOverrideWriteBuckets,
            LoadingCache<String, Bucket> staffBookingSmsBuckets,
            LoadingCache<String, Bucket> selfDeleteBuckets,
            ObjectMapper objectMapper) {
        this.bookingWriteBuckets = bookingWriteBuckets;
        this.bookingDeclineBuckets = bookingDeclineBuckets;
        this.scheduleOverrideWriteBuckets = scheduleOverrideWriteBuckets;
        this.staffBookingSmsBuckets = staffBookingSmsBuckets;
        this.selfDeleteBuckets = selfDeleteBuckets;
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
     * Resolves the path used for throttle-rule matching from the request's DECODED and
     * NORMALIZED path — mirroring how Spring MVC routes the request — instead of the raw,
     * percent-encoded, un-normalized {@link HttpServletRequest#getRequestURI()}. Ported verbatim
     * from {@code AuthRateLimitFilter#resolveMatchPath} (PR #95), which closed the identical
     * bypass on the auth buckets; see that method's javadoc for the full rationale.
     *
     * <p>The servlet container hands back {@code getRequestURI()} exactly as received: still
     * percent-encoded and not collapsed. Spring MVC, however, routes on the decoded/normalized
     * path, so matching a rule on the raw URI let a caller reach a throttled handler with an
     * equivalent spelling that skipped EVERY route in {@link #selectRoute} — e.g. {@code POST
     * /api/v1/masters/&#123;id&#125;/booking%73} (routes to {@code StaffBookingController}, but
     * {@code endsWith("/bookings")} is false, so no token is consumed), {@code PATCH
     * /api/v1/bookings/&#123;id&#125;/declin%65} (the SMS-spend budget from Phase 22.7), or
     * {@code PUT /api/v1/masters/&#123;id&#125;/override%73/&#123;date&#125;} (the bulk-decline
     * budget). {@code StrictHttpFirewall} does NOT reject these: {@code %73}/{@code %65} are
     * ordinary alphanumeric encodings, none of the trailing-slash / matrix-variable /
     * encoded-slash shapes the firewall actually blocks.
     *
     * <p>This filter runs after {@code JwtAuthenticationFilter} but still BEFORE the
     * {@code DispatcherServlet} parses and caches the request path, so
     * {@code ServletRequestPathUtils.getCachedPath(request)} is not yet populated here. We
     * therefore decode/normalize independently via {@link UrlPathHelper} (which percent-decodes
     * ONCE, strips {@code ;matrix} content, and collapses duplicate slashes) and then fold any
     * {@code .}/{@code ..} segments that survive decoding (e.g. {@code %2e%2e}) with
     * {@link StringUtils#cleanPath}. {@code cleanPath} performs no decoding, so there is no
     * double-decode (which would itself open a bypass), and unencoded paths pass through
     * byte-for-byte so every existing rule's spelling still matches.
     */
    private String resolveMatchPath(HttpServletRequest request) {
        return StringUtils.cleanPath(MATCH_PATH_HELPER.getPathWithinApplication(request));
    }

    /**
     * Maps a request to the bucket cache (and matching {@code Retry-After} value) it must be
     * throttled against, or {@code null} if this filter does not cover the request at all.
     *
     * <p>Matches on the DECODED + NORMALIZED path ({@link #resolveMatchPath}), never on the raw
     * {@code getRequestURI()} — see that method for the bypass this closes.
     */
    private BucketRoute selectRoute(HttpServletRequest request) {
        String path = resolveMatchPath(request);
        String method = request.getMethod();

        // DELETE /api/v1/users/me — the CLIENT self-deletion endpoint (Phase 300 perf finding 2).
        // See USERS_ME_PATH's javadoc for why this unrelated route lives in the booking filter.
        if (HttpMethod.DELETE.matches(method) && USERS_ME_PATH.equals(path)) {
            return new BucketRoute(selfDeleteBuckets, SELF_DELETE_RETRY_AFTER_SECONDS);
        }
        // POST /bookings (single-service create) and POST /appointments (BE-3 multi-service visit
        // create) share the bookingWriteBuckets budget: both take the per-client advisory lock, so a
        // visit create is one token on the same threat model as a single-service create.
        if (HttpMethod.POST.matches(method) && (BOOKINGS_PATH.equals(path) || APPOINTMENTS_PATH.equals(path))) {
            return new BucketRoute(bookingWriteBuckets, CREATE_RESCHEDULE_RETRY_AFTER_SECONDS);
        }
        // POST /masters/{masterId}/bookings (Phase 22.4 staff walk-in create) — its OWN
        // staffBookingSmsBuckets budget since Phase 22.7 made the route an SMS-SPEND path
        // (SEC MEDIUM, 2026-08-18). It used to share bookingWriteBuckets on the lock-contention
        // argument; that argument is still true and still covered, because the SMS budget (10/60s)
        // is 3x TIGHTER than the create budget (5/10s => 30/min) and therefore binds first on this
        // route. What the shared bucket did NOT cover is the money: 30 attacker-chosen, Beautica-
        // branded SMS per minute per account, aimed at numbers that never consented — a worse
        // surface than the decline path, which at least targets an OTP-verified guest and already
        // has its own tighter bucket. See RateLimitConfig#staffBookingSmsCapacity.
        if (HttpMethod.POST.matches(method) && path.startsWith(MASTERS_PATH_PREFIX)
                && path.endsWith(BOOKINGS_SUFFIX)) {
            return new BucketRoute(staffBookingSmsBuckets, STAFF_BOOKING_SMS_RETRY_AFTER_SECONDS);
        }
        // PUT /masters/{masterId}/overrides/{date} — its OWN bucket (2026-07-26 product decision
        // reversal, D6): an override-driven decline no longer carries a note or dispatches a
        // notification, so this route is throttled purely as a destructive bulk write, sized for its
        // own fan-out pattern (see class Javadoc's scheduleOverrideWriteBuckets bullet) rather than
        // sharing the SMS-driven decline budget.
        if (HttpMethod.PUT.matches(method) && path.startsWith(MASTERS_PATH_PREFIX)
                && path.contains(OVERRIDES_SEGMENT)) {
            return new BucketRoute(scheduleOverrideWriteBuckets, SCHEDULE_OVERRIDE_RETRY_AFTER_SECONDS);
        }
        if (!HttpMethod.PATCH.matches(method)) {
            return null;
        }
        if (path.startsWith(BOOKINGS_PATH_PREFIX)) {
            return selectBookingPatchRoute(path);
        }
        if (path.startsWith(APPOINTMENTS_PATH_PREFIX)) {
            return selectAppointmentPatchRoute(path);
        }
        return null;
    }

    /**
     * {@code PATCH /bookings/{bookingId}/*} routing. {@code /complete} is deliberately unmatched —
     * see the class Javadoc's {@code bookingWriteBuckets} bullet for why a single-booking complete
     * carries none of this filter's lock-contention concern.
     */
    private BucketRoute selectBookingPatchRoute(String path) {
        if (path.endsWith(RESCHEDULE_SUFFIX) || path.endsWith(CANCEL_SUFFIX)) {
            return new BucketRoute(bookingWriteBuckets, CREATE_RESCHEDULE_RETRY_AFTER_SECONDS);
        }
        if (path.endsWith(DECLINE_SUFFIX) || path.endsWith(NOT_COMPLETE_SUFFIX)) {
            return new BucketRoute(bookingDeclineBuckets, DECLINE_RETRY_AFTER_SECONDS);
        }
        return null;
    }

    /**
     * {@code PATCH /appointments/{appointmentId}/*} routing — the whole mutation family (SEC MEDIUM
     * finding; previously entirely unmatched by this filter). {@code endsWith} is deliberately used
     * rather than a fixed segment count: {@code .../services/{bookingId}/decline} (the per-service
     * decline variant, TWO path segments after the appointment id) ends with the identical
     * {@code "/decline"} suffix as the whole-visit {@code .../{appointmentId}/decline} route (ONE
     * segment after the id), and both are INTENTIONALLY bucketed together (same SMS/free-text-note
     * threat model, same per-user budget) — so no extra segment-counting is needed to route it
     * correctly. {@code /not-complete} cannot be mis-matched by the {@code "/complete"} check below:
     * the character immediately before {@code "complete"} in {@code ".../not-complete"} is a hyphen,
     * not a slash, so {@code endsWith("/complete")} is {@code false} for it.
     *
     * <p><b>Track 30.x — the per-item reschedule/cancel routes need no change here either.</b>
     * {@code .../services/{bookingId}/reschedule} and {@code .../services/{bookingId}/cancel} are
     * two-segment siblings of the {@code /decline} shape above, and the SAME {@code endsWith} logic
     * that already bucketed the per-service decline with its whole-visit sibling buckets these two
     * with {@code bookingWriteBuckets} alongside {@code .../{appointmentId}/reschedule} and
     * {@code .../{appointmentId}/cancel} — no new constant, no new branch. All THREE per-item verbs
     * ({@code /services/{bookingId}/{decline,reschedule,cancel}}) are therefore matched by the
     * identical suffix checks as their whole-visit counterparts and are intentionally bucketed with
     * them, never on a dedicated per-item budget.
     */
    private BucketRoute selectAppointmentPatchRoute(String path) {
        if (path.endsWith(DECLINE_SUFFIX) || path.endsWith(NOT_COMPLETE_SUFFIX)) {
            return new BucketRoute(bookingDeclineBuckets, DECLINE_RETRY_AFTER_SECONDS);
        }
        if (path.endsWith(RESCHEDULE_SUFFIX) || path.endsWith(CANCEL_SUFFIX) || path.endsWith(COMPLETE_SUFFIX)) {
            return new BucketRoute(bookingWriteBuckets, CREATE_RESCHEDULE_RETRY_AFTER_SECONDS);
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
