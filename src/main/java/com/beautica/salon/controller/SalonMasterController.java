package com.beautica.salon.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.service.MasterService;
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
 * Exposes the owner-as-master toggle endpoints for Phase 12.4.
 *
 * <p>Route: {@code /api/v1/salons/{salonId}/master}
 *
 * <ul>
 *   <li>{@code POST} — re-enable (or create) the owner's SALON_OWNER master profile.
 *       Idempotent: returns 200 with the current {@link MasterDetailResponse} whether the
 *       row was newly created or was simply reactivated.</li>
 *   <li>{@code DELETE} — soft-delete the owner's master profile. Returns 204 No Content.
 *       Never hard-deletes.</li>
 * </ul>
 *
 * <p>Both endpoints are guarded by
 * {@code hasRole('SALON_OWNER') and @authz.canManageSalon(authentication, #salonId)}.
 * SALON_ADMIN is intentionally excluded — only the owner can toggle their own master
 * profile.
 */
@RestController
@RequestMapping("/api/v1/salons")
@RequiredArgsConstructor
public class SalonMasterController {

    private final MasterService masterService;

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
}
