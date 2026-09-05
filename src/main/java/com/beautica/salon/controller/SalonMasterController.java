package com.beautica.salon.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.service.MasterService;
import com.beautica.salon.service.SalonService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Exposes the owner-as-master toggle endpoints for Phase 12.4, plus (Phase 297) the
 * single-master-removal endpoint.
 *
 * <p>Route: {@code /api/v1/salons/{salonId}/master} (owner-as-master toggle, two segments):
 *
 * <ul>
 *   <li>{@code POST} — re-enable (or create) the owner's SALON_OWNER master profile.
 *       Idempotent: returns 200 with the current {@link MasterDetailResponse} whether the
 *       row was newly created or was simply reactivated.</li>
 *   <li>{@code DELETE} — soft-delete the owner's master profile. Returns 204 No Content.
 *       Never hard-deletes.</li>
 * </ul>
 *
 * <p>Both are guarded by {@code hasRole('SALON_OWNER') and
 * @authz.canManageSalon(authentication, #salonId)}. SALON_ADMIN is intentionally excluded —
 * only the owner can toggle their own master profile.
 *
 * <p>Route: {@code /api/v1/salons/{salonId}/masters/{masterId}} (single-master removal,
 * three segments — a distinct route, not a variant of the one above; see
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

    private final MasterService masterService;
    private final SalonService salonService;

    /**
     * Re-enables (or initially creates) the caller's {@code SALON_OWNER} master profile
     * inside the given salon.
     *
     * @param salonId salon the caller owns
     * @param authentication JWT-backed security context; actor UUID is stored in {@code details}
     * @return 200 with the active {@link MasterDetailResponse}
     */
    @PostMapping("/{salonId}/master")
    @PreAuthorize("hasRole('SALON_OWNER') and @authz.canManageSalon(authentication, #salonId)")
    public ApiResponse<MasterDetailResponse> enableOwnerMaster(
            @PathVariable UUID salonId,
            Authentication authentication) {

        UUID actorId = AuthenticationUtils.userId(authentication);
        Master master = masterService.createMasterForOwner(actorId, salonId);
        // MEDIUM-2: use entity overload to avoid a redundant findByIdWithUserAndSalon
        // graph-fetch — the master entity is already in the Hibernate first-level cache.
        return ApiResponse.ok(masterService.getMasterDetail(master));
    }

    /**
     * Soft-deletes the caller's {@code SALON_OWNER} master profile inside the given salon.
     *
     * @param salonId salon the caller owns
     * @param authentication JWT-backed security context; actor UUID is stored in {@code details}
     * @return 204 No Content
     */
    @DeleteMapping("/{salonId}/master")
    @PreAuthorize("hasRole('SALON_OWNER') and @authz.canManageSalon(authentication, #salonId)")
    public ResponseEntity<Void> disableOwnerMaster(
            @PathVariable UUID salonId,
            Authentication authentication) {

        UUID actorId = AuthenticationUtils.userId(authentication);
        masterService.deactivateOwnerMaster(actorId, salonId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Removes ONE invited master from {@code salonId} (Phase 297) — hard-deletes the master's
     * account and deletes-or-detaches the {@code masters} row behind it via
     * {@code SalonService#removeMaster}, identically to how a whole-salon deletion disposes of
     * its staff. Three path segments ({@code /{salonId}/masters/{masterId}}) vs. the two-segment
     * {@code /{salonId}/master} above — no routing collision, and NOT the same operation: this
     * endpoint targets one arbitrary master by id, the one above only ever toggles the OWNER's
     * own master profile.
     *
     * <p><b>{@code hasRole('SALON_OWNER')} — deliberately NOT {@code hasAnyRole('SALON_OWNER',
     * 'SALON_ADMIN')}, unlike {@code removeAdmin} (D5).</b> {@code removeAdmin} may be
     * admin-callable because it only nulls a column; this endpoint hard-deletes a person's
     * account, and matches this controller's other two endpoints plus {@code
     * SalonController.java:209}, which are all owner-only. Do NOT "align" this with
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
