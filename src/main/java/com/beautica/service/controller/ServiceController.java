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
import com.beautica.service.dto.ServicePriceShapeMismatchErrorResponse;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.dto.UpdateServicePhotoRequest;
import com.beautica.service.service.MasterServiceFavoriteDecorator;
import com.beautica.service.service.SalonServiceFavoriteDecorator;
import com.beautica.service.service.ServiceCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
     * Description attached to the {@code 409 DUPLICATE_SERVICE} declaration on the salon-master
     * bulk endpoint specifically (Phase 305 D3). Phase 302 D4 narrowed this endpoint's conflict
     * scope from per-SALON to per-MASTER: two different masters in the same salon can both offer
     * the same service type — the second reuses the salon's existing definition and gets its own
     * assignment, {@code 201}, not {@code 409} — a breaking behavioural change to a shipped
     * endpoint (a call that used to return 409 for the second master now returns 201). The 409
     * fires ONLY when THIS master already has an active assignment for the type.
     *
     * <p>Declared separately from {@link #DUPLICATE_SERVICE_409} (used by every other write
     * endpoint here) because that shared text describes the still-accurate per-OWNER conflict for
     * every other path; only this endpoint's semantics changed with Phase 302 D4.
     */
    private static final String DUPLICATE_SERVICE_PER_MASTER_409 =
            "The service type is already assigned to THIS master (Phase 302 D4 — the conflict is "
                    + "per-MASTER, not per-salon: another master in the same salon already offering "
                    + "this type is NOT a conflict here, the salon's existing definition is reused "
                    + "and this call returns 201). One active assignment per (master, service "
                    + "type); price and duration are irrelevant. Branch on `data.code` == "
                    + "DUPLICATE_SERVICE, never on `message`.";

    /**
     * Description attached to {@link #getSalonServiceCatalog}'s {@code @Operation} (Phase 305 D2):
     * states the five-condition visibility rule directly in the published spec so a mobile engineer
     * reading {@code /api-docs} sees it without spelunking the service layer.
     */
    private static final String SALON_CATALOGUE_VISIBILITY_RULE =
            "A service appears here iff ALL of: (1) its definition is owner_type=SALON with "
                    + "owner_id=salonId; (2) the definition is active; (3) at least one master_services "
                    + "assignment for it is active; (4) that assignment's master belongs to this salon "
                    + "and is active; (5) that master has a free future slot for the service's "
                    + "effective duration. Condition 5 is DELIBERATE, not a bug: a master with no "
                    + "working hours configured has none of their services listed here, because this "
                    + "endpoint answers \"what can a client book right now\", not \"what does the "
                    + "staff list on paper\".";

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

    /**
     * Description attached to the {@code 400 SERVICE_PRICE_SHAPE_MISMATCH} declaration on
     * {@link #bulkCreateMasterServices} — the only endpoint that can raise it. Inherited from
     * Phase 302 (see {@link ServicePriceShapeMismatchErrorResponse}): the wire contract has been
     * live and correct since Phase 302 shipped, but Phase 302 was forbidden from touching this
     * file, so the declaration was missing from {@code /api-docs} until now. The sibling
     * independent-master bulk endpoint never reuses another owner's definition, so it cannot
     * raise this error and does not carry this declaration.
     */
    private static final String SERVICE_PRICE_SHAPE_MISMATCH_400 =
            "A batch item's price shape cannot be represented against the salon's existing "
                    + "active definition for that service type, which this item would reuse "
                    + "(master_services carries only a price floor override, no per-master "
                    + "ceiling or price type). The whole batch is rejected — nothing was "
                    + "written. Branch on `data.code` == SERVICE_PRICE_SHAPE_MISMATCH; "
                    + "`data.salonPriceType`/`salonPriceMin`/`salonPriceMax` name the salon's "
                    + "governing shape.";

    /**
     * Description attached to the {@code 429} declaration on every write endpoint in this
     * controller. {@code AuthRateLimitFilter} throttles the single-item write routes
     * (create / update / photo / deactivate) at {@code app.rate-limit.service-write-capacity}
     * (60 / 60 s per IP) and the two {@code /bulk} routes at
     * {@code app.rate-limit.bulk-service-setup-capacity} (10 / 60 s per IP) — two SEPARATE
     * buckets, so exhausting one does not lock out the other.
     *
     * <p>Declared for the same reason as {@link #DUPLICATE_SERVICE_409} and
     * {@link #BULK_LOCK_TIMEOUT_503}: springdoc scans controller signatures, and the throttle
     * lives in a servlet filter it never sees, so an undeclared 429 is simply absent from
     * {@code /api-docs} — and the mobile Dio client, regenerated from that spec, has no branch
     * for it.
     *
     * <p>No {@code content} schema is declared (matching the existing {@code 429} idiom in
     * {@code AppointmentController}): the filter writes a fixed
     * {@code {"error":"Too many requests"}} body with nothing machine-readable to branch on, so
     * the client keys on the status code and the {@code Retry-After} header alone.
     */
    private static final String RATE_LIMITED_429 =
            "Per-IP rate limit exceeded. Honour the `Retry-After` header (seconds) and retry after "
                    + "backoff — nothing was written. Single-item writes and the bulk routes use "
                    + "separate buckets. Branch on the status code; the body carries no "
                    + "machine-readable code.";

    private final ServiceCatalogService serviceCatalogService;
    private final MasterServiceFavoriteDecorator masterServiceFavoriteDecorator;
    private final SalonServiceFavoriteDecorator salonServiceFavoriteDecorator;

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
                    responseCode = "429", description = RATE_LIMITED_429)
    })
    @PostMapping("/salons/{salonId}/services")
    // Phase 306 D4 — role-only conjunct dropped; @authz.canManageSalon already admits both
    // SALON_OWNER (by ownership) and SALON_ADMIN (by salon assignment), matching the bulk
    // on-behalf endpoint below, which never had the extra hasRole conjunct.
    @PreAuthorize("@authz.canManageSalon(authentication, #salonId)")
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
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            // Same lone-@ApiResponse guard as every other write endpoint in this file: the 429
            // alone would be read by springdoc as the COMPLETE response set and drop the
            // auto-derived typed 200, regenerating the mobile client to Response<void>.
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = RATE_LIMITED_429)
    })
    @PostMapping("/salons/{salonId}/masters/{masterId}/services")
    // Phase 306 D4 — role-only conjunct dropped; @authz.canManageSalon already admits both
    // SALON_OWNER (by ownership) and SALON_ADMIN (by salon assignment), matching the bulk
    // on-behalf endpoint below, which never had the extra hasRole conjunct. masterBelongsToSalon
    // is unchanged — it closes the timing-oracle IDOR regardless of caller role.
    @PreAuthorize("@authz.canManageSalon(authentication, #salonId) and @authz.masterBelongsToSalon(#masterId, #salonId)")
    public ResponseEntity<ApiResponse<MasterServiceResponse>> assignServiceToMaster(
            @PathVariable UUID salonId,
            @Parameter(description = "Master row id (NOT a user id)") @PathVariable UUID masterId,
            @Valid @RequestBody AssignServiceToMasterRequest request
    ) {
        MasterServiceResponse response =
                serviceCatalogService.assignServiceToMaster(salonId, masterId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    /**
     * Unassigns ONE master from ONE service — NOT {@link #deactivateServiceDefinition}, which
     * deactivates the shared definition and removes it from EVERY master in the salon at once.
     * This is the surgical, per-master counterpart (Phase 307): different path, different row,
     * different blast radius, neither replaces the other.
     *
     * <p>Soft-unassigns only — {@code master_services.is_active} flips to {@code false}; the row,
     * the shared {@link com.beautica.service.entity.ServiceDefinition}, and every other master's
     * assignment are untouched (D1/D2). A future {@code CONFIRMED} booking through this exact
     * assignment refuses the call with {@code 409} and writes nothing (D4 — the shipping contract;
     * Phase 308's cancel-and-notify cascade was deferred by the user). A second call against an
     * already-inactive pair is a plain {@code 404} (D7).
     *
     * <p>Guard mirrors {@link #assignServiceToMaster}: {@code canManageSalon} admits the salon's
     * owner and admin, {@code masterBelongsToSalon} closes the same timing-oracle IDOR. Service-
     * layer re-validation still runs — the SpEL gate is never trusted alone.
     *
     * <p>{@code masterId} is the {@code masters} row primary key, NOT a {@code userId} — passing a
     * user id yields {@code 404}, not {@code 403}.
     */
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            // Same lone-@ApiResponse guard as every other write endpoint in this file — see
            // assignServiceToMaster above.
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "The master has a future CONFIRMED booking "
                            + "for this exact service; nothing was written. Cancel or decline it "
                            + "first, or wait for it to pass."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = RATE_LIMITED_429)
    })
    @DeleteMapping("/salons/{salonId}/masters/{masterId}/services/{serviceDefId}")
    @PreAuthorize("@authz.canManageSalon(authentication, #salonId) and @authz.masterBelongsToSalon(#masterId, #salonId)")
    public ResponseEntity<Void> unassignServiceFromMaster(
            @PathVariable UUID salonId,
            @Parameter(description = "Master row id (NOT a user id)") @PathVariable UUID masterId,
            @PathVariable UUID serviceDefId
    ) {
        serviceCatalogService.unassignServiceFromMaster(salonId, masterId, serviceDefId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the FULL service list of one master in {@code salonId}, {@code priceOverride}
     * unmasked — the management read for the salon's OWNER/ADMIN (Phase 309), widened by Phase
     * 310 to also admit the master's OWN {@code SALON_MASTER} reading their own row.
     *
     * <p><strong>Not a substitute route for {@link #getMasterServices}.</strong> That public
     * browse masks {@code priceOverride} via {@code MasterServiceResponse.fromPublic} and is
     * cached with the masked shape; this endpoint returns {@code MasterServiceResponse::from}
     * straight, unmasked, because a salon owner MANAGING a master's menu — or that master reading
     * their own catalogue (Phase 310) — needs the exact field an anonymous browser must not see
     * (Phase 309 D1, Phase 310 D4). The public route was deliberately rejected as the SALON_MASTER
     * path for this reason (Phase 310 background).
     *
     * <p><strong>Who is admitted (Phase 310 D2).</strong> {@code @authz.canReadSalonMasterServices}
     * grants: (1) {@code SALON_OWNER} / {@code SALON_ADMIN} managing {@code salonId} — Phase 309's
     * behaviour, unchanged; (2) a {@code SALON_MASTER} whose {@code masters} row IS {@code
     * masterId}, provided {@code salonId} actually owns that row ({@code masterBelongsToSalon},
     * D2.4) — a {@code SALON_MASTER} reading a PEER master, in their own salon or any other, is
     * refused. {@code CLIENT} is rejected on a role fast path before any DB hit.
     *
     * <p><strong>Authorization diverges from {@link #unassignServiceFromMaster}'s failure code
     * on purpose (Phase 309 D2) — unchanged by Phase 310.</strong> That DELETE's {@code
     * @PreAuthorize} also carries {@code @authz.masterBelongsToSalon(#masterId, #salonId)}, so a
     * cross-salon {@code masterId} 403s there. Here that conjunct is deliberately NOT in the
     * owner/admin branch of the SpEL gate — a cross-salon or nonexistent {@code masterId} for an
     * OWNER/ADMIN caller is resolved INSIDE {@link ServiceCatalogService#getSalonMasterServices}
     * and denied with a plain 404, so the response body cannot distinguish "belongs to another
     * salon" from "no such master". Phase 310 does not touch this: the new own-row branch runs
     * its OWN {@code masterBelongsToSalon} check inside the SpEL predicate instead (D2.4), so a
     * {@code SALON_MASTER} passing a foreign {@code salonId} alongside their own {@code masterId}
     * is refused at the gate, before the D3 404 path is ever reached.
     *
     * <p>{@code masterId} is the {@code masters} row primary key, NOT a {@code userId} (D3) — a
     * user id also 404s.
     *
     * <p><strong>NOT cached (D4).</strong> Mirrors {@link #getMyServices}; the public {@code
     * masterServices} cache that backs {@link #getMasterServices} is never read, populated or
     * evicted by this endpoint.
     */
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            // Explicit 200 so springdoc does not drop the typed body — see the note on
            // addIndependentMasterService above; omitting this regenerates the mobile Dart
            // client to Response<void> and breaks res.data?.data.
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "No master with this id in this salon — "
                            + "also returned when masterId belongs to a different salon, or is a "
                            + "user id rather than a masters row id (D3); the body never "
                            + "distinguishes these cases. Note: the sibling DELETE on this same "
                            + "path (unassign) returns 403, not 404, for the identical "
                            + "cross-salon-master fact — this GET intentionally uses 404 instead "
                            + "so an unauthorized caller cannot tell a master belonging to "
                            + "another salon from one that does not exist at all.")
            // No 429 here — unlike the write endpoints on this path (POST/DELETE), this GET is
            // not matched by AuthRateLimitFilter's method-gated serviceWriteBuckets check
            // (HttpMethod.POST.matches(method) guards that branch), so documenting RATE_LIMITED_429
            // would claim a status this read never actually returns.
    })
    @GetMapping("/salons/{salonId}/masters/{masterId}/services")
    @PreAuthorize("@authz.canReadSalonMasterServices(authentication, #salonId, #masterId)")
    public ApiResponse<List<MasterServiceResponse>> getSalonMasterServices(
            @PathVariable UUID salonId,
            @Parameter(description = "Master row id (NOT a user id)") @PathVariable UUID masterId,
            Authentication authentication
    ) {
        UUID actorId = AuthenticationUtils.userId(authentication);
        return ApiResponse.ok(serviceCatalogService.getSalonMasterServices(actorId, salonId, masterId));
    }

    /**
     * Returns the active services offered by the given master.
     *
     * <p><strong>Public endpoint — no authentication required.</strong>
     * Unauthenticated clients browse a master's service menu before deciding to book.
     * No {@code @PreAuthorize} guard is intentional; adding one would break the
     * discovery flow for anonymous users.
     *
     * <p><strong>{@code isFavorite} decoration (Phase 32.1).</strong> {@code
     * serviceCatalogService.getMasterServices} is cached per-{@code masterId} and shared across
     * every caller, so it can never know who is asking. This method composes the cached, caller-
     * agnostic list with {@link MasterServiceFavoriteDecorator#decorate}, a SEPARATE bean invoked
     * here — lexically outside the {@code @Cacheable} method — so the per-client flag is applied
     * fresh on every request and never enters the cache. An authenticated CLIENT sees {@code true}/
     * {@code false} per row; every other caller (anonymous, or any other role) sees {@code null}.
     */
    @GetMapping("/masters/{masterId}/services")
    public ApiResponse<List<MasterServiceResponse>> getMasterServices(
            @PathVariable UUID masterId, Authentication authentication) {
        List<MasterServiceResponse> services = serviceCatalogService.getMasterServices(masterId);
        return ApiResponse.ok(masterServiceFavoriteDecorator.decorate(services, authentication));
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
     *
     * <p><strong>{@code isFavorite} decoration (salon-service-favourites track).</strong>
     * {@code serviceCatalogService.getSalonServiceCatalog} is cached per-{@code salonId} and
     * shared across every caller, so it can never know who is asking. This method composes the
     * cached, caller-agnostic catalog with {@link SalonServiceFavoriteDecorator#decorate}, a
     * SEPARATE bean invoked here — lexically outside the {@code @Cacheable} method — so the
     * per-client flag is applied fresh on every request and never enters the cache. An
     * authenticated CLIENT sees {@code true}/{@code false} per service; every other caller
     * (anonymous, or any other role) sees {@code null}.
     */
    @Operation(summary = "Salon's public bookable service catalog",
            description = SALON_CATALOGUE_VISIBILITY_RULE)
    @GetMapping("/salons/{salonId}/services")
    public ApiResponse<SalonServiceCatalogResponse> getSalonServiceCatalog(
            @PathVariable UUID salonId, Authentication authentication) {
        SalonServiceCatalogResponse catalog = serviceCatalogService.getSalonServiceCatalog(salonId);
        return ApiResponse.ok(salonServiceFavoriteDecorator.decorate(catalog, authentication));
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
                    content = @Content(schema = @Schema(implementation = DuplicateServiceErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = RATE_LIMITED_429)
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
                    responseCode = "503", description = BULK_LOCK_TIMEOUT_503),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = RATE_LIMITED_429)
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
                    responseCode = "409", description = DUPLICATE_SERVICE_PER_MASTER_409,
                    content = @Content(schema = @Schema(implementation = DuplicateServiceErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = SERVICE_PRICE_SHAPE_MISMATCH_400,
                    content = @Content(schema = @Schema(implementation = ServicePriceShapeMismatchErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = BULK_LOCK_TIMEOUT_503),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = RATE_LIMITED_429)
    })
    @PostMapping("/salons/{salonId}/masters/{masterId}/services/bulk")
    @PreAuthorize("@authz.canManageSalon(authentication, #salonId) and @authz.masterBelongsToSalon(#masterId, #salonId)")
    public ResponseEntity<ApiResponse<List<MasterServiceResponse>>> bulkCreateMasterServices(
            @PathVariable UUID salonId,
            @Parameter(description = "Master row id (NOT a user id)") @PathVariable UUID masterId,
            @Valid @RequestBody BulkCreateServicesRequest request
    ) {
        List<MasterServiceResponse> response =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            // useReturnTypeSchema=true on a ResponseEntity<Void> re-states exactly what springdoc
            // already derives (an empty 200) — it is declared only so the 429 below is not the
            // lone @ApiResponse, which springdoc would treat as the COMPLETE response set and use
            // to drop the success response from the spec entirely.
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = RATE_LIMITED_429)
    })
    @DeleteMapping("/services/{serviceDefId}")
    // Role-only fast gate here; salon-management/ownership is enforced once inside the service
    // against the already-needed findOwnerUserId projection (anti-bug §D split — no duplicate
    // SpEL canManage* lookup that would issue a second round-trip). Phase 306 D5 — SALON_ADMIN
    // added for full parity with the other service-management writes; the salon-scoping that
    // keeps this safe lives in enforceCanManageServiceDefinition (D3), which this role-only gate
    // shares with PATCH.
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER')")
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
                    content = @Content(schema = @Schema(implementation = DuplicateServiceErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = RATE_LIMITED_429)
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
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            // Same lone-@ApiResponse guard as every other write endpoint in this file — see
            // assignServiceToMaster above.
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = RATE_LIMITED_429)
    })
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
