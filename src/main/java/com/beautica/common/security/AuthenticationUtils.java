package com.beautica.common.security;

import com.beautica.auth.Role;
import com.beautica.common.exception.ForbiddenException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Shared extraction of the authenticated principal's identity and role from the
 * Spring Security {@link Authentication}.
 *
 * <p>The {@link JwtAuthenticationFilter} stores the user id as the token
 * {@code details} (a {@link UUID}) on a {@link UsernamePasswordAuthenticationToken}
 * and the role as a single {@code ROLE_*} granted authority. These helpers are the
 * single source of truth for reading that contract — previously each controller
 * carried a copy-pasted private {@code extractUserId}/{@code principalId}/{@code extractRole}
 * which drifted in exception message and role-resolution strictness (B14 divergence risk).
 *
 * <p>Both methods fail closed with {@link ForbiddenException} (HTTP 403) rather than a
 * raw cast — a non-{@code UsernamePasswordAuthenticationToken} principal or a missing/
 * malformed {@code details} surfaces as 403, never a 500 {@code ClassCastException}
 * (Anti-Bug Playbook § B).
 */
public final class AuthenticationUtils {

    /**
     * Privilege precedence used to resolve a single {@link Role} when an
     * {@link Authentication} unexpectedly carries more than one {@code ROLE_*}
     * authority. Most-privileged first so a multi-role principal is never silently
     * down-graded. In this project every principal carries exactly one role, so the
     * precedence is purely defensive — it makes resolution deterministic instead of
     * dependent on {@code getAuthorities()} iteration order.
     */
    private static final List<Role> ROLE_PRECEDENCE = List.of(
            Role.SALON_OWNER,
            Role.SALON_ADMIN,
            Role.INDEPENDENT_MASTER,
            Role.SALON_MASTER,
            Role.CLIENT);

    private AuthenticationUtils() {
    }

    /**
     * Extracts the authenticated user's id from the token details.
     *
     * @throws ForbiddenException if the authentication is absent, not a
     *                            {@link UsernamePasswordAuthenticationToken}, or carries
     *                            non-{@link UUID} details.
     */
    public static UUID userId(Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof UUID id) {
            return id;
        }
        throw new ForbiddenException("Not authenticated");
    }

    /**
     * Nullable counterpart to {@link #userId(Authentication)} for the rare caller that must
     * treat "no principal" as a legitimate, non-exceptional case rather than a 403 — e.g. a
     * {@code permitAll} route that behaves differently for an authenticated CLIENT than for an
     * anonymous guest, but must not fail the guest's request (Phase 32.1 D4).
     *
     * <p>Returns {@code null} for a missing {@link Authentication}, a non-
     * {@link UsernamePasswordAuthenticationToken} principal (e.g. Spring Security's anonymous
     * token), or non-{@link UUID} {@code details} — the same contract as {@link #userId} minus
     * the throw. Callers that require an authenticated principal must keep using {@link #userId}.
     */
    public static UUID userIdOrNull(Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof UUID id) {
            return id;
        }
        return null;
    }

    /**
     * Resolves the authenticated user's {@link Role} from its granted authorities
     * (e.g. {@code ROLE_SALON_OWNER} → {@link Role#SALON_OWNER}).
     *
     * <p>Resolution fails closed: all authorities are scanned and unrecognised
     * {@code ROLE_*} strings are ignored. Every principal carries exactly one role, so
     * if more than one valid role survives the scan the state is unexpected and is
     * rejected rather than resolved — silently picking the most-privileged role would
     * over-grant scope (e.g. {@code {CLIENT, SALON_OWNER}} → owner-wide access). The
     * {@link #ROLE_PRECEDENCE} pick therefore only ever returns the single matched role.
     *
     * @throws ForbiddenException if the authentication is absent, not a
     *                            {@link UsernamePasswordAuthenticationToken}, carries
     *                            no recognised role authority, or carries more than one
     *                            recognised role.
     */
    public static Role role(Authentication authentication) {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken token)) {
            throw new ForbiddenException("Not authenticated");
        }
        EnumSet<Role> matched = EnumSet.noneOf(Role.class);
        for (GrantedAuthority authority : token.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                try {
                    matched.add(Role.valueOf(name.substring("ROLE_".length())));
                } catch (IllegalArgumentException ignored) {
                    // Not a known Role — skip; another authority might be valid.
                }
            }
        }
        if (matched.isEmpty()) {
            throw new ForbiddenException("No role assigned");
        }
        if (matched.size() > 1) {
            // Fail closed: every principal carries exactly one role today. A multi-role
            // principal is an unexpected state — resolving by precedence would silently
            // over-grant scope (e.g. {CLIENT, SALON_OWNER} → owner-wide access), so reject.
            throw new ForbiddenException("Ambiguous role");
        }
        return ROLE_PRECEDENCE.stream()
                .filter(matched::contains)
                .findFirst()
                .orElseThrow(() -> new ForbiddenException("No role assigned"));
    }
}
