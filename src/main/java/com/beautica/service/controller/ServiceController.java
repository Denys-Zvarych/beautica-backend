package com.beautica.service.controller;

import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.DuplicateServiceErrorResponse;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.dto.UpdateServicePhotoRequest;
import com.beautica.service.service.ServiceCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ServiceController {

    /**
     * Description attached to the {@code 409 DUPLICATE_SERVICE} declaration on every write
     * endpoint that can raise {@code DuplicateServiceException} — the five below, i.e. every path
     * that inserts or re-types a {@code ServiceDefinition} and so can collide with V121's
     * {@code ux_service_def_owner_service_type_active}.
     *
     * <p>Declared explicitly because springdoc scans controller signatures, not
     * {@code @RestControllerAdvice} handlers: without these annotations the 409's body has no
     * schema in {@code /api-docs}, and the generated mobile client has no model for the payload
     * it must branch on. The swagger {@code @ApiResponse} annotation is written fully qualified
     * throughout this file — its simple name collides with {@link com.beautica.common.ApiResponse},
     * this project's response envelope, which is the return type of nearly every method here.
     */
    private static final String DUPLICATE_SERVICE_409 =
            "The owner already offers an active service of this type. One active service per "
                    + "(owner, service type); price and duration are irrelevant. Branch on "
                    + "`data.code` == DUPLICATE_SERVICE, never on `message`.";

    /**
     * Description attached to the {@code 503} declaration on the two BULK endpoints — the only
     * paths that can raise it. {@code ServiceCatalogService#acquireBulkSetupLockWithTimeout}
     * translates a Postgres {@code 55P03 lock_not_available} (the fused 3s {@code lock_timeout}
     * elapsing on the per-master bulk-setup advisory lock) into a
     * {@code BusinessException(SERVICE_UNAVAILABLE)}.
     *
     * <p>Declared for the same reason as {@link #DUPLICATE_SERVICE_409}: springdoc scans
     * controller signatures, not {@code @RestControllerAdvice} handlers, so an undeclared status
     * is simply absent from {@code /api-docs} — and the mobile Dio client, regenerated from that
     * spec, has no branch for it.
     *
     * <p>No {@code content} schema is declared (matching the existing {@code 429} idiom in
     * {@code AppointmentController}): {@code GlobalExceptionHandler#handleBusiness} deliberately
     * replaces the message with generic copy for this status and sends {@code data: null}, so
     * there is nothing branchable in the body — the client keys on the status code alone.
     */
    private static final String BULK_LOCK_TIMEOUT_503 =
            "Transient: another bulk service-setup for this master is in flight and held the "
                    + "per-master lock past the 3s ceiling. Safe to retry after a short backoff — "
                    + "the batch is all-or-nothing, so nothing was written. Branch on the status "
                    + "code; the body carries no machine-readable code and `message` is generic.";

    private final ServiceCatalogService serviceCatalogService;

    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            // Explicit success response so springdoc does NOT treat the lone 409 below as the
            // COMPLETE response set — without this it drops the auto-derived typed body and
            // documents the endpoint with a void/empty success, which regenerates the mobile
            // Dart client to Response<void> and breaks `res.data?.data`. useReturnTypeSchema=true
            // makes springdoc emit the method's real return-type schema (ApiResponse<Dto>,
            // unwrapped from ResponseEntity) under 200 — springdoc's default status for a
            // ResponseEntity<T> return, matching the pre-regression contract the client compiles
            // against. Annotation-only: the runtime status (201/200) is unchanged.
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = DUPLICATE_SERVICE_409,
                    content = @Content(schema = @Schema(implementation = DuplicateServiceErrorResponse.class)))
    })
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
     * Returns a salon's public, bookable service catalog grouped by category.
     *
     * <p><strong>Public endpoint — no authentication required.</strong> Matches the
     * {@link #getMasterServices(UUID)} no-auth pattern above: unauthenticated clients
     * browse a salon's menu before deciding to book. No {@code @PreAuthorize} guard is
     * intentional; adding one would break the discovery flow for anonymous users.
     *
     * <p>Note this shares its path with {@link #addServiceToSalon} above — no route
     * collision, they are distinct HTTP methods (POST vs GET).
     */
    @GetMapping("/salons/{salonId}/services")
    public ApiResponse<SalonServiceCatalogResponse> getSalonServiceCatalog(
            @PathVariable UUID salonId) {
        return ApiResponse.ok(serviceCatalogService.getSalonServiceCatalog(salonId));
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
        UUID userId = AuthenticationUtils.userId(authentication);
        return ApiResponse.ok(serviceCatalogService.getMyServices(userId));
    }

    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            // Explicit success response so springdoc does NOT treat the lone 409 below as the
            // COMPLETE response set — without this it drops the auto-derived typed body and
            // documents the endpoint with a void/empty success, which regenerates the mobile
            // Dart client to Response<void> and breaks `res.data?.data`. useReturnTypeSchema=true
            // makes springdoc emit the method's real return-type schema (ApiResponse<Dto>,
            // unwrapped from ResponseEntity) under 200 — springdoc's default status for a
            // ResponseEntity<T> return, matching the pre-regression contract the client compiles
            // against. Annotation-only: the runtime status (201/200) is unchanged.
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = DUPLICATE_SERVICE_409,
                    content = @Content(schema = @Schema(implementation = DuplicateServiceErrorResponse.class)))
    })
    @PostMapping("/independent-masters/me/services")
    @PreAuthorize("hasRole('INDEPENDENT_MASTER')")
    public ResponseEntity<ApiResponse<MasterServiceResponse>> addIndependentMasterService(
            @Valid @RequestBody CreateServiceDefinitionRequest request,
            Authentication authentication
    ) {
        UUID userId = AuthenticationUtils.userId(authentication);
        MasterServiceResponse response = serviceCatalogService.addIndependentMasterService(userId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    /**
     * Bulk service creation for the authenticated INDEPENDENT_MASTER.
     *
     * <p>Self-scoped: the acting master is resolved from the principal, never a path/query
     * parameter — mirroring {@link #addIndependentMasterService}. Additive: callable whether the
     * master's catalogue is empty or already populated, so one screen serves both initial setup
     * and later "add more services" passes. The whole batch is created in one transaction
     * (all-or-nothing) and the response is the same {@link MasterServiceResponse} list shape
     * the single-create endpoint returns.
     *
     * <p>The only 409 this endpoint returns is {@code DUPLICATE_SERVICE} — a batch item whose
     * service type the master already offers.
     */
    @Operation(summary = "Bulk-create my services",
            description = "Creates every selected service in one transaction (all-or-nothing). "
                    + "Additive — callable whether or not the master already has services.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            // Explicit success response so springdoc does NOT treat the lone 409 below as the
            // COMPLETE response set — without this it drops the auto-derived typed body and
            // documents the endpoint with a void/empty success, which regenerates the mobile
            // Dart client to Response<void> and breaks `res.data?.data`. useReturnTypeSchema=true
            // makes springdoc emit the method's real return-type schema (ApiResponse<Dto>,
            // unwrapped from ResponseEntity) under 200 — springdoc's default status for a
            // ResponseEntity<T> return, matching the pre-regression contract the client compiles
            // against. Annotation-only: the runtime status (201/200) is unchanged.
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = DUPLICATE_SERVICE_409,
                    content = @Content(schema = @Schema(implementation = DuplicateServiceErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = BULK_LOCK_TIMEOUT_503)
    })
    @PostMapping("/independent-masters/me/services/bulk")
    @PreAuthorize("hasRole('INDEPENDENT_MASTER')")
    public ResponseEntity<ApiResponse<List<MasterServiceResponse>>> bulkCreateMyServices(
            @Valid @RequestBody BulkCreateServicesRequest request,
            Authentication authentication
    ) {
        UUID userId = AuthenticationUtils.userId(authentication);
        List<MasterServiceResponse> response =
                serviceCatalogService.bulkCreateIndependentMasterServices(userId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    /**
     * Bulk service creation performed on behalf of a master in a salon.
     *
     * <p>Authorized for the salon's SALON_OWNER and SALON_ADMIN via {@code canManageSalon},
     * with {@code masterBelongsToSalon} closing the timing-oracle IDOR (same guard pair as
     * {@link #assignServiceToMaster}). SALON_ADMIN inclusion is intentional and matches the
     * confirmed contract — admins manage masters' menus but own no services themselves.
     * The owner-operated master row resolves through the same path (its {@code salon_id}
     * equals {@code salonId}).
     *
     * <p>Additive: callable whether the target master's catalogue is empty or already populated.
     * The whole batch is created in one transaction (all-or-nothing). The only 409 this endpoint
     * returns is {@code DUPLICATE_SERVICE} — a batch item whose service type the master already
     * offers.
     */
    @Operation(summary = "Bulk-create a salon master's services",
            description = "Creates every selected service for the given master in one "
                    + "transaction (all-or-nothing). Additive — callable whether or not the "
                    + "master already has services.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            // Explicit success response so springdoc does NOT treat the lone 409 below as the
            // COMPLETE response set — without this it drops the auto-derived typed body and
            // documents the endpoint with a void/empty success, which regenerates the mobile
            // Dart client to Response<void> and breaks `res.data?.data`. useReturnTypeSchema=true
            // makes springdoc emit the method's real return-type schema (ApiResponse<Dto>,
            // unwrapped from ResponseEntity) under 200 — springdoc's default status for a
            // ResponseEntity<T> return, matching the pre-regression contract the client compiles
            // against. Annotation-only: the runtime status (201/200) is unchanged.
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = DUPLICATE_SERVICE_409,
                    content = @Content(schema = @Schema(implementation = DuplicateServiceErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = BULK_LOCK_TIMEOUT_503)
    })
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
    // Role-only fast gate here; ownership is enforced once inside the service against the
    // already-needed findOwnerUserId projection (anti-bug §D split — no duplicate SpEL
    // canManage* lookup that would issue a second round-trip).
    @PreAuthorize("hasAnyRole('SALON_OWNER','INDEPENDENT_MASTER')")
    public ResponseEntity<Void> deactivateServiceDefinition(
            @PathVariable UUID serviceDefId,
            Authentication authentication
    ) {
        serviceCatalogService.deactivateServiceDefinition(AuthenticationUtils.userId(authentication), serviceDefId);
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
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            // Explicit success response so springdoc does NOT treat the lone 409 below as the
            // COMPLETE response set — without this it drops the auto-derived typed body and
            // documents the endpoint with a void/empty success, which regenerates the mobile
            // Dart client to Response<void> and breaks `res.data?.data`. useReturnTypeSchema=true
            // makes springdoc emit the method's real return-type schema (ApiResponse<Dto>,
            // unwrapped from ResponseEntity) under 200 — springdoc's default status for a
            // ResponseEntity<T> return, matching the pre-regression contract the client compiles
            // against. Annotation-only: the runtime status (201/200) is unchanged.
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = DUPLICATE_SERVICE_409,
                    content = @Content(schema = @Schema(implementation = DuplicateServiceErrorResponse.class)))
    })
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
}
