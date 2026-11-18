package com.beautica.user.phone;

import com.beautica.auth.Role;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.IdentityException;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.config.AuthConsentProperties;
import com.beautica.user.PhoneGateRow;
import com.beautica.user.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * B516 (AUTH-45, D2) — the server-side defence behind the app's phone prompt, active only with
 * {@code APP_AUTH_REQUIRE_VERIFIED_PHONE=true}:
 * <ul>
 *   <li>a CLIENT without a verified phone may not create an APP booking ({@link RequireVerifiedPhone} handlers);</li>
 *   <li>a provider without a verified phone may not call a write endpoint (POST / PUT / PATCH / DELETE) once
 *       {@code APP_PHONE_PROVIDER_DEADLINE} has passed — reads stay open, and so do the account self-service routes
 *       needed to fix the situation ({@link #PROVIDER_EXEMPT_PREFIXES}).</li>
 * </ul>
 * Cheap first: flag off → the interceptor never calls {@link #check} ({@link #isActive}); with the flag on, the role
 * comes from the token's authority and the deadline from config, so the one DB read (a column projection, no
 * transaction of its own) happens only for a CLIENT booking create or a provider write past the deadline on a
 * non-exempt path. The DB row then decides (a role changed since the token was minted is honoured).
 */
@Service
public class VerifiedPhoneGate {

    /**
     * Provider writes that stay open past the deadline: auth (logout, refresh, password), the user's own account
     * (add the phone, email, profile, delete), push-token registration and the support form.
     */
    static final Set<String> PROVIDER_EXEMPT_PREFIXES = Set.of(
            "/api/v1/auth/", "/api/v1/users/me", "/api/v1/devices/", "/api/v1/support/");

    private final UserRepository userRepository;
    private final PhoneRequirementPolicy policy;
    private final AuthConsentProperties properties;

    public VerifiedPhoneGate(UserRepository userRepository, PhoneRequirementPolicy policy,
                             AuthConsentProperties properties) {
        this.userRepository = userRepository;
        this.policy = policy;
        this.properties = properties;
    }

    /** {@code APP_AUTH_REQUIRE_VERIFIED_PHONE}: when false nothing is ever gated (and nothing is read). */
    public boolean isActive() {
        return properties.isRequireVerifiedPhone();
    }

    /**
     * @param authentication  the caller ({@code null} / anonymous: never gated)
     * @param bookingCreate   the handler is a {@link RequireVerifiedPhone} booking create
     * @param write           the request method is not a safe read
     * @param path            the request path (servlet path, no context)
     * @throws IdentityException 409 {@code PHONE_NOT_VERIFIED}
     */
    public void check(Authentication authentication, boolean bookingCreate, boolean write, String path) {
        UUID userId = AuthenticationUtils.userIdOrNull(authentication);
        if (!isActive() || userId == null || !(bookingCreate || write)) {
            return;
        }
        Role tokenRole = roleOrNull(authentication);
        boolean clientCandidate = bookingCreate && (tokenRole == null || tokenRole == Role.CLIENT);
        boolean providerCandidate = write && (tokenRole == null || tokenRole != Role.CLIENT)
                && !isExempt(path) && policy.providerDeadlinePassed();
        if (!clientCandidate && !providerCandidate) {
            return;
        }
        PhoneGateRow row = userRepository.findPhoneGateRow(userId).orElse(null);
        if (row == null) {
            return;
        }
        boolean clientBlocked = bookingCreate && policy.blocksClientBooking(row.role(), row.phoneVerified());
        boolean providerBlocked = write && !isExempt(path)
                && policy.blocksProviderWrite(row.role(), row.phoneVerified());
        if (clientBlocked || providerBlocked) {
            throw new IdentityException(IdentityException.Code.PHONE_NOT_VERIFIED);
        }
    }

    /** The token's single role, or {@code null} when it carries none / several (then the DB row decides). */
    private static Role roleOrNull(Authentication authentication) {
        try {
            return AuthenticationUtils.role(authentication);
        } catch (ForbiddenException unresolved) {
            return null;
        }
    }

    private static boolean isExempt(String path) {
        return path == null || PROVIDER_EXEMPT_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
