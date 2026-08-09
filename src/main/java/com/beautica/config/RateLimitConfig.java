package com.beautica.config;

import com.beautica.booking.filter.BookingRateLimitFilter;
import com.beautica.booking.service.ScheduleOverrideConflictService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitConfig {

    // Caffeine eviction caps — how many distinct per-IP buckets are retained, NOT the
    // per-IP rate limit. The default covers the high-fan-out auth/write endpoints; the
    // verify/resend one-time flows see far fewer distinct IPs, so a smaller cap is plenty.
    private static final long DEFAULT_BUCKET_CACHE_SIZE = 100_000;
    private static final long VERIFY_EMAIL_BUCKET_CACHE_SIZE = 50_000;
    private static final long RESEND_VERIFICATION_BUCKET_CACHE_SIZE = 10_000;

    private static final Duration STANDARD_EVICTION = Duration.ofHours(1);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    @Value("${app.rate-limit.register-capacity:3}")
    private long registerCapacity;

    @Value("${app.rate-limit.login-capacity:5}")
    private long loginCapacity;

    @Value("${app.rate-limit.refresh-capacity:20}")
    private long refreshCapacity;

    @Value("${app.rate-limit.slots-capacity:60}")
    private long slotsCapacity;

    @Value("${app.rate-limit.device-token-capacity:30}")
    private long deviceTokenCapacity;

    // Per-IP cap for POST/DELETE /api/v1/media/* (60 s window). Phase 7.2 backlog
    // originally suggested 5/min; 10/min is the chosen ceiling because legitimate
    // clients may retry after a 400 (wrong MIME, oversize) or replace an avatar
    // immediately after upload — keeping headroom prevents false-positive lockouts
    // while still blocking sustained abuse.
    @Value("${app.rate-limit.media-upload-capacity:10}")
    private long mediaUploadCapacity;

    // Per-IP cap for POST /api/v1/auth/verify-email (15-minute window).
    // 10 attempts per window is generous for legitimate users while still
    // preventing brute-force of the 6-digit OTP space (1,000,000 combinations).
    // Configurable so integration tests running on 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.verify-email-capacity:10}")
    private long verifyEmailCapacity;

    private static final Duration VERIFY_EMAIL_WINDOW = Duration.ofMinutes(15);

    // Per-IP cap for POST /api/v1/auth/resend-verification (60-second window).
    // Configurable so integration tests running on 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.resend-verification-capacity:3}")
    private long resendVerificationCapacity;

    private static final Duration RESEND_VERIFICATION_WINDOW = Duration.ofSeconds(60);

    // Per-IP cap for POST /api/v1/auth/forgot-password (60-minute window).
    // forgot-password is the email-bomb surface — keep it low (3/hr). The 60-minute
    // window matches the token TTL so a user who exhausts their budget at the start of
    // a reset session can always retry when the token would have expired anyway.
    // Configurable so integration tests can raise the cap.
    @Value("${app.rate-limit.forgot-password-capacity:3}")
    private long forgotPasswordCapacity;

    // Per-IP cap for POST /api/v1/auth/reset-password (60-minute window).
    // Decoupled from forgot-password so a user behind NAT who spams forgot-password
    // does NOT block their own (or a co-located user's) reset-password submit. A
    // legitimate user may retry a new-password typo / network error several times
    // against one emailed token, so the cap is higher (10/hr) than the email-bomb
    // surface. reset-password sends no email, so a higher cap carries no spam risk.
    // Configurable so integration tests can raise the cap.
    @Value("${app.rate-limit.reset-password-capacity:10}")
    private long resetPasswordCapacity;

    private static final Duration PASSWORD_RESET_WINDOW = Duration.ofMinutes(60);

    // Per-IP cap for POST /api/v1/auth/verify-password-reset-otp (15-minute window).
    // Mirrors verify-email-capacity exactly (10/15min): both endpoints brute-force-guard
    // a 6-digit OTP (1,000,000 combinations) at the IP layer, complementing the per-account
    // attempt cap + cumulative lockout enforced inside PasswordResetOtpProcessor.
    // Configurable so integration tests running on 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.verify-password-reset-otp-capacity:10}")
    private long verifyPasswordResetOtpCapacity;

    private static final Duration VERIFY_PASSWORD_RESET_OTP_WINDOW = Duration.ofMinutes(15);

    // Per-IP cap for POST /api/v1/users/me/change-password/request-otp (60-minute window).
    // Mirrors forgot-password-capacity exactly (3/hr): every successful request emails an
    // OTP, so this is the same email-bomb surface as forgot-password, just for the
    // authenticated entry point. Configurable so integration tests can raise the cap.
    @Value("${app.rate-limit.change-password-otp-capacity:3}")
    private long changePasswordOtpCapacity;

    // Per-IP cap for PATCH /api/v1/independent-masters/me/profile (60-second window).
    // 10/min prevents unbounded DB writes and cache churn from a token-holding client
    // while remaining generous enough for a legitimate profile-edit retry.
    @Value("${app.rate-limit.profile-update-capacity:10}")
    private long profileUpdateCapacity;

    // Per-IP cap for POST /api/v1/service-categories/requests (60-minute window).
    // This path sends an admin email on every successful request, so it is an
    // inbox-flood surface — kept low (5/hr) to match the forgot-password email-bomb
    // posture. IP-keyed for consistency with every other bucket in this filter
    // (JWT is not yet parsed when AuthRateLimitFilter runs). Configurable so
    // integration tests on 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.category-request-capacity:5}")
    private long categoryRequestCapacity;

    private static final Duration CATEGORY_REQUEST_WINDOW = Duration.ofMinutes(60);

    // Per-IP cap for POST /api/v1/service-types/suggest (60-minute window).
    // Like the category-request path, every successful suggestion emails the admin,
    // so this is an inbox-flood surface — kept low (5/hr) to match that posture.
    // IP-keyed for consistency with every other bucket in this filter (JWT is not yet
    // parsed when AuthRateLimitFilter runs). Configurable so integration tests on
    // 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.suggest-service-type-capacity:5}")
    private long suggestServiceTypeCapacity;

    private static final Duration SUGGEST_SERVICE_TYPE_WINDOW = Duration.ofMinutes(60);

    // Per-IP cap for the two bulk-service-create endpoints (60-second window):
    //   - POST /api/v1/independent-masters/me/services/bulk
    //   - POST /api/v1/salons/{salonId}/masters/{masterId}/services/bulk
    // Every call runs full 100-item validation + persistence, and the path is additive (no
    // cheap precondition rejects repeat calls), so this is an authenticated DoS-amplifier
    // surface — kept low (10/min) to match the other write
    // buckets (media, profile-update, device-token). IP-keyed for consistency with every
    // other bucket in this filter (JWT is not yet parsed when AuthRateLimitFilter runs).
    // Configurable so integration tests on 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.bulk-service-setup-capacity:10}")
    private long bulkServiceSetupCapacity;

    // Per-IP cap for the SINGLE-item service write endpoints on ServiceController
    // (60-second window):
    //   - POST   /api/v1/independent-masters/me/services
    //   - POST   /api/v1/salons/{salonId}/services
    //   - POST   /api/v1/salons/{salonId}/masters/{masterId}/services
    //   - PATCH  /api/v1/services/{serviceDefId}
    //   - PATCH  /api/v1/services/{serviceDefId}/photo
    //   - DELETE /api/v1/services/{serviceDefId}
    //
    // Every one of these fell through to the unmatched else/non-POST branch of
    // AuthRateLimitFilter with NO bucket at all, which undercut bulkServiceSetupCapacity's own
    // stated rationale: the bulk bucket is capped to bound service_definitions row growth, but an
    // attacker wanting that growth simply looped the unthrottled single-create instead — the
    // strictly better lever, since it also skipped the per-master advisory lock the bulk path
    // takes. Closing it makes bulk the tighter path again, as intended.
    //
    // SEPARATE bucket from bulkServiceSetupCapacity, deliberately: a bulk request does up to 100
    // items of work, a single write does one. Sharing the bulk bucket would either impose its
    // tight 10/min on legitimate per-item usage, or force that cap up and re-loosen exactly the
    // DoS amplifier the bulk bucket exists to bound. Each is sized for its own unit of work.
    //
    // Sizing (60/min). The bulk endpoint already accepts a 100-item catalogue in ONE request, so
    // a provider who works through the per-item forms instead — building out a menu service by
    // service, or sweeping prices across an existing one with PATCH, each service costing a
    // create plus a photo PATCH — must not be throttled into a 60-second stall for preferring
    // that UX. 60/min covers a 30-service build-out (create + photo per service) in one sitting
    // with no 429, well past the realistic per-master catalogue size, while still capping
    // sustained row growth at 60 rows/min — 16x BELOW what the already-accepted bulk ceiling
    // permits (10 req/min x 100 items = 1000 rows/min). IP-keyed for consistency with every other
    // bucket in this filter (JWT is not yet parsed when AuthRateLimitFilter runs). Configurable so
    // integration tests on 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.service-write-capacity:60}")
    private long serviceWriteCapacity;

    // Per-IP cap for POST /api/v1/support/contact (60-minute window).
    // Each successful call sends an email to the support inbox, so this is an
    // email-bomb / outbound-quota DoS surface — kept low (5/hr) to mirror the
    // category-request and suggest-service-type inbox-flood posture. IP-keyed for
    // consistency with every other bucket in this filter (JWT is not yet parsed when
    // AuthRateLimitFilter runs). Configurable so integration tests on 127.0.0.1 can
    // raise the cap.
    @Value("${app.rate-limit.support-contact-capacity:5}")
    private long supportContactCapacity;

    private static final Duration SUPPORT_CONTACT_WINDOW = Duration.ofMinutes(60);

    // Per-IP cap for POST /api/v1/book/otp/send (15-minute window). Every successful
    // request dispatches an SMS via Turbosms — a billable, outbound-quota / SMS-bomb
    // surface — so this is the IP-layer defence that complements the per-phone rate
    // limit inside PhoneOtpService (dual-layer). Cap mirrors the per-phone service cap
    // (3 / 15 min) so a single IP cannot out-pace what one phone is allowed. IP-keyed
    // for consistency with every other bucket in this filter (JWT is not parsed when
    // AuthRateLimitFilter runs — and this is a permitAll endpoint anyway). Configurable
    // so integration tests on 127.0.0.1 can raise the cap.
    @Value("${app.rate-limit.otp-send-capacity:3}")
    private long otpSendCapacity;

    private static final Duration OTP_SEND_WINDOW = Duration.ofMinutes(15);

    // Per-user cap for the two authenticated booking-conflict write endpoints that take the
    // per-client advisory lock (BookingRepository.acquireClientAdvisoryLockWithTimeout):
    //   - POST /api/v1/bookings
    //   - PATCH /api/v1/bookings/{bookingId}/reschedule
    // Unlike every other bucket in this class (IP-keyed, because AuthRateLimitFilter runs
    // BEFORE JwtAuthenticationFilter on permitAll surfaces), this bucket is keyed by the
    // authenticated user id — see BookingRateLimitFilter, which runs AFTER
    // JwtAuthenticationFilter so the principal is available. The advisory lock is salted by
    // the caller's OWN user id, so a single authenticated CLIENT needs no IP diversity to
    // serialize N concurrent requests on the identical lock; without this bucket, N > Hikari's
    // maximum-pool-size (10) concurrent requests from one free account can exhaust the pool
    // and 503 the whole app for every other tenant (booking advisory-lock DoS fix). 5/10s is
    // generous for a legitimate client retrying a declined slot while bounding a scripted
    // flood. Both endpoints share ONE bucket — same "shared bucket for one class of endpoint"
    // pattern as rotateStaffBuckets in AuthRateLimitFilter. Configurable so integration tests
    // (e.g. the 10-concurrent-request BookingConcurrencyTest) can raise the cap.
    @Value("${app.rate-limit.booking-write-capacity:5}")
    private long bookingWriteCapacity;

    private static final Duration BOOKING_WRITE_WINDOW = Duration.ofSeconds(10);

    // Per-user cap for the SMS/notification-triggering booking-write endpoints:
    //   - PATCH /api/v1/bookings/{bookingId}/decline
    //   - PATCH /api/v1/bookings/{bookingId}/not-complete
    // Deliberately a SEPARATE bucket from bookingWriteCapacity above (different threat model —
    // see BookingRateLimitFilter's class javadoc): declineBooking substitutes the provider's own
    // free text into a Beautica-branded SMS sent to a real, OTP-verified guest phone number
    // (NotificationService.buildGuestDeclineSms, Phase 25.7); an unthrottled provider account
    // could otherwise mass-dispatch that SMS across every booking they own in one burst
    // (smishing / SMS-bomb concern), which the tight 5-per-10s create/reschedule budget was never
    // sized to bound. 10 requests / 60s mirrors the other authenticated-write buckets in this
    // class (media upload, profile update, bulk service setup) — generous enough for a provider
    // clearing a backlog of no-shows/declines in one sitting. Configurable so integration tests
    // can raise the cap.
    //
    // NOT shared with PUT /api/v1/masters/{masterId}/overrides/{date} (2026-07-26 product decision
    // reversal, D6) — that route used to share this bucket (plus a proportional per-conflict charge
    // via the now-deleted BookingDeclineRateLimiter) because an override write could fan a
    // provider-authored note out to guest phones as SMS. An override-driven decline now carries no
    // note and dispatches no notification at all, so it has its own dedicated bucket instead —
    // see {@link #scheduleOverrideWriteBuckets()}.
    @Value("${app.rate-limit.booking-decline-capacity:10}")
    private long bookingDeclineCapacity;

    private static final Duration BOOKING_DECLINE_WINDOW = Duration.ofSeconds(60);

    // Per-user cap for PUT /api/v1/masters/{masterId}/overrides/{date} (the schedule-override
    // write). Own bucket, deliberately NOT shared with bookingDeclineBuckets above (2026-07-26
    // product decision reversal, D6 — see that field's javadoc for why the two used to be one
    // bucket and no longer are).
    //
    // Sized for the mobile client's real fan-out, not for one logical action: a multi-day
    // save (e.g. a vacation) is expanded CLIENT-SIDE into one PUT per date, so a month-long
    // vacation is realistically ~31 consecutive requests from one actor in a single save flow.
    // 50/60s gives that legitimate worst case comfortable headroom (19 requests, ~60% margin) —
    // enough to also absorb the occasional 409-then-retry round trip (a PUT that 409s because a
    // booking was created between preview and confirm re-submits with cancelOverlapping=true) —
    // while still meaningfully bounding a scripted loop far beyond what any real save flow needs.
    // This is still a destructive bulk write (each request can mass-decline up to
    // ScheduleOverrideConflictService.MAX_CONFLICTS_PER_WRITE bookings), so it is throttled like
    // every other authenticated write in this class — just sized for its own fan-out pattern
    // rather than borrowed from a sibling bucket. Configurable so integration tests can raise
    // the cap.
    @Value("${app.rate-limit.schedule-override-write-capacity:50}")
    private long scheduleOverrideWriteCapacity;

    /**
     * Per-actor AGGREGATE decline-count budget for {@code PUT /api/v1/masters/{masterId}/overrides/{date}}
     * (2026-07-26 security audit finding 1 — MEDIUM). {@link #scheduleOverrideWriteCapacity} above and
     * {@code ScheduleOverrideConflictService.MAX_CONFLICTS_PER_WRITE} (100) are INDEPENDENT caps — one
     * bounds request frequency, the other bounds conflicts declined per request — so nothing bounds
     * their PRODUCT: an already-authorized ({@code canManageMasterSchedule}) but compromised account
     * could mass-decline on the order of 50 req/min &times; 100 conflicts/req = 5,000 {@code CONFIRMED}
     * bookings per minute across every master it manages, with D6 removing the one signal (a client
     * notification) that would otherwise surface the damage quickly. This bucket closes that gap by
     * charging {@code conflicts.size()} tokens per write (not the flat 1-per-request charge every other
     * bucket in this class uses) — see {@code ScheduleOverrideConflictService}'s consumption call,
     * placed in the same spot as its {@code MAX_CONFLICTS_PER_WRITE} check: before any decline executes,
     * all-or-nothing, nothing mutated on rejection.
     *
     * <p><b>Sizing arithmetic.</b> The legitimate worst case is a master saving a long vacation: the
     * mobile client expands a multi-day span into ONE {@code PUT} per date (see
     * {@link #scheduleOverrideWriteCapacity}'s javadoc), so a month-long holiday is realistically ~31
     * consecutive requests from one actor. Per date, the physical ceiling on a master's own confirmed
     * bookings is bounded by the calendar itself — a 10-hour working day at 20-minute slots is ~30
     * bookings, and {@code MAX_CONFLICTS_PER_WRITE} independently caps any single date at 100 regardless.
     * Taking the (deliberately unrealistic, upper-bound) case where EVERY one of the 31 dates is maximally
     * booked: 31 &times; 30 = 930 conflicts declined across the whole save flow. This bucket's capacity
     * (1500) clears that with the SAME ~61% headroom ratio {@link #scheduleOverrideWriteCapacity}
     * itself uses (50 vs. a 31-request legitimate worst case is also a ~61% margin: 50 / 31 &asymp; 1.61;
     * 1500 / 930 &asymp; 1.61) — comfortable room for the occasional 409-then-retry resubmit, while
     * remaining two orders of magnitude below the ~300,000 conflicts/hour (5,000/min &times; 60) the
     * uncapped product above would otherwise allow: an attacker sustaining the maximum per-request /
     * per-minute rate exhausts this budget in about 18 seconds (1500 &divide; 83.3 conflicts/sec) and is
     * then locked out of further schedule-override declines against this budget for the rest of the
     * hour, regardless of how many more {@code PUT} requests {@link #scheduleOverrideWriteCapacity}
     * would otherwise still allow.
     *
     * <p><b>Window (1 hour, not 1 minute).</b> A per-minute window would clip the legitimate vacation-save
     * burst described above if the mobile client fires its ~31 requests faster than tokens refill; a
     * longer, larger-capacity window absorbs that burst in full while still bounding sustained abuse — see
     * {@link #SCHEDULE_OVERRIDE_DECLINE_BUDGET_WINDOW}.
     *
     * <p><b>CRITICAL invariant (must never regress).</b> Bucket4j's {@code tryConsume(N)} can never
     * succeed when {@code N} exceeds the bucket's OWN capacity, even fully refilled — an earlier version
     * of this feature charged proportionally against a capacity-10 bucket and thereby PERMANENTLY 429'd
     * any override declining 11+ bookings. This capacity (1500) MUST exceed
     * {@code ScheduleOverrideConflictService.MAX_CONFLICTS_PER_WRITE} (100) so a single maximal
     * legitimate request (100 conflicts) can always be satisfied by a full bucket — pinned against the
     * production default by {@code ScheduleOverrideConflictServiceTest
     * #should_allowSingleRequestDecliningMaxConflictsPerWrite_when_budgetBucketIsFull} and enforced at
     * boot for ANY configured value (not just the default) by
     * {@link #validateScheduleOverrideDeclineBudgetCapacity()}, pinned by
     * {@code RateLimitConfigTest#should_failStartup_when_declineBudgetCapacityDoesNotExceedMaxConflictsPerWrite}.
     * Configurable so integration tests can raise the cap.
     */
    @Value("${app.rate-limit.schedule-override-decline-budget-capacity:1500}")
    private long scheduleOverrideDeclineBudgetCapacity;

    /** See {@link #scheduleOverrideDeclineBudgetCapacity}'s javadoc for why 1 hour, not 1 minute. */
    private static final Duration SCHEDULE_OVERRIDE_DECLINE_BUDGET_WINDOW = Duration.ofHours(1);

    /**
     * Boot-time guard for the CRITICAL invariant documented on
     * {@link #scheduleOverrideDeclineBudgetCapacity}: that field's capacity MUST exceed
     * {@link ScheduleOverrideConflictService#MAX_CONFLICTS_PER_WRITE} (100), because Bucket4j's
     * {@code tryConsume(N)} can never succeed for {@code N} greater than the bucket's OWN capacity,
     * even fully refilled. Both existing pin tests (this class's and
     * {@code ScheduleOverrideConflictServiceTest}'s) only ever exercise the hardcoded default (1500)
     * reflected into the field — neither reads whatever value is ACTUALLY configured at runtime — so
     * an operator setting {@code app.rate-limit.schedule-override-decline-budget-capacity} (e.g. via a
     * Railway env var) to anything at or below 100 would silently and PERMANENTLY 429 every legitimate
     * schedule-override write that declines 51-100 conflicts, with no test catching it. This method
     * fails application startup instead, referencing
     * {@link ScheduleOverrideConflictService#MAX_CONFLICTS_PER_WRITE} directly (never a duplicated
     * literal) so the guard cannot itself drift from the value it protects.
     */
    @PostConstruct
    void validateScheduleOverrideDeclineBudgetCapacity() {
        int maxConflictsPerWrite = ScheduleOverrideConflictService.MAX_CONFLICTS_PER_WRITE;
        if (scheduleOverrideDeclineBudgetCapacity <= maxConflictsPerWrite) {
            throw new IllegalStateException(
                    ("app.rate-limit.schedule-override-decline-budget-capacity is %d, but it must be "
                            + "strictly greater than ScheduleOverrideConflictService.MAX_CONFLICTS_PER_WRITE "
                            + "(%d). Bucket4j's tryConsume(N) can never succeed for N greater than a bucket's "
                            + "own capacity, even fully refilled, so a decline-budget capacity at or below "
                            + "the per-write conflict cap would permanently reject (HTTP 429) any legitimate "
                            + "schedule-override write that declines more conflicts than the configured "
                            + "capacity allows. Raise "
                            + "app.rate-limit.schedule-override-decline-budget-capacity to a value strictly "
                            + "greater than %d.")
                            .formatted(scheduleOverrideDeclineBudgetCapacity, maxConflictsPerWrite, maxConflictsPerWrite));
        }
    }

    // 5-minute eviction grace past the rate-limit window so a bucket entry is not
    // evicted the instant its window rolls over (avoids a false-start on the very
    // next request). Shared by every windowed bucket below.
    private static final Duration EVICTION_GRACE = Duration.ofMinutes(5);

    @Bean
    public LoadingCache<String, Bucket> registerBuckets() {
        return bucketCache(DEFAULT_BUCKET_CACHE_SIZE, STANDARD_EVICTION, registerCapacity, ONE_MINUTE);
    }

    @Bean
    public LoadingCache<String, Bucket> loginBuckets() {
        return bucketCache(DEFAULT_BUCKET_CACHE_SIZE, STANDARD_EVICTION, loginCapacity, ONE_MINUTE);
    }

    @Bean
    public LoadingCache<String, Bucket> refreshBuckets() {
        return bucketCache(DEFAULT_BUCKET_CACHE_SIZE, STANDARD_EVICTION, refreshCapacity, ONE_MINUTE);
    }

    @Bean
    public LoadingCache<String, Bucket> slotsBuckets() {
        return bucketCache(DEFAULT_BUCKET_CACHE_SIZE, STANDARD_EVICTION, slotsCapacity, ONE_MINUTE);
    }

    @Bean
    public LoadingCache<String, Bucket> deviceTokenBuckets() {
        return bucketCache(DEFAULT_BUCKET_CACHE_SIZE, STANDARD_EVICTION, deviceTokenCapacity, ONE_MINUTE);
    }

    @Bean
    public LoadingCache<String, Bucket> mediaUploadBuckets() {
        return bucketCache(DEFAULT_BUCKET_CACHE_SIZE, STANDARD_EVICTION, mediaUploadCapacity, ONE_MINUTE);
    }

    @Bean
    public LoadingCache<String, Bucket> verifyEmailBuckets() {
        return bucketCache(
                VERIFY_EMAIL_BUCKET_CACHE_SIZE,
                VERIFY_EMAIL_WINDOW.plus(EVICTION_GRACE),
                verifyEmailCapacity,
                VERIFY_EMAIL_WINDOW);
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/auth/resend-verification}.
     *
     * <p>3 requests per 60-second window matches the per-account RESEND_COOLDOWN (60 s)
     * and is generous enough for a legitimate retry (network hiccup, paste error) while
     * blocking rapid volumetric abuse from a single IP. {@code expireAfterAccess(90 s)}
     * gives a 30-second grace past the window so the entry is not evicted the instant the
     * window rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> resendVerificationBuckets() {
        return bucketCache(
                RESEND_VERIFICATION_BUCKET_CACHE_SIZE,
                Duration.ofSeconds(90),
                resendVerificationCapacity,
                RESEND_VERIFICATION_WINDOW);
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/auth/forgot-password}.
     *
     * <p>Window is 60 minutes — matching the 1-hour token TTL. A user who exhausts
     * the budget at the start of a reset flow will be able to retry when the issued
     * token has expired, naturally forcing a fresh forgot-password request.
     *
     * <p>Kept deliberately separate from {@link #resetPasswordBuckets()} so that
     * exhausting the email-bomb surface here cannot deplete the reset-confirm budget
     * for a user sharing the same NAT egress IP.
     *
     * <p>{@code expireAfterAccess(65 min)} gives a 5-minute grace so the bucket entry
     * is not evicted the moment the window rolls over (avoids a false-start on the
     * very next request).
     */
    @Bean
    public LoadingCache<String, Bucket> forgotPasswordBuckets() {
        return bucketCache(
                DEFAULT_BUCKET_CACHE_SIZE,
                PASSWORD_RESET_WINDOW.plus(EVICTION_GRACE),
                forgotPasswordCapacity,
                PASSWORD_RESET_WINDOW);
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/auth/verify-password-reset-otp}.
     *
     * <p>Mirrors {@link #verifyEmailBuckets()}: 10 requests per 15-minute window per source
     * IP. Generous for a legitimate user re-typing a 6-digit code while still bounding
     * brute-force of the 1,000,000-value OTP space at the IP layer.
     */
    @Bean
    public LoadingCache<String, Bucket> verifyPasswordResetOtpBuckets() {
        return bucketCache(
                VERIFY_EMAIL_BUCKET_CACHE_SIZE,
                VERIFY_PASSWORD_RESET_OTP_WINDOW.plus(EVICTION_GRACE),
                verifyPasswordResetOtpCapacity,
                VERIFY_PASSWORD_RESET_OTP_WINDOW);
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/users/me/change-password/request-otp}.
     *
     * <p>Mirrors {@link #forgotPasswordBuckets()}: 3 requests per 60-minute window per source
     * IP — every successful request emails an OTP, so this is the same email-bomb surface,
     * just for the authenticated "change password from settings" entry point. Kept as its
     * own bucket (not shared with {@link #forgotPasswordBuckets()}) so a NAT-shared client
     * exhausting one flow's budget cannot lock out the other.
     */
    @Bean
    public LoadingCache<String, Bucket> changePasswordOtpBuckets() {
        return bucketCache(
                DEFAULT_BUCKET_CACHE_SIZE,
                PASSWORD_RESET_WINDOW.plus(EVICTION_GRACE),
                changePasswordOtpCapacity,
                PASSWORD_RESET_WINDOW);
    }

    /**
     * Per-IP bucket for {@code PATCH /api/v1/independent-masters/me/profile}.
     *
     * <p>Cap: 10 requests per 60-second window per source IP. An authenticated
     * user with a valid JWT can send at most 10 profile-update writes per minute;
     * beyond that the bucket returns 429. This prevents sustained cache churn
     * ({@code master-detail-by-user} / {@code master-by-user} evictions) and
     * unbounded DB dirty-checking flushes without impacting legitimate usage.
     *
     * <p>IP-keyed (not user-keyed) for consistency with existing buckets: JWT
     * parsing happens in {@link com.beautica.auth.JwtAuthenticationFilter} which
     * runs after this filter, so the principal is not yet available here.
     */
    @Bean
    public LoadingCache<String, Bucket> profileUpdateBuckets() {
        return bucketCache(DEFAULT_BUCKET_CACHE_SIZE, STANDARD_EVICTION, profileUpdateCapacity, ONE_MINUTE);
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/auth/reset-password}.
     *
     * <p>Decoupled from {@link #forgotPasswordBuckets()} (SEC fix): coupling the two
     * paths to one bucket meant a NAT-shared client spamming forgot-password could
     * lock out their own reset-password submit. reset-password sends no email, so a
     * higher cap (10/hr) carries no spam risk and tolerates legitimate new-password
     * typo retries against a single emailed token.
     *
     * <p>Same 60-minute window + 5-minute eviction grace as forgot-password.
     */
    @Bean
    public LoadingCache<String, Bucket> resetPasswordBuckets() {
        return bucketCache(
                DEFAULT_BUCKET_CACHE_SIZE,
                PASSWORD_RESET_WINDOW.plus(EVICTION_GRACE),
                resetPasswordCapacity,
                PASSWORD_RESET_WINDOW);
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/service-categories/requests}.
     *
     * <p>Cap: 5 requests per 60-minute window per source IP. Every successful
     * request triggers an admin notification email, so this is an inbox-flood
     * surface; the low cap mirrors the forgot-password email-bomb posture.
     * {@code expireAfterAccess(65 min)} gives a 5-minute grace past the window so
     * the entry is not evicted the instant the window rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> categoryRequestBuckets() {
        return bucketCache(
                DEFAULT_BUCKET_CACHE_SIZE,
                CATEGORY_REQUEST_WINDOW.plus(EVICTION_GRACE),
                categoryRequestCapacity,
                CATEGORY_REQUEST_WINDOW);
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/service-types/suggest}.
     *
     * <p>Cap: 5 requests per 60-minute window per source IP. Every successful
     * suggestion triggers an admin notification email, so this is an inbox-flood
     * surface; the low cap mirrors the {@link #categoryRequestBuckets()} posture.
     * {@code expireAfterAccess(65 min)} gives a 5-minute grace past the window so
     * the entry is not evicted the instant the window rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> suggestServiceTypeBuckets() {
        return bucketCache(
                DEFAULT_BUCKET_CACHE_SIZE,
                SUGGEST_SERVICE_TYPE_WINDOW.plus(EVICTION_GRACE),
                suggestServiceTypeCapacity,
                SUGGEST_SERVICE_TYPE_WINDOW);
    }

    /**
     * Per-IP bucket for the two bulk-service-create endpoints
     * ({@code POST .../services/bulk}).
     *
     * <p>Cap: 10 requests per 60-second window per source IP — matching the other
     * authenticated write buckets ({@link #mediaUploadBuckets()},
     * {@link #profileUpdateBuckets()}, {@link #deviceTokenBuckets()}). Each request can
     * carry up to 100 items whose validation (type resolution, category checks,
     * persistence) runs in full on every call — the path is additive, so no cheap
     * precondition short-circuits a repeat caller. An unthrottled token-holder is
     * therefore a DoS amplifier; the low cap removes that lever while staying generous
     * for a legitimate retry.
     *
     * <p>IP-keyed (not user-keyed) for consistency with every other bucket in this
     * filter: JWT parsing happens in
     * {@link com.beautica.auth.JwtAuthenticationFilter} which runs after this filter.
     */
    @Bean
    public LoadingCache<String, Bucket> bulkServiceSetupBuckets() {
        return bucketCache(DEFAULT_BUCKET_CACHE_SIZE, STANDARD_EVICTION, bulkServiceSetupCapacity, ONE_MINUTE);
    }

    /**
     * Per-IP bucket for the SINGLE-item service write endpoints on
     * {@code ServiceController} — the create/update/delete/photo routes that are not the
     * {@code /bulk} pair (see {@link #serviceWriteCapacity}'s javadoc for the full route list,
     * why this is not shared with {@link #bulkServiceSetupBuckets()}, and the 60/min arithmetic).
     *
     * <p>Cap: 60 requests per 60-second window per source IP. Before this bucket these routes had
     * no throttle at all, which made single-create a strictly better row-growth lever than the
     * bulk endpoint the {@link #bulkServiceSetupBuckets()} cap was sized to bound.
     *
     * <p>IP-keyed (not user-keyed) for consistency with every other bucket in this filter: JWT
     * parsing happens in {@link com.beautica.auth.JwtAuthenticationFilter}, which runs after
     * {@code AuthRateLimitFilter}.
     */
    @Bean
    public LoadingCache<String, Bucket> serviceWriteBuckets() {
        return bucketCache(DEFAULT_BUCKET_CACHE_SIZE, STANDARD_EVICTION, serviceWriteCapacity, ONE_MINUTE);
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/support/contact}.
     *
     * <p>Cap: 5 requests per 60-minute window per source IP. Every successful request
     * sends an email to the fixed support inbox, so this is an email-bomb / outbound-quota
     * surface; the low cap mirrors the {@link #categoryRequestBuckets()} and
     * {@link #suggestServiceTypeBuckets()} posture. {@code expireAfterAccess(65 min)} gives
     * a 5-minute grace past the window so the entry is not evicted the instant the window
     * rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> supportContactBuckets() {
        return bucketCache(
                DEFAULT_BUCKET_CACHE_SIZE,
                SUPPORT_CONTACT_WINDOW.plus(EVICTION_GRACE),
                supportContactCapacity,
                SUPPORT_CONTACT_WINDOW);
    }

    /**
     * Per-IP bucket for {@code POST /api/v1/book/otp/send}.
     *
     * <p>Cap: 3 requests per 15-minute window per source IP — matching the per-phone
     * service-layer cap in {@code PhoneOtpService} so neither layer is the looser of the
     * two. Each successful request sends a billable SMS, so this is an SMS-bomb /
     * outbound-quota surface guarded at the IP layer (the per-phone check guards a single
     * number; this guards a single network identity rotating phones).
     * {@code expireAfterAccess(20 min)} gives a 5-minute grace past the window so the
     * entry is not evicted the instant the window rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> otpSendBuckets() {
        return bucketCache(
                DEFAULT_BUCKET_CACHE_SIZE,
                OTP_SEND_WINDOW.plus(EVICTION_GRACE),
                otpSendCapacity,
                OTP_SEND_WINDOW);
    }

    /**
     * Per-USER bucket (see field javadoc above) shared by {@code POST /api/v1/bookings} and
     * {@code PATCH /api/v1/bookings/{bookingId}/reschedule}, consumed by
     * {@link com.beautica.booking.filter.BookingRateLimitFilter}. {@code expireAfterAccess}
     * gives a 5-minute grace past the 10-second window so a bucket entry is not evicted the
     * instant the window rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> bookingWriteBuckets() {
        return bucketCache(
                DEFAULT_BUCKET_CACHE_SIZE,
                BOOKING_WRITE_WINDOW.plus(EVICTION_GRACE),
                bookingWriteCapacity,
                BOOKING_WRITE_WINDOW);
    }

    /**
     * Per-user bucket (see {@link #bookingDeclineCapacity} field javadoc) shared by
     * {@code PATCH /api/v1/bookings/{bookingId}/decline} and {@code PATCH
     * /api/v1/bookings/{bookingId}/not-complete}, consumed by
     * {@link com.beautica.booking.filter.BookingRateLimitFilter}'s flat one-token-per-request entry
     * charge. {@code expireAfterAccess} gives a 5-minute grace past the 60-second window so a bucket
     * entry is not evicted the instant the window rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> bookingDeclineBuckets() {
        return bucketCache(
                DEFAULT_BUCKET_CACHE_SIZE,
                BOOKING_DECLINE_WINDOW.plus(EVICTION_GRACE),
                bookingDeclineCapacity,
                BOOKING_DECLINE_WINDOW);
    }

    /**
     * Per-user bucket (see {@link #scheduleOverrideWriteCapacity} field javadoc) for
     * {@code PUT /api/v1/masters/{masterId}/overrides/{date}}, consumed by
     * {@link com.beautica.booking.filter.BookingRateLimitFilter}'s flat one-token-per-request entry
     * charge — the SAME charging shape as every other bucket in this class; this route no longer
     * carries any additional proportional charge (see {@link #bookingDeclineCapacity}'s javadoc for
     * why that was removed). {@code expireAfterAccess} gives a 5-minute grace past the 60-second
     * window so a bucket entry is not evicted the instant the window rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> scheduleOverrideWriteBuckets() {
        return bucketCache(
                DEFAULT_BUCKET_CACHE_SIZE,
                ONE_MINUTE.plus(EVICTION_GRACE),
                scheduleOverrideWriteCapacity,
                ONE_MINUTE);
    }

    /**
     * Per-actor bucket for {@link #scheduleOverrideDeclineBudgetCapacity} (security audit finding 1) —
     * consumed directly by {@code ScheduleOverrideConflictService}, NOT by {@link BookingRateLimitFilter}
     * (see that field's javadoc for why this cannot be a flat per-request filter charge). {@code
     * expireAfterAccess} gives a 5-minute grace past the 1-hour window so a bucket entry is not evicted
     * the instant the window rolls over.
     */
    @Bean
    public LoadingCache<String, Bucket> scheduleOverrideDeclineBudgetBuckets() {
        return bucketCache(
                DEFAULT_BUCKET_CACHE_SIZE,
                SCHEDULE_OVERRIDE_DECLINE_BUDGET_WINDOW.plus(EVICTION_GRACE),
                scheduleOverrideDeclineBudgetCapacity,
                SCHEDULE_OVERRIDE_DECLINE_BUDGET_WINDOW);
    }

    /**
     * {@link BookingRateLimitFilter} — declared here as an explicit {@code @Bean} rather than
     * annotated {@code @Component}, deliberately co-located with the {@link #bookingWriteBuckets()}
     * / {@link #bookingDeclineBuckets()} {@link LoadingCache} beans it consumes.
     *
     * <p><b>Why not {@code @Component}:</b> {@code @WebMvcTest} includes
     * {@link jakarta.servlet.Filter} in its component-scan type filter, so a {@code @Component}
     * filter is auto-detected by EVERY narrow slice — but this {@code @Configuration}, which
     * supplies the filter's buckets, is not loaded by a slice. That mismatch is what made every
     * unprotected slice fail to refresh with {@code No qualifying bean of type
     * LoadingCache<String, Bucket>} (e.g. {@code InternalApiKeyFilterTest},
     * {@code InternalCategoryControllerTest}). Binding filter + buckets into one non-scanned
     * {@code @Configuration} makes them all-or-nothing and therefore safe by construction: the
     * full application context loads all of them — so the production per-user rate limits that
     * close the advisory-lock connection-pool-exhaustion DoS, the guest-decline-SMS mass-burst
     * DoS, AND the schedule-override bulk-write DoS remain fully active and unchanged — while a
     * slice loads none of them and refreshes cleanly, with no per-test mock or import needed.
     */
    @Bean
    public BookingRateLimitFilter bookingRateLimitFilter(ObjectMapper objectMapper) {
        // Direct call (not a LoadingCache<String, Bucket> parameter): this class declares many
        // beans of that exact generic type, so injecting by type would be ambiguous and resolve
        // only by lucky parameter-name matching. @Configuration is CGLIB-proxied, so this returns
        // the same bookingWriteBuckets/bookingDeclineBuckets/scheduleOverrideWriteBuckets
        // singletons — unambiguous by construction.
        return new BookingRateLimitFilter(
                bookingWriteBuckets(), bookingDeclineBuckets(), scheduleOverrideWriteBuckets(), objectMapper);
    }

    /**
     * Shared builder for every per-IP {@link Bucket} cache in this filter. Each bean
     * keeps its OWN security-critical {@code capacity} / {@code refillPeriod} (the rate
     * limit) and its OWN {@code maxSize} / {@code evictAfterAccess} (Caffeine eviction
     * tuning); only the Caffeine + Bucket4j construction boilerplate is shared here.
     */
    private LoadingCache<String, Bucket> bucketCache(
            long maxSize, Duration evictAfterAccess, long capacity, Duration refillPeriod) {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(evictAfterAccess)
                .build(key -> Bucket.builder()
                        .addLimit(bandwidthOf(capacity, refillPeriod))
                        .build());
    }

    private Bandwidth bandwidthOf(long capacity, Duration period) {
        return BandwidthBuilder.builder()
                .capacity(capacity)
                .refillIntervally(capacity, period)
                .build();
    }
}
