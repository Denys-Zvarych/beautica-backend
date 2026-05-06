package com.beautica.salon.controller;

import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.InviteRequest;
import com.beautica.salon.dto.PublicSalonResponse;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.service.SalonService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        UUID ownerId = extractUserId(authentication);
        SalonResponse response = salonService.createSalon(ownerId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @GetMapping("/{salonId}")
    public ApiResponse<PublicSalonResponse> getSalon(@PathVariable UUID salonId) {
        return ApiResponse.ok(PublicSalonResponse.from(salonService.getSalonEntity(salonId)));
    }

    @PatchMapping("/{salonId}")
    @PreAuthorize("@authz.canManageSalon(authentication, #salonId)")
    public ApiResponse<SalonResponse> updateSalon(
            @PathVariable UUID salonId,
            @Valid @RequestBody UpdateSalonRequest request,
            Authentication authentication
    ) {
        UUID ownerId = extractUserId(authentication);
        return ApiResponse.ok(salonService.updateSalon(ownerId, salonId, request));
    }

    @PostMapping("/{salonId}/invite")
    @PreAuthorize("@authz.canManageSalon(authentication, #salonId)")
    public ResponseEntity<ApiResponse<InviteResponse>> inviteMaster(
            @PathVariable UUID salonId,
            @Valid @RequestBody InviteRequest request,
            Authentication authentication
    ) {
        UUID ownerId = extractUserId(authentication);
        InviteResponse response = salonService.inviteMaster(ownerId, salonId, request.email(), request.effectiveRole());
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @GetMapping("/{salonId}/masters")
    public ApiResponse<PageResponse<MasterSummaryResponse>> getMastersBySalon(
            @PathVariable UUID salonId,
            Pageable pageable
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
        UUID ownerId = extractUserId(authentication);
        salonService.deactivateSalon(ownerId, salonId);
        return ResponseEntity.noContent().build();
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken token) {
            return (UUID) token.getDetails();
        }
        throw new com.beautica.common.exception.ForbiddenException("Not authenticated");
    }
}
