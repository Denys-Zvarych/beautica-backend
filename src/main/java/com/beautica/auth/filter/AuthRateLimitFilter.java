package com.beautica.auth.filter;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final byte[] TOO_MANY_REQUESTS_BODY =
            "{\"error\":\"Too many requests\"}".getBytes(StandardCharsets.UTF_8);

    private static final String REGISTER_PATH = "/api/v1/auth/register";
    private static final String REGISTER_IM_PATH = "/api/v1/auth/register/independent-master";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String VERIFY_EMAIL_PATH = "/api/v1/auth/verify-email";
    private static final String RESEND_VERIFICATION_PATH = "/api/v1/auth/resend-verification";
    private static final String FORGOT_PASSWORD_PATH = "/api/v1/auth/forgot-password";
    private static final String VERIFY_PASSWORD_RESET_OTP_PATH = "/api/v1/auth/verify-password-reset-otp";
    private static final String RESET_PASSWORD_PATH = "/api/v1/auth/reset-password";
    private static final String CHANGE_PASSWORD_OTP_PATH = "/api/v1/users/me/change-password/request-otp";
    private static final String INVITE_PATH = "/api/v1/auth/invite";
    private static final String LOGOUT_PATH = "/api/v1/auth/logout";
    // The two master-availability READ endpoints, which share ONE bucket (slotsBuckets) because they are
    // the same class of request from the same screen: the client booking calendar fetches
    // /working-days for the visible month, then /slots for the tapped day. Same technique as
    // rotateStaffBuckets (one bucket for one class of endpoint).
    //
    // /working-days was previously UNTHROTTLED — nothing in this filter matched /api/v1/masters/** except
    // /slots, and the booking-write bucket only covers /api/v1/bookings. That mattered because its
    // master-bookable-days cache is keyed on the RAW client-supplied {from, to}: an authenticated caller
    // rotating `from` by a day could force an unbounded stream of cache misses, evicting every legitimate
    // entry (2 000-entry cache) and turning each miss into 4 DB queries + a full slot walk. The other half
    // of that fix is the ≤63-day window cap in SlotCalculationService#assertBookableSpan, which bounds the
    // COST and SIZE of a single miss; this bucket bounds the RATE.
    private static final String MASTER_AVAILABILITY_PATH_PREFIX = "/api/v1/masters/";
    private static final String SLOTS_PATH_SUFFIX = "/slots";
    private static final String WORKING_DAYS_PATH_SUFFIX = "/working-days";
    private static final String DEVICE_TOKEN_PATH = "/api/v1/devices/token";
    private static final String MEDIA_PATH_PREFIX = "/api/v1/media/";
    private static final String PROFILE_UPDATE_PATH = "/api/v1/independent-masters/me/profile";
    private static final String USER_ME_PATH = "/api/v1/users/me";
    private static final String IM_LOCALITY_PATH = "/api/v1/independent-masters/me";
    private static final String MASTERS_ME_PROFILE_PATH = "/api/v1/masters/me/profile";
    private static final String CATEGORY_REQUEST_PATH = "/api/v1/service-categories/requests";
    private static final String SUGGEST_SERVICE_TYPE_PATH = "/api/v1/service-types/suggest";
    // Bulk-service-create endpoints. The independent path is an exact match;
    // the salon path carries {salonId}/{masterId} variables, so it is matched by prefix +
    // suffix (same technique as the parameterized SLOTS_PATH below).
    private static final String BULK_IM_SERVICES_PATH = "/api/v1/independent-masters/me/services/bulk";
    private static final String BULK_SALON_SERVICES_PREFIX = "/api/v1/salons/";
    private static final String BULK_SALON_SERVICES_SUFFIX = "/services/bulk";
    // Salon-scoped invite POST carries the {salonId} variable, so it is matched by prefix +
    // suffix (same technique as BULK_SALON_SERVICES above): /api/v1/salons/{salonId}/invite.
    // This is the actual HTTP path SalonController.inviteMaster exposes to SALON_OWNER and
    // (Phase 21.1 multi-admin relaxation) SALON_ADMIN callers; it delegates to the same
    // InviteService.sendInvite as POST /api/v1/auth/invite (see INVITE_PATH below) and so
    // carries the identical residual timing/enumeration side-channel — it needs its own bucket
    // because it is a distinct HTTP path.
    private static final String SALON_INVITE_PATH_PREFIX = "/api/v1/salons/";
    private static final String SALON_INVITE_PATH_SUFFIX = "/invite";
    private static final String SUPPORT_CONTACT_PATH = "/api/v1/support/contact";
    private static final String OTP_SEND_PATH = "/api/v1/book/otp/send";
    private static final String OTP_VERIFY_PATH = "/api/v1/book/otp/verify";
    // Guest-booking POST carries the {slug} variable, so it is matched by prefix + suffix
    // (same technique as BULK_SALON_SERVICES / SLOTS): /api/v1/book/{slug}/booking. The suffix
    // /booking does not collide with the OTP paths (/send, /verify) nor with the public GET
    // availability read, so only the booking POST consumes this bucket.
    private static final String GUEST_BOOKING_PATH_PREFIX = "/api/v1/book/";
    private static final String GUEST_BOOKING_PATH_SUFFIX = "/booking";
    // Guest cancel-by-link POST carries the {token} variable as a single path segment, so it
    // is matched by prefix only: /api/v1/book/cancel/{token}. The prefix /api/v1/book/cancel/
    // does not collide with the guest-booking POST (which ends in /booking) nor with the OTP
    // exact paths (/book/otp/send, /book/otp/verify), and the GET cancel-info read is excluded
    // by the POST-method guard, so only the cancel POST consumes this bucket.
    private static final String CANCEL_POST_PATH_PREFIX = "/api/v1/book/cancel/";
    // Discovery search read paths (all GET, all permitAll): /api/v1/search/masters and
    // /api/v1/search/salons. Matched by prefix so any future /search/* read inherits the
    // same throttle. These endpoints now surface authed-only street addresses for
    // independent masters, so an unthrottled crawler could bulk-harvest home addresses;
    // this is the IP-layer defence against that scraping.
    private static final String SEARCH_PATH_PREFIX = "/api/v1/search/";
    // Remove-admin DELETE carries both {salonId} and {userId} path variables, with the literal
    // "/admins/" segment between them: /api/v1/salons/{salonId}/admins/{userId}. Neither variable
    // can itself contain a "/" (both are UUIDs), so prefix + contains(segment) uniquely identifies
    // this route — it does not collide with the sibling DELETE /api/v1/salons/{salonId}
    // (deactivateSalon), which has no "/admins/" segment at all.
    private static final String REMOVE_ADMIN_PATH_PREFIX = "/api/v1/salons/";
    private static final String REMOVE_ADMIN_PATH_SEGMENT = "/admins/";
    // Phase 21.3 staff-rotation PATCH endpoints share ONE bucket (rotateStaffBuckets below) — both
    // are the same class of low-frequency admin action. PATCH /api/v1/salons/{salonId}/admins/{userId}/salon
    // (SalonController#rotateAdmin) carries both {salonId} and {userId} variables with the literal
    // "/admins/" segment between them, same as REMOVE_ADMIN above, plus a literal "/salon" suffix —
    // matched by prefix + REMOVE_ADMIN_PATH_SEGMENT + suffix so it does not collide with the sibling
    // DELETE /api/v1/salons/{salonId}/admins/{userId} (no suffix) nor with PATCH /api/v1/salons/{salonId}
    // (updateSalon — a bare UUID path, which can never contain the literal substring "salon" since
    // UUIDs are hex-only). PATCH /api/v1/masters/{masterId}/salon (MasterController#rotateMasterSalon)
    // carries the {masterId} variable, matched by prefix + suffix only (no "/admins/" segment applies).
    private static final String ROTATE_ADMIN_SALON_PATH_PREFIX = "/api/v1/salons/";
    private static final String ROTATE_MASTER_SALON_PATH_PREFIX = "/api/v1/masters/";
    private static final String ROTATE_STAFF_SALON_PATH_SUFFIX = "/salon";
    // Resolves the DECODED + NORMALIZED request path for rule matching (see resolveMatchPath).
    // urlDecode + removeSemicolonContent are UrlPathHelper defaults; set explicitly so the
    // security-critical decode step is self-documenting and cannot be silently disabled by a
    // future default change. Stateless after construction and thread-safe for the read-only
    // getPathWithinApplication call, so a single shared static instance is correct.
    private static final UrlPathHelper MATCH_PATH_HELPER = createMatchPathHelper();

    private static UrlPathHelper createMatchPathHelper() {
        UrlPathHelper helper = new UrlPathHelper();
        helper.setUrlDecode(true);
        helper.setRemoveSemicolonContent(true);
        return helper;
    }

    private static final int RETRY_AFTER_SECONDS = 60;
    // category-request bucket window is 60 minutes — Retry-After reflects the window.
    private static final int CATEGORY_REQUEST_RETRY_AFTER_SECONDS = 3600;
    // suggest-service-type bucket window is 60 minutes — Retry-After reflects the window.
    private static final int SUGGEST_SERVICE_TYPE_RETRY_AFTER_SECONDS = 3600;
    // support-contact bucket window is 60 minutes — Retry-After reflects the window.
    private static final int SUPPORT_CONTACT_RETRY_AFTER_SECONDS = 3600;
    // verify-email bucket window is 15 minutes — Retry-After must reflect the actual window
    // so clients do not spin-retry every 60 s and waste their remaining IP quota.
    private static final int VERIFY_EMAIL_RETRY_AFTER_SECONDS = 900;
    // forgot-password / reset-password / change-password-otp bucket window is 60 minutes.
    private static final int FORGOT_PASSWORD_RETRY_AFTER_SECONDS = 3600;
    // verify-password-reset-otp bucket window is 15 minutes — mirrors VERIFY_EMAIL.
    private static final int VERIFY_PASSWORD_RESET_OTP_RETRY_AFTER_SECONDS = 900;
    // otp-send bucket window is 15 minutes — Retry-After reflects the actual window.
    private static final int OTP_SEND_RETRY_AFTER_SECONDS = 900;
    // otp-verify bucket window is 15 minutes — Retry-After reflects the actual window.
    private static final int OTP_VERIFY_RETRY_AFTER_SECONDS = 900;
    // guest-booking-POST bucket window is 15 minutes — Retry-After reflects the actual window.
    private static final int GUEST_BOOKING_RETRY_AFTER_SECONDS = 900;
    // cancel-POST bucket window is 15 minutes — Retry-After reflects the actual window.
    private static final int CANCEL_POST_RETRY_AFTER_SECONDS = 900;
    // Per-IP cap for POST /api/v1/book/otp/verify (10 / 15 min). Higher than /send (3)
    // since a legitimate guest may retype a code, but bounded so a single IP cannot pour
    // unlimited verify attempts at freshly-sent OTPs and defeat the per-OTP attempt cap by
    // re-sending. This bucket is built internally (not an injected @Qualifier bean) so the
    // existing 16-arg constructor — depended on by several slice/regression tests — is
    // unchanged.
    private static final long OTP_VERIFY_CAPACITY = 10;
    private static final Duration OTP_VERIFY_WINDOW = Duration.ofMinutes(15);
    // Per-IP cap for POST /api/v1/book/{slug}/booking (5 / 15 min). The endpoint is
    // permitAll() — the guest JWT is validated inside GuestBookingService, not the Spring
    // filter chain — so without this an attacker holding (or replaying) one valid guest token
    // could pour unlimited CONFIRMED bookings + billable confirmation SMS at a master. Built
    // internally (not an injected @Qualifier bean) so the public 16-arg constructor stays stable
    // for the slice/regression tests that construct this filter directly.
    private static final long GUEST_BOOKING_CAPACITY = 5;
    private static final Duration GUEST_BOOKING_WINDOW = Duration.ofMinutes(15);
    // Per-IP cap for POST /api/v1/book/cancel/{token} (10 / 15 min). The endpoint is
    // permitAll() — the guest holds only the one-time cancel token, validated inside the
    // booking service, not the Spring filter chain — so without this a single source IP can
    // pour cancel attempts: each accepted POST runs a findByCancelTokenWithGraph JOIN-FETCH +
    // conditional UPDATE, and a successful cancel dispatches a billable SMS. The token is
    // 122-bit (not brute-forceable), so the cap is generous enough for a legitimate guest
    // retrying their own cancel. Built internally (not an injected @Qualifier bean) so the
    // public 16-arg constructor stays stable for the slice/regression tests that construct
    // this filter directly.
    private static final long CANCEL_POST_CAPACITY = 10;
    private static final Duration CANCEL_POST_WINDOW = Duration.ofMinutes(15);
    // Per-IP cap for GET /api/v1/search/** (240 / 60 s). These permitAll() discovery reads
    // expose authed-only street addresses for independent masters, so the throttle bounds the
    // RATE at which a single source can page through every district/city and the DB work each
    // request costs.
    //
    // RAISED from 40 (perf/security audit 2026-07-29) — 40/min was an availability regression
    // for legitimate users, not a meaningful anti-enumeration control:
    //
    //  * The bucket bounds rate, never total. `size` is capped at 100 and there are low
    //    thousands of active providers, so a crawler drains the whole catalogue in a couple of
    //    dozen requests either way — 40/min made that take ~35 s instead of ~6 s. What the cap
    //    actually buys is a ceiling on sustained DB amplification per source, and 4 req/s is a
    //    firm one for a query whose heaviest measured shape is tens of milliseconds.
    //  * 40/min was below real usage. The client search box is incremental: it issues a request
    //    per settled keystroke, and the discovery screen queries masters AND salons, so ~2
    //    requests per settled keystroke. One user typing two queries («ламінування вій» ≈ 13
    //    settle points) consumes the entire minute's budget on their own.
    //  * The key makes that worse in exactly the market this serves. The rightmost
    //    X-Forwarded-For entry is the correct spoof-resistant choice (see resolveClientIp), but
    //    Ukrainian mobile users sit behind carrier-grade NAT, so thousands of unrelated
    //    subscribers resolve to ONE bucket and the whole pool 429s permanently.
    //
    // NOT switched to principal keying. Doing so would mean parsing the JWT here, in a filter
    // that runs BEFORE JwtAuthenticationFilter and deliberately knows nothing about the auth
    // subsystem (see the comment on deviceTokenBuckets) — and it would not fix the case that
    // motivates the change, since /search/** is permitAll and the CGNAT-shared callers being
    // locked out are precisely the anonymous ones with no principal to key on. IP-keyed for
    // consistency with every other bucket in this filter. Built internally (not an injected
    // @Qualifier bean) so the public 16-arg constructor — depended on by several
    // slice/regression tests — stays unchanged.
    private static final long SEARCH_CAPACITY = 240;
    private static final Duration SEARCH_WINDOW = Duration.ofMinutes(1);
    // Token cost per search request — see searchTokenCost() for why a deep page costs 2.
    // The capacity above is deliberately UNCHANGED: this corrects the accounting, not the cap.
    private static final long SEARCH_TOKENS_FIRST_PAGE = 1;
    private static final long SEARCH_TOKENS_DEEP_PAGE = 2;
    private static final String SEARCH_PAGE_PARAM = "page";
    // Per-IP cap for POST /api/v1/auth/invite (15 / 60 s) — the FIRST bound on a previously
    // unthrottled surface. This is both the residual enumeration/timing surface left after the
    // InviteService 409->idempotent fix (the already-registered and active-invite branches do
    // measurably less work — no token gen, hash, persist or outbox encrypt — so an unthrottled
    // authenticated SALON_OWNER could gather enough timing samples to infer registration
    // status) AND an invite-email surface (the happy path enqueues an outbox e-mail). 15/min
    // is generous for a human onboarding their team one invite at a time while bounding both
    // automated probing and e-mail abuse; any abuse is still attributable to the authenticated
    // SALON_OWNER principal. IP-keyed for consistency with every other bucket here (JWT is
    // parsed in JwtAuthenticationFilter, which runs AFTER this filter). Built internally (not
    // an injected @Qualifier bean) so the public 16-arg constructor — depended on by several
    // slice/regression tests — stays unchanged.
    private static final long INVITE_CAPACITY = 15;
    private static final Duration INVITE_WINDOW = Duration.ofMinutes(1);
    // Per-IP cap for POST /api/v1/salons/{salonId}/invite (15 / 60 s) — mirrors INVITE_CAPACITY
    // / INVITE_WINDOW above (kept as its own dedicated constants, not shared, so the two
    // endpoints can be tuned independently). Phase 21.1 (multi-admin relaxation) widened the
    // population that can reach InviteService.sendInvite through this path from SALON_OWNER-only
    // to SALON_OWNER + SALON_ADMIN, so the residual already-registered/active-invite timing
    // side-channel documented on InviteService.sendInvite is now reachable by more principals.
    // This bucket is the compensating control for that path, exactly as inviteBuckets is for
    // POST /api/v1/auth/invite.
    private static final long SALON_INVITE_CAPACITY = 15;
    private static final Duration SALON_INVITE_WINDOW = Duration.ofMinutes(1);
    // Per-IP cap for POST /api/v1/auth/logout (30 / 60 s) — SEC-fix regression net: logout
    // was previously not covered by this filter at all, an unbounded surface on an otherwise
    // fully-throttled /auth/* namespace. Logout carries no email/SMS cost and is the
    // lowest-risk auth mutation in this filter (it can only ever narrow what the calling
    // token can do — revoke its own session), so the cap is deliberately generous: at or
    // above the refreshBuckets ceiling (20/min default) rather than the tighter email-bomb
    // buckets, while still bounding a pathological client-side retry loop hammering the
    // denylist-write + refresh-token-delete path. Built internally (not an injected
    // @Qualifier bean) so the public 16-arg constructor — depended on by several
    // slice/regression tests — stays unchanged.
    private static final long LOGOUT_CAPACITY = 30;
    private static final Duration LOGOUT_WINDOW = Duration.ofMinutes(1);
    // Per-IP cap for DELETE /api/v1/salons/{salonId}/admins/{userId} (10 / 60 s) — Phase 21.2
    // SEC-fix regression net. This is the actual HTTP path SalonController.removeAdmin exposes to
    // SALON_OWNER and SALON_ADMIN callers (canManageSalon + adminBelongsToSalon compose correctly
    // in the @PreAuthorize SpEL, so cross-salon IDOR is not the concern here); without this bucket
    // it fell through to the unmatched-DELETE branch with zero throttling, letting an authorized
    // actor script rapid-fire admin removals or probe many {userId} values within their own salon.
    // Mirrors the salonInviteBuckets pattern (Phase 21.1): built internally (not an injected
    // @Qualifier bean) so the public 16-arg constructor — depended on by several slice/regression
    // tests — stays unchanged.
    private static final long REMOVE_ADMIN_CAPACITY = 10;
    private static final Duration REMOVE_ADMIN_WINDOW = Duration.ofMinutes(1);
    // Per-IP cap for the two Phase 21.3 staff-rotation PATCH endpoints, SHARED as one bucket
    // (30 / 60 s) — mirrors logoutBuckets' reasoning (30/min, "generous cap, at or above the
    // refreshBuckets ceiling") rather than the tighter removeAdminBuckets/salonInviteBuckets
    // ceilings (10-15/min). The security audit that requested this bucket assessed it as
    // LOW/optional defense-in-depth, not a strict abuse-prevention requirement — staff rotation
    // has no email/SMS cost and is bounded by the same-owner authorization check regardless of
    // request volume, so 10/min was too tight: a SALON_OWNER doing a busy day of reshuffling
    // staff across several owned salons can easily exceed 10 combined admin+master rotations
    // within 60 s, and clients that honour Retry-After (Apache HttpClient5's default retry
    // strategy, common in Retrofit/OkHttp mobile stacks) will block for the full 60 s window
    // rather than failing fast. 30/min comfortably absorbs a realistic bulk-reshuffle session
    // while still bounding a scripted rapid-fire probe of destination-salon ids.
    private static final long ROTATE_STAFF_CAPACITY = 30;
    private static final Duration ROTATE_STAFF_WINDOW = Duration.ofMinutes(1);

    private final LoadingCache<String, Bucket> registerBuckets;
    private final LoadingCache<String, Bucket> loginBuckets;
    private final LoadingCache<String, Bucket> refreshBuckets;
    private final LoadingCache<String, Bucket> verifyEmailBuckets;
    private final LoadingCache<String, Bucket> slotsBuckets;
    // IP-keyed (not user-keyed): JWT parsing is the responsibility of JwtAuthenticationFilter
    // which runs *after* this filter; resolving the principal here would duplicate that work
    // and couple the rate limiter to the auth subsystem.
    private final LoadingCache<String, Bucket> deviceTokenBuckets;
    // Same IP-keyed rationale applies to media uploads — JwtAuthenticationFilter
    // runs after this one, so the rate limiter sees only the network identity.
    private final LoadingCache<String, Bucket> mediaUploadBuckets;
    // Per-IP bucket for PATCH /api/v1/independent-masters/me/profile — prevents
    // unbounded DB writes and cache churn from a token-holding client (10/min).
    private final LoadingCache<String, Bucket> profileUpdateBuckets;
    private final LoadingCache<String, Bucket> resendVerificationBuckets;
    // Separate per-IP buckets for forgot-password and reset-password (each 60-minute
    // window). Decoupled (SEC fix): a NAT-shared client spamming forgot-password must
    // not deplete the reset-confirm budget. forgot-password is the email-bomb surface
    // (low cap); reset-password sends no email (higher cap, tolerant of typo retries).
    private final LoadingCache<String, Bucket> forgotPasswordBuckets;
    private final LoadingCache<String, Bucket> resetPasswordBuckets;
    // Per-IP bucket for POST /api/v1/auth/verify-password-reset-otp — mirrors
    // verifyEmailBuckets (10/15min), the IP-layer brute-force guard on the 6-digit OTP.
    private final LoadingCache<String, Bucket> verifyPasswordResetOtpBuckets;
    // Per-IP bucket for POST /api/v1/users/me/change-password/request-otp — mirrors
    // forgotPasswordBuckets (3/hr), the email-bomb guard for the authenticated
    // change-password-from-settings entry point. Kept separate from forgotPasswordBuckets
    // so a NAT-shared client cannot deplete one flow's budget and lock out the other.
    private final LoadingCache<String, Bucket> changePasswordOtpBuckets;
    // Per-IP bucket for POST /api/v1/service-categories/requests — every successful
    // request emails the admin, so this is an inbox-flood surface (5/hr).
    private final LoadingCache<String, Bucket> categoryRequestBuckets;
    // Per-IP bucket for POST /api/v1/service-types/suggest — every successful
    // suggestion emails the admin, so this is an inbox-flood surface (5/hr).
    private final LoadingCache<String, Bucket> suggestServiceTypeBuckets;
    // Per-IP bucket for the two bulk-service-create endpoints. Every call runs full
    // 100-item validation + persistence (the path is additive — no cheap precondition
    // rejects a repeat caller), so an authenticated token-holder is a DoS amplifier
    // without this guard (10/min).
    private final LoadingCache<String, Bucket> bulkServiceSetupBuckets;
    // Per-IP bucket for POST /api/v1/support/contact — every successful request emails
    // the support inbox, so this is an email-bomb / outbound-quota surface (5/hr).
    private final LoadingCache<String, Bucket> supportContactBuckets;
    // Per-IP bucket for POST /api/v1/book/otp/send — every successful request sends a
    // billable SMS, so this is an SMS-bomb / outbound-quota surface (3 / 15 min). This is
    // the IP-layer defence complementing the per-phone rate limit in PhoneOtpService.
    private final LoadingCache<String, Bucket> otpSendBuckets;
    // Per-IP bucket for POST /api/v1/book/otp/verify — the IP-layer half of the CRITICAL
    // brute-force fix (the per-OTP attempt counter in PhoneOtpService is the other half).
    // Built internally rather than injected so the public 16-arg constructor stays stable
    // for the slice/regression tests that construct this filter directly.
    private final LoadingCache<String, Bucket> otpVerifyBuckets;
    // Per-IP bucket for POST /api/v1/book/{slug}/booking — the HIGH-fix flood guard for the
    // permitAll() guest-booking endpoint (each accepted POST persists a CONFIRMED booking and
    // sends a billable SMS). Built internally rather than injected so the public 16-arg
    // constructor stays stable for the slice/regression tests that construct this filter directly.
    private final LoadingCache<String, Bucket> guestBookingBuckets;
    // Per-IP bucket for POST /api/v1/book/cancel/{token} — the LOW-fix flood guard for the
    // permitAll() guest cancel-by-link endpoint (each accepted POST runs a JOIN-FETCH graph
    // load + conditional UPDATE and a successful cancel sends a billable SMS). Built internally
    // rather than injected so the public 16-arg constructor stays stable for the slice/regression
    // tests that construct this filter directly.
    private final LoadingCache<String, Bucket> cancelPostBuckets;
    // Per-IP bucket for GET /api/v1/search/** — the SEC-fix scraping guard for the permitAll()
    // discovery reads (which now surface authed-only independent-master street addresses).
    // Built internally rather than injected so the public 16-arg constructor stays stable for
    // the slice/regression tests that construct this filter directly.
    private final LoadingCache<String, Bucket> searchBuckets;
    // Per-IP bucket for POST /api/v1/auth/invite — the compensating control for the residual
    // timing oracle in InviteService.sendInvite (the already-registered / active-invite
    // branches return fast). Built internally rather than injected so the public 16-arg
    // constructor stays stable for the slice/regression tests that construct this filter directly.
    private final LoadingCache<String, Bucket> inviteBuckets;
    // Per-IP bucket for POST /api/v1/salons/{salonId}/invite — the SEC-fix compensating control
    // closing the gap left when this path (the actual HTTP surface for SalonController.inviteMaster,
    // reachable by SALON_OWNER and, since Phase 21.1, SALON_ADMIN) fell through to the unmatched
    // else branch with no throttle at all, even though it delegates to the same
    // InviteService.sendInvite timing-oracle surface as inviteBuckets above. Built internally
    // rather than injected so the public 16-arg constructor stays stable for the slice/regression
    // tests that construct this filter directly.
    private final LoadingCache<String, Bucket> salonInviteBuckets;
    // Per-IP bucket for POST /api/v1/auth/logout — the SEC-fix regression net closing the
    // previously-unthrottled /auth/logout surface. Built internally rather than injected so
    // the public 16-arg constructor stays stable for the slice/regression tests that
    // construct this filter directly.
    private final LoadingCache<String, Bucket> logoutBuckets;
    // Per-IP bucket for DELETE /api/v1/salons/{salonId}/admins/{userId} — the SEC-fix compensating
    // control closing the gap left when this destructive route (Phase 21.2) fell through to the
    // unmatched-DELETE branch with no throttle at all. Built internally rather than injected so
    // the public 16-arg constructor stays stable for the slice/regression tests that construct
    // this filter directly.
    private final LoadingCache<String, Bucket> removeAdminBuckets;
    // Per-IP bucket shared by BOTH Phase 21.3 staff-rotation PATCH endpoints — the SEC-fix
    // compensating control closing the gap left when these routes fell through to the
    // unmatched-PATCH branch with no throttle at all. Built internally (like removeAdminBuckets)
    // so the public 18-arg constructor stays stable for the slice/regression tests that
    // construct this filter directly. Cap is 30/60s — see ROTATE_STAFF_CAPACITY for the
    // reasoning behind the generous ceiling (raised from an initial 10/60s that regressed both
    // realistic bulk-reshuffle usage and CI test isolation).
    private final LoadingCache<String, Bucket> rotateStaffBuckets;

    public AuthRateLimitFilter(
            @Qualifier("registerBuckets") LoadingCache<String, Bucket> registerBuckets,
            @Qualifier("loginBuckets") LoadingCache<String, Bucket> loginBuckets,
            @Qualifier("refreshBuckets") LoadingCache<String, Bucket> refreshBuckets,
            @Qualifier("verifyEmailBuckets") LoadingCache<String, Bucket> verifyEmailBuckets,
            @Qualifier("slotsBuckets") LoadingCache<String, Bucket> slotsBuckets,
            @Qualifier("deviceTokenBuckets") LoadingCache<String, Bucket> deviceTokenBuckets,
            @Qualifier("mediaUploadBuckets") LoadingCache<String, Bucket> mediaUploadBuckets,
            @Qualifier("profileUpdateBuckets") LoadingCache<String, Bucket> profileUpdateBuckets,
            @Qualifier("resendVerificationBuckets") LoadingCache<String, Bucket> resendVerificationBuckets,
            @Qualifier("forgotPasswordBuckets") LoadingCache<String, Bucket> forgotPasswordBuckets,
            @Qualifier("resetPasswordBuckets") LoadingCache<String, Bucket> resetPasswordBuckets,
            @Qualifier("categoryRequestBuckets") LoadingCache<String, Bucket> categoryRequestBuckets,
            @Qualifier("suggestServiceTypeBuckets") LoadingCache<String, Bucket> suggestServiceTypeBuckets,
            @Qualifier("bulkServiceSetupBuckets") LoadingCache<String, Bucket> bulkServiceSetupBuckets,
            @Qualifier("supportContactBuckets") LoadingCache<String, Bucket> supportContactBuckets,
            @Qualifier("otpSendBuckets") LoadingCache<String, Bucket> otpSendBuckets,
            @Qualifier("verifyPasswordResetOtpBuckets") LoadingCache<String, Bucket> verifyPasswordResetOtpBuckets,
            @Qualifier("changePasswordOtpBuckets") LoadingCache<String, Bucket> changePasswordOtpBuckets) {
        this.registerBuckets = registerBuckets;
        this.loginBuckets = loginBuckets;
        this.refreshBuckets = refreshBuckets;
        this.verifyEmailBuckets = verifyEmailBuckets;
        this.slotsBuckets = slotsBuckets;
        this.deviceTokenBuckets = deviceTokenBuckets;
        this.mediaUploadBuckets = mediaUploadBuckets;
        this.profileUpdateBuckets = profileUpdateBuckets;
        this.resendVerificationBuckets = resendVerificationBuckets;
        this.forgotPasswordBuckets = forgotPasswordBuckets;
        this.resetPasswordBuckets = resetPasswordBuckets;
        this.categoryRequestBuckets = categoryRequestBuckets;
        this.suggestServiceTypeBuckets = suggestServiceTypeBuckets;
        this.bulkServiceSetupBuckets = bulkServiceSetupBuckets;
        this.supportContactBuckets = supportContactBuckets;
        this.otpSendBuckets = otpSendBuckets;
        this.verifyPasswordResetOtpBuckets = verifyPasswordResetOtpBuckets;
        this.changePasswordOtpBuckets = changePasswordOtpBuckets;
        this.otpVerifyBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(OTP_VERIFY_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(otpVerifyBandwidth())
                        .build());
        this.guestBookingBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(GUEST_BOOKING_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(guestBookingBandwidth())
                        .build());
        this.cancelPostBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(CANCEL_POST_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(cancelPostBandwidth())
                        .build());
        this.searchBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(SEARCH_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(searchBandwidth())
                        .build());
        this.inviteBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(INVITE_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(inviteBandwidth())
                        .build());
        this.salonInviteBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(SALON_INVITE_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(salonInviteBandwidth())
                        .build());
        this.logoutBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(LOGOUT_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(logoutBandwidth())
                        .build());
        this.removeAdminBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(REMOVE_ADMIN_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(removeAdminBandwidth())
                        .build());
        this.rotateStaffBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(ROTATE_STAFF_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(rotateStaffBandwidth())
                        .build());
    }

    private static Bandwidth otpVerifyBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(OTP_VERIFY_CAPACITY)
                .refillIntervally(OTP_VERIFY_CAPACITY, OTP_VERIFY_WINDOW)
                .build();
    }

    private static Bandwidth guestBookingBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(GUEST_BOOKING_CAPACITY)
                .refillIntervally(GUEST_BOOKING_CAPACITY, GUEST_BOOKING_WINDOW)
                .build();
    }

    private static Bandwidth cancelPostBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(CANCEL_POST_CAPACITY)
                .refillIntervally(CANCEL_POST_CAPACITY, CANCEL_POST_WINDOW)
                .build();
    }

    private static Bandwidth searchBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(SEARCH_CAPACITY)
                .refillIntervally(SEARCH_CAPACITY, SEARCH_WINDOW)
                .build();
    }

    private static Bandwidth inviteBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(INVITE_CAPACITY)
                .refillIntervally(INVITE_CAPACITY, INVITE_WINDOW)
                .build();
    }

    private static Bandwidth salonInviteBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(SALON_INVITE_CAPACITY)
                .refillIntervally(SALON_INVITE_CAPACITY, SALON_INVITE_WINDOW)
                .build();
    }

    private static Bandwidth logoutBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(LOGOUT_CAPACITY)
                .refillIntervally(LOGOUT_CAPACITY, LOGOUT_WINDOW)
                .build();
    }

    private static Bandwidth removeAdminBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(REMOVE_ADMIN_CAPACITY)
                .refillIntervally(REMOVE_ADMIN_CAPACITY, REMOVE_ADMIN_WINDOW)
                .build();
    }

    private static Bandwidth rotateStaffBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(ROTATE_STAFF_CAPACITY)
                .refillIntervally(ROTATE_STAFF_CAPACITY, ROTATE_STAFF_WINDOW)
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = resolveMatchPath(request);
        String method = request.getMethod();

        // Device-token rate-limit: POST or DELETE /api/v1/devices/token — checked before
        // the POST-only branch so DELETE is also covered.
        if (DEVICE_TOKEN_PATH.equals(path)
                && (HttpMethod.POST.matches(method) || HttpMethod.DELETE.matches(method))) {
            applyRateLimit(request, response, filterChain, deviceTokenBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Media rate-limit: POST or DELETE /api/v1/media/* (avatar + portfolio).
        // Checked before the POST-only branch so DELETE /api/v1/media/avatar and
        // DELETE /api/v1/media/portfolio/{id} are also covered. Public GET
        // listings (/api/v1/salons/{id}/portfolio etc.) are intentionally NOT
        // rate-limited here — they're read-only and cached behind R2/CDN.
        if ((HttpMethod.POST.matches(method) || HttpMethod.DELETE.matches(method))
                && path.startsWith(MEDIA_PATH_PREFIX)) {
            applyRateLimit(request, response, filterChain, mediaUploadBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Master-availability read rate-limit: GET /api/v1/masters/{masterId}/slots AND
        // GET /api/v1/masters/{masterId}/working-days — checked before the POST guard. Both are the
        // client booking calendar's reads and share slotsBuckets (default 60 / 60 s per IP; a user
        // paging months + tapping days makes a handful of requests per minute, nowhere near the cap).
        // See MASTER_AVAILABILITY_PATH_PREFIX for why /working-days must be throttled.
        if (HttpMethod.GET.matches(method)
                && path.startsWith(MASTER_AVAILABILITY_PATH_PREFIX)
                && (path.endsWith(SLOTS_PATH_SUFFIX) || path.endsWith(WORKING_DAYS_PATH_SUFFIX))) {
            applyRateLimit(request, response, filterChain, slotsBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Search rate-limit: GET /api/v1/search/** (discovery of masters + salons) — checked
        // before the POST-only guard so these GET reads are covered. These permitAll() paths
        // expose authed-only independent-master street addresses, so the throttle is the
        // IP-layer ceiling on sustained scraping and DB amplification. Cap: 240 / 60 s per IP
        // (see SEARCH_CAPACITY for why 40 was too low). This is the ONLY search bucket — do
        // not add a second one; both /search/masters and /search/salons share it by design.
        if (HttpMethod.GET.matches(method)
                && path.startsWith(SEARCH_PATH_PREFIX)) {
            applyRateLimit(request, response, filterChain, searchBuckets, RETRY_AFTER_SECONDS,
                    searchTokenCost(request));
            return;
        }

        // Profile-update rate-limit: PATCH /me endpoints — checked before the POST-only
        // guard so PATCH is covered. Cap: 10 requests/min per IP (shared profileUpdateBuckets).
        // JWT is not yet parsed at this point; rate limiting is IP-keyed for consistency
        // with all other buckets in this filter (see comment on deviceTokenBuckets field).
        // Covers three paths:
        //   - /api/v1/independent-masters/me/profile (bio, phone, instagram)
        //   - /api/v1/users/me                       (first/last name, phone, locality — all roles)
        //   - /api/v1/independent-masters/me          (locality-only — INDEPENDENT_MASTER)
        if (HttpMethod.PATCH.matches(method)
                && (PROFILE_UPDATE_PATH.equals(path)
                        || USER_ME_PATH.equals(path)
                        || IM_LOCALITY_PATH.equals(path)
                        || MASTERS_ME_PROFILE_PATH.equals(path))) {
            applyRateLimit(request, response, filterChain, profileUpdateBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Bulk-service-setup rate-limit: POST on either bulk route. The independent route
        // is an exact path; the salon route carries {salonId}/{masterId} variables, so it is
        // matched by prefix + suffix (same technique as the SLOTS_PATH branch above). Cap:
        // 10 requests/min per IP (shared bulkServiceSetupBuckets).
        if (HttpMethod.POST.matches(method)
                && (BULK_IM_SERVICES_PATH.equals(path)
                        || (path.startsWith(BULK_SALON_SERVICES_PREFIX)
                                && path.endsWith(BULK_SALON_SERVICES_SUFFIX)))) {
            applyRateLimit(request, response, filterChain, bulkServiceSetupBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Salon-invite rate-limit: POST /api/v1/salons/{salonId}/invite — matched by prefix +
        // suffix (the {salonId} is one path segment, same technique as the bulk-service-setup
        // branch above). This is the actual HTTP path through which SalonController.inviteMaster
        // reaches InviteService.sendInvite for SALON_OWNER and (Phase 21.1 multi-admin relaxation)
        // SALON_ADMIN callers; without this bucket it fell through to the unmatched else branch
        // below with no throttle at all, unlike its sibling POST /api/v1/auth/invite. Cap: 15 / 60 s
        // per IP (salonInviteBuckets).
        if (HttpMethod.POST.matches(method)
                && path.startsWith(SALON_INVITE_PATH_PREFIX)
                && path.endsWith(SALON_INVITE_PATH_SUFFIX)) {
            applyRateLimit(request, response, filterChain, salonInviteBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Cancel-POST rate-limit: POST /api/v1/book/cancel/{token} — matched by prefix only
        // (the {token} is one path segment). Placed before the guest-booking and exact-path POST
        // branches below. The /book/cancel/ prefix does not match the guest-booking suffix
        // (/booking) nor the OTP exact paths (/send, /verify), and the GET cancel-info read is
        // excluded by the POST-method guard, so only the cancel POST consumes this bucket.
        // Cap: 10 / 15 min per IP.
        if (HttpMethod.POST.matches(method)
                && path.startsWith(CANCEL_POST_PATH_PREFIX)) {
            applyRateLimit(request, response, filterChain, cancelPostBuckets,
                    CANCEL_POST_RETRY_AFTER_SECONDS);
            return;
        }

        // Guest-booking-POST rate-limit: POST /api/v1/book/{slug}/booking — matched by
        // prefix + suffix (the {slug} is one path segment). Placed before the exact-path POST
        // branch below. The /booking suffix does not match the OTP exact paths (/send, /verify),
        // and the GET availability read is excluded by the POST-method guard, so only the
        // booking POST consumes this bucket. Cap: 5 / 15 min per IP.
        if (HttpMethod.POST.matches(method)
                && path.startsWith(GUEST_BOOKING_PATH_PREFIX)
                && path.endsWith(GUEST_BOOKING_PATH_SUFFIX)) {
            applyRateLimit(request, response, filterChain, guestBookingBuckets,
                    GUEST_BOOKING_RETRY_AFTER_SECONDS);
            return;
        }

        // Remove-admin rate-limit: DELETE /api/v1/salons/{salonId}/admins/{userId} — Phase 21.2
        // SEC-fix regression net. Checked before the POST-only guard below so this DELETE is
        // covered; without it, this destructive endpoint fell through to the unmatched-DELETE
        // branch with zero throttling. Cap: 10 / 60 s per IP (removeAdminBuckets).
        if (HttpMethod.DELETE.matches(method)
                && path.startsWith(REMOVE_ADMIN_PATH_PREFIX)
                && path.contains(REMOVE_ADMIN_PATH_SEGMENT)) {
            applyRateLimit(request, response, filterChain, removeAdminBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Staff-rotation rate-limit: PATCH /api/v1/salons/{salonId}/admins/{userId}/salon
        // (SalonController#rotateAdmin) and PATCH /api/v1/masters/{masterId}/salon
        // (MasterController#rotateMasterSalon) — Phase 21.3 SEC-fix regression net, mirroring the
        // removeAdminBuckets pattern (Phase 21.2). Both routes are the same class of
        // low-frequency admin action, so they share ONE bucket rather than two. Checked before
        // the POST-only guard so these PATCHes are covered; without this bucket they fell through
        // to the unmatched branch below with zero throttling. Cap: 30 / 60 s per IP (raised from
        // an initial 10/60s — see ROTATE_STAFF_CAPACITY).
        if (HttpMethod.PATCH.matches(method)
                && ((path.startsWith(ROTATE_ADMIN_SALON_PATH_PREFIX)
                        && path.contains(REMOVE_ADMIN_PATH_SEGMENT)
                        && path.endsWith(ROTATE_STAFF_SALON_PATH_SUFFIX))
                    || (path.startsWith(ROTATE_MASTER_SALON_PATH_PREFIX)
                        && path.endsWith(ROTATE_STAFF_SALON_PATH_SUFFIX)))) {
            applyRateLimit(request, response, filterChain, rotateStaffBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        if (!HttpMethod.POST.matches(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        LoadingCache<String, Bucket> cache;

        int retryAfterSeconds = RETRY_AFTER_SECONDS;

        if (REGISTER_PATH.equals(path) || REGISTER_IM_PATH.equals(path)) {
            cache = registerBuckets;
        } else if (LOGIN_PATH.equals(path)) {
            cache = loginBuckets;
        } else if (REFRESH_PATH.equals(path)) {
            cache = refreshBuckets;
        } else if (VERIFY_EMAIL_PATH.equals(path)) {
            cache = verifyEmailBuckets;
            retryAfterSeconds = VERIFY_EMAIL_RETRY_AFTER_SECONDS;
        } else if (RESEND_VERIFICATION_PATH.equals(path)) {
            cache = resendVerificationBuckets;
        } else if (FORGOT_PASSWORD_PATH.equals(path)) {
            cache = forgotPasswordBuckets;
            retryAfterSeconds = FORGOT_PASSWORD_RETRY_AFTER_SECONDS;
        } else if (VERIFY_PASSWORD_RESET_OTP_PATH.equals(path)) {
            cache = verifyPasswordResetOtpBuckets;
            retryAfterSeconds = VERIFY_PASSWORD_RESET_OTP_RETRY_AFTER_SECONDS;
        } else if (RESET_PASSWORD_PATH.equals(path)) {
            cache = resetPasswordBuckets;
            retryAfterSeconds = FORGOT_PASSWORD_RETRY_AFTER_SECONDS;
        } else if (CHANGE_PASSWORD_OTP_PATH.equals(path)) {
            cache = changePasswordOtpBuckets;
            retryAfterSeconds = FORGOT_PASSWORD_RETRY_AFTER_SECONDS;
        } else if (INVITE_PATH.equals(path)) {
            cache = inviteBuckets;
        } else if (LOGOUT_PATH.equals(path)) {
            cache = logoutBuckets;
        } else if (CATEGORY_REQUEST_PATH.equals(path)) {
            cache = categoryRequestBuckets;
            retryAfterSeconds = CATEGORY_REQUEST_RETRY_AFTER_SECONDS;
        } else if (SUGGEST_SERVICE_TYPE_PATH.equals(path)) {
            cache = suggestServiceTypeBuckets;
            retryAfterSeconds = SUGGEST_SERVICE_TYPE_RETRY_AFTER_SECONDS;
        } else if (SUPPORT_CONTACT_PATH.equals(path)) {
            cache = supportContactBuckets;
            retryAfterSeconds = SUPPORT_CONTACT_RETRY_AFTER_SECONDS;
        } else if (OTP_SEND_PATH.equals(path)) {
            cache = otpSendBuckets;
            retryAfterSeconds = OTP_SEND_RETRY_AFTER_SECONDS;
        } else if (OTP_VERIFY_PATH.equals(path)) {
            cache = otpVerifyBuckets;
            retryAfterSeconds = OTP_VERIFY_RETRY_AFTER_SECONDS;
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        applyRateLimit(request, response, filterChain, cache, retryAfterSeconds);
    }

    /**
     * Resolves the path used for throttle-rule matching from the request's DECODED and
     * NORMALIZED path — mirroring how Spring MVC routes the request — instead of the raw,
     * percent-encoded, un-normalized {@link HttpServletRequest#getRequestURI()}.
     *
     * <p>The servlet container hands back {@code getRequestURI()} exactly as received: still
     * percent-encoded and not collapsed. Spring MVC, however, routes on the decoded/normalized
     * path, so matching a rule on the raw URI lets a caller reach a throttled handler with an
     * equivalent spelling that skips the rule — e.g. {@code POST /api/v1/auth/logi%6e} (routes to
     * login, unthrottled credential stuffing) or {@code GET /api/v1/masters/{id}/working%2ddays}.
     * {@code StrictHttpFirewall} does not reject these.
     *
     * <p>This filter runs BEFORE the {@code DispatcherServlet} parses and caches the request path,
     * so {@code ServletRequestPathUtils.getCachedPath(request)} is not yet populated here. We
     * therefore decode/normalize independently via {@link UrlPathHelper} (which percent-decodes
     * ONCE, strips {@code ;matrix} content, and collapses duplicate slashes) and then fold any
     * {@code .}/{@code ..} segments that survive decoding (e.g. {@code %2e%2e}) with
     * {@link StringUtils#cleanPath}. {@code cleanPath} performs no decoding, so there is no
     * double-decode (which would itself open a bypass). Verified against spring-web 6.2.6:
     * {@code working%2ddays -> working-days}, {@code logi%6e -> login},
     * {@code /auth/./login -> /auth/login}, {@code //auth -> /auth}, {@code %2e%2e -> ..} folded,
     * while unencoded paths pass through byte-for-byte so every existing rule's spelling still
     * matches.
     */
    private String resolveMatchPath(HttpServletRequest request) {
        return StringUtils.cleanPath(MATCH_PATH_HELPER.getPathWithinApplication(request));
    }

    /**
     * Tokens a {@code GET /api/v1/search/**} request costs: {@link #SEARCH_TOKENS_FIRST_PAGE}
     * for {@code page=0}, {@link #SEARCH_TOKENS_DEEP_PAGE} for any deeper page.
     *
     * <h4>Why the flat 1-token charge understated the work by 2×</h4>
     * {@link #SEARCH_CAPACITY} was sized as "4 req/s of a query whose heaviest measured shape
     * is tens of milliseconds". But a request with {@code offset > 0} can execute <b>two</b>
     * statements, not one: {@code COUNT(*) OVER()} rides on the returned rows, so an
     * out-of-range page has no row to carry the total and {@code SearchService} recovers it
     * with a first-page probe. And {@code @Cacheable(condition = "#pageable.pageNumber < 5")}
     * means every page ≥ 5 is an unconditional miss, so the deep pages are exactly the ones
     * that always reach the DB. A caller sweeping page indices therefore bought up to 8
     * statements/s against a cap sized for 4. Charging 2 for those makes the number mean what
     * it was sized to mean, without changing the capacity — which both audits agreed is the
     * right value for availability behind carrier-grade NAT (see {@link #SEARCH_CAPACITY}).
     *
     * <p>Reads the {@code page} query parameter directly. Safe here: the branch is GET-only, so
     * {@code getParameter} cannot consume a request body, and an absent / unparsable / negative
     * value falls back to the cheap first-page charge — the DTO's own {@code @PositiveOrZero} /
     * {@code @Max} / result-window constraints are what reject malformed paging, not this
     * filter. The reachable window is bounded by {@code SearchResultWindow} (10 000 rows), so
     * the deep-page population this surcharges is itself finite.</p>
     */
    private static long searchTokenCost(HttpServletRequest request) {
        String page = request.getParameter(SEARCH_PAGE_PARAM);
        if (page == null || page.isBlank()) {
            return SEARCH_TOKENS_FIRST_PAGE;
        }
        try {
            return Long.parseLong(page.trim()) > 0
                    ? SEARCH_TOKENS_DEEP_PAGE
                    : SEARCH_TOKENS_FIRST_PAGE;
        } catch (NumberFormatException ex) {
            // Unparsable page — the request will 400 in validation; charge the base cost.
            return SEARCH_TOKENS_FIRST_PAGE;
        }
    }

    private void applyRateLimit(HttpServletRequest request,
                                HttpServletResponse response,
                                FilterChain filterChain,
                                LoadingCache<String, Bucket> cache,
                                int retryAfterSeconds) throws ServletException, IOException {
        applyRateLimit(request, response, filterChain, cache, retryAfterSeconds, 1L);
    }

    private void applyRateLimit(HttpServletRequest request,
                                HttpServletResponse response,
                                FilterChain filterChain,
                                LoadingCache<String, Bucket> cache,
                                int retryAfterSeconds,
                                long tokens) throws ServletException, IOException {
        String ip = resolveClientIp(request);
        // Clamp to max IPv6 length (45 chars) to prevent oversized Caffeine cache keys
        // crafted via a long X-Forwarded-For header value.
        if (ip.length() > 45) {
            ip = request.getRemoteAddr();
        }
        Bucket bucket = cache.get(ip);

        if (bucket.tryConsume(tokens)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentLength(TOO_MANY_REQUESTS_BODY.length);
            response.getOutputStream().write(TOO_MANY_REQUESTS_BODY);
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xfwd = request.getHeader("X-Forwarded-For");
        if (xfwd != null && !xfwd.isBlank()) {
            String[] parts = xfwd.split(",");
            // Rightmost entry is appended by Railway's trusted proxy — cannot be spoofed.
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].trim();
                if (!part.isEmpty()) {
                    return part.length() > 45 ? request.getRemoteAddr() : part;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
