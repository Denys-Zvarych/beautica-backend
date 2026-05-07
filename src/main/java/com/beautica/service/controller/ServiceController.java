package com.beautica.service.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.service.ServiceCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceCatalogService serviceCatalogService;

    @PostMapping("/salons/{salonId}/services")
    @PreAuthorize("hasRole('SALON_OWNER') and @authz.canManageSalon(authentication, #salonId)")
    public ResponseEntity<ApiResponse<ServiceDefinitionResponse>> addServiceToSalon(
            @PathVariable UUID salonId,
            @Valid @RequestBody CreateServiceDefinitionRequest request
    ) {
        ServiceDefinitionResponse response = serviceCatalogService.addServiceToSalon(salonId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @PostMapping("/salons/{salonId}/masters/{masterId}/services")
    @PreAuthorize("hasRole('SALON_OWNER') and @authz.canManageSalon(authentication, #salonId) and @authz.masterBelongsToSalon(#masterId, #salonId)")
    public ResponseEntity<ApiResponse<MasterServiceResponse>> assignServiceToMaster(
            @PathVariable UUID salonId,
            @PathVariable UUID masterId,
            @Valid @RequestBody AssignServiceToMasterRequest request
    ) {
        MasterServiceResponse response =
                serviceCatalogService.assignServiceToMaster(salonId, masterId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @GetMapping("/masters/{masterId}/services")
    public ApiResponse<List<MasterServiceResponse>> getMasterServices(@PathVariable UUID masterId) {
        return ApiResponse.ok(serviceCatalogService.getMasterServices(masterId));
    }

    @PostMapping("/independent-masters/me/services")
    @PreAuthorize("hasRole('INDEPENDENT_MASTER')")
    public ResponseEntity<ApiResponse<MasterServiceResponse>> addIndependentMasterService(
            @Valid @RequestBody CreateServiceDefinitionRequest request,
            Authentication authentication
    ) {
        UUID userId = extractUserId(authentication);
        MasterServiceResponse response = serviceCatalogService.addIndependentMasterService(userId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @DeleteMapping("/services/{serviceDefId}")
    @PreAuthorize("@authz.canManageServiceDefinition(authentication, #serviceDefId)")
    public ResponseEntity<Void> deactivateServiceDefinition(
            @PathVariable UUID serviceDefId,
            Authentication authentication
    ) {
        UUID ownerId = extractUserId(authentication);
        serviceCatalogService.deactivateServiceDefinition(ownerId, serviceDefId);
        return ResponseEntity.noContent().build();
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof UUID userId) {
            return userId;
        }
        throw new ForbiddenException("Not authenticated");
    }
}
