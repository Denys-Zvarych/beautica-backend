package com.beautica.salon.controller;

import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.InviteRequest;
import com.beautica.salon.dto.PublicSalonResponse;
import com.beautica.salon.dto.RotateAdminRequest;
import com.beautica.salon.dto.SalonAdminResponse;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.service.SalonService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/salons")
@RequiredArgsConstructor
public class SalonController {

    private final SalonService salonService;

    @PostMapping
    @PreAuthorize("hasRole('SALON_OWNER')")
    public ResponseEntity<ApiResponse<SalonResponse>> createSalon(
            @Valid @RequestBody CreateSalonRequest request,
            Authentication authentication
    ) {
        UUID ownerId = AuthenticationUtils.userId(authentication);
        SalonResponse response = salonService.createSalon(ownerId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('SALON_OWNER')")
    public ApiResponse<List<SalonResponse>> getOwnedSalons(Authentication authentication) {
        UUID ownerId = AuthenticationUtils.userId(authentication);
        return ApiResponse.ok(salonService.getOwnerSalons(ownerId));
    }

    @GetMapping("/{salonId}")
    public ApiResponse<PublicSalonResponse> getSalon(@PathVariable UUID salonId) {
        return ApiResponse.ok(PublicSalonResponse.from(salonService.getSalonEntity(salonId)));
    }

    @PatchMapping("/{salonId}")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(authentication, #salonId)")
    public ApiResponse<SalonResponse> updateSalon(
            @PathVariable UUID salonId,
            @Valid @RequestBody UpdateSalonRequest request,
            Authentication authentication
    ) {
        UUID ownerId = AuthenticationUtils.userId(authentication);
        return ApiResponse.ok(salonService.updateSalon(ownerId, salonId, request));
    }

    // SALON_ADMIN may invite masters to their own salon — intentional per product decision.
    // canManageSalon enforces salonId == admin's assigned salon_id set at invite time.
    @PostMapping("/{salonId}/invite")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(authentication, #salonId)")
    public ResponseEntity<ApiResponse<InviteResponse>> inviteMaster(
            @PathVariable UUID salonId,
            @Valid @RequestBody InviteRequest request,
            Authentication authentication
    ) {
        UUID ownerId = AuthenticationUtils.userId(authentication);
        InviteResponse response = salonService.inviteMaster(ownerId, salonId, request.email(), request.effectiveRole());
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @GetMapping("/{salonId}/masters")
    public ApiResponse<PageResponse<MasterSummaryResponse>> getMastersBySalon(
            @PathVariable UUID salonId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        var page = salonService.getMastersBySalon(salonId, pageable);
        return ApiResponse.ok(PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        ));
    }

    @DeleteMapping("/{salonId}")
    @PreAuthorize("hasRole('SALON_OWNER') and @authz.canManageSalon(authentication, #salonId)")
    public ResponseEntity<Void> deactivateSalon(
            @PathVariable UUID salonId,
            Authentication authentication
    ) {
        UUID ownerId = AuthenticationUtils.userId(authentication);
        salonService.deactivateSalon(ownerId, salonId);
        return ResponseEntity.noContent().build();
    }

    // SALON_OWNER may remove any admin from a salon they own; SALON_ADMIN may remove another
    // admin from their own salon only. canManageSalon enforces the salon-scoping half of that;
    // adminBelongsToSalon additionally confirms #userId is actually a SALON_ADMIN assigned to
    // #salonId — without it a caller with management access to Salon A could probe arbitrary
    // user UUIDs and distinguish "exists elsewhere" from "not an admin" via 403 vs 404 (IDOR).
    @DeleteMapping("/{salonId}/admins/{userId}")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') "
            + "and @authz.canManageSalon(authentication, #salonId) "
            + "and @authz.adminBelongsToSalon(#userId, #salonId)")
    public ResponseEntity<Void> removeAdmin(
            @PathVariable UUID salonId,
            @PathVariable UUID userId,
            Authentication authentication
    ) {
        UUID actorId = AuthenticationUtils.userId(authentication);
        salonService.removeAdmin(actorId, salonId, userId);
        return ResponseEntity.noContent().build();
    }

    // Phase 21.3 — rotate a SALON_ADMIN from #salonId to another salon owned by the SAME owner.
    // canManageSalon enforces salon-scoping for the source salon (owner-of-any-salon or
    // admin-of-this-salon); adminBelongsToSalon confirms #userId is actually a SALON_ADMIN
    // assigned to #salonId (same IDOR guard as removeAdmin — see its Javadoc). The destination
    // salon's same-owner requirement is NOT expressible in this SpEL gate (it depends on the
    // request body, not a path variable) — SalonService.rotateAdmin enforces it via
    // AuthorizationService.salonsShareOwner before mutating anything.
    @PatchMapping("/{salonId}/admins/{userId}/salon")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') "
            + "and @authz.canManageSalon(authentication, #salonId) "
            + "and @authz.adminBelongsToSalon(#userId, #salonId)")
    public ResponseEntity<ApiResponse<SalonAdminResponse>> rotateAdmin(
            @PathVariable UUID salonId,
            @PathVariable UUID userId,
            @Valid @RequestBody RotateAdminRequest request,
            Authentication authentication
    ) {
        UUID actorId = AuthenticationUtils.userId(authentication);
        SalonAdminResponse response =
                salonService.rotateAdmin(actorId, salonId, userId, request.destinationSalonId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
