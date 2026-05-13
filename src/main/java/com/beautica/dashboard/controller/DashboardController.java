package com.beautica.dashboard.controller;

import com.beautica.auth.Role;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.dashboard.dto.RevenueResponse;
import com.beautica.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Revenue dashboard endpoint — read-only analytics for salon owners and independent masters.
 *
 * <p><b>Authz:</b> class-level {@code @PreAuthorize} gates the whole controller to
 * {@code SALON_OWNER} and {@code INDEPENDENT_MASTER}. Ownership scoping (which salon or master
 * the actor may see) is enforced inside {@link DashboardService#getRevenueSummary} — the
 * controller must not duplicate that logic.
 *
 * <p><b>Principal extraction:</b> follows Anti-Bug Playbook § B — the UUID actor ID is read from
 * {@link UsernamePasswordAuthenticationToken#getDetails()} via an {@code instanceof} guard so
 * a malformed authentication object surfaces as 403, not a {@link ClassCastException} 500.
 *
 * <p><b>filterMasterId IDOR:</b> the service validates that {@code filterMasterId} belongs to the
 * actor's salon; the controller intentionally passes it through without extra checks.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyRole('SALON_OWNER', 'INDEPENDENT_MASTER')")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * Returns an aggregated revenue summary for the authenticated actor.
     *
     * <p>When {@code from} and {@code to} are omitted the service defaults to the last 30 days
     * in the Europe/Kyiv timezone. The date range must not exceed 365 days (validated in the
     * service; violation returns HTTP 400).
     *
     * @param from           optional range start (inclusive); ISO-8601 date, e.g. {@code 2024-01-01}
     * @param to             optional range end (inclusive); ISO-8601 date
     * @param filterMasterId optional master UUID to narrow results (SALON_OWNER only; service
     *                       enforces that the master belongs to the actor's salon)
     * @param serviceDefId   optional service-definition UUID to further narrow results
     */
    @GetMapping("/revenue")
    public ApiResponse<RevenueResponse> getRevenueSummary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @RequestParam(required = false) UUID filterMasterId,
            @RequestParam(required = false) UUID serviceDefId,

            Authentication authentication
    ) {
        UUID actorId = extractUserId(authentication);
        Role actorRole = extractRole(authentication);

        RevenueResponse result = dashboardService.getRevenueSummary(
                actorId, actorRole, from, to, filterMasterId, serviceDefId);

        return ApiResponse.ok(result);
    }

    // ── auth helpers (§ B — instanceof UUID id pattern) ───────────────────────

    /**
     * Resolves the authenticated user's UUID from the token details.
     *
     * <p>Uses the {@code instanceof UUID id} pattern (Anti-Bug Playbook § B): a type mismatch
     * throws {@link ForbiddenException} (→ 403) rather than a raw {@link ClassCastException}
     * (→ 500).
     */
    private UUID extractUserId(Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof UUID id) {
            return id;
        }
        throw new ForbiddenException("Not authenticated");
    }

    /**
     * Resolves the authenticated user's {@link Role} from the granted authorities.
     *
     * <p>Uses the {@code instanceof UsernamePasswordAuthenticationToken} guard (Anti-Bug
     * Playbook § B), mirroring {@link #extractUserId}: a non-{@code UsernamePasswordAuthentication}
     * principal (e.g. {@code AnonymousAuthenticationToken} if the security config ever changes)
     * surfaces as 403 instead of a potential NPE on {@code getAuthorities()}.
     *
     * <p>Each principal carries exactly one {@code ROLE_*} authority. The first matching entry is
     * translated; unrecognised authority strings are skipped rather than thrown — the loop
     * exhausts and then throws {@link ForbiddenException} with "No role assigned".
     */
    private Role extractRole(Authentication authentication) {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken token)) {
            throw new ForbiddenException("Not authenticated");
        }
        for (GrantedAuthority authority : token.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                try {
                    return Role.valueOf(name.substring("ROLE_".length()));
                } catch (IllegalArgumentException ignored) {
                    // fall through — another authority might be a valid role
                }
            }
        }
        throw new ForbiddenException("No role assigned");
    }
}
