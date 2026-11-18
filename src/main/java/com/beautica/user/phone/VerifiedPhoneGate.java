package com.beautica.user.phone;

import com.beautica.auth.Role;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.IdentityException;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.config.AuthConsentProperties;
import com.beautica.user.PhoneGateRow;
import com.beautica.user.UserRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * B516 (AUTH-45, D2) — the server-side defence behind the app's phone prompt, active only with
 * {@code APP_AUTH_REQUIRE_VERIFIED_PHONE=true}:
 * <ul>
 *   <li>a CLIENT without a verified phone may not create an APP booking ({@link RequireVerifiedPhone} handlers);</li>
 *   <li>a provider without a verified phone may not call a write endpoint (POST / PUT / PATCH / DELETE) — there is
 *       no provider deadline (AUTH-D12); reads stay open (the app owns the blocking screen), and so do the exact
 *       writes listed in {@link #PROVIDER_EXEMPT_WRITES}.</li>
 * </ul>
 * Cheap first: flag off → the interceptor never calls {@link #check} ({@link #isActive}); with the flag on, the role
 * comes from the token's authority, so the DB read (a column projection, no transaction of its own) happens only for
 * a CLIENT booking create or a provider write on a non-exempt route — and not even then once the account is known
 * verified ({@link UserProfileCacheEvictor#VERIFIED_PHONE_CACHE}). The DB row then decides (a role changed since the
 * token was minted is honoured).
 */
@Service
public class VerifiedPhoneGate {

    private static final Set<String> SAFE_METHODS = Set.of(
            HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());

    /**
     * The ONLY provider writes open without a verified phone, as exact {@code "METHOD path"} pairs (an allow-list, so
     * a new endpoint is gated by default). AUTH-D12: add the phone ({@code /users/me/phone/otp/*}) and log out; plus
     * the sign-in / registration / password-recovery flows (a stale token sent along must not lock them), invite
     * ACCEPT (never invite create — {@code POST /auth/invite} sends an SMS on the owner's behalf), account deletion
     * and the automatic push-token registration. Not exempt: {@code PATCH /users/me}, {@code /users/me/email/*},
     * {@code /users/me/change-password/*}, {@code /support/contact}. {@code GET /auth/invite/validate} is a read.
     */
    static final Set<String> PROVIDER_EXEMPT_WRITES = Set.of(
            "POST /api/v1/auth/register",
            "POST /api/v1/auth/register/independent-master",
            "POST /api/v1/auth/register/phone/otp/send",
            "POST /api/v1/auth/register/phone/otp/verify",
            "POST /api/v1/auth/verify-email",
            "POST /api/v1/auth/resend-verification",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout",
            "POST /api/v1/auth/forgot-password",
            "POST /api/v1/auth/verify-password-reset-otp",
            "POST /api/v1/auth/reset-password",
            "POST /api/v1/auth/invite/accept",
            "POST /api/v1/users/me/phone/otp/send",
            "POST /api/v1/users/me/phone/otp/verify",
            "DELETE /api/v1/users/me",
            "POST /api/v1/devices/token",
            "DELETE /api/v1/devices/token");

    private final UserRepository userRepository;
    private final PhoneRequirementPolicy policy;
    private final AuthConsentProperties properties;
    private final Cache verifiedPhones;

    public VerifiedPhoneGate(UserRepository userRepository, PhoneRequirementPolicy policy,
                             AuthConsentProperties properties, CacheManager cacheManager) {
        this.userRepository = userRepository;
        this.policy = policy;
        this.properties = properties;
        this.verifiedPhones = Objects.requireNonNull(
                cacheManager.getCache(UserProfileCacheEvictor.VERIFIED_PHONE_CACHE), "verified-phone cache");
    }

    /** {@code APP_AUTH_REQUIRE_VERIFIED_PHONE}: when false nothing is ever gated (and nothing is read). */
    public boolean isActive() {
        return properties.isRequireVerifiedPhone();
    }

    /** @return {@code true} unless the HTTP method is a safe read (GET / HEAD / OPTIONS) */
    static boolean isWrite(String method) {
        return method != null && !SAFE_METHODS.contains(method);
    }

    /**
     * @param authentication  the caller ({@code null} / anonymous: never gated)
     * @param bookingCreate   the handler is a {@link RequireVerifiedPhone} booking create
     * @param method          the request's HTTP method
     * @param path            the request path (servlet path, no context)
     * @throws IdentityException 409 {@code PHONE_NOT_VERIFIED}
     */
    public void check(Authentication authentication, boolean bookingCreate, String method, String path) {
        UUID userId = AuthenticationUtils.userIdOrNull(authentication);
        boolean gatedWrite = isWrite(method) && !isExempt(method, path);
        if (!isActive() || userId == null || !(bookingCreate || gatedWrite)) {
            return;
        }
        Role tokenRole = roleOrNull(authentication);
        boolean clientCandidate = bookingCreate && (tokenRole == null || tokenRole == Role.CLIENT);
        boolean providerCandidate = gatedWrite && (tokenRole == null || tokenRole != Role.CLIENT);
        if ((!clientCandidate && !providerCandidate) || isKnownVerified(userId)) {
            return;
        }
        PhoneGateRow row = userRepository.findPhoneGateRow(userId).orElse(null);
        if (row == null) {
            return;
        }
        if (row.phoneVerified()) {
            verifiedPhones.put(userId, Boolean.TRUE);
            return;
        }
        reject(row, bookingCreate, gatedWrite);
    }

    private void reject(PhoneGateRow row, boolean bookingCreate, boolean gatedWrite) {
        boolean clientBlocked = bookingCreate && policy.blocksClientBooking(row.role(), row.phoneVerified());
        boolean providerBlocked = gatedWrite && policy.blocksProviderWrite(row.role(), row.phoneVerified());
        if (clientBlocked || providerBlocked) {
            throw new IdentityException(IdentityException.Code.PHONE_NOT_VERIFIED);
        }
    }

    /**
     * Only "verified" is ever remembered (never "unverified": the add-phone verify must take effect on the very next
     * request). A verified account is never blocked whatever its role, so a hit skips the row read entirely.
     */
    private boolean isKnownVerified(UUID userId) {
        return verifiedPhones.get(userId) != null;
    }

    /** The token's single role, or {@code null} when it carries none / several (then the DB row decides). */
    private static Role roleOrNull(Authentication authentication) {
        try {
            return AuthenticationUtils.role(authentication);
        } catch (ForbiddenException unresolved) {
            return null;
        }
    }

    private static boolean isExempt(String method, String path) {
        return path == null || PROVIDER_EXEMPT_WRITES.contains(method + " " + path);
    }
}
