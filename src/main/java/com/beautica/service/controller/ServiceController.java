package com.beautica.service.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.dto.UpdateServicePhotoRequest;
import com.beautica.service.service.ServiceCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    // Also authorizes a SALON_OWNER assigning services to their OWN owner-operated
    // master row (master_type = SALON_OWNER): that row's salon_id equals #salonId, so
    // masterBelongsToSalon resolves true. No owner-specific branch is required.
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

    /**
     * Returns the active services offered by the given master.
     *
     * <p><strong>Public endpoint — no authentication required.</strong>
     * Unauthenticated clients browse a master's service menu before deciding to book.
     * No {@code @PreAuthorize} guard is intentional; adding one would break the
     * discovery flow for anonymous users.
     */
    @GetMapping("/masters/{masterId}/services")
    public ApiResponse<List<MasterServiceResponse>> getMasterServices(
            @PathVariable UUID masterId) {
        return ApiResponse.ok(serviceCatalogService.getMasterServices(masterId));
    }

    /**
     * Returns the authenticated master's OWN active services.
     *
     * <p>Owner-scoped: the master is resolved from the authenticated principal's user id,
     * never from a path/query parameter the caller controls — a master can only read their
     * own services. This complements the public {@link #getMasterServices(UUID)} browse,
     * which is keyed off a path parameter and is unchanged.
     *
     * <p>The role gate mirrors the create endpoint below
     * ({@code POST /independent-masters/me/services}); ownership is enforced inside the
     * service via the principal-derived master resolution.
     */
    @Operation(summary = "List my own active services",
            description = "Returns the authenticated master's own active services. "
                    + "Owner-scoped to the authenticated principal; never exposes another "
                    + "master's services.")
    @GetMapping("/independent-masters/me/services")
    @PreAuthorize("hasRole('INDEPENDENT_MASTER')")
    public ApiResponse<List<MasterServiceResponse>> getMyServices(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ApiResponse.ok(serviceCatalogService.getMyServices(userId));
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

    /**
     * First-time bulk service setup for the authenticated INDEPENDENT_MASTER.
     *
     * <p>Self-scoped: the acting master is resolved from the principal, never a path/query
     * parameter — mirroring {@link #addIndependentMasterService}. Valid only when the master
     * currently has ZERO active services; otherwise the service returns 409 (first-time-only
     * product rule, enforced server-side). The whole batch is created in one transaction
     * (all-or-nothing) and the response is the same {@link MasterServiceResponse} list shape
     * the single-create endpoint returns.
     */
    @Operation(summary = "Bulk-create my services (first-time setup)",
            description = "Creates every selected service in one transaction. Only valid when "
                    + "the master has no active services yet (409 otherwise).")
    @PostMapping("/independent-masters/me/services/bulk")
    @PreAuthorize("hasRole('INDEPENDENT_MASTER')")
    public ResponseEntity<ApiResponse<List<MasterServiceResponse>>> bulkCreateMyServices(
            @Valid @RequestBody BulkCreateServicesRequest request,
            Authentication authentication
    ) {
        UUID userId = extractUserId(authentication);
        List<MasterServiceResponse> response =
                serviceCatalogService.bulkCreateIndependentMasterServices(userId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    /**
     * First-time bulk service setup performed on behalf of a master in a salon.
     *
     * <p>Authorized for the salon's SALON_OWNER and SALON_ADMIN via {@code canManageSalon},
     * with {@code masterBelongsToSalon} closing the timing-oracle IDOR (same guard pair as
     * {@link #assignServiceToMaster}). SALON_ADMIN inclusion is intentional and matches the
     * confirmed contract — admins manage masters' menus but own no services themselves.
     * The owner-operated master row resolves through the same path (its {@code salon_id}
     * equals {@code salonId}).
     *
     * <p>Valid only when the target master has ZERO active services (409 otherwise). The
     * whole batch is created in one transaction (all-or-nothing).
     */
    @Operation(summary = "Bulk-create a salon master's services (first-time setup)",
            description = "Creates every selected service for the given master in one "
                    + "transaction. Only valid when the master has no active services yet "
                    + "(409 otherwise).")
    @PostMapping("/salons/{salonId}/masters/{masterId}/services/bulk")
    @PreAuthorize("@authz.canManageSalon(authentication, #salonId) and @authz.masterBelongsToSalon(#masterId, #salonId)")
    public ResponseEntity<ApiResponse<List<MasterServiceResponse>>> bulkCreateMasterServices(
            @PathVariable UUID salonId,
            @PathVariable UUID masterId,
            @Valid @RequestBody BulkCreateServicesRequest request
    ) {
        List<MasterServiceResponse> response =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @DeleteMapping("/services/{serviceDefId}")
    @PreAuthorize("@authz.canManageServiceDefinition(authentication, #serviceDefId)")
    public ResponseEntity<Void> deactivateServiceDefinition(
            @PathVariable UUID serviceDefId
    ) {
        serviceCatalogService.deactivateServiceDefinition(serviceDefId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Partially updates a service definition.
     *
     * <p>Only non-null fields in the request body are applied; omitted fields retain
     * their current values (PATCH semantics).
     *
     * <p>Authorization uses the same {@code canManageServiceDefinition} SpEL expression
     * as DELETE: a single DB lookup resolves the owner user UUID and compares it to the
     * authenticated principal. No redundant role guard is added at the controller level
     * because ownership implies the required role (anti-bug §D).
     */
    @PatchMapping("/services/{serviceDefId}")
    @PreAuthorize("@authz.canManageServiceDefinition(authentication, #serviceDefId)")
    public ResponseEntity<ApiResponse<ServiceDefinitionResponse>> updateServiceDefinition(
            @PathVariable UUID serviceDefId,
            @Valid @RequestBody UpdateServiceDefinitionRequest request
    ) {
        ServiceDefinitionResponse response =
                serviceCatalogService.updateServiceDefinition(serviceDefId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Sets or replaces the photo URL for a service definition.
     *
     * <p>Accepts a presigned Cloudflare R2 URL or any direct HTTPS URL. Validation
     * enforces {@code https://} scheme and a 2048-character length cap at the DTO
     * boundary (anti-bug §A URL-field rule).
     */
    @PatchMapping("/services/{serviceDefId}/photo")
    @PreAuthorize("@authz.canManageServiceDefinition(authentication, #serviceDefId)")
    public ResponseEntity<ApiResponse<ServiceDefinitionResponse>> updateServicePhoto(
            @PathVariable UUID serviceDefId,
            @Valid @RequestBody UpdateServicePhotoRequest request
    ) {
        ServiceDefinitionResponse response =
                serviceCatalogService.updateServicePhoto(serviceDefId, request.photoUrl());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof UUID userId) {
            return userId;
        }
        throw new ForbiddenException("Not authenticated");
    }
}
