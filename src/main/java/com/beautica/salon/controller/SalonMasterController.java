package com.beautica.salon.controller;

import com.beautica.common.security.AuthenticationUtils;
import com.beautica.salon.service.SalonService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Salon staff endpoints. The Phase 12.4 owner-as-master toggle
 * ({@code POST/DELETE /api/v1/salons/{salonId}/master}) was removed in Phase 346: the
 * owner-master row is permanent, created with the salon and never toggled.
 *
 * <p>Route: {@code /api/v1/salons/{salonId}/masters/{masterId}} (single-master removal; see
 * {@link #removeMaster}'s javadoc):
 *
 * <ul>
 *   <li>{@code DELETE} — hard-deletes ONE invited master's account, exactly the way a
 *       whole-salon deletion disposes of its staff. Returns 204 No Content.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/salons")
@RequiredArgsConstructor
public class SalonMasterController {

    private final SalonService salonService;

    /**
     * Removes ONE invited master from {@code salonId} (Phase 297) — hard-deletes the master's
     * account and deletes-or-detaches the {@code masters} row behind it via
     * {@code SalonService#removeMaster}, identically to how a whole-salon deletion disposes of
     * its staff. The owner's own {@code SALON_OWNER} row is rejected with 409 — it is permanent.
     *
     * <p><b>{@code hasRole('SALON_OWNER')} — deliberately NOT {@code hasAnyRole('SALON_OWNER',
     * 'SALON_ADMIN')}, unlike {@code removeAdmin} (D5).</b> {@code removeAdmin} may be
     * admin-callable because it only nulls a column; this endpoint hard-deletes a person's
     * account, and matches {@code SalonController.java:209}, which is owner-only. Do NOT "align" this with
     * {@code removeAdmin} to admit SALON_ADMIN — that would silently widen who can hard-delete a
     * teammate's account.
     *
     * <p>{@code masterBelongsToSalon(#masterId, #salonId)} (already used by {@code
     * assignServiceToMaster}) closes the same timing-oracle IDOR {@code adminBelongsToSalon}
     * closes for {@code removeAdmin}: without it, a caller with management access to Salon A
     * could probe arbitrary master UUIDs and distinguish "exists at a different salon" from
     * "does not exist" by 403 vs. 404. Service-layer re-validation still runs inside {@code
     * removeMaster} — the SpEL gate is never trusted alone.
     *
     * @param salonId  salon the caller owns
     * @param masterId the master to remove — must belong to {@code salonId}
     * @return 204 No Content
     */
    @DeleteMapping("/{salonId}/masters/{masterId}")
    @PreAuthorize("hasRole('SALON_OWNER') "
            + "and @authz.canManageSalon(authentication, #salonId) "
            + "and @authz.masterBelongsToSalon(#masterId, #salonId)")
    public ResponseEntity<Void> removeMaster(
            @PathVariable UUID salonId,
            @PathVariable UUID masterId,
            Authentication authentication) {

        UUID actorId = AuthenticationUtils.userId(authentication);
        salonService.removeMaster(actorId, salonId, masterId);
        return ResponseEntity.noContent().build();
    }
}
