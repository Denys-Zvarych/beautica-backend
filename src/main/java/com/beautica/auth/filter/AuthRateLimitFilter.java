package com.beautica.auth.filter;

import com.beautica.common.security.BearerTokenExtractor;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final byte[] TOO_MANY_REQUESTS_BODY =
            "{\"error\":\"Too many requests\"}".getBytes(StandardCharsets.UTF_8);

    /**
     * Request attribute set on a catalogue-browse / public-profile GET that carries a
     * {@code Bearer} token (B8 regression fix, 2026-10-05). This filter runs BEFORE
     * {@code JwtAuthenticationFilter}, so it cannot tell a real owner's token from a forged one;
     * instead of charging the anonymous per-IP {@code catalogueBrowseBuckets} it DEFERS the
     * decision, storing the already-resolved, length-clamped client-IP bucket key under this
     * attribute. {@code BookingRateLimitFilter} (after the JWT filter) then charges the caller's
     * per-PRINCIPAL bucket when the token authenticated, or falls back to
     * {@code catalogueBrowseBuckets} under this exact IP key when it did not (forged, expired,
     * revoked, refresh-token-as-bearer, …) — so presenting a bogus bearer never skips throttling.
     * Request attributes are server-side only; a client cannot set one.
     */
    public static final String CATALOGUE_BROWSE_DEFERRED_IP_KEY_ATTRIBUTE =
            AuthRateLimitFilter.class.getName() + ".catalogueBrowseDeferredIpKey";

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
    // The two invite-TOKEN endpoints an invitee (not the inviting SALON_OWNER/SALON_ADMIN) hits
    // directly from the emailed link — both permitAll() in SecurityConfig, both exact matches so
    // neither can collide with INVITE_PATH ("/api/v1/auth/invite", no trailing segment) or with
    // each other. See INVITE_VALIDATE_CAPACITY / INVITE_ACCEPT_CAPACITY for why each has its own
    // bucket and sizing rather than sharing inviteBuckets above (that bucket protects an
    // AUTHENTICATED admin's send-invite action; these protect an UNAUTHENTICATED invitee's
    // read-then-write flow — different actor population, different risk).
    private static final String INVITE_VALIDATE_PATH = "/api/v1/auth/invite/validate";
    private static final String INVITE_ACCEPT_PATH = "/api/v1/auth/invite/accept";
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
    // Phase 343 salon logo/cover: POST/DELETE /api/v1/salons/{salonId}/media/{slot}. Same R2 upload cost
    // class as /api/v1/media/*, so it shares mediaUploadBuckets (see isSalonImagePath).
    private static final String SALON_IMAGE_PATH_PREFIX = "/api/v1/salons/";
    private static final String SALON_IMAGE_SEGMENT = "/media/";
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
    // SINGLE-item service write routes (ServiceController), all sharing serviceWriteBuckets.
    // Three shapes:
    //   1. exact  POST   /api/v1/independent-masters/me/services            (IM single-create)
    //   2. prefix+suffix POST /api/v1/salons/{salonId}/services  AND
    //                    POST /api/v1/salons/{salonId}/masters/{masterId}/services
    //      — both carry path variables and both end in the literal "/services", so one
    //        prefix+suffix rule covers the salon-side single-creates (same technique as
    //        BULK_SALON_SERVICES above). It cannot collide with the bulk route, which ends in
    //        "/services/bulk", nor with the salon-invite route, which ends in "/invite".
    //   3. prefix PATCH/DELETE /api/v1/services/{serviceDefId}
    //      — one prefix covers the update and deactivate routes. It cannot collide
    //        with /api/v1/service-categories/** or /api/v1/service-types/**, which do not start
    //        with the literal "services/" segment.
    //      The photo routes POST/DELETE /api/v1/services/{serviceDefId}/photo are NOT in this
    //      bucket: they are R2 image writes and share mediaUploadBuckets (see isServicePhotoPath),
    //      matched by the earlier media branch so this prefix rule never sees them.
    //
    // Phase 309 added GET /api/v1/salons/{salonId}/masters/{masterId}/services (the salon
    // management read) at the SAME prefix+suffix as shape 2's salon single-create POST. It does
    // NOT join serviceWriteBuckets: every branch below that matches SALON_SINGLE_SERVICE_PREFIX/
    // SUFFIX is additionally gated on HttpMethod.POST.matches(method), so the GET falls through
    // unthrottled, same as every other authenticated read on this controller. Noted here only so
    // this inventory stays truthful about every route living at this path.
    //
    // Phase 314 audit gave the SIBLING public read — GET /api/v1/salons/{salonId}/services (shape
    // 2's OTHER route, the 2-segment one) — its own bucket, catalogueBrowseBuckets (see that
    // field's javadoc + RateLimitConfig#catalogueBrowseCapacity). It reuses these SAME
    // SALON_SINGLE_SERVICE_PREFIX/SUFFIX constants but is matched via
    // isSalonCatalogueServicesPath, which additionally checks the middle segment is a bare
    // {salonId} with no further "/" — precisely so it does NOT also catch the Phase 309 GET two
    // paragraphs above, which stays the documented accepted-risk exception it always was.
    private static final String IM_SINGLE_SERVICE_PATH = "/api/v1/independent-masters/me/services";
    private static final String SALON_SINGLE_SERVICE_PREFIX = "/api/v1/salons/";
    private static final String SALON_SINGLE_SERVICE_SUFFIX = "/services";
    // GET /api/v1/masters/{masterId}/services — the public master-catalogue read, matched by
    // prefix + suffix (same technique as SALON_SINGLE_SERVICE, {masterId} is one path segment).
    // Reuses MASTER_AVAILABILITY_PATH_PREFIX ("/api/v1/masters/") for the prefix half. No other
    // route under that prefix ends in the literal "/services" (unlike the salon side), so a plain
    // prefix+suffix check is unambiguous here — no disambiguation helper needed.
    private static final String MASTER_SERVICES_PATH_SUFFIX = "/services";
    // permitAll public profile/roster reads, throttled to cap id-sweeps (audit 2026-10-05,
    // finding 2; the roster reads run an uncached bookability EXISTS gate per request via
    // MasterRepository#findBookableIdsBySalonId): GET /api/v1/masters/{id}, GET /api/v1/masters/by-salon/{salonId},
    // GET /api/v1/salons/{id} and GET /api/v1/salons/{id}/masters. The {id} segment is captured as
    // ANY single segment ([^/]+) and accepted only if it parses as a UUID exactly the way Spring's
    // router does (see isUuidPathVariable) — NOT a bare prefix, so the authenticated siblings at the
    // same prefix (/masters/me, /salons/mine, /masters/by-salon, …) never enter the anonymous per-IP
    // bucket (cycle-2 B8), and NOT a strict canonical-36-char regex either: Spring trims the path
    // variable and UUID.fromString accepts short forms, so "%20<uuid>", "<uuid>%20" and
    // "a1b2c3d-e4f-…" all reach the handler and used to skip the throttle (re-audit 2026-10-05).
    // Group 1 = the {id} of the single-segment reads, group 2 = the {id} of /salons/{id}/masters.
    private static final Pattern PUBLIC_PROFILE_READ_PATH = Pattern.compile(
            "^/api/v1/(?:masters/(?:by-salon/)?|salons/)([^/]+)$"
                    + "|^/api/v1/salons/([^/]+)/masters$");
    private static final String SERVICE_DEF_WRITE_PATH_PREFIX = "/api/v1/services/";
    // Phase 342 service photo: POST/DELETE /api/v1/services/{serviceDefId}/photo. Same R2 upload cost
    // class as /api/v1/media/*, so it shares mediaUploadBuckets (see isServicePhotoPath).
    private static final String SERVICE_PHOTO_SEGMENT = "/photo";
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
    // Guest availability READ: GET /api/v1/book/{slug}/availability — the public booking page's slot
    // list, matched by the same prefix + a distinct suffix. It is deliberately NOT part of
    // guestBookingBuckets (that bucket's own comment says "only the booking POST consumes this bucket"),
    // because a legitimate guest issues MANY availability GETs per single booking POST — sharing one
    // 5/15min bucket would make browsing dates impossible. See GUEST_AVAILABILITY_CAPACITY.
    private static final String GUEST_AVAILABILITY_PATH_SUFFIX = "/availability";
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
    // Phase 326 settlement autocomplete (GET, permitAll): GET /api/v1/settlements.
    // Matched EXACTLY, not by prefix: there is one route here and no /settlements/** subtree,
    // so a prefix match would silently adopt any future child route into this bucket's budget.
    private static final String SETTLEMENT_SEARCH_PATH = "/api/v1/settlements";
    // Phase 331 search-suggestions autocomplete (GET, permitAll): GET /api/v1/search/suggestions.
    // Matched EXACTLY and checked BEFORE the SEARCH_PATH_PREFIX branch below — this path also
    // starts with "/api/v1/search/", so if this check ran AFTER the prefix branch it would never
    // be reached (the prefix branch returns unconditionally) and suggestions would silently spend
    // the results-search (searchBuckets) budget instead of its own. See the SEARCH_PATH_PREFIX
    // branch's amended comment in doFilterInternal.
    private static final String SEARCH_SUGGESTIONS_PATH = "/api/v1/search/suggestions";
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
    // Per-IP cap for GET /api/v1/book/{slug}/availability (60 / 60 s) — the LOW-fix flood guard for the
    // last unthrottled permitAll() surface under /book. Deliberately the SAME budget as slotsBuckets
    // (the authenticated twin, GET /masters/{id}/slots + /working-days, default 60/60s): the two answer
    // the very same question from the same SlotCalculationService oracle, so the guest page must not be
    // throttled harder than the in-app calendar that costs the server exactly as much.
    //
    // Why it needed one at all: the endpoint drives resolveEffectiveDay + the booking-overlap query + the
    // full slot walk per distinct `date`, at zero auth cost, and the create gate now calls that same
    // oracle. An attacker rotating `date` could sustain that work unbounded. The cap bounds the RATE (the
    // ≤180-day horizon in SlotCalculationService already bounds how many distinct dates exist to rotate,
    // and the available-slots Caffeine cache absorbs repeats of one date).
    //
    // Why 60/min does not throttle legitimate browsing: a real client hits this once per DATE TAP on the
    // public booking page — a human picking a day makes single-digit requests per minute, and even an
    // impatient user tapping through a whole visible week is ~7. 60/min leaves an order of magnitude of
    // headroom, which matters because this bucket is IP-keyed and Ukrainian mobile users share
    // carrier-grade NAT (the availability regression documented at length on SEARCH_CAPACITY). A shared
    // booking link opened by several people behind one CGNAT egress still fits comfortably.
    //
    // Built internally (not an injected @Qualifier bean) so the public 19-arg constructor — depended on
    // by several slice/regression tests — stays unchanged, mirroring guestBookingBuckets/cancelPostBuckets.
    private static final long GUEST_AVAILABILITY_CAPACITY = 60;
    private static final Duration GUEST_AVAILABILITY_WINDOW = Duration.ofMinutes(1);
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
    // Per-IP cap for GET /api/v1/settlements (240 / 60 s) — the Phase 326 settlement
    // autocomplete. permitAll, because the «Населений пункт» field is reached during
    // registration before a token exists (phase-326 D7).
    //
    // WHY IT NEEDS A BUCKET AT ALL, when the sibling locality cascade has none. The cascade's
    // documented exemption (SecurityConfig, Phase 10.7) rests on a fully static dataset served
    // behind a long-lived @Cacheable with no write path: after one cold miss per JVM its
    // uncached surface is bounded by deploy frequency, not request volume. That argument ends
    // exactly where a caller-supplied parameter begins. Only the pre-typing major list is
    // cached here; every typed keystroke runs a real GIN bitmap scan over 25 698 rows, and the
    // key space is every prefix a user can type, so caching the results is not an option
    // either (it would be an anonymous-fillable Caffeine cache). The per-IP ceiling is the
    // control that fits that shape. The cascade's note anticipated this: "revisit only if Part
    // B adds a dynamic/parameterised locality query."
    //
    // WHY NOT FOLD INTO searchBuckets. Same starvation argument salonBoardReadCapacity records,
    // one level over: /search/** is a DISCOVERY search the user runs while browsing, and this
    // is an ADDRESS field the user fills while registering or editing a profile. Sharing one
    // 240-token budget would let a long browsing session 429 an unrelated registration from
    // the same carrier-grade-NAT egress — and CGNAT is the norm on Ukrainian mobile networks,
    // so "same IP" says nothing about "same person". searchBuckets' own comment forbids a
    // second bucket for /search/**; this is not one, it is a different route.
    //
    // SIZING: 240/min, deliberately identical to SEARCH_CAPACITY, because the traffic SHAPE is
    // identical — an incremental field that fires a request per settled keystroke. The reasoning
    // recorded there transfers verbatim: 40/min was measured to be below real usage for a box
    // that emits ~1 request per settle point, and a shared CGNAT egress multiplies that across
    // unrelated subscribers. There is no token-cost function here — every request costs 1 —
    // because this endpoint has no COUNT companion and no deep-paging surcharge.
    //
    // WHAT "CHEAPER PER REQUEST" IS WORTH, MEASURED ADVERSARIALLY. The original note asserted this
    // endpoint was cheaper per request than discovery search and derived 240 from that, on FRIENDLY
    // inputs only. The arithmetic under that heading has now been restated twice and been wrong
    // twice — both times for the same two reasons, which is why this block records the INVARIANT
    // and not only the numbers:
    //
    //  * THE BENIGN WORST CASE WAS THE WRONG TERM. «нов» was chosen for having the most PREFIX
    //    hits (1 065). The cost does not live in the prefix tier. It lives in the SIMILARITY
    //    tier's candidate count, where every candidate pays a similarity() recheck: «вка» alone
    //    yields 6 672 candidates, and the measured benign worst is «іванівка» — 5 102 rechecks,
    //    10.9 ms — roughly 3x the term that was being quoted as the ceiling.
    //  * THE ADVERSARIAL WORST WAS MEASURED AGAINST WHATEVER ATTACK WAS KNOWN THAT WEEK. Each
    //    revision re-measured the input the previous fix had just closed: 119 ms for a zero-trigram
    //    term, then 52 ms for «ка »x17 once a whole-term trigram guard landed, then 22 ms for
    //    «•к»x25 once a per-token floor landed. Three proxies, three bypasses, three sizing notes
    //    that were stale the day after they were written.
    //
    // CURRENT SIZING:
    //
    //   benign worst       «іванівка»   5 102 similarity rechecks        10.9 ms
    //   adversarial worst  best input still admitted by the run guard    10.1 ms
    //   240 x ~10 ms                                                   ~ 2.4 s of DB time /IP-minute
    //
    // The two worst cases are now within 10 % OF EACH OTHER, and that is the durable part of this
    // note rather than a coincidence to re-measure next time. Admission is decided by ONE property:
    // the term must carry an uninterrupted alphanumeric RUN of at least MIN_QUERY_LENGTH characters
    // (NormalizedSearchQuery#hasIndexServableRun). pg_trgm's key set — hence the candidate count,
    // hence the recheck cost — is a function of the DISTINCT trigrams in the term, and repeating a
    // fragment contributes no distinct key. Padding therefore cannot buy an attacker a statement
    // more expensive than some real Ukrainian word of the same run length already costs: the
    // adversarial ceiling is pinned to the benign one BY CONSTRUCTION. ~2.4 s per IP-minute is
    // stable for as long as that predicate is what admits a term.
    //
    // 240 is KEPT at that cost. 2.4 s of statement time is bounded to ~4 % of one connection
    // because the greedy refill spreads it across the 60 s window it was sized for, and the
    // first-contact burst is separately bounded to a quarter of the budget — see
    // settlementSearchBandwidth for both. The @Size(50) ceiling still does independent work: it
    // bounds normalisation and the bound-parameter size, and once bounded the run guard is what
    // bounds the STATEMENT.
    //
    // IF YOU ARE ABOUT TO RE-DERIVE THIS NUMBER: do not re-measure "the worst attack I can think of
    // today", and do not pick the benign term with the most prefix hits. Measure the highest-
    // candidate SIMILARITY term, and then check whether the run-length invariant above still holds —
    // if it does, the adversarial figure follows from the benign one and needs no fresh attack. Both
    // earlier revisions failed by measuring correctly on the wrong input.
    private static final long SETTLEMENT_SEARCH_CAPACITY = 240;
    private static final Duration SETTLEMENT_SEARCH_WINDOW = Duration.ofMinutes(1);
    // FIRST-CONTACT BUDGET — a quarter of the capacity. Bucket4j initialises a bandwidth FULL
    // unless told otherwise, so without this a never-seen IP holds all 240 tokens the instant it
    // arrives and can spend ~2.4 s of database work in one breath at pool-limited concurrency. The
    // greedy refill alone does not fix that; it only governs what happens after the first budget is
    // spent, so it bought roughly 2x, not the ~35x the older note implied.
    //
    // WHY A QUARTER rather than a smaller slice. The drip is SETTLEMENT_SEARCH_CAPACITY per window
    // = 4 tokens/s, which already exceeds the ~1 request/settled-keystroke a human generates, so the
    // initial grant is pure burst headroom, not throughput. 60 tokens covers several settlement
    // names typed end to end plus a mistyped retry before the drip has to carry the session — while
    // capping the cold-start burst at ~0.6 s of database time, a quarter of what it was.
    private static final long SETTLEMENT_SEARCH_INITIAL_TOKENS = SETTLEMENT_SEARCH_CAPACITY / 4;
    // Per-IP cap for GET /api/v1/search/suggestions (Phase 331) — its OWN bucket, a clone of
    // settlementSearchBuckets' shape (same capacity, same greedy refill, same quarter first-
    // contact grant), because the traffic SHAPE is identical: an incremental autocomplete box
    // that fires roughly one request per settled keystroke. Not folded into searchBuckets
    // (SEARCH_CAPACITY) — that bucket's own comment ("This is the ONLY search bucket") is about
    // /search/masters and /search/salons sharing ONE result-page budget; typing traffic on the
    // suggestions box must not starve a concurrent results-page read from the same IP, the exact
    // reasoning settlementSearchBuckets already records for why it is not folded into
    // searchBuckets either. 240/60s per IP.
    private static final long SEARCH_SUGGESTIONS_CAPACITY = 240;
    private static final Duration SEARCH_SUGGESTIONS_WINDOW = Duration.ofMinutes(1);
    private static final long SEARCH_SUGGESTIONS_INITIAL_TOKENS = SEARCH_SUGGESTIONS_CAPACITY / 4;
    // Per-IP cap for POST /api/v1/auth/invite (15 / 60 s) — the FIRST bound on a previously
    // unthrottled surface. This is both the residual enumeration/timing surface left after the
    // InviteService 409->idempotent fix (the already-registered and active-invite branches do
    // measurably less work — no token gen, hash, persist or outbox encrypt — so an unthrottled
    // authenticated SALON_OWNER could gather enough timing samples to infer registration
    // status) AND an invite-email surface (the happy path enqueues an outbox e-mail). 15/min
    // is generous for a human onboarding their team one invite at a time while bounding both
    // automated probing and e-mail abuse; any abuse is still attributable to the authenticated
    // SALON_OWNER principal. IP-keyed for consistency with every other bucket here (JWT is
    // parsed in JwtAuthenticationFilter, which runs AFTER this filter). The capacity/window now
    // live in RateLimitConfig#inviteBuckets() (app.rate-limit.invite-capacity, default 15 / 60 s)
    // so local seeding can raise it; the 429 path reuses RETRY_AFTER_SECONDS (60-second window).
    // Capacity/window for GET /api/v1/auth/invite/validate and POST /api/v1/auth/invite/accept
    // are @Value-configurable in RateLimitConfig (inviteValidateBuckets() / inviteAcceptBuckets(),
    // defaults 30/60s and 20/15min) — UNLIKE salonInviteBuckets below, which is built internally. Reason for the split: InviteControllerIT alone drives dozens of real HTTP
    // calls against these two exact endpoints from 127.0.0.1 across its test methods (unlike the
    // send-invite path, which existing integration coverage reaches only a handful of times), so a
    // fixed low cap would make the test suite itself trip the throttle. Making the cap
    // @Value-configurable lets application-test.yml raise it the same way it already does for
    // register/login/service-write/etc., without weakening the production default. See
    // RateLimitConfig#inviteValidateCapacity / #inviteAcceptCapacity for the full sizing rationale
    // (both are pure load/replay bounds — the 256-bit hashed token makes guessing infeasible
    // regardless, and acceptInvite sends no email/SMS).
    //
    // Retry-After for the accept bucket must reflect its OWN 15-minute window (mirrors
    // CANCEL_POST_RETRY_AFTER_SECONDS / GUEST_BOOKING_RETRY_AFTER_SECONDS below) — otherwise a
    // client honouring Retry-After would spin-retry every 60 s against a budget that will not
    // have refilled. The validate bucket reuses RETRY_AFTER_SECONDS (its window is 60 s).
    private static final int INVITE_ACCEPT_RETRY_AFTER_SECONDS = 900;
    // Per-IP cap for POST /api/v1/salons/{salonId}/invite (15 / 60 s) — mirrors the
    // app.rate-limit.invite-capacity default for POST /auth/invite (kept as its own dedicated
    // constants, not shared, so the two endpoints can be tuned independently). Phase 21.1 (multi-admin relaxation) widened the
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
    // Per-IP bucket for the SINGLE-item service write routes (create / update / delete). The
    // service photo routes are R2 uploads and draw on mediaUploadBuckets instead.
    // Every one of them was previously unthrottled, which made single-create a strictly BETTER
    // service_definitions row-growth lever than the bulk endpoint bulkServiceSetupBuckets caps —
    // and one that skips the per-master advisory lock too. 60/min; see
    // RateLimitConfig#serviceWriteCapacity for the sizing arithmetic and for why this is a
    // separate bucket rather than a share of bulkServiceSetupBuckets.
    private final LoadingCache<String, Bucket> serviceWriteBuckets;
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
    // Per-IP bucket for GET /api/v1/book/{slug}/availability — the LOW-fix flood guard for the public
    // booking page's slot read, the one permitAll() surface under /book that had no bucket at all. Built
    // internally rather than injected so the public 19-arg constructor stays stable for the
    // slice/regression tests that construct this filter directly.
    private final LoadingCache<String, Bucket> guestAvailabilityBuckets;
    // Per-IP bucket for GET /api/v1/search/** — the SEC-fix scraping guard for the permitAll()
    // discovery reads (which now surface authed-only independent-master street addresses).
    // Built internally rather than injected so the public 16-arg constructor stays stable for
    // the slice/regression tests that construct this filter directly.
    private final LoadingCache<String, Bucket> searchBuckets;
    // Per-IP bucket for GET /api/v1/settlements — the Phase 326 settlement autocomplete's
    // flood/enumeration guard. Built internally rather than injected so the public constructor
    // stays stable for the slice/regression tests that construct this filter directly.
    private final LoadingCache<String, Bucket> settlementSearchBuckets;
    // Per-IP bucket for GET /api/v1/search/suggestions — the Phase 331 search-suggestions
    // autocomplete's own budget, carved out of searchBuckets so typing in the suggestions box
    // cannot starve a concurrent /search/masters or /search/salons read from the same IP. Built
    // internally rather than injected so the public constructor stays stable for the
    // slice/regression tests that construct this filter directly.
    private final LoadingCache<String, Bucket> searchSuggestionBuckets;
    // Per-IP bucket for POST /api/v1/auth/invite — the compensating control for the residual
    // timing oracle in InviteService.sendInvite (the already-registered / active-invite
    // branches return fast). An injected @Qualifier bean (RateLimitConfig#inviteBuckets) so the
    // capacity is configurable via app.rate-limit.invite-capacity (default 15 / 60 s).
    private final LoadingCache<String, Bucket> inviteBuckets;
    // Per-IP bucket for GET /api/v1/auth/invite/validate — the LOW-fix flood guard for the
    // permitAll() invite-preview read that previously fell through the unconditional non-POST
    // early return with no throttle at all. UNLIKE most buckets below, this one IS an injected
    // @Qualifier bean (RateLimitConfig#inviteValidateBuckets) rather than built internally — see
    // the comment on INVITE_ACCEPT_RETRY_AFTER_SECONDS above for why.
    private final LoadingCache<String, Bucket> inviteValidateBuckets;
    // Per-IP bucket for POST /api/v1/auth/invite/accept — the LOW-fix flood guard for the
    // permitAll() invite-acceptance write that previously fell through to the unmatched-POST
    // else branch with no throttle at all. UNLIKE most buckets below, this one IS an injected
    // @Qualifier bean (RateLimitConfig#inviteAcceptBuckets) — same reason as inviteValidateBuckets
    // above.
    private final LoadingCache<String, Bucket> inviteAcceptBuckets;
    // Per-IP bucket for the two public catalogue-browse reads — GET /api/v1/salons/{salonId}/services
    // and GET /api/v1/masters/{masterId}/services (Phase 314 audit finding, MEDIUM). Both are
    // permitAll() and were previously unthrottled anywhere in this filter: ServiceCatalogService's
    // @Cacheable only absorbs repeat hits on the SAME id, so a caller sweeping distinct ids forced
    // an unbounded stream of cache misses plus a full per-master N+1 read on every request. Like
    // inviteValidateBuckets/inviteAcceptBuckets above, this is an injected @Qualifier bean
    // (RateLimitConfig#catalogueBrowseBuckets) rather than built internally, so integration tests
    // hitting these paths many times from 127.0.0.1 can raise the cap via
    // app.rate-limit.catalogue-browse-capacity — see that field's javadoc for the full sizing
    // rationale (60/min, mirroring slotsBuckets).
    private final LoadingCache<String, Bucket> catalogueBrowseBuckets;
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
            @Qualifier("changePasswordOtpBuckets") LoadingCache<String, Bucket> changePasswordOtpBuckets,
            @Qualifier("serviceWriteBuckets") LoadingCache<String, Bucket> serviceWriteBuckets,
            @Qualifier("inviteValidateBuckets") LoadingCache<String, Bucket> inviteValidateBuckets,
            @Qualifier("inviteAcceptBuckets") LoadingCache<String, Bucket> inviteAcceptBuckets,
            @Qualifier("catalogueBrowseBuckets") LoadingCache<String, Bucket> catalogueBrowseBuckets,
            @Qualifier("inviteBuckets") LoadingCache<String, Bucket> inviteBuckets) {
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
        this.serviceWriteBuckets = serviceWriteBuckets;
        this.inviteValidateBuckets = inviteValidateBuckets;
        this.inviteAcceptBuckets = inviteAcceptBuckets;
        this.catalogueBrowseBuckets = catalogueBrowseBuckets;
        this.inviteBuckets = inviteBuckets;
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
        this.guestAvailabilityBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(GUEST_AVAILABILITY_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(guestAvailabilityBandwidth())
                        .build());
        this.searchBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(SEARCH_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(searchBandwidth())
                        .build());
        this.settlementSearchBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(SETTLEMENT_SEARCH_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(settlementSearchBandwidth())
                        .build());
        this.searchSuggestionBuckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(SEARCH_SUGGESTIONS_WINDOW.plusMinutes(5))
                .build(key -> Bucket.builder()
                        .addLimit(searchSuggestionBandwidth())
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

    private static Bandwidth guestAvailabilityBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(GUEST_AVAILABILITY_CAPACITY)
                .refillIntervally(GUEST_AVAILABILITY_CAPACITY, GUEST_AVAILABILITY_WINDOW)
                .build();
    }

    /**
     * Step refill and a full initial budget, unlike its {@link #settlementSearchBandwidth()}
     * neighbour. That asymmetry is deliberate and the reason is a property of the route, not an
     * unmeasured surface.
     *
     * <p>{@code /search/**} parses its {@code q} through
     * {@link com.beautica.search.service.NormalizedSearchQuery#of(String)}, which keeps at most
     * {@link com.beautica.search.service.NormalizedSearchQuery#MAX_TOKENS} (4) whitespace tokens and
     * runs nothing at all unless one of those four is trigram-servable — at least
     * {@link com.beautica.search.service.NormalizedSearchQuery#MIN_QUERY_LENGTH} (3) characters AND
     * trigram-bearing. So the predicate this route can be made to issue is bounded at four terms no
     * matter how long the input is, and the padding shapes that drove the settlement fix («ка »x17
     * and friends) are refused before any SQL: repeating a 2-character fragment produces four
     * unservable tokens and an empty page. Its tables are also low thousands of providers, orders of
     * magnitude under the 25 698-row settlement taxonomy.
     *
     * <p>Both halves of the settlement problem — a worst-case statement far above the benign one,
     * and a per-request cost an attacker could inflate with input length — are therefore absent
     * here, which is what a burst control would have been bounding. "Not measured" was the reason
     * recorded previously and it was the wrong one.
     */
    private static Bandwidth searchBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(SEARCH_CAPACITY)
                .refillIntervally(SEARCH_CAPACITY, SEARCH_WINDOW)
                .build();
    }

    /**
     * GREEDY and NOT initially full, unlike every sibling here — the one bucket in this filter that
     * is neither a step refill nor a full first-contact grant. Both departures bound the same thing,
     * the instantaneous BURST, and neither is sufficient alone.
     *
     * <p><b>Greedy refill</b> — {@code refillIntervally} hands the whole capacity back at once when
     * the window rolls, so a recharged budget is spendable as fast as the client can open sockets.
     * For a 15-token invite bucket that is irrelevant; for 240 tokens against this endpoint it is
     * the attack. {@code refillGreedy} drips the same 240/min back continuously — ~1 token per
     * 250 ms — so a recharged budget is spent over the minute it was sized for. The sustained rate,
     * and therefore every legitimate typing session, is unchanged.
     *
     * <p><b>{@link #SETTLEMENT_SEARCH_INITIAL_TOKENS}</b> — greedy refill governs only the SECOND
     * budget onward. Bucket4j initialises a bandwidth at full capacity, so without an explicit
     * initial-token count a first-seen IP still holds all 240 tokens the moment it arrives and
     * spends ~2.4 s of database work in one breath at pool-limited concurrency — and a rotating
     * source address is free. Greedy refill alone was therefore worth roughly 2x, not the ~35x the
     * earlier note here implied. A quarter of the capacity caps that cold-start burst at ~0.6 s
     * while leaving more headroom than a human typist can consume; the sizing argument is on the
     * constant.
     */
    // Package-private, unlike its siblings: SettlementSearchGetRateLimitRegressionTest builds a
    // bucket from this EXACT Bandwidth over a controllable TimeMeter to assert the greedy drip and
    // the first-contact grant, neither of which is observable through doFilterInternal without
    // sleeping (banned) or waiting out a real 60-second window. Widening the method is cheaper than
    // a reflective read and says out loud that the refill strategy is a tested property, not an
    // incidental one.
    static Bandwidth settlementSearchBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(SETTLEMENT_SEARCH_CAPACITY)
                .refillGreedy(SETTLEMENT_SEARCH_CAPACITY, SETTLEMENT_SEARCH_WINDOW)
                .initialTokens(SETTLEMENT_SEARCH_INITIAL_TOKENS)
                .build();
    }

    /**
     * A clone of {@link #settlementSearchBandwidth()}'s shape (greedy refill + a quarter
     * first-contact grant) for the same reason: {@code GET /api/v1/search/suggestions} is another
     * per-settled-keystroke autocomplete box, so the same burst-vs-sustained-rate argument
     * applies verbatim. Package-private for the same reason as its sibling —
     * {@code SearchSuggestionsGetRateLimitRegressionTest} builds a bucket from this EXACT
     * Bandwidth over a controllable {@code TimeMeter} to assert the refill strategy, which is not
     * observable through {@code doFilterInternal} without a banned sleep or a real 60s wait.
     */
    static Bandwidth searchSuggestionBandwidth() {
        return BandwidthBuilder.builder()
                .capacity(SEARCH_SUGGESTIONS_CAPACITY)
                .refillGreedy(SEARCH_SUGGESTIONS_CAPACITY, SEARCH_SUGGESTIONS_WINDOW)
                .initialTokens(SEARCH_SUGGESTIONS_INITIAL_TOKENS)
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

    /**
     * True for exactly {@code /api/v1/salons/{salonId}/media/{slot}} — one non-empty {@code {salonId}} segment,
     * the literal {@code media} segment, one non-empty {@code {slot}} segment and nothing after it (Phase 343).
     */
    static boolean isSalonImagePath(String path) {
        if (path == null || !path.startsWith(SALON_IMAGE_PATH_PREFIX)) {
            return false;
        }
        String rest = path.substring(SALON_IMAGE_PATH_PREFIX.length());
        int idEnd = rest.indexOf('/');
        if (idEnd <= 0) {
            return false;
        }
        String tail = rest.substring(idEnd);
        return tail.startsWith(SALON_IMAGE_SEGMENT)
                && tail.length() > SALON_IMAGE_SEGMENT.length()
                && tail.indexOf('/', SALON_IMAGE_SEGMENT.length()) < 0;
    }

    /**
     * True for exactly {@code /api/v1/services/{serviceDefId}/photo} — one non-empty {@code {serviceDefId}}
     * segment followed by the literal {@code photo} segment, tolerating one trailing {@code /} (Phase 342).
     * The input is the already decoded/normalized match path (see {@link #resolveMatchPath}), so
     * {@code %2F}, {@code ;matrix} and {@code ./} spellings arrive here in canonical form.
     */
    static boolean isServicePhotoPath(String path) {
        if (path == null || !path.startsWith(SERVICE_DEF_WRITE_PATH_PREFIX)) {
            return false;
        }
        String rest = path.substring(SERVICE_DEF_WRITE_PATH_PREFIX.length());
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        int idEnd = rest.indexOf('/');
        return idEnd > 0 && rest.substring(idEnd).equals(SERVICE_PHOTO_SEGMENT);
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
        // Phase 343: the salon logo/cover routes (/api/v1/salons/{salonId}/media/{slot}) share the same
        // bucket — one R2 upload per call, same abuse profile as the avatar.
        // Phase 342: the service photo routes (/api/v1/services/{serviceDefId}/photo) join it too —
        // POST uploads up to 6 MB to R2 and DELETE removes the R2 object, mirroring avatar POST/DELETE.
        // Matched here, before the service-write PATCH/DELETE prefix branch, so DELETE .../photo is
        // charged to the media bucket rather than serviceWriteBuckets.
        if ((HttpMethod.POST.matches(method) || HttpMethod.DELETE.matches(method))
                && (path.startsWith(MEDIA_PATH_PREFIX) || isSalonImagePath(path)
                        || isServicePhotoPath(path))) {
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

        // Guest-availability read rate-limit: GET /api/v1/book/{slug}/availability — matched by
        // prefix + suffix (the {slug} is one path segment), checked before the POST-only guard so this
        // GET is covered. The /availability suffix cannot collide with the sibling public reads under
        // /book: /{slug}/info, /cancel/{token} (GET cancel-info) and the OTP/booking/cancel POSTs all
        // end in something else, so ONLY the availability GET consumes this bucket.
        //
        // Until this branch the endpoint fell through to the unmatched-GET path with no throttle at all —
        // the LINK counterpart of the slots branch immediately above, driving the same
        // SlotCalculationService oracle at zero auth cost. Cap: 60 / 60 s per IP, deliberately the same
        // budget as slotsBuckets (see GUEST_AVAILABILITY_CAPACITY for the sizing and for why it does not
        // throttle a guest tapping through dates).
        if (HttpMethod.GET.matches(method)
                && path.startsWith(GUEST_BOOKING_PATH_PREFIX)
                && path.endsWith(GUEST_AVAILABILITY_PATH_SUFFIX)) {
            applyRateLimit(request, response, filterChain, guestAvailabilityBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Search-suggestions rate-limit: GET /api/v1/search/suggestions (Phase 331) — checked
        // BEFORE the SEARCH_PATH_PREFIX branch below on purpose. That branch matches by prefix on
        // "/api/v1/search/", which this exact path also starts with; if this check ran after it,
        // the prefix branch would already have returned and this one would NEVER run, silently
        // spending the results-search (searchBuckets) budget instead of its own. Cap: 240 / 60 s
        // per IP, its own bucket (searchSuggestionBuckets) — see SEARCH_SUGGESTIONS_CAPACITY for
        // why it is a separate budget from searchBuckets.
        // FALSIFY: move this branch after the SEARCH_PATH_PREFIX branch below and
        // SearchSuggestionsGetRateLimitRegressionTest's carve-out test must go red.
        if (HttpMethod.GET.matches(method)
                && path.equals(SEARCH_SUGGESTIONS_PATH)) {
            applyRateLimit(request, response, filterChain, searchSuggestionBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Search rate-limit: GET /api/v1/search/** (discovery of masters + salons) — checked
        // before the POST-only guard so these GET reads are covered. These permitAll() paths
        // expose authed-only independent-master street addresses, so the throttle is the
        // IP-layer ceiling on sustained scraping and DB amplification. Cap: 240 / 60 s per IP
        // (see SEARCH_CAPACITY for why 40 was too low). This is the ONLY bucket for RESULT reads
        // — do not add a second one for /search/masters or /search/salons, which still share it
        // by design. GET /api/v1/search/suggestions is deliberately carved OUT of this prefix by
        // the branch above: it is typing traffic, not a result-page read, and must not compete
        // with it for the same 240-token budget (Phase 331).
        if (HttpMethod.GET.matches(method)
                && path.startsWith(SEARCH_PATH_PREFIX)) {
            applyRateLimit(request, response, filterChain, searchBuckets, RETRY_AFTER_SECONDS,
                    searchTokenCost(request));
            return;
        }

        // Settlement-autocomplete rate-limit: GET /api/v1/settlements (Phase 326) — checked
        // before the POST-only guard so this GET read is covered at all. permitAll, reached
        // during registration, and unlike the locality cascade its response depends on caller
        // input, so request volume reaches the database instead of a static cache. Cap:
        // 240 / 60 s per IP, one token per request (see SETTLEMENT_SEARCH_CAPACITY for why it
        // matches /search/**'s cap and why it is nevertheless a SEPARATE bucket).
        if (HttpMethod.GET.matches(method)
                && path.equals(SETTLEMENT_SEARCH_PATH)) {
            applyRateLimit(request, response, filterChain, settlementSearchBuckets,
                    RETRY_AFTER_SECONDS);
            return;
        }

        // Catalogue-browse rate-limit: GET /api/v1/salons/{salonId}/services AND
        // GET /api/v1/masters/{masterId}/services — checked before the POST-only guard so these
        // GET reads are covered. Phase 314 audit finding (MEDIUM): both are permitAll() and were
        // previously unthrottled anywhere in this filter, letting a caller sweeping distinct
        // salon/master ids force a cache miss plus a full per-master N+1 read on every request.
        // Cap: 60 / 60 s per IP (catalogueBrowseBuckets) — see RateLimitConfig#catalogueBrowseCapacity
        // for the sizing.
        //
        // The salon half is matched via isSalonCatalogueServicesPath rather than a bare
        // prefix+suffix check, because the THIRD route living at this prefix+suffix —
        // GET /api/v1/salons/{salonId}/masters/{masterId}/services (Phase 309/310's salon-management
        // read, TWO path variables not one) — is matched by its own named helper below. The master
        // half needs no such helper: no other route under MASTER_AVAILABILITY_PATH_PREFIX ends in
        // "/services".
        //
        // 2026-09-13 audit (P5/S3): the management read
        // GET /api/v1/salons/{salonId}/masters/{masterId}/services is no longer the "accepted
        // risk" exception it was documented as on RateLimitConfig#serviceWriteCapacity — but it is
        // NOT throttled here. Cycle 1 routed it into catalogueBrowseBuckets, an ANONYMOUS per-IP
        // bucket; under carrier-grade NAT (the norm on Ukrainian mobile networks) the aggregate
        // anonymous browse traffic leaving one egress IP would then 429 a salon owner's management
        // UI (cycle-2 audit, B8). It is an AUTHENTICATED route, so it belongs on a per-PRINCIPAL
        // bucket, and this filter runs BEFORE JwtAuthenticationFilter — the principal does not
        // exist yet here. It is therefore throttled by BookingRateLimitFilter, which runs AFTER
        // the JWT filter and is the app's only per-authenticated-user Bucket4j mechanism (the same
        // reason DELETE /api/v1/users/me lives there), against its own salonMasterServicesRead
        // bucket at the same 60/min capacity.
        //
        // Audit 2026-10-05 (finding 2) widened the bucket to the public PROFILE reads
        // (PUBLIC_PROFILE_READ_PATH): GET /masters/{id}, GET /salons/{id}, GET /salons/{id}/masters
        // and GET /masters/by-salon/{id} are permitAll, so the bucket caps id-sweeps against them;
        // the two roster reads also run an UNCACHED bookability EXISTS gate on every request
        // (MasterRepository#findBookableIdsBySalonId), which a sweep would otherwise drive. Same
        // per-IP capacity (app.rate-limit.catalogue-browse-capacity), deliberately ONE bucket: a
        // profile visit spends ~3 tokens (detail + services + roster), so a crawler is capped at
        // ~20 profiles/min per IP while a human never notices.
        //
        // B8 regression fix (2026-10-05): GET /salons/{id} is ALSO the salon owner/admin management
        // screen's read (mobile salonManagementProfileProvider), and GET /salons/{id} +
        // GET /masters/{id}/services back the master/owner own-profile screens — all with a token.
        // A request carrying a Bearer token is therefore NOT charged here: it is deferred to
        // BookingRateLimitFilter (post-JWT), which charges the PRINCIPAL's
        // catalogueBrowsePrincipalBuckets when the token authenticated and falls back to THIS
        // per-IP bucket (key handed over via CATALOGUE_BROWSE_DEFERRED_IP_KEY_ATTRIBUTE) when it
        // did not — so a forged bearer cannot be used to skip throttling. Anonymous callers are
        // charged here exactly as before.
        if (HttpMethod.GET.matches(method)
                && (isSalonCatalogueServicesPath(path)
                        || (path.startsWith(MASTER_AVAILABILITY_PATH_PREFIX)
                                && path.endsWith(MASTER_SERVICES_PATH_SUFFIX))
                        || isPublicProfileReadPath(path))) {
            if (BearerTokenExtractor.extract(request) != null) {
                // NOT charged on any bucket here — not even the authenticated per-IP ceiling. This
                // filter runs before JWT validation, so charging the ceiling here let a junk
                // "Bearer x" flood from a shared CGNAT IP drain it and 429 every real signed-in user
                // behind that IP (re-audit 2026-10-05 follow-up, LOW). BookingRateLimitFilter charges
                // the ceiling post-JWT, for authenticated principals only.
                request.setAttribute(CATALOGUE_BROWSE_DEFERRED_IP_KEY_ATTRIBUTE, resolveBucketKey(request));
                filterChain.doFilter(request, response);
                return;
            }
            applyRateLimit(request, response, filterChain, catalogueBrowseBuckets, RETRY_AFTER_SECONDS);
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

        // Single-service-CREATE rate-limit: POST on any of the three non-bulk create routes —
        // /api/v1/independent-masters/me/services (exact) and the two salon-side routes matched by
        // prefix + the literal "/services" suffix. Placed AFTER the bulk branch above so ordering
        // is self-evidently safe, though the two rules are disjoint anyway (bulk ends in
        // "/services/bulk", never "/services"). Cap: 60 / 60 s per IP (serviceWriteBuckets).
        //
        // Why this branch exists: without it these creates fell through to the unmatched else
        // branch with NO throttle, so an attacker chasing service_definitions row growth just
        // looped single-create instead of the rate-limited bulk endpoint — the strictly better
        // lever, and one that skips the per-master advisory lock as well.
        if (HttpMethod.POST.matches(method)
                && (IM_SINGLE_SERVICE_PATH.equals(path)
                        || (path.startsWith(SALON_SINGLE_SERVICE_PREFIX)
                                && path.endsWith(SALON_SINGLE_SERVICE_SUFFIX)))) {
            applyRateLimit(request, response, filterChain, serviceWriteBuckets, RETRY_AFTER_SECONDS);
            return;
        }

        // Service-definition MUTATE rate-limit: PATCH /api/v1/services/{serviceDefId} and
        // DELETE /api/v1/services/{serviceDefId} — matched by prefix, which covers both and cannot
        // reach the sibling /api/v1/service-categories/** or /api/v1/service-types/** namespaces.
        // DELETE .../photo never reaches here: the media branch above claims it. Checked before the
        // POST-only guard below so these PATCH/DELETE routes are covered; without this branch they
        // fell through to it entirely unthrottled, the same gap as the creates above. They share
        // ONE bucket with the creates by design — same class of single-item catalogue write.
        if ((HttpMethod.PATCH.matches(method) || HttpMethod.DELETE.matches(method))
                && path.startsWith(SERVICE_DEF_WRITE_PATH_PREFIX)) {
            applyRateLimit(request, response, filterChain, serviceWriteBuckets, RETRY_AFTER_SECONDS);
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

        // Invite-validate rate-limit: GET /api/v1/auth/invite/validate — a single literal
        // equality check on the exact path, checked before the unconditional non-POST bypass
        // immediately below. Before this branch the endpoint fell straight through that bypass
        // with NO throttle at all (backlog LOW finding). This does NOT widen the bypass itself —
        // every other GET in the app still falls through unmatched, exactly as before; this rule
        // can only ever match the one literal path. See INVITE_VALIDATE_CAPACITY for sizing.
        if (HttpMethod.GET.matches(method) && INVITE_VALIDATE_PATH.equals(path)) {
            applyRateLimit(request, response, filterChain, inviteValidateBuckets, RETRY_AFTER_SECONDS);
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
        } else if (INVITE_ACCEPT_PATH.equals(path)) {
            cache = inviteAcceptBuckets;
            retryAfterSeconds = INVITE_ACCEPT_RETRY_AFTER_SECONDS;
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

    /**
     * True for the four public profile reads ({@link #PUBLIC_PROFILE_READ_PATH}) when — and only
     * when — the {@code {id}} segment would bind to a {@code UUID} path variable in Spring MVC.
     *
     * <p>{@code path} is already percent-decoded by {@link #resolveMatchPath} (via
     * {@link UrlPathHelper}, as Spring's router decodes path variables), so {@code %20} arrives
     * here as a literal space. Spring's {@code StringToUUIDConverter} binds
     * {@code UUID.fromString(source.trim())} after a {@code hasText} guard, and
     * {@code UUID.fromString} accepts non-canonical short spellings — so this applies that exact
     * parse instead of a canonical-only regex that a padded or short spelling slipped past. The
     * literal siblings ({@code me}, {@code mine}, {@code by-salon}) never parse, so they stay out.
     */
    static boolean isPublicProfileReadPath(String path) {
        Matcher matcher = PUBLIC_PROFILE_READ_PATH.matcher(path);
        if (!matcher.matches()) {
            return false;
        }
        String segment = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return isUuidPathVariable(segment);
    }

    private static boolean isUuidPathVariable(String segment) {
        if (!StringUtils.hasText(segment)) {
            return false;
        }
        try {
            UUID.fromString(segment.trim());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * True only for {@code /api/v1/salons/{salonId}/services} — the public catalogue-browse GET —
     * never for {@code /api/v1/salons/{salonId}/masters/{masterId}/services} (Phase 309's
     * authenticated salon-management read), even though both share the literal
     * {@link #SALON_SINGLE_SERVICE_PREFIX} prefix and {@link #SALON_SINGLE_SERVICE_SUFFIX} suffix.
     * The two are told apart by the segment BETWEEN prefix and suffix: for the catalogue route it
     * is a bare {@code {salonId}} (no further "/"); for the management route it is
     * {@code {salonId}/masters/{masterId}} (contains "/"). Callers must still check
     * {@code startsWith}/{@code endsWith} themselves — this method assumes both already hold.
     */
    private static boolean isSalonCatalogueServicesPath(String path) {
        if (!path.startsWith(SALON_SINGLE_SERVICE_PREFIX) || !path.endsWith(SALON_SINGLE_SERVICE_SUFFIX)) {
            return false;
        }
        String middle = path.substring(
                SALON_SINGLE_SERVICE_PREFIX.length(),
                path.length() - SALON_SINGLE_SERVICE_SUFFIX.length());
        return !middle.isEmpty() && middle.indexOf('/') < 0;
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
        Bucket bucket = cache.get(resolveBucketKey(request));

        if (bucket.tryConsume(tokens)) {
            filterChain.doFilter(request, response);
        } else {
            writeTooManyRequests(response, retryAfterSeconds);
        }
    }

    private static void writeTooManyRequests(HttpServletResponse response, int retryAfterSeconds)
            throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentLength(TOO_MANY_REQUESTS_BODY.length);
        response.getOutputStream().write(TOO_MANY_REQUESTS_BODY);
    }

    /**
     * The per-IP bucket key: the resolved client IP, clamped to the max IPv6 length (45 chars) to
     * prevent oversized Caffeine cache keys crafted via a long X-Forwarded-For header value. Also
     * the key handed to BookingRateLimitFilter via {@link #CATALOGUE_BROWSE_DEFERRED_IP_KEY_ATTRIBUTE},
     * so both filters charge the identical per-IP bucket entry.
     */
    private String resolveBucketKey(HttpServletRequest request) {
        String ip = resolveClientIp(request);
        return ip.length() > 45 ? request.getRemoteAddr() : ip;
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
